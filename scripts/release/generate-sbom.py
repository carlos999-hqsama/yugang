#!/usr/bin/env python3
"""Build an identity SBOM from the actual collected inventories, without guessing dependency edges."""
import argparse
import json
from pathlib import Path
from urllib.parse import quote


def generate(licenses: Path, debian: Path, output: Path) -> None:
    root = Path(__file__).resolve().parents[2]
    components = {}
    runtime = json.loads((licenses / 'runtime-packages.json').read_text())
    for row in runtime:
        purl = f"pkg:npm/{quote(row['name'], safe='/')}@{quote(row['version'], safe='')}"
        item = components.setdefault(purl, {
            'type': 'library', 'name': row['name'], 'version': row['version'], 'purl': purl,
            'properties': [],
        })
        item['properties'].append({'name': 'yugang:runtime-package-path', 'value': row['path']})
        if isinstance(row.get('license'), str) and row['license'].strip():
            choice = {'expression': row['license']}
            if choice not in item.setdefault('licenses', []):
                item['licenses'].append(choice)
    for row in json.loads((licenses / 'maven-inventory.json').read_text()):
        group, name, version = row['coordinate'].split(':')
        purl = f'pkg:maven/{group}/{name}@{version}'
        item = {'type': 'library', 'group': group, 'name': name, 'version': version,
                'purl': purl, 'hashes': [{'alg': 'SHA-256', 'content': row['sha256']}]}
        choices = [{'license': {'name': license['name']}} for license in row['licenses']
                   if license.get('name')]
        if choices:
            item['licenses'] = choices
        components[purl] = item
    for row in json.loads((debian / 'inventory.json').read_text()):
        purl = f"pkg:deb/debian/{row['name']}@{quote(row['version'], safe='')}?distro=debian-13"
        components[purl] = {'type': 'library', 'name': row['name'], 'version': row['version'], 'purl': purl}
    native = json.loads((root / 'scripts/release/native-lock.json').read_text())
    for row in native['packages']:
        purl = f"pkg:generic/{row['name']}@{row['version']}"
        components[purl] = {'type': 'library', 'name': row['name'], 'version': row['version'],
                            'purl': purl, 'licenses': [{'license': {'name': row['license']}}]}
    lock = json.loads((root / 'android/tools/runtime/runtime-lock.json').read_text())
    node = lock['node']['version'].removeprefix('v')
    purl = f'pkg:generic/node@{node}'
    components[purl] = {'type': 'application', 'name': 'Node.js', 'version': node, 'purl': purl}
    result = {'bomFormat': 'CycloneDX', 'specVersion': '1.6', 'version': 1,
              'metadata': {'properties': [{'name': 'yugang:scope', 'value':
                  'Actual npm instances (deduplicated identities with all paths), Maven, Debian source packages, '
                  'Termux native components and Node. Not a complete embedded-ELF dependency inventory. '
                  'License fields retain collected declarations; Debian and embedded-component legal terms '
                  'remain in the accompanying complete notices and corresponding source archives.'}]},
              'components': [components[key] for key in sorted(components)]}
    output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'components': len(components), 'runtimeInstances': len(runtime), 'output': str(output)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('licenses', type=Path)
    parser.add_argument('debian_sources', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    generate(args.licenses, args.debian_sources, args.output)
