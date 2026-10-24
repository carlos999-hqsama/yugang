import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fetch_native", ROOT / "scripts/release/fetch-native.py")
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


class NativeInputsTests(unittest.TestCase):
    def test_all_pinned_inputs_have_exact_bytes_and_work_without_network(self):
        lock = json.loads((ROOT / "scripts/release/native-lock.json").read_text())
        inputs = ROOT / "scripts/release/native-inputs"
        cases = [(inputs / "packages" / Path(p["url"]).name, p["sha256"]) for p in lock["packages"]]
        cases += [(inputs / "corresponding-source" / Path(p["source"]).name, p["sourceSha256"]) for p in lock["packages"]]
        cases += [(inputs / "corresponding-source" / ("termux-packages-" + lock["termuxCommit"] + ".tar.gz"), lock["termuxSha256"])]
        with tempfile.TemporaryDirectory() as tmp, patch.object(native.urllib.request, "urlopen", side_effect=AssertionError("network must not be used")):
            for bundled, expected in cases:
                with self.subTest(name=bundled.name):
                    cached = Path(tmp) / bundled.name
                    self.assertEqual(native.download("https://invalid.example/", cached, expected, bundled), expected)
                    self.assertEqual(native.sha256(cached), expected)

    def test_tampered_or_missing_input_fails_even_when_cache_is_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            cached = Path(tmp) / "cache"
            bundled = Path(tmp) / "bundled"
            cached.write_bytes(b"expected")
            bundled.write_bytes(b"tampered")
            digest = hashlib.sha256(cached.read_bytes()).hexdigest()
            for candidate in (bundled, Path(tmp) / "missing"):
                with self.assertRaisesRegex(SystemExit, "Pinned native input missing or checksum mismatch"):
                    native.download("https://invalid.example/", cached, digest, candidate)

    def test_tampered_cache_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            cached = Path(tmp) / "cache"
            bundled = Path(tmp) / "bundled"
            cached.write_bytes(b"bad cache")
            bundled.write_bytes(b"expected")
            with self.assertRaisesRegex(SystemExit, "Checksum mismatch"):
                native.download("https://invalid.example/", cached, hashlib.sha256(bundled.read_bytes()).hexdigest(), bundled)


if __name__ == "__main__":
    unittest.main()
