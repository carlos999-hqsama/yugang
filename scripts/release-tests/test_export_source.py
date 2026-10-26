import importlib.util
import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "release" / "export-source.py"
spec = importlib.util.spec_from_file_location("export_source", SCRIPT)
export_source = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(export_source)


class ExportSourceTests(unittest.TestCase):
    def test_java_regressions_in_android_sources_are_exported(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = Path("android/app/src/test/java/example/NetworkRaceTest.java")
            (root / relative).parent.mkdir(parents=True)
            (root / relative).write_text("class NetworkRaceTest {}\n")
            self.assertIn(relative, export_source._source_candidates(root))

    def test_source_roots_and_selected_file_parents_cannot_be_symlinks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            root.mkdir()
            outside = Path(temporary) / "outside"
            outside.mkdir()
            (outside / "outside.py").write_text("SYNTHETIC_OUTSIDE = True\n")
            (root / "scripts").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                export_source._source_candidates(root)
            (root / "scripts").unlink()
            (outside / "gradlew").write_text("#!/bin/sh\n")
            (root / "android").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                export_source._validate_source(root, Path("android/gradlew"))

    def test_public_template_parent_cannot_be_a_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            root.mkdir()
            outside = Path(temporary) / "outside"
            outside.mkdir()
            for name in export_source.PUBLIC_TEMPLATES:
                file = outside / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("synthetic public template\n")
            (root / "release").symlink_to(outside, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symbolic link"):
                export_source._copy_templates(root, Path(temporary) / "export")

    def copy_native_fixture(self, root: Path) -> None:
        authority = SCRIPT.parents[2]
        for relative in [Path("scripts/release/native-lock.json"), *sorted(export_source.ADMITTED_BINARIES)]:
            if "native-inputs" not in relative.parts and relative.name != "native-lock.json":
                continue
            destination = root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(authority / relative, destination)

    def test_incomplete_or_changed_native_inputs_fail_before_export(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            self.copy_native_fixture(root)
            export_source._validate_native_inputs(root)
            relative = Path("scripts/release/native-inputs/packages/proot_5.1.107.98_aarch64.deb")
            (root / relative).write_bytes(b"changed upstream package")
            destination = Path(temporary) / "export"
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                export_source.export_tree(root, destination)
            self.assertFalse(destination.exists())
            (root / relative).unlink()
            with self.assertRaisesRegex(ValueError, "Missing regular admitted"):
                export_source.export_tree(root, destination)
            self.assertFalse(destination.exists())
            (root / relative).symlink_to(SCRIPT.parents[2] / relative)
            with self.assertRaisesRegex(ValueError, "Missing regular admitted"):
                export_source.export_tree(root, destination)
            self.assertFalse(destination.exists())

    def test_required_persona_fixture_is_admitted_without_other_markdown(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            consumer, fixture = next(iter(export_source.REQUIRED_TEST_FIXTURES.items()))
            (root / consumer).parent.mkdir(parents=True)
            (root / consumer).write_text("// consumes the public seed fixture\n")
            destination = Path(temporary) / "export"
            with self.assertRaisesRegex(ValueError, "required test fixture"):
                export_source.export_tree(root, destination)
            self.assertFalse(destination.exists())
            (root / fixture).parent.mkdir(parents=True)
            (root / fixture).write_text("public synthetic fixture\n")
            private_note = root / fixture.parent / "private-notes.md"
            private_note.write_text("not part of the source contract\n")
            export_source._validate_test_fixtures(root)
            selected = export_source._source_candidates(root)
            self.assertIn(fixture, selected)
            self.assertNotIn(private_note.relative_to(root), selected)
            (root / fixture).unlink()
            (root / fixture).symlink_to(private_note)
            with self.assertRaisesRegex(ValueError, "required test fixture"):
                export_source.export_tree(root, destination)
            self.assertFalse(destination.exists())

    def test_third_party_declarations_are_verbatim(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ("third-party-notices.html", "third-party-inventory.json"):
                source = root / "input" / name
                source.parent.mkdir(exist_ok=True)
                content = "Copyright example Example. BY WAY OF EXAMPLE.\r\n"
                source.write_bytes(content.encode())
                target = root / "output" / name
                export_source._copy_text(source, target)
                self.assertEqual(target.read_bytes(), source.read_bytes())

    def test_public_exporter_reexport_keeps_its_policy_and_example_text(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first/scripts/release/export-source.py"
            export_source._copy_text(SCRIPT, first)
            spec = importlib.util.spec_from_file_location("public_exporter", first)
            public = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(public)
            self.assertEqual(public.REPLACE, [])
            second = root / "second/scripts/release/export-source.py"
            public._copy_text(first, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            sample = root / "sample.txt"
            sample.write_text("EXAMPLE AKIAIOSFODNN7EXAMPLE\n")
            copied = root / "copied.txt"
            public._copy_text(sample, copied)
            self.assertEqual(sample.read_bytes(), copied.read_bytes())

    def test_uses_template_allowlist_and_explicit_binary_admission(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            root = base / "root"
            self.copy_native_fixture(root)
            release = root / "release"
            release.mkdir(parents=True)
            for name in export_source.PUBLIC_TEMPLATES:
                (release / name).parent.mkdir(parents=True, exist_ok=True)
                (release / name).write_text(f"public template: {name}\n", encoding="utf-8")
            (release / "private.env").write_text("TOKEN=secret\n", encoding="utf-8")
            (release / "screenshot.png").write_bytes(b"\x89PNG private")

            (root / "plugins" / "business.js").parent.mkdir(parents=True)
            (root / "plugins" / "business.js").write_text("export {}\n", encoding="utf-8")
            (root / "mac").mkdir()
            (root / "mac" / "private.py").write_text("# private companion\n", encoding="utf-8")

            (root / "scripts" / "release").mkdir(parents=True, exist_ok=True)
            (root / "scripts" / "release" / "token.pem").write_text("PRIVATE KEY\n", encoding="utf-8")
            (root / "scripts" / "release" / "export-source.py").write_text(
                "# fixture copy\n", encoding="utf-8"
            )
            for relative in export_source.EXTRA_SOURCE_FILES:
                fixture = root / relative
                if not fixture.exists():
                    fixture.parent.mkdir(parents=True, exist_ok=True)
                    fixture.write_text("# fixture\n", encoding="utf-8")

            wrapper = root / Path("android/gradle/wrapper")
            wrapper.mkdir(parents=True)
            wrapper_jar = wrapper / "gradle-wrapper.jar"
            with zipfile.ZipFile(wrapper_jar, "w") as archive:
                archive.writestr("fixture", "admitted")
            denied_jar = wrapper / "unreviewed.jar"
            denied_jar.write_bytes(b"denied")

            static = root / Path("server/app/static")
            static.mkdir(parents=True)
            (static / "index.html").write_text("<!doctype html>dashboard</>", encoding="utf-8")
            (static / "private.html").write_text("private", encoding="utf-8")

            (root / "scripts/release/dependencies.init.gradle").write_text("allprojects {}")
            destination = base / "public"
            result = export_source.export_tree(root, destination)
            actual = {
                item.relative_to(destination).as_posix()
                for item in destination.rglob("*")
                if item.is_file()
            }

            self.assertNotIn("plugins/business.js", actual)
            self.assertNotIn("mac/private.py", actual)
            self.assertIn("scripts/release/export-source.py", actual)
            self.assertIn("android/gradle/wrapper/gradle-wrapper.jar", actual)
            self.assertNotIn("server/app/static/index.html", actual)
            self.assertIn("README.en.md", actual)
            self.assertIn("scripts/release/dependencies.init.gradle", actual)
            self.assertIn(".gitignore", actual)
            self.assertIn("SOURCE_MANIFEST.json", actual)

            self.assertNotIn("release/private.env", actual)
            self.assertNotIn("release/screenshot.png", actual)
            self.assertNotIn("scripts/release/token.pem", actual)
            self.assertNotIn("android/gradle/wrapper/unreviewed.jar", actual)
            self.assertNotIn("server/app/static/private.html", actual)
            self.assertEqual(
                wrapper_jar.read_bytes(),
                (destination / "android/gradle/wrapper/gradle-wrapper.jar").read_bytes(),
            )

            manifest = json.loads((destination / "SOURCE_MANIFEST.json").read_text(encoding="utf-8"))
            self.assertEqual(set(manifest["files"]), actual - {"SOURCE_MANIFEST.json"})
            self.assertEqual(result["sourceFiles"], len(actual) - 1)


if __name__ == "__main__":
    unittest.main()
