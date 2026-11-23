#!/usr/bin/env python3
"""Generate app/src/main/assets/models/manifest.json: the pinned set of model downloads.

Run from the repository root with `python3 -I scripts/make-model-manifest.py`. Standard library only.

- sherpa-onnx archives and files are downloaded (cached in bench/host-tools/manifest-cache/, git-ignored) and
  hashed; their sha256 is cross-checked against the GitHub release asset digest when GitHub reports one.
- Hugging Face files are pinned to the repository's current commit and checked against the LFS sha256 from the
  HF API. A copy already under models/ is reused when its hash matches.
- Archive listings (read with tarfile) give mustExist and unpackedSize, applying the same strip/drop rules the
  app's Unpacker uses. Overlay files are read from app/src/main/assets/models/bundled/, so copy those first.
"""
import hashlib
import json
import os
import tarfile
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..'))
MODELS = os.path.join(ROOT, 'models')
CACHE = os.path.join(ROOT, 'bench', 'host-tools', 'manifest-cache')
ASSETS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'models')
OUT = os.path.join(ASSETS, 'manifest.json')
SHERPA_REPO = 'k2-fsa/sherpa-onnx'
UA = 'jarvis-manifest-generator'


def github(tag, name):
    return {'host': 'github', 'tag': tag, 'name': name}


def hf(repo, name):
    return {'host': 'hf', 'repo': repo, 'name': name}


