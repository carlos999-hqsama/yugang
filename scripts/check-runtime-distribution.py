#!/usr/bin/env python3
"""Inspect the shipped runtime archive, never the phone's live private filesystem."""
import argparse
import json
import posixpath
import tarfile
from pathlib import PurePosixPath


def forbidden(name):
    parts = PurePosixPath(name).parts
    if PurePosixPath(name).is_absolute() or '..' in parts or '.git' in parts or any(p.startswith('._') or p in {'.DS_Store', '__MACOSX'} for p in parts):
        return True
    key = '/'.join(parts)
    private = ('root/projects/.dsh', 'BOT', 'PLUGINS', 'CONFIG', 'sessions', 'shared_prefs', 'recovery')
    if any(key == root or key.startswith(root + '/') for root in private):
        return True
    return key in {'registry.json', 'bots.json', 'workspace.json'}


def forbidden_link(member):
    target = member.linkname
    if member.issym() and not PurePosixPath(target).is_absolute():
        target = posixpath.join(str(PurePosixPath(member.name).parent), target)
    # Absolute public rootfs links are normal; private targets and escapes are not.
    return forbidden(posixpath.normpath(target).lstrip('/'))


def inspect(archive):
    rejected = []
    count = 0
    public_licenses = 0
    generated_patch = False
    with tarfile.open(archive, 'r|xz') as stream:
        for member in stream:
            count += 1
            if forbidden(member.name):
                rejected.append(member.name)
            if (member.issym() or member.islnk()) and forbidden_link(member):
                rejected.append(member.name + ' -> ' + member.linkname)
            if member.name.endswith(('/LICENSE', '/copyright', '/LICENSE.txt')):
                public_licenses += 1
            if member.name == 'opt/pd/standard-questions.patch.yml' and member.isfile():
                data = stream.extractfile(member).read().decode('utf-8')
                generated_patch = 'id: preset-standard' in data and 'file:///opt/pd/app-tools.mjs' in data
    return {'entries': count, 'privateEntries': rejected, 'licenseEntries': public_licenses,
            'standardPatchIsPublicTemplate': generated_patch,
            'passed': not rejected and generated_patch and public_licenses > 0}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive')
    result = inspect(parser.parse_args().archive)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
