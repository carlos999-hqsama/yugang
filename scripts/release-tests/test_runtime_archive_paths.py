import importlib.util
import io
from pathlib import Path
import tarfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('runtime_distribution', ROOT / 'scripts/check-runtime-distribution.py')
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class RuntimeArchivePathTests(unittest.TestCase):
    def archive(self, name, target=None, hardlink=False):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w:xz') as archive:
            for path, content in [('pkg/LICENSE', b'public license'),
                                  ('opt/pd/standard-questions.patch.yml', b'id: preset-standard\nfile:///opt/pd/app-tools.mjs')]:
                entry = tarfile.TarInfo(path)
                entry.size = len(content)
                archive.addfile(entry, io.BytesIO(content))
            entry = tarfile.TarInfo(name)
            if target is not None:
                entry.type = tarfile.LNKTYPE if hardlink else tarfile.SYMTYPE
                entry.linkname = target
            archive.addfile(entry)
        data.seek(0)
        return data

    def result(self, name, target=None, hardlink=False):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'runtime.tar.xz'
            path.write_bytes(self.archive(name, target, hardlink).getvalue())
            return checker.inspect(path)

    def test_absolute_and_private_archive_members_are_rejected(self):
        for path in ['/BOT/private/data', '/usr/bin/public', 'CONFIG/PERSONA.md', '../outside']:
            with self.subTest(path=path):
                self.assertFalse(self.result(path)['passed'])

    def test_private_link_targets_are_rejected_after_resolution(self):
        for name, target, hard in [('opt/pd/link', '/BOT/private/data', False),
                                   ('opt/pd/link', '../../CONFIG/PERSONA.md', False),
                                   ('opt/pd/link', 'root/projects/.dsh/config', True)]:
            with self.subTest(target=target):
                self.assertFalse(self.result(name, target, hard)['passed'])

    def test_normal_public_rootfs_links_remain_accepted(self):
        for name, target, hard in [('bin/sh', '/usr/bin/dash', False),
                                   ('usr/local/bin/npm', '../lib/node_modules/npm/bin/npm-cli.js', False),
                                   ('usr/bin/public', './usr/bin/other', True)]:
            with self.subTest(target=target):
                self.assertTrue(self.result(name, target, hard)['passed'])


if __name__ == '__main__':
    unittest.main()
