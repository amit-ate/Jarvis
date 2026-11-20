#!/usr/bin/env python3
"""Jarvis 60-prompt evaluation scorer implementing EchoGuard rules, tool accuracy, keyword hit, and CI gate."""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

GATE_MAX_ECHO_RATE_PCT = 5.0
GATE_MIN_TOOL_ACCURACY_PCT = 80.0

STOP_WORDS = {
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "could",
    "did", "do", "does", "for", "from", "had", "has", "have", "he", "her",
    "here", "him", "his", "how", "i", "if", "in", "is", "it", "its", "just",
    "me", "my", "no", "not", "now", "of", "on", "or", "our", "out", "please",
    "say", "she", "so", "some", "tell", "that", "the", "their", "them", "then",
    "there", "these", "they", "this", "to", "too", "up", "us", "was", "we",
    "were", "what", "when", "where", "which", "who", "why", "will", "with",
    "would", "you", "your", "jarvis",
}


def normalize_text(text: str) -> str:
    s = text.lower().strip()
    s = re.sub(r"^jarvis[\s,:\-!]+", "", s)
    s = re.sub(r"^(?:say|assistant)\s*:\s*", "", s)
    s = re.sub(r"[^a-z0-9\s]", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def tokenize(text: str) -> List[str]:
    norm = normalize_text(text)
    return norm.split() if norm else []


def has_intra_reply_stutter(tokens: List[str]) -> bool:
    """Detect 3-gram repetition loop inside a single reply."""
    if len(tokens) < 6:
        return False
    counts: collections.Counter = collections.Counter()
    for i in range(len(tokens) - 2):
        trigram = (tokens[i], tokens[i + 1], tokens[i + 2])
        counts[trigram] += 1
        if counts[trigram] >= 2:
            return True
    return False


def is_echo(user_text: str, reply_text: str) -> Tuple[bool, str]:
    """EchoGuard detection matching Jarvis EchoGuard rules."""
    u_norm = normalize_text(user_text)
    r_norm = normalize_text(reply_text)
    if not u_norm or not r_norm:
        return False, ""

    # Exempt explicit repeat/say-after-me instructions
    if re.match(r"^(?:repeat|say after me|say the words)\b", u_norm):
        return False, ""

    u_tokens = u_norm.split()
    r_tokens = r_norm.split()

    # 1. Exact normalized match
    if u_norm == r_norm:
        return True, "exact_match"

    # 2. Containment with <= 2 extra words
    if (u_norm in r_norm or r_norm in u_norm) and abs(len(r_tokens) - len(u_tokens)) <= 2:
        return True, "near_containment"

    u_set = set(u_tokens)
    r_set = set(r_tokens)

    # 3. Word Jaccard >= 0.62 for >= 2 user words
    if len(u_tokens) >= 2 and (u_set | r_set):
        jaccard = len(u_set & r_set) / float(len(u_set | r_set))
        if jaccard >= 0.62:
            return True, f"jaccard={jaccard:.2f}"

    # 4. High content-word overlap (>= 0.78) with <= 1 new content word
    u_content = {t for t in u_tokens if t not in STOP_WORDS}
    r_content = {t for t in r_tokens if t not in STOP_WORDS}
    if u_content and r_content:
        overlap = len(u_content & r_content) / float(len(u_content))
        new_content = len(r_content - u_content)
        if overlap >= 0.78 and new_content <= 1:
            return True, f"content_overlap={overlap:.2f},new={new_content}"

    # 5. Intra-reply 3-gram stutter loop
    if has_intra_reply_stutter(r_tokens):
        return True, "trigram_stutter"

    return False, ""


def extract_prediction_fields(pred: Dict[str, Any]) -> Tuple[str, Optional[str], str, Optional[float]]:
    """Normalize prediction record across possible JSONL schemas."""
    reply = str(pred.get("reply") or pred.get("text") or pred.get("spoken") or "").strip()
    tool_name = pred.get("tool") or pred.get("tool_name")
    tool_args = pred.get("args") or pred.get("tool_args") or {}

    if not tool_name and reply.startswith("{"):
        try:
            parsed = json.loads(reply)
            if isinstance(parsed, dict) and "tool" in parsed:
                tool_name = parsed.get("tool")
                tool_args = parsed.get("args") or {}
        except json.JSONDecodeError:
            pass

    if tool_name:
        pred_type = "tool"
        searchable = f"{tool_name} {json.dumps(tool_args, sort_keys=True)} {reply}".lower()
    else:
        pred_type = str(pred.get("type") or "say").lower()
        searchable = reply.lower()

    latency_raw = pred.get("latency_ms")
    latency_ms = float(latency_raw) if isinstance(latency_raw, (int, float)) else None
    return pred_type, (str(tool_name) if tool_name else None), searchable, latency_ms


def evaluate(prompts: List[Dict[str, Any]], predictions: List[Dict[str, Any]]) -> Dict[str, Any]:
    pred_by_id = {str(p.get("id")): p for p in predictions if "id" in p}

    total = len(prompts)
    evaluated = 0
    echo_checked = 0
    echo_count = 0
    tool_expected_count = 0
    tool_correct_count = 0
    keyword_scores: List[float] = []
    latencies: List[float] = []

    by_category: Dict[str, Dict[str, Any]] = collections.defaultdict(
        lambda: {
            "total": 0,
            "evaluated": 0,
            "echoes": 0,
            "tool_expected": 0,
            "tool_correct": 0,
            "keyword_hits": [],
        }
    )
    failures: List[Dict[str, Any]] = []

    for item in prompts:
        pid = str(item["id"])
        cat = str(item["category"])
        cat_stats = by_category[cat]
        cat_stats["total"] += 1

        pred = pred_by_id.get(pid)
        if pred is None:
            failures.append({"id": pid, "category": cat, "reason": "missing_prediction"})
            continue

        evaluated += 1
        cat_stats["evaluated"] += 1

        pred_type, pred_tool, searchable, latency_ms = extract_prediction_fields(pred)
        if latency_ms is not None:
            latencies.append(latency_ms)

        reply_for_echo = str(pred.get("reply") or pred.get("spoken") or pred.get("text") or "")
        if item.get("forbid_echo", True) and pred_type == "say":
            echo_checked += 1
            echoed, echo_reason = is_echo(item["prompt"], reply_for_echo)
            if echoed:
                echo_count += 1
                cat_stats["echoes"] += 1
                failures.append(
                    {
                        "id": pid,
                        "category": cat,
                        "reason": f"echo ({echo_reason})",
                        "prompt": item["prompt"],
                        "reply": reply_for_echo,
                    }
                )
        elif item.get("forbid_echo", True):
            echo_checked += 1

        if item["expected_type"] == "tool":
            tool_expected_count += 1
            cat_stats["tool_expected"] += 1
            exp_tool = str(item.get("expected_tool") or "").strip().lower()
            got_tool = str(pred_tool or "").strip().lower()
            if pred_type == "tool" and exp_tool == got_tool:
                tool_correct_count += 1
                cat_stats["tool_correct"] += 1
            else:
                failures.append(
                    {
                        "id": pid,
                        "category": cat,
                        "reason": f"tool_mismatch (expected={exp_tool}, got={got_tool or pred_type})",
                    }
                )

        exp_kws = [str(k).lower() for k in item.get("expected_keywords") or [] if str(k).strip()]
        if exp_kws:
            any_hit = 1.0 if any(kw in searchable for kw in exp_kws) else 0.0
            keyword_scores.append(any_hit)
            cat_stats["keyword_hits"].append(any_hit)

    echo_rate_pct = (echo_count / float(echo_checked) * 100.0) if echo_checked else 0.0
    tool_acc_pct = (tool_correct_count / float(tool_expected_count) * 100.0) if tool_expected_count else 100.0
    keyword_hit_pct = (sum(keyword_scores) / float(len(keyword_scores)) * 100.0) if keyword_scores else 100.0
    avg_latency_ms = (sum(latencies) / float(len(latencies))) if latencies else None

    gate_passed = (
        evaluated == total
        and echo_rate_pct <= GATE_MAX_ECHO_RATE_PCT
        and tool_acc_pct >= GATE_MIN_TOOL_ACCURACY_PCT
    )

    cat_summary = {}
    for cat, st in sorted(by_category.items()):
        kh = st["keyword_hits"]
        cat_summary[cat] = {
            "total": st["total"],
            "evaluated": st["evaluated"],
            "echoes": st["echoes"],
            "tool_expected": st["tool_expected"],
            "tool_correct": st["tool_correct"],
            "keyword_hit_pct": round((sum(kh) / len(kh) * 100.0), 1) if kh else 100.0,
        }

    return {
        "total_prompts": total,
        "evaluated": evaluated,
        "echo_count": echo_count,
        "echo_rate_pct": round(echo_rate_pct, 2),
        "tool_expected": tool_expected_count,
        "tool_correct": tool_correct_count,
        "tool_accuracy_pct": round(tool_acc_pct, 2),
        "keyword_hit_pct": round(keyword_hit_pct, 2),
        "avg_latency_ms": round(avg_latency_ms, 1) if avg_latency_ms is not None else None,
        "gate": {
            "max_echo_rate_pct": GATE_MAX_ECHO_RATE_PCT,
            "min_tool_accuracy_pct": GATE_MIN_TOOL_ACCURACY_PCT,
            "passed": gate_passed,
        },
        "categories": cat_summary,
        "failures": failures,
    }


def format_summary_table(report: Dict[str, Any]) -> str:
    lines = [
        "=== Jarvis 60-Prompt Evaluation Summary ===",
        f"{'Category':<24} {'Eval':>6} {'Echoes':>7} {'ToolAcc':>10} {'KwHit%':>8}",
        "-" * 60,
    ]
    for cat, st in report["categories"].items():
        tool_str = (
            f"{st['tool_correct']}/{st['tool_expected']}"
            if st["tool_expected"] > 0
            else "-"
        )
        lines.append(
            f"{cat:<24} {st['evaluated']:>2}/{st['total']:<3} {st['echoes']:>7} {tool_str:>10} {st['keyword_hit_pct']:>7.1f}%"
        )
    lines.extend(
        [
            "-" * 60,
            f"Overall Evaluated : {report['evaluated']}/{report['total_prompts']}",
            f"Echo Rate         : {report['echo_rate_pct']:.2f}% (gate <= {report['gate']['max_echo_rate_pct']:.1f}%)",
            f"Tool Accuracy     : {report['tool_accuracy_pct']:.2f}% ({report['tool_correct']}/{report['tool_expected']}, gate >= {report['gate']['min_tool_accuracy_pct']:.1f}%)",
            f"Keyword Hit Rate  : {report['keyword_hit_pct']:.2f}%",
            f"Avg Latency       : {report['avg_latency_ms']} ms" if report["avg_latency_ms"] is not None else "Avg Latency       : n/a",
            f"GATE STATUS       : {'PASS' if report['gate']['passed'] else 'FAIL'}",
        ]
    )
    return "\n".join(lines)


def _synthetic_passing_predictions(prompts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    preds: List[Dict[str, Any]] = []
    for idx, item in enumerate(prompts):
        kws = item.get("expected_keywords") or ["ok"]
        kw = str(kws[0])
        if item["expected_type"] == "tool":
            preds.append(
                {
                    "id": item["id"],
                    "type": "tool",
                    "tool": item["expected_tool"],
                    "args": {"value": kw},
                    "latency_ms": 120 + (idx % 5) * 15,
                }
            )
        else:
            preds.append(
                {
                    "id": item["id"],
                    "type": "say",
                    "reply": f"Certainly. Here is the answer: {kw}, handled cleanly without repeating your sentence.",
                    "latency_ms": 180 + (idx % 4) * 20,
                }
            )
    return preds


def _synthetic_failing_predictions(prompts: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    preds: List[Dict[str, Any]] = []
    for idx, item in enumerate(prompts):
        if item["expected_type"] == "tool":
            # Corrupt half of the tool calls so tool accuracy drops well below 80%
            tool_name = None if idx % 2 == 0 else item["expected_tool"]
            preds.append(
                {
                    "id": item["id"],
                    "type": "say" if tool_name is None else "tool",
                    "tool": tool_name,
                    "reply": item["prompt"] if tool_name is None else "",
                    "latency_ms": 420,
                }
            )
        else:
            # Echo user prompt verbatim on say prompts
            preds.append(
                {
                    "id": item["id"],
                    "type": "say",
                    "reply": item["prompt"],
                    "latency_ms": 310,
                }
            )
    return preds


def run_self_test(prompts_path: str) -> int:
    with open(prompts_path, "r", encoding="utf-8") as f:
        prompts = json.load(f)

    assert len(prompts) == 60, f"Expected 60 prompts in {prompts_path}, found {len(prompts)}"
    cats = collections.Counter(p["category"] for p in prompts)
    expected_cats = {
        "factual_qa": 10,
        "echo_traps": 10,
        "tool_calls": 10,
        "chit_chat_reasoning": 10,
        "self_awareness_memory": 10,
        "unknown_web_fallback": 10,
    }
    assert dict(cats) == expected_cats, f"Category counts mismatch: {dict(cats)}"
    assert all(p.get("forbid_echo") is True for p in prompts), "Every prompt must set forbid_echo=true"

    # Unit checks for EchoGuard rules
    assert is_echo("What is the capital of France?", "What is the capital of France?")[0] is True
    assert is_echo("I bought two apples today", "You bought two apples today")[0] is True
    assert is_echo("Set a timer for five minutes", "Timer set for 5 minutes.")[0] is False
    assert is_echo("Tell me a joke", "Why did the photon skip luggage? It was traveling light. traveling light. traveling light.")[0] is True

    pass_report = evaluate(prompts, _synthetic_passing_predictions(prompts))
    assert pass_report["gate"]["passed"] is True, f"Expected passing suite to pass: {pass_report}"
    assert pass_report["echo_rate_pct"] == 0.0
    assert pass_report["tool_accuracy_pct"] == 100.0

    fail_report = evaluate(prompts, _synthetic_failing_predictions(prompts))
    assert fail_report["gate"]["passed"] is False, "Expected echoing/broken suite to fail gate"
    assert fail_report["echo_rate_pct"] > GATE_MAX_ECHO_RATE_PCT
    assert fail_report["tool_accuracy_pct"] < GATE_MIN_TOOL_ACCURACY_PCT

    print(format_summary_table(pass_report))
    print("\n--- Failing Synthetic Suite Check ---")
    print(
        f"Echo Rate={fail_report['echo_rate_pct']}% | "
        f"Tool Accuracy={fail_report['tool_accuracy_pct']}% | "
        f"GATE STATUS={'PASS' if fail_report['gate']['passed'] else 'FAIL'}"
    )
    print("\nSELF-TEST PASSED: 60-prompt schema verified, passing suite PASSED gate, echoing suite FAILED gate.")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    default_prompts = os.path.join(os.path.dirname(os.path.abspath(__file__)), "eval_prompts.json")
    parser = argparse.ArgumentParser(description="Score Jarvis 60-prompt evaluation predictions.")
    parser.add_argument("--prompts", default=default_prompts, help="Path to eval_prompts.json")
    parser.add_argument("--predictions", help="Path to predictions JSONL file")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Print JSON report")
    parser.add_argument("--self-test", action="store_true", help="Run built-in scorer & EchoGuard self-test")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test(args.prompts)

    if not args.predictions:
        parser.error("--predictions is required unless --self-test is specified")

    with open(args.prompts, "r", encoding="utf-8") as f:
        prompts = json.load(f)
    predictions: List[Dict[str, Any]] = []
    with open(args.predictions, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                predictions.append(json.loads(line))

    report = evaluate(prompts, predictions)
    if args.as_json:
        print(json.dumps(report, indent=2))
    else:
        print(format_summary_table(report))
        print("\nJSON Report:")
        print(json.dumps(report, indent=2))
    return 0 if report["gate"]["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
