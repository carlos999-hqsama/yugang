#!/usr/bin/env python3
"""Extract actual bundled license texts and package metadata, without executing code."""
import argparse,html,json,re,tarfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('runtime',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
packages={};texts=[]
with tarfile.open(a.runtime,'r|xz') as tar:
 for m in tar:
  if not m.isfile():continue
  n=m.name;name=Path(n).name.lower()
  if name=='package.json' and '/node_modules/' in n:
   try:
    d=json.load(tar.extractfile(m))
    if d.get('name') and d.get('version'):packages[n.rsplit('/',1)[0]]={k:d[k] for k in ['name','version','license','repository','homepage'] if k in d}
   except (ValueError,UnicodeError):pass
  elif re.match(r'^(licen[sc]e|copying|copyright|notice)([._-].*)?$',name) or '/usr/share/common-licenses/' in '/'+n:
   data=tar.extractfile(m).read()
   if len(data)>2_000_000:continue
   try:text=data.decode()
   except UnicodeError:continue
   target=a.output/'texts'/n;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text);texts.append((n,text))
rows=[]
for path,meta in sorted(packages.items()):
 own=[n for n,t in texts if n.startswith(path+'/') and '/node_modules/' not in n[len(path)+1:]]
 rows.append({'path':path,**meta,'licenseFiles':own})
(a.output/'runtime-packages.json').write_text(json.dumps(rows,indent=2)+'\n')
(a.output/'index.html').write_text('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Third-party notices</title><style>body{max-width:900px;margin:auto;padding:24px;font:15px/1.6 system-ui}pre{white-space:pre-wrap;overflow-wrap:anywhere}summary{cursor:pointer;padding:12px;border-bottom:1px solid #ddd}</style><h1>Third-party notices</h1><p>Exact license texts retained from the distributed runtime. Components retain their own licenses.</p>'+''.join('<details><summary>'+html.escape(n)+'</summary><pre>'+html.escape(t)+'</pre></details>' for n,t in texts))
print(json.dumps({'packages':len(rows),'licenseTexts':len(texts),'copyleft':[{'name':r['name'],'version':r['version'],'license':r.get('license'),'licenseFiles':len(r['licenseFiles'])} for r in rows if any(s in str(r.get('license')) for s in ['GPL','MPL'])],'missingOwnLicenseFiles':[r['name'] for r in rows if not r['licenseFiles']]},indent=2))
