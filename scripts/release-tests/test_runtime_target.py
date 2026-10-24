import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('runtime_inputs', ROOT / 'android/tools/runtime/runtime_inputs.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeTargetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.lock = {'packages': {}}
        for name, version in [('sharp', '0.35.5'), ('@img/sharp-linux-arm64', '0.35.5'),
                              ('@img/sharp-libvips-linux-arm64', '1.3.4')]:
            relative = 'node_modules/' + name
            self.lock['packages'][relative] = {'version': version}
            folder = self.base / relative
            folder.mkdir(parents=True)
            (folder / 'package.json').write_text(json.dumps({'name': name, 'version': version}))
        for relative in ['@img/sharp-linux-arm64/lib/sharp-linux-arm64.node',
                         '@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.18.7']:
            binary = self.base / 'node_modules' / relative
            binary.parent.mkdir(parents=True)
            binary.write_bytes(b'\x7fELF\x02\x01' + b'\x00' * 12 + b'\xb7\x00')

    def test_target_uses_locked_packages_and_arm64_binaries(self):
        runtime.verify_target_packages(self.base, self.lock)
        target = self.base / 'node_modules/@img/sharp-libvips-linux-arm64/package.json'
        target.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing target dependency'):
            runtime.verify_target_packages(self.base, self.lock)

    def test_wrong_version_does_not_pass_as_the_locked_dependency(self):
        target = self.base / 'node_modules/@img/sharp-linux-arm64/package.json'
        target.write_text(json.dumps({'name': '@img/sharp-linux-arm64', 'version': '0.0.0'}))
        with self.assertRaisesRegex(ValueError, 'does not match package-lock'):
            runtime.verify_target_packages(self.base, self.lock)

    def test_missing_or_host_architecture_library_is_rejected(self):
        target = self.base / 'node_modules/@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.18.7'
        target.write_bytes(b'\x7fELF\x02\x01' + b'\x00' * 12 + b'\x3e\x00')
        with self.assertRaisesRegex(ValueError, 'not little-endian ELF64 ARM64'):
            runtime.verify_target_packages(self.base, self.lock)
        target.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing ARM64 image library'):
            runtime.verify_target_packages(self.base, self.lock)

    def test_metadata_does_not_hide_real_source_files(self):
        for name in ['opt/._pd', 'opt/pd/.DS_Store', '__MACOSX/opt/pd/source.mjs']:
            self.assertTrue(runtime.is_metadata(name))
        for name in ['opt/pd/source.mjs', 'opt/pd/.well-known/config', 'node_modules/@img/lib.so']:
            self.assertFalse(runtime.is_metadata(name))


if __name__ == '__main__':
    unittest.main()
