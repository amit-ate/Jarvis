package com.jarvis.edge.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PersonalSpeechModelTest {
    private val dict by lazy { WordList.fromFile(File("src/main/assets/english_words.txt")) }

    @Test
    fun `word list knows English words and their common endings`() {
        assertTrue(dict.contains("timer"))
        assertTrue(dict.contains("timers"))
        assertTrue(dict.contains("parked"))
        assertTrue(dict.contains("minutes"))
        assertFalse(dict.contains("tymers"))
        assertFalse(dict.contains("dummmies"))
    }

    @Test
    fun `a confusion seen before is fixed in context`() {
        val m = PersonalSpeechModel(null, dict)
        repeat(3) { m.learnText("set a timer for five minutes") }
        // "fine" is a real word, so the confusion has to be seen twice before it is trusted.
        repeat(2) { m.learnPair("set a timer for fine minutes", "set a timer for five minutes") }
        val fix = m.correct("Set a timer for fine minutes.")
        assertEquals("Set a timer for five minutes.", fix.text)
        assertEquals(listOf("fine" to "five"), fix.changes)
    }

    @Test
    fun `a non-word that sounds like one of the user's words is fixed`() {
        val m = PersonalSpeechModel(null, dict)
        repeat(3) { m.learnText("cancel all my timers") }
        assertEquals("Cancel all my timers.", m.correct("Cancel all my tymers.").text)
    }

    @Test
    fun `correct English and unrelated sentences are left alone`() {
        val m = PersonalSpeechModel(null, dict)
        repeat(3) { m.learnText("set a timer for five minutes") }
        m.learnPair("set a timer for fine minutes", "set a timer for five minutes")
        for (s in listOf("I feel fine today.", "What is the capital of France?", "Tell me a joke.", "Call Priyanka.")) {
            assertEquals(s, m.correct(s).text, s)
        }
    }

    @Test
    fun `a correction teaches the name`() {
        val m = PersonalSpeechModel(null, dict)
        m.learnCorrection("send a message to raoul", "send a message to rahul")
        m.learnText("call rahul")
        assertEquals("Send a message to Rahul.", m.correct("Send a message to Raoul.").text)
    }

    @Test
    fun `learning survives a restart`(@TempDir dir: File) {
        val f = File(dir, "psm.json")
        PersonalSpeechModel(f, dict).apply {
            repeat(3) { learnText("cancel all my timers") }
            save()
        }
        assertEquals("cancel all my timers", PersonalSpeechModel(f, dict).correct("cancel all my tymers").text)
    }

    @Test
    fun `alignment pairs substitutions`() {
        val a = PersonalSpeechModel.align(listOf("call", "on", "my", "brother"), listOf("call", "my", "brother"))
        assertEquals(listOf("call" to "call", "on" to null, "my" to "my", "brother" to "brother"), a)
    }

    /**
     * Replays the user's read-aloud recordings (5-fold, held-out sentences; transcripts from recognisers that never
     * trained on them) to measure the model on real mistakes. Needs JARVIS_PSM_EVAL=psm_eval.json from
     * tools/voice-train (private data, not in the repo).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "JARVIS_PSM_EVAL", matches = ".+")
    fun `evaluation on the user's recordings`() {
        val data = JSONObject(File(System.getenv("JARVIS_PSM_EVAL")).readText())
        val folds = data.getJSONArray("folds")
        val rows = (0 until folds.length()).map { folds.getJSONObject(it) }
        var before = 0; var after = 0; var total = 0
        val changes = ArrayList<String>()
        for (k in 0 until 5) {
            val m = PersonalSpeechModel(null, dict)
            rows.filter { it.getInt("fold") == k && it.getString("split") == "train" }.forEach {
                m.learnPair(it.getString("tuned"), it.getString("text"))
            }
            rows.filter { it.getInt("fold") == k && it.getString("split") == "test" }.forEach {
                val ref = norm(it.getString("text"))
                val hyp = it.getString("tuned")
                val fixed = m.correct(hyp)
                total += ref.size
                before += errors(norm(hyp), ref)
                after += errors(norm(fixed.text), ref)
                if (fixed.changes.isNotEmpty()) changes.add("${it.getString("text")} | $hyp -> ${fixed.text}")
            }
        }
        // Everything learned, applied to other voices saying other sentences: must not break them.
        val all = PersonalSpeechModel(null, dict)
        rows.filter { it.getString("split") == "test" }.forEach { all.learnPair(it.getString("tuned"), it.getString("text")) }
        val gen = data.getJSONArray("general")
        var gBefore = 0; var gAfter = 0; var gTotal = 0
        for (i in 0 until gen.length()) {
            val o = gen.getJSONObject(i)
            val ref = norm(o.getString("text"))
            val fixed = all.correct(o.getString("hyp"))
            gTotal += ref.size
            gBefore += errors(norm(o.getString("hyp")), ref)
            gAfter += errors(norm(fixed.text), ref)
            if (fixed.changes.isNotEmpty()) changes.add("GENERAL ${o.getString("text")} | ${o.getString("hyp")} -> ${fixed.text}")
        }
        val report = "user held-out: $before -> $after errors of $total words; other voices: $gBefore -> $gAfter of $gTotal\n" +
            changes.joinToString("\n")
        File(System.getenv("JARVIS_PSM_EVAL") + ".report.txt").writeText(report)
        println(report)
        assertTrue(after <= before && gAfter <= gBefore, report)
    }

    private val numbers = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "favourite" to "favorite", "colour" to "color",
        "kilometres" to "kilometers")

    private fun norm(s: String) = PersonalSpeechModel.words(s.lowercase().replace("wi fi", "wifi").replace("wi-fi", "wifi"))
        .map { numbers[it] ?: it }

    private fun errors(h: List<String>, r: List<String>) = PersonalSpeechModel.align(h, r).count { it.first != it.second }
}
