"""Validate the actual ARM64 image runtime before publishing an archive."""
import json
from pathlib import Path


def is_metadata(path):
    return any(part.startswith('._') or part in {'.DS_Store', '__MACOSX'}
               for part in Path(path).parts)


def verify_target_packages(base, lock):
    base = Path(base)
    packages = lock['packages']
    required = ('sharp', '@img/sharp-linux-arm64', '@img/sharp-libvips-linux-arm64')
    for name in required:
        relative = 'node_modules/' + name
        expected = packages[relative]['version']
        try:
            actual = json.loads((base / relative / 'package.json').read_text())
        except (OSError, ValueError) as error:
            raise ValueError(f'Missing target dependency {name}@{expected}; rerun fetch.sh') from error
        if actual.get('name') != name or actual.get('version') != expected:
            raise ValueError(f'Target dependency does not match package-lock: {name}@{expected}')
    for pattern in ('@img/sharp-linux-arm64/lib/*.node',
                    '@img/sharp-libvips-linux-arm64/lib/*.so.*'):
        binaries = list((base / 'node_modules').glob(pattern))
        if not binaries:
            raise ValueError(f'Missing ARM64 image library: {pattern}; rerun fetch.sh')
        for binary in binaries:
            with binary.open('rb') as stream:
                header = stream.read(20)
            if (header[:6] != b'\x7fELF\x02\x01' or header[18:20] != b'\xb7\x00'):
                raise ValueError(f'Image library is not little-endian ELF64 ARM64: {binary.name}')


if __name__ == '__main__':
    import sys
    lock = json.loads((Path(__file__).parent / 'package-lock.json').read_text())
    try:
        verify_target_packages(sys.argv[1], lock)
    except (OSError, ValueError) as error:
        raise SystemExit(str(error)) from error
    print('Pinned ARM64 image runtime dependencies verified')
