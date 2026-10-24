import hashlib
import importlib.util
import json
import os
import shutil
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOL = ROOT / "android/tools/runtime/snapshot_runtime.py"
spec = importlib.util.spec_from_file_location("runtime_snapshot", TOOL)
snapshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot)


class RuntimeSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.tmp = Path(self.temp.name)
        self.source = self.tmp / "source"
        self.output = self.tmp / "output" / "runtime"
        self.source.mkdir(parents=True)

    def write_runtime(self, label: str):
        archive = (label.encode() * 4096) + b"\n"
        digest = hashlib.sha256(archive).hexdigest()
        (self.source / "runtime.tar.xz").write_bytes(archive)
        (self.source / "runtime.json").write_text(
            json.dumps({"version": label, "sha256": digest}), encoding="utf-8"
        )
        return archive, digest

    def names(self, directory: Path):
        return sorted(path.name for path in directory.iterdir())

    def test_snapshot_contains_only_manifest_and_archive_and_ignores_part(self):
        old_archive, _ = self.write_runtime("old")
        (self.source / "runtime.tar.xz.part").write_bytes(b"incomplete")

        snapshot.snapshot_runtime(
            self.source / "runtime.json",
            self.source / "runtime.tar.xz",
            self.output,
        )

        self.assertEqual(self.names(self.output), ["runtime.json", "runtime.tar.xz"])
        self.assertEqual((self.output / "runtime.tar.xz").read_bytes(), old_archive)
        self.assertFalse(list(self.output.parent.glob(".runtime.stage-*")))

    def test_replacement_between_file_opens_rejects_mixed_pair(self):
        self.write_runtime("old")
        new_archive = (b"new" * 4096) + b"\n"
        replacement = self.source / "replacement.tar.xz"
        replacement.write_bytes(new_archive)
        self.write_runtime("old")
        original_open = snapshot.open_regular

        def open_with_mid_capture_race(path, *_args, **_kwargs):
            if path == self.source / "runtime.json":
                replacement.replace(self.source / "runtime.tar.xz")
            return original_open(path)

        output_archive = b"prior valid snapshot\n"
        output_digest = hashlib.sha256(output_archive).hexdigest()
        self.output.parent.mkdir(parents=True)
        self.output.mkdir()
        (self.output / "runtime.tar.xz").write_bytes(output_archive)
        (self.output / "runtime.json").write_text(
            json.dumps({"version": "prior", "sha256": output_digest})
        )

        snapshot.open_regular = open_with_mid_capture_race
        try:
            with self.assertRaisesRegex(ValueError, "does not match captured manifest"):
                snapshot.snapshot_runtime(
                    self.source / "runtime.json",
                    self.source / "runtime.tar.xz",
                    self.output,
                )
        finally:
            snapshot.open_regular = original_open

        self.assertEqual((self.output / "runtime.tar.xz").read_bytes(), output_archive)
        self.assertEqual(
            json.loads((self.output / "runtime.json").read_text())["version"], "prior"
        )

    def test_archive_rename_during_copy_cannot_change_open_snapshot(self):
        old_archive, _ = self.write_runtime("old")
        replacement = self.source / "replacement.tar.xz"
        replacement_bytes = (b"unrelated-runtime" * 4096) + b"\n"
        replacement.write_bytes(replacement_bytes)
        original_copy = shutil.copyfileobj

        def copy_with_rename_while_descriptor_is_open(source, destination, length=0):
            first = source.read(11)
            replacement.replace(self.source / "runtime.tar.xz")
            destination.write(first)
            original_copy(source, destination, length=length or 65536)

        shutil.copyfileobj = copy_with_rename_while_descriptor_is_open
        try:
            snapshot.snapshot_runtime(
                self.source / "runtime.json",
                self.source / "runtime.tar.xz",
                self.output,
            )
        finally:
            shutil.copyfileobj = original_copy

        self.assertEqual((self.output / "runtime.tar.xz").read_bytes(), old_archive)
        self.assertEqual(
            (self.source / "runtime.tar.xz").read_bytes(), replacement_bytes
        )

    def test_complete_replacement_refreshes_snapshot(self):
        self.write_runtime("old")
        snapshot.snapshot_runtime(
            self.source / "runtime.json",
            self.source / "runtime.tar.xz",
            self.output,
        )
        new_archive, _ = self.write_runtime("new")
        snapshot.snapshot_runtime(
            self.source / "runtime.json",
            self.source / "runtime.tar.xz",
            self.output,
        )

        self.assertEqual((self.output / "runtime.tar.xz").read_bytes(), new_archive)
        self.assertEqual(
            json.loads((self.output / "runtime.json").read_text())["version"], "new"
        )

    def test_invalid_replacement_failure_preserves_valid_snapshot(self):
        self.write_runtime("old")
        snapshot.snapshot_runtime(
            self.source / "runtime.json",
            self.source / "runtime.tar.xz",
            self.output,
        )
        old_archive = (self.output / "runtime.tar.xz").read_bytes()
        broken_archive = b"not the archived described by this manifest"
        (self.source / "runtime.tar.xz").write_bytes(broken_archive)

        with self.assertRaisesRegex(ValueError, "does not match captured manifest"):
            snapshot.snapshot_runtime(
                self.source / "runtime.json",
                self.source / "runtime.tar.xz",
                self.output,
            )

        self.assertEqual((self.output / "runtime.tar.xz").read_bytes(), old_archive)
        self.assertEqual(self.names(self.output), ["runtime.json", "runtime.tar.xz"])

    def test_directory_publication_failure_restores_previous_snapshot(self):
        self.output.parent.mkdir(parents=True)
        self.output.mkdir()
        (self.output / "runtime.tar.xz").write_bytes(b"previous valid archive\n")
        stage = self.output.parent / "runtime.stage"
        stage.mkdir()
        (stage / "runtime.tar.xz").write_bytes(b"new archive\n")
        original_replace = os.replace
        replacements = 0

        def fail_when_publishing_stage(source, destination):
            nonlocal replacements
            replacements += 1
            if replacements == 2:
                raise OSError("simulated failure publishing new directory")
            original_replace(source, destination)

        os.replace = fail_when_publishing_stage
        try:
            with self.assertRaisesRegex(OSError, "simulated failure"):
                snapshot.publish_directory(stage, self.output)
        finally:
            os.replace = original_replace

        self.assertEqual(
            (self.output / "runtime.tar.xz").read_bytes(), b"previous valid archive\n"
        )
        self.assertEqual(list(self.output.parent.glob("*.previous-*")), [])


if __name__ == "__main__":
    unittest.main()
