#!/usr/bin/env python3
"""Verify the installed-plugin/APK boundary; never read or export phone data."""
import argparse
import json
from pathlib import Path
import zipfile

FORBIDDEN_ASSETS = {
    'assets/www/js/nas.js',
    'assets/www/js/mac-usage.js',
    'assets/plugin-framework/v1/js/nas.js',
    'assets/plugin-framework/v1/js/mac-usage.js',
    'assets/plugin-framework/v1/js/local-quota.js',
    'assets/plugin-framework/v1/js/monitor-settings.js',
}

def forbidden(name):
    parts=Path(name).parts
    if name.startswith('assets/plugins/'):
        return True
    if name in FORBIDDEN_ASSETS:
        return True
    if name.startswith('assets/') and any(p in {'BOT','PLUGINS','CONFIG','shared_prefs','recovery','sessions'} for p in parts):
        return True
    if name.startswith('assets/root/projects/.dsh/'):
        return True
    return name.startswith('assets/') and Path(name).name in {'registry.json','bots.json','workspace.json'}

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('apk',type=Path)
a=p.parse_args()
with zipfile.ZipFile(a.apk) as z:
    names=z.namelist()
    failures=[n for n in names if forbidden(n)]
    expected={'assets/www/index.html','assets/plugin-framework/v1/page.js','assets/plugin-framework/v1/base.css'}
    missing=sorted(expected-set(names))
    print(json.dumps({'apk':a.apk.name,'boundaryPassed':not failures and not missing,'forbiddenEntries':failures,'missingCore':missing,'note':'Checks packaging paths; source/content and dependency licensing review remain separate.'},ensure_ascii=False,indent=2))
    raise SystemExit(bool(failures or missing))
