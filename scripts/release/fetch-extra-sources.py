#!/usr/bin/env python3
"""Fetch exact supplemental source archives for redistribution with the APK."""
import argparse,concurrent.futures,hashlib,json,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('destination',type=Path);a=p.parse_args();a.destination.mkdir(parents=True,exist_ok=True)
lock=json.loads(Path(__file__).with_name('extra-sources.lock.json').read_text())
def fetch(row):
 path=a.destination/row['file'];path.parent.mkdir(parents=True,exist_ok=True)
 if not path.exists():
  with urllib.request.urlopen(row['url'],timeout=120) as r,path.with_suffix(path.suffix+'.part').open('wb') as f:
   while b:=r.read(1048576):f.write(b)
  path.with_suffix(path.suffix+'.part').replace(path)
 if hashlib.file_digest(path.open('rb'),'sha256').hexdigest()!=row['sha256']:raise ValueError('Source checksum mismatch: '+row['file'])
 return row['file']
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
 for name in pool.map(fetch,lock['archives']):print(name)
(a.destination/'supplemental-inventory.json').write_text(json.dumps(lock,indent=2)+'\n')
