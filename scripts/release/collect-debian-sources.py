#!/usr/bin/env python3
"""Collect exact Debian source packages for the pinned root filesystem."""
import argparse,concurrent.futures,hashlib,json,lzma,re,tarfile,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('rootfs',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
def fields(text):
 d={};key=None
 for line in text.splitlines():
  if line.startswith(' ') and key:d[key]+='\n'+line[1:]
  elif ':' in line:key,value=line.split(':',1);d[key]=value.lstrip()
 return d
with tarfile.open(a.rootfs) as t:
 member=next(m for m in t if m.name.lstrip('./')=='var/lib/dpkg/status');status=t.extractfile(member).read().decode()
wanted={}
for block in status.split('\n\n'):
 d=fields(block)
 if d.get('Status')!='install ok installed':continue
 src=d.get('Source',d['Package']);m=re.fullmatch(r'([^ ]+)(?: \(([^)]+)\))?',src)
 wanted[(m[1],m[2] or d['Version'])]=[]
indexes=[('https://deb.debian.org/debian','trixie'),('https://deb.debian.org/debian','trixie-updates'),('https://security.debian.org/debian-security','trixie-security')]
found={}
for base,suite in indexes:
 raw=urllib.request.urlopen(f'{base}/dists/{suite}/main/source/Sources.xz',timeout=120).read()
 for block in lzma.decompress(raw).decode().split('\n\n'):
  d=fields(block);key=(d.get('Package'),d.get('Version'))
  if key in wanted:found[key]=(base,d)
missing=sorted(set(wanted)-set(found));(a.output/'missing.json').write_text(json.dumps(missing,indent=2))
jobs=[];inventory=[]
for (name,version),(base,d) in found.items():
 files=[]
 for line in d['Checksums-Sha256'].splitlines():
  parts=line.split()
  if len(parts)!=3:continue
  sha,size,filename=parts;url=f"{base}/{d['Directory']}/{filename}";jobs.append((url,a.output/name/filename,sha));files.append({'file':name+'/'+filename,'url':url,'sha256':sha})
 inventory.append({'name':name,'version':version,'files':files})
def get(job):
 url,path,sha=job;path.parent.mkdir(exist_ok=True)
 if not path.exists():
  with urllib.request.urlopen(url,timeout=120) as r,path.with_suffix(path.suffix+'.part').open('wb') as f:
   while b:=r.read(1048576):f.write(b)
  path.with_suffix(path.suffix+'.part').replace(path)
 if hashlib.file_digest(path.open('rb'),'sha256').hexdigest()!=sha:raise ValueError('Checksum mismatch: '+path.name)
 return path.name
print(json.dumps({'sourcePackages':len(found),'missing':missing,'downloads':len(jobs)}),flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
 for name in pool.map(get,jobs):print(name,flush=True)
(a.output/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
if missing:raise SystemExit('Exact source versions missing; do not publish binaries')