# Fields: id, label, required, kind, dest, source, drop, overlay, mustExtra (folders that must exist too).
ITEMS = [
    dict(id='kws', label='Wake word', required=True, kind='tar.bz2', dest='kws',
         source=github('kws-models', 'sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2'),
         drop=[], overlay=['kws/keywords.txt'], mustExtra=[]),
    dict(id='vad', label='Voice detector', required=True, kind='file', dest='vad/silero_vad.onnx',
         source=github('asr-models', 'silero_vad.onnx'), drop=[], overlay=[], mustExtra=[]),
    dict(id='asr-zipformer', label='Basic hearing', required=True, kind='tar.bz2', dest='asr-zipformer',
         source=github('asr-models', 'sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='asr-live', label='Live hearing', required=True, kind='tar.bz2', dest='asr-live',
         source=github('asr-models', 'sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='asr-parakeet', label='Accurate hearing', required=False, kind='tar.bz2', dest='asr-parakeet',
         source=github('asr-models', 'sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8.tar.bz2'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='tts', label='Voice · Alan', required=True, kind='tar.bz2', dest='tts',
         source=github('tts-models', 'vits-piper-en_GB-alan-medium.tar.bz2'),
         drop=[], overlay=[], mustExtra=['tts/espeak-ng-data']),
    dict(id='qwen-1.5b', label='Brain · Qwen2.5 1.5B', required=True, kind='file',
         dest='llm/qwen2.5-1.5b-instruct-q4_0.gguf',
         source=hf('Qwen/Qwen2.5-1.5B-Instruct-GGUF', 'qwen2.5-1.5b-instruct-q4_0.gguf'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='voice-arctic', label='Voice · Arctic', required=False, kind='tar.bz2', dest='tts-voices/arctic',
         source=github('tts-models', 'vits-piper-en_US-arctic-medium.tar.bz2'),
         drop=['espeak-ng-data'], overlay=[], mustExtra=[]),
    dict(id='voice-l2arctic', label='Voice · L2-Arctic', required=False, kind='tar.bz2',
         dest='tts-voices/l2arctic',
         source=github('tts-models', 'vits-piper-en_US-l2arctic-medium.tar.bz2'),
         drop=['espeak-ng-data'], overlay=[], mustExtra=[]),
    dict(id='voice-kusal', label='Voice · Kusal', required=False, kind='tar.bz2', dest='tts-voices/kusal',
         source=github('tts-models', 'vits-piper-en_US-kusal-medium.tar.bz2'),
         drop=['espeak-ng-data'], overlay=[], mustExtra=[]),
    dict(id='asr-moonshine', label='Hearing fallback', required=False, kind='tar.bz2', dest='asr-moonshine',
         source=github('asr-models', 'sherpa-onnx-moonshine-tiny-en-int8.tar.bz2'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='qwen-0.5b', label='Backup brain · Qwen2.5 0.5B', required=False, kind='file',
         dest='llm/qwen2.5-0.5b-instruct-q4_0.gguf',
         source=hf('Qwen/Qwen2.5-0.5B-Instruct-GGUF', 'qwen2.5-0.5b-instruct-q4_0.gguf'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='vlm', label='Vision', required=False, kind='file', dest='vlm/SmolVLM-256M-Instruct-Q8_0.gguf',
         source=hf('ggml-org/SmolVLM-256M-Instruct-GGUF', 'SmolVLM-256M-Instruct-Q8_0.gguf'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='vlm-mmproj', label='Vision projector', required=False, kind='file',
         dest='vlm/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf',
         source=hf('ggml-org/SmolVLM-256M-Instruct-GGUF', 'mmproj-SmolVLM-256M-Instruct-Q8_0.gguf'),
         drop=[], overlay=[], mustExtra=[]),
    dict(id='embed', label='Memory embedder', required=False, kind='file',
         dest='embed/all-MiniLM-L6-v2-Q8_0.gguf',
         source=hf('second-state/All-MiniLM-L6-v2-Embedding-GGUF', 'all-MiniLM-L6-v2-Q8_0.gguf'),
         drop=[], overlay=[], mustExtra=[]),
]


def fetch_json(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, dest):
    """Stream url to dest (through dest.part) unless dest already exists; a short body is an error."""
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    part = dest + '.part'
    print('downloading', url, flush=True)
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req) as resp, open(part, 'wb') as out:
        expected = resp.headers.get('Content-Length')
        written = 0
        for chunk in iter(lambda: resp.read(1 << 20), b''):
            out.write(chunk)
            written += len(chunk)
    if expected is not None and int(expected) != written:
        raise IOError(f'short download of {url}: {written} of {expected} bytes')
    os.replace(part, dest)
    return dest


def github_asset(tag, name):
    release = fetch_json(f'https://api.github.com/repos/{SHERPA_REPO}/releases/tags/{tag}')
    for asset in release.get('assets', []):
        if asset['name'] == name:
            return asset
    raise RuntimeError(f'no GitHub asset {name} in release {tag}')


def resolve_github(source):
    asset = github_asset(source['tag'], source['name'])
    url = f"https://github.com/{SHERPA_REPO}/releases/download/{source['tag']}/{source['name']}"
    path = download(url, os.path.join(CACHE, source['name']))
    size = os.path.getsize(path)
    sha = sha256_of(path)
    if asset.get('size') is not None and asset['size'] != size:
        raise RuntimeError(f"{source['name']}: size {size} differs from GitHub's {asset['size']}")
    digest = asset.get('digest')
    if digest and digest != 'sha256:' + sha:
        raise RuntimeError(f"{source['name']}: sha256 {sha} differs from GitHub's {digest}")
    return url, size, sha


def resolve_hf(source):
    repo, name = source['repo'], source['name']
    commit = fetch_json(f'https://huggingface.co/api/models/{repo}')['sha']
    tree = fetch_json(f'https://huggingface.co/api/models/{repo}/tree/{commit}')
    entry = next(e for e in tree if e['path'] == name)
    lfs = entry['lfs']
    expected_sha, expected_size = lfs['oid'], lfs['size']  # HF's LFS oid is the sha256
    url = f'https://huggingface.co/{repo}/resolve/{commit}/{name}'
    path = None
    for folder in ('llm', 'embed', 'vlm'):
        cand = os.path.join(MODELS, folder, name)
        if os.path.isfile(cand) and os.path.getsize(cand) == expected_size and sha256_of(cand) == expected_sha:
            path = cand
            print('reusing', cand, flush=True)
            break
    if path is None:
        path = download(url, os.path.join(CACHE, name))
    size = os.path.getsize(path)
    sha = sha256_of(path)
    if sha != expected_sha or size != expected_size:
        raise RuntimeError(f'{name}: got {size} bytes sha256 {sha}, HF lfs says {expected_size} {expected_sha}')
    return url, size, sha


def archive_listing(path, spec):
    """Return (mustExist, unpackedSize) for a tar.bz2 after the Unpacker's strip and drop rules."""
    must, total = [], 0
    with tarfile.open(path, 'r:bz2') as tar:
        for member in tar.getmembers():
            parts = member.name.split('/', 1)
            if len(parts) < 2 or not parts[1]:
                continue  # the top folder itself
            rel = parts[1].rstrip('/')
            if '..' in rel.split('/') or rel.startswith('/'):
                raise RuntimeError(f'unsafe path in {path}: {member.name}')
            if any(rel == d or rel.startswith(d + '/') for d in spec['drop']):
                continue
            if not member.isreg():
                continue
            must.append(f"{spec['dest']}/{rel}")
            total += member.size
    for overlay in spec['overlay']:
        if overlay not in must:
            must.append(overlay)
            total += os.path.getsize(os.path.join(ASSETS, 'bundled', overlay))
    must.extend(spec['mustExtra'])
    return sorted(set(must)), total


def build_item(spec):
    source = spec['source']
    if source['host'] == 'github':
        url, size, sha = resolve_github(source)
    else:
        url, size, sha = resolve_hf(source)
    entry = {
        'id': spec['id'],
        'label': spec['label'],
        'required': spec['required'],
        'url': url,
        'size': size,
        'sha256': sha,
        'kind': spec['kind'],
        'dest': spec['dest'],
        'drop': spec['drop'],
        'overlay': spec['overlay'],
    }
    if spec['kind'] == 'file':
        entry['mustExist'] = [spec['dest']]
        entry['unpackedSize'] = size
    else:
        archive = os.path.join(CACHE, source['name'])
        entry['mustExist'], entry['unpackedSize'] = archive_listing(archive, spec)
    return entry


def main():
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    items = []
    for spec in ITEMS:
        items.append(build_item(spec))
        print(f"{spec['id']}: {items[-1]['size']} bytes, {len(items[-1]['mustExist'])} must-exist", flush=True)
    manifest = {'version': 1, 'items': items}
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(manifest, f, sort_keys=True, indent=2, ensure_ascii=False)
        f.write('\n')
    print('wrote', OUT, flush=True)


if __name__ == '__main__':
    main()
