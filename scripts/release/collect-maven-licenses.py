#!/usr/bin/env python3
import argparse,concurrent.futures,hashlib,io,json,re,urllib.request,xml.etree.ElementTree as E,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('inventory',type=Path);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
rows=json.loads(a.inventory.read_text());ns={'m':'http://maven.apache.org/POM/4.0.0'}
def fetch(url,path):
 if not path.exists():
  path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(urllib.request.urlopen(url,timeout=60).read())
 return path.read_bytes()
def collect(row):
 g,n,v=row['coordinate'].split(':');base=('https://dl.google.com/dl/android/maven2/' if g.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/')+g.replace('.','/')+'/'+n+'/'+v+'/'+n+'-'+v
 folder=a.output/'maven'/row['coordinate'].replace(':','_');folder.mkdir(parents=True,exist_ok=True)
 pom=E.fromstring(fetch(base+'.pom',folder/'pom.xml'));licenses=[{'name':x.findtext('m:name',namespaces=ns),'url':x.findtext('m:url',namespaces=ns)} for x in pom.findall('m:licenses/m:license',ns)]
 artifact=Path(row['file']);texts=[]
 def inspect(data,prefix):
  with zipfile.ZipFile(io.BytesIO(data)) as z:
   for name in z.namelist():
    if name.endswith('/'):continue
    if re.match(r'(?i)^(license|notice|copying)([._-].*)?$',Path(name).name):
     dest=folder/(prefix+'_'+name.replace('/','_'));dest.write_bytes(z.read(name));texts.append(dest.name)
    elif name=='classes.jar':inspect(z.read(name),'classes')
 inspect(artifact.read_bytes(),'binary')
 try:
  data=fetch(base+'-sources.jar',folder/'sources.jar');inspect(data,'sources')
 except Exception as e:raise RuntimeError('Missing exact sources for '+row['coordinate']) from e
 return {'coordinate':row['coordinate'],'sha256':hashlib.file_digest(artifact.open('rb'),'sha256').hexdigest(),'licenses':licenses,'licenseFiles':texts,'sourceArtifact':str((folder/'sources.jar').relative_to(a.output)),'repository':base}
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:out=list(pool.map(collect,rows))
(a.output/'maven-inventory.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps({'dependencies':len(out),'missingLicenseFiles':[x['coordinate'] for x in out if not x['licenseFiles']],'missingMetadata':[x['coordinate'] for x in out if not x['licenses']]},indent=2))
