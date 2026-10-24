#!/usr/bin/env python3
"""Download the exact public runtime layers. No maintainer account is required."""
import hashlib,json,os,urllib.request
from pathlib import Path
HERE=Path(__file__).resolve().parent
LOCK=json.loads((HERE/'runtime-lock.json').read_text())
DL=Path(os.environ.get('RT',HERE.parents[2]/'.rtbuild'))/'dl';DL.mkdir(parents=True,exist_ok=True)
def get(url,path,digest,headers=None):
 if path.exists() and hashlib.file_digest(path.open('rb'),'sha256').hexdigest()==digest:return
 part=path.with_suffix(path.suffix+'.part')
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers or {}),timeout=120) as r,part.open('wb') as f:
  while chunk:=r.read(1024*1024):f.write(chunk)
 if hashlib.file_digest(part.open('rb'),'sha256').hexdigest()!=digest:raise SystemExit('Checksum mismatch: '+path.name)
 part.replace(path)
n=LOCK['node'];get(f"https://nodejs.org/dist/{n['version']}/{n['filename']}",DL/n['filename'],n['sha256'])
npm=LOCK['npm'];get(npm['url'],DL/npm['filename'],npm['sha256'])
ip_address=LOCK['npmIpAddress'];get(ip_address['url'],DL/ip_address['filename'],ip_address['sha256'])
d=LOCK['debian'];path=DL/'debian-rootfs.tar.gz';digest=d['layer'].split(':')[1]
if not path.exists() or hashlib.file_digest(path.open('rb'),'sha256').hexdigest()!=digest:
 token=json.load(urllib.request.urlopen('https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull'))['token']
 get('https://registry-1.docker.io/v2/library/debian/blobs/'+d['layer'],path,digest,{'Authorization':'Bearer '+token})
(DL/'debian.source').write_text(f"debian:trixie-slim arm64/v8 manifest={d['manifest']} layer={d['layer']}\n")
print('Verified pinned Node, npm, ip-address security update and Debian layers')
