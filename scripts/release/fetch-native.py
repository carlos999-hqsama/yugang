#!/usr/bin/env python3
"""Fetch pinned Termux native binaries and their upstream/build sources."""
import argparse,hashlib,io,json,shutil,tarfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def sha256(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def download(url,path,sha=None,bundled=None):
 path.parent.mkdir(parents=True,exist_ok=True)
 if bundled is not None:
  if not bundled.is_file() or sha256(bundled)!=sha:
   raise SystemExit('Pinned native input missing or checksum mismatch: '+bundled.name)
  if not path.exists():shutil.copyfile(bundled,path)
 if not path.exists():
  with urllib.request.urlopen(url,timeout=120) as r,path.with_suffix(path.suffix+'.part').open('wb') as f:
   while b:=r.read(1048576):f.write(b)
  path.with_suffix(path.suffix+'.part').replace(path)
 digest=sha256(path)
 if sha and digest!=sha:raise SystemExit('Checksum mismatch: '+path.name)
 return digest
def ar_data(data):
 assert data[:8]==b'!<arch>\n';pos=8
 while pos<len(data):
  name=data[pos:pos+16].decode().strip().rstrip('/');size=int(data[pos+48:pos+58]);start=pos+60
  if name.startswith('data.tar'):return data[start:start+size]
  pos=start+size+size%2
 raise ValueError('Missing Debian data archive')
def main():
 p=argparse.ArgumentParser();p.add_argument('--cache',type=Path,required=True);a=p.parse_args()
 spec=json.loads((ROOT/'scripts/release/native-lock.json').read_text());out=ROOT/'android/app/src/main/jniLibs/arm64-v8a';out.mkdir(parents=True,exist_ok=True)
 records=[]
 for item in spec['packages']:
  path=a.cache/'packages'/Path(item['url']).name
  download(item['url'],path,item['sha256'],ROOT/'scripts/release/native-inputs/packages'/path.name)
  with tarfile.open(fileobj=io.BytesIO(ar_data(path.read_bytes())),mode='r:*') as tar:
   for target,suffix in item['files'].items():
    members=[m for m in tar if m.isfile() and m.name.endswith('/'+suffix)]
    if len(members)!=1:raise SystemExit('Ambiguous native file '+suffix)
    payload=tar.extractfile(members[0]).read()
    # Android extracts only lib*.so. Preserve byte offsets while changing the
    # Termux SONAME/NEEDED string to the packaged filename (no code modification).
    payload=payload.replace(b'libtalloc.so.2\0',b'libtalloc.so\0\0\0')
    (out/target).write_bytes(payload);(out/target).chmod(0o755)
  source=a.cache/'corresponding-source'/Path(item['source']).name
  digest=download(item['source'],source,item['sourceSha256'],ROOT/'scripts/release/native-inputs/corresponding-source'/source.name)
  records.append({'name':item['name'],'version':item['version'],'license':item['license'],'source':item['source'],'sourceSha256':digest})
 build=a.cache/'corresponding-source'/('termux-packages-'+spec['termuxCommit']+'.tar.gz')
 digest=download('https://codeload.github.com/termux/termux-packages/tar.gz/'+spec['termuxCommit'],build,spec['termuxSha256'],ROOT/'scripts/release/native-inputs/corresponding-source'/build.name)
 records.append({'name':'Termux build recipes and patches','commit':spec['termuxCommit'],'sourceSha256':digest})
 (a.cache/'native-inventory.json').write_text(json.dumps(records,indent=2)+'\n')
 print(json.dumps({'nativeFiles':len(list(out.glob('*.so'))),'sourceArchives':len(records)}))
if __name__=='__main__':main()
