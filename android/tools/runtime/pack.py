#!/usr/bin/env python3
"""把 fetch.sh 下载的三层（Debian rootfs / Node / dsh）+ overlay 合成一个 runtime.tar.xz，
放到 android/runtime-assets/runtime/（gradle 作为 assets 打进 APK，不进 git/zip）。
用法：python3 pack.py   环境变量：RT=<仓库>/.rtbuild  PD_RT_FULL=1 保留可选重组件。"""
from fixed_workspace import patch_vendor
from runtime_inputs import is_metadata, verify_target_packages
from snapshot_runtime import publish_directory
import atexit, fcntl, hashlib, io, json, lzma, os, re, shutil, sys, tarfile, tempfile, time
from pathlib import Path
HERE = os.path.dirname(os.path.abspath(__file__))
RT = os.environ.get('RT', os.path.join(HERE, '..', '..', '..', '.rtbuild')); DL = os.path.join(RT, 'dl')
# Temporary archives and packer locks live outside runtime-assets, so a failed
# pack can never become an APK asset. This lock coordinates pack.py processes
# only; Android consumers use snapshot_runtime.py instead of pretending that
# flock is a cross-language lock protocol.
OUT = os.path.abspath(os.path.join(HERE, '..', '..', 'runtime-assets', 'runtime'))
PACK_WORK = os.path.abspath(os.path.join(HERE, '..', '..', 'build', 'runtime-pack'))
os.makedirs(PACK_WORK, exist_ok=True)
_pack_lock = open(os.path.join(PACK_WORK, 'pack.lock'), 'a')
try:
    fcntl.flock(_pack_lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit('Runtime packaging is already running; wait for that build.')
FULL = os.environ.get('PD_RT_FULL') == '1'
runtime_lock = json.load(open(os.path.join(HERE, 'runtime-lock.json')))
source_root = Path(HERE).parents[2]
runtime_sources = [Path(HERE) / name for name in (
    'runtime-lock.json', 'package.json', 'package-lock.json', 'pack.py',
    'fixed_workspace.py', 'fetch-runtime.py', 'fetch.sh', 'build-runtime.sh', 'runtime_inputs.py',
    'snapshot_runtime.py')]
runtime_sources += sorted(p for p in (Path(HERE) / 'overlay').rglob('*') if p.is_file() and not is_metadata(p.relative_to(HERE)))
runtime_inputs = {str(p.relative_to(source_root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in runtime_sources}
node_tar = runtime_lock['node']['filename']
node_ver = node_tar.split('-')[1]
dsh_pkg = json.load(open(os.path.join(DL, 'dsh/node_modules/@deepseek-ai/dsh/package.json')))
assert dsh_pkg['version'] == runtime_lock['dsh'], 'Runtime DSH version does not match lock'
office_pkg = json.load(open(os.path.join(DL, 'dsh/node_modules/@deepseek-ai/libreoffice-kit/package.json')))
assert office_pkg['version'] == runtime_lock['officeKit'], 'Office wrapper source version does not match lock'
verify_target_packages(Path(DL) / 'dsh', json.load(open(Path(HERE) / 'package-lock.json')))
# 可选重组件：office→PDF 预览的 LibreOffice wasm（146MB）、实验性语音转文字 sherpa-onnx（39MB）。
# dsh 只在用到时才加载它们，去掉后其余功能不受影响（用到时会报错）。PD_RT_FULL=1 保留。
OPTIONAL = [] if FULL else ['node_modules/@deepseek-ai/libreoffice-kit-wasm', 'node_modules/sherpa-onnx-linux-arm64']
DROP_DSH = [re.compile(p) for p in [r'/prebuilds/(darwin|win32|linux-x64)[^/]*(/|$)', r'/musl(_arm64)?(/|$)', r'\.map$', r'(^|/)node_modules/@types(/|$)']]
DROP_ROOT = [re.compile(p) for p in [r'^\./?usr/share/(man|info|locale)/', r'^\./?etc/(resolv\.conf|hosts)$']]
_stage = Path(tempfile.mkdtemp(prefix='publish-', dir=PACK_WORK))
out_path = str(_stage / 'runtime.tar.xz'); tmp = out_path + '.part'
_committed = False
def _discard_failed_stage():
    if not _committed:
        shutil.rmtree(_stage, ignore_errors=True)
atexit.register(_discard_failed_stage)
stats = {'files': 0, 'bytes': 0}
xz = lzma.LZMAFile(tmp, 'wb', format=lzma.FORMAT_XZ, preset=int(os.environ.get('PD_XZ', '9')))
tw = tarfile.open(fileobj=xz, mode='w|', format=tarfile.GNU_FORMAT)
def put(ti, f=None):
    if is_metadata(ti.name): return
    ti.uid = ti.gid = 0; ti.uname = ti.gname = 'root'
    tw.addfile(ti, f); stats['files'] += 1; stats['bytes'] += ti.size if ti.isreg() else 0
# 1) Debian rootfs（Docker 官方 debian:trixie-slim arm64 层）
with tarfile.open(os.path.join(DL, 'debian-rootfs.tar.gz'), 'r|gz') as t:
    for m in t:
        n = m.name.lstrip('./')
        if m.ischr() or m.isblk() or m.isfifo() or os.path.basename(n).startswith('.wh.'): continue
        if any(p.search('./' + n) for p in DROP_ROOT): continue
        m.name = n
        if m.islnk(): m.linkname = m.linkname.lstrip('./')
        put(m, t.extractfile(m) if m.isreg() else None)
# 2) Node.js → /usr/local
with tarfile.open(os.path.join(DL, node_tar), 'r|xz') as t:
    for m in t:
        parts = m.name.split('/', 1)
        if len(parts) < 2 or not parts[1]: continue
        rel = parts[1]
        if rel.startswith(('include/', 'share/doc/', 'share/man/', 'lib/node_modules/npm/')) or rel == 'lib/node_modules/npm' or rel in ('CHANGELOG.md', 'README.md'): continue
        m.name = 'usr/local/' + rel
        if m.islnk(): m.linkname = 'usr/local/' + m.linkname.split('/', 1)[1]
        put(m, t.extractfile(m) if m.isreg() else None)
# Replace Node's bundled npm with the pinned patched release, retaining npm/npx entry symlinks.
with tarfile.open(os.path.join(DL, runtime_lock['npm']['filename']), 'r|gz') as t:
    for m in t:
        parts = m.name.split('/', 1)
        if len(parts) < 2 or not parts[1]: continue
        rel = parts[1]
        # npm's bundled SOCKS dependency still ships the affected 10.7.2 release.
        # Replace that exact subtree with the separately pinned upstream fix below.
        if rel == 'node_modules/ip-address' or rel.startswith('node_modules/ip-address/'): continue
        m.name = 'usr/local/lib/node_modules/npm/' + rel
        if m.islnk(): m.linkname = 'usr/local/lib/node_modules/npm/' + m.linkname.split('/', 1)[1]
        if rel == 'package.json':
            data = t.extractfile(m).read()
            assert json.loads(data)['version'] == runtime_lock['npm']['version'], 'npm version does not match lock'
            put(m, io.BytesIO(data))
        else:
            put(m, t.extractfile(m) if m.isreg() else None)
# The dependency remains inside socks' declared ^10.1.1 range; npm itself is unchanged.
ip_address = runtime_lock['npmIpAddress']
ip_path = Path(DL) / ip_address['filename']
assert hashlib.sha256(ip_path.read_bytes()).hexdigest() == ip_address['sha256'], 'ip-address source checksum mismatch'
with tarfile.open(ip_path, 'r|gz') as t:
    for m in t:
        parts = m.name.split('/', 1)
        if len(parts) < 2 or not parts[1]: continue
        rel = parts[1]
        m.name = 'usr/local/lib/node_modules/npm/node_modules/ip-address/' + rel
        if m.islnk(): m.linkname = 'usr/local/lib/node_modules/npm/node_modules/ip-address/' + m.linkname.split('/', 1)[1]
        if rel == 'package.json':
            data = t.extractfile(m).read()
            assert json.loads(data)['version'] == ip_address['version'], 'ip-address version does not match lock'
            put(m, io.BytesIO(data))
        else:
            put(m, t.extractfile(m) if m.isreg() else None)
# 3) dsh → /opt/pd/dsh
base = os.path.join(DL, 'dsh'); seen = {}
for root, dirs, files in os.walk(base):
    relroot = os.path.relpath(root, base)
    dirs[:] = sorted(d for d in dirs if os.path.normpath(os.path.join(relroot, d)) not in OPTIONAL
                     and not any(p.search(os.path.normpath(os.path.join(relroot, d)) + '/') for p in DROP_DSH))
    for name in sorted(dirs) + sorted(files):
        full = os.path.join(root, name); rel = os.path.normpath(os.path.join(relroot, name))
        if name in files and any(p.search(rel) for p in DROP_DSH): continue
        ti = tw.gettarinfo(full, 'opt/pd/dsh/' + rel)
        if ti.isreg():
            # Phone policy applies only to the packaged public dependency copy.
            if rel in {f'node_modules/@deepseek-ai/{name}/lib/index.js' for name in [
                'dsh-compaction-basic','dsh-sandbox-policy','dsh-user-approval','dsh-permission-presets',
                'dsh-api-workspace-controller','dsh-api-session-controller','dsh-skill-filesystem','dsh-tool-skill','dsh-agent-instructions','dsh-agent-loop','dsh-subagent','dsh-tool-bash','dsh-tool-fs','dsh-tool-fs-search','dsh-tools','dsh-tool-present']}:
                payload = patch_vendor(rel, open(full, encoding='utf-8').read())
                # Preserve this existing history-search adjustment on the same module.
                if rel == 'node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js':
                    assert payload.count('values: ["current"]') == 1
                    assert payload.count('hit.bestMatch.surface !== "current"') == 1
                    payload = payload.replace('values: ["current"]', 'values: ["current", "shadowed"]')
                    payload = payload.replace('hit.bestMatch.surface !== "current"', '!["current", "shadowed"].includes(hit.bestMatch.surface)')
                payload = payload.encode(); ti.size = len(payload); put(ti, io.BytesIO(payload)); continue
            # Keep the pinned Host RPC, including authorization and result caps,
            # but search retained messages superseded by compaction as well.
            if rel == 'node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js':
                payload = open(full, 'rb').read().decode()
                surface_filter = 'values: ["current"]'
                surface_guard = 'hit.bestMatch.surface !== "current"'
                assert payload.count(surface_filter) == 1 and payload.count(surface_guard) == 1, 'Pinned Host search policy changed'
                payload = payload.replace(surface_filter, 'values: ["current", "shadowed"]')
                payload = payload.replace(surface_guard, '!["current", "shadowed"].includes(hit.bestMatch.surface)')
                payload = payload.encode(); ti.size = len(payload)
                put(ti, io.BytesIO(payload)); continue
            if rel == 'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js':
                payload = open(full, 'rb').read().decode()
                signature = 'async function writeFileAtomic(absolutePath, content, mode, signal, internals = {}, createIfAbsent) {'
                missing = 'return FsVersion(`missing:${target.targetKey}`);'
                assert payload.count(signature) == 1 and payload.count(missing) == 1, 'Pinned filesystem publication changed'
                payload = payload.replace(missing, 'throw new FsError(`cannot write "${target.displayPath}": publication verification failed`, "FS_IO_ERROR");')
                payload = payload.encode(); ti.size = len(payload)
                put(ti, io.BytesIO(payload)); continue
            key = (os.lstat(full).st_dev, os.lstat(full).st_ino)
            if key in seen: ti.type = tarfile.LNKTYPE; ti.linkname = seen[key]; ti.size = 0; put(ti); continue
            seen[key] = ti.name
            with open(full, 'rb') as f: put(ti, f)
        else: put(ti)
# Preset child ids are not top-level loader entries. Preserve the vendor standard
# composition and adjust its child tools/persona; a root tool patch is ignored.
standard = open(os.path.join(base, 'node_modules/@deepseek-ai/dsh-web-app/presets/standard.patch.yml')).read()
assert standard.count('    - id: preset-standard\n') == 1
preset = '\n'.join(line[4:] if line.startswith('    ') else line for line in standard.split('- insert:\n', 1)[1].splitlines()) + '\n'
compaction = """          - id: compaction-basic
            name: '@deepseek-ai/dsh-compaction-basic'"""
assert preset.count(compaction) == 1, 'Pinned compaction preset changed'
preset = preset.replace(compaction, compaction + "\n            config:\n              thresholdRatio: 0.6\n              retainRatio: 0.3")
pattern = r'(?m)^( *)(- id: tool-ask-user\n)( +name: [^\n]+\n)'
def timed_question(match):
    indent = match.group(1)
    return match.group(0) + indent + '  config:\n' + indent + '    mode: timed\n' + indent + '    timeout: 120\n'
persona_suffix = 'suffix: Your working directory is {{cwd}}.'
assert preset.count('suffix: Your working directory is {{cwd}}.') == 1
preset = preset.replace('suffix: Your working directory is {{cwd}}.', persona_suffix)
assert preset.count('      - id: tool-fs-search\n') == 1
preset = preset.replace('      - id: tool-fs-search\n', '      - id: tool-history\n        name: file:///opt/pd/history-tools.mjs\n      - id: tool-fs-search\n')
preset = preset.replace('      - id: tool-fs-search\n', '      - id: tool-app-settings\n        name: file:///opt/pd/app-tools.mjs\n      - id: tool-fs-search\n')
preset = preset.replace('      - id: tool-fs-search\n', '      - id: tool-app-sessions\n        name: file:///opt/pd/app-sessions.mjs\n      - id: tool-fs-search\n')
preset, changes = re.subn(pattern, timed_question, preset)
assert changes == 1, 'Pinned standard preset must contain exactly one ask-user tool'
patch_bytes = preset.encode()
ti = tarfile.TarInfo('opt/pd/standard-questions.patch.yml'); ti.mode = 0o644; ti.size = len(patch_bytes)
put(ti, io.BytesIO(patch_bytes))
# 4) overlay（启动脚本、硬链接垫片、DNS、hosts、xdg-user-dir）
ov = os.path.join(HERE, 'overlay')
for root, dirs, files in os.walk(ov):
    for name in sorted(files):
        full = os.path.join(root, name); rel = os.path.relpath(full, ov)
        ti = tw.gettarinfo(full, rel)
        with open(full, 'rb') as f: put(ti, f)
tw.close(); xz.close()
assert runtime_inputs == {str(p.relative_to(source_root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in runtime_sources}, 'Runtime source changed while packaging; rebuild'
os.replace(tmp, out_path)
h = hashlib.sha256(); [h.update(b) for b in iter(lambda f=open(out_path, 'rb'): f.read(1 << 20), b'')]
deb_src = open(os.path.join(DL, 'debian.source')).read().strip() if os.path.exists(os.path.join(DL, 'debian.source')) else ''
man = {'version': f"{dsh_pkg['version']}+node{node_ver}+{time.strftime('%Y%m%d')}", 'dsh': dsh_pkg['version'], 'node': node_ver,
       'npm': runtime_lock['npm']['version'], 'officeKit': office_pkg['version'], 'debian': deb_src, 'files': stats['files'], 'bytes': stats['bytes'], 'xzBytes': os.path.getsize(out_path),
       'sha256': h.hexdigest(), 'inputs': runtime_inputs, 'optionalDropped': OPTIONAL, 'builtAt': time.strftime('%Y-%m-%dT%H:%M:%S%z')}
with open(_stage / 'runtime.json', 'w', encoding='utf-8') as manifest:
    json.dump(man, manifest, indent=1, ensure_ascii=False)
publish_directory(_stage, Path(OUT), Path(PACK_WORK))
_committed = True
print(json.dumps(man, indent=1, ensure_ascii=False))
