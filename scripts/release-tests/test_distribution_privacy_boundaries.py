import importlib.util
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
EXPORT_SCRIPT = ROOT / "scripts/release/export-source.py"
RUNTIME_SCRIPT = ROOT / "scripts/check-runtime-distribution.py"
APP_SCRIPT = ROOT / "scripts/check-app-distribution.py"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


export_source = load_module("distribution_export_source", EXPORT_SCRIPT)
runtime_checker = load_module("distribution_runtime_checker", RUNTIME_SCRIPT)


class DistributionPrivacyBoundaryTests(unittest.TestCase):
    def test_source_exporter_rejects_declared_data_directories_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "source"
            private = {
                Path("scripts/BOT/workspace.json"),
                Path("scripts/PLUGINS/plugin.xml"),
                Path("scripts/CONFIG/settings.json"),
                Path("scripts/sessions/state.json"),
                Path("scripts/shared_prefs/state.xml"),
                Path("scripts/recovery/backup.json"),
                Path("scripts/.dsh/private.json"),
            }
            allowed = {
                Path("scripts/history/history.json"),
                Path("scripts/cache/cache.json"),
                Path("scripts/accounts/accounts.json"),
                Path("scripts/tools/shared.js"),
                Path("scripts/release-tests/test_example.py"),
                Path("scripts/release/native-inputs/packages/proot_5.1.107.98_aarch64.deb"),
                Path("scripts/release/native-inputs/corresponding-source/v5.1.107.98.zip"),
            }
            for relative in private | allowed:
                file = root / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("synthetic public or private fixture\n", encoding="utf-8")

            selected = set(export_source._source_candidates(root))
            self.assertTrue(private.isdisjoint(selected))
            self.assertLessEqual(allowed, selected)

    def test_repository_and_exported_gitignore_have_equivalent_git_semantics(self):
        ignored = {
            ".env",
            ".env.production",
            "service.env",
            "release/signing.jks",
            "release/upload-key.p12",
            "release/certificate.pfx",
            "BOT/state.json",
            "android/BOT/state.json",
            "PLUGINS/plugin.xml",
            "CONFIG/config.xml",
            "sessions/state.json",
            "shared_prefs/app.xml",
            "recovery/state.json",
            ".dsh/private.json",
            "data/runtime-state.json",
            "screens/synthetic.png",
            "never-export-runtime-data/state.json",
        }
        not_ignored = {
            "scripts/release/native-inputs/packages/proot.deb",
            "scripts/release/native-inputs/corresponding-source/source.zip",
            "scripts/release-tests/test_distribution.py",
            "scripts/tools/implement.js",
        }
        candidates = sorted(ignored | not_ignored)

        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            source = base / "source"
            release = source / "release"
            for name in export_source.PUBLIC_TEMPLATES:
                template = release / name
                template.parent.mkdir(parents=True, exist_ok=True)
                template.write_text("synthetic public template\n", encoding="utf-8")
            for name in export_source.EXTRA_SOURCE_FILES:
                extra = source / name
                extra.parent.mkdir(parents=True, exist_ok=True)
                extra.write_text("synthetic source file\n", encoding="utf-8")
            destination = base / "export"
            # The stripped fixture has no native inputs; this test covers only ignore semantics.
            with mock.patch.object(export_source, "_validate_native_inputs", lambda root: None):
                export_source.export_tree(source, destination)

            source_matches = self.git_ignored(base / "repository", ROOT / ".gitignore", candidates)
            exported_matches = self.git_ignored(base / "exported", destination / ".gitignore", candidates)
            self.assertEqual(source_matches, ignored)
            self.assertEqual(exported_matches, ignored)

    def git_ignored(self, repository, ignore_file, candidates):
        repository.mkdir()
        (repository / ".gitignore").write_bytes(ignore_file.read_bytes())
        environment = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "TMPDIR": tempfile.gettempdir(),
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_CONFIG_SYSTEM": "/dev/null",
        }
        subprocess.run(
            ["git", "init", "--quiet"],
            cwd=repository,
            check=True,
            env=environment,
        )
        result = subprocess.run(
            ["git", "check-ignore", "--no-index", "--stdin"],
            cwd=repository,
            input="\n".join(candidates),
            text=True,
            capture_output=True,
            check=True,
            env=environment,
        )
        return set(result.stdout.splitlines())

    def test_apk_checker_blocks_known_private_paths_without_broad_directory_bans(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "synthetic.apk"
            forbidden = {
                "assets/root/projects/.dsh/private.json",
                "assets/BOT/state.json",
                "assets/plugins/private.js",
                "assets/CONFIG/config.xml",
                "assets/sessions/state.json",
                "assets/shared_prefs/state.xml",
                "assets/recovery/state.json",
                "assets/registry.json",
                "assets/bots.json",
                "assets/workspace.json",
            }
            allowed = {
                "assets/www/index.html",
                "assets/plugin-framework/v1/page.js",
                "assets/plugin-framework/v1/base.css",
                "assets/root/projects/public-tool.js",
                "assets/root/projects/history/accounts.json",
                "assets/www/sessions.js",
            }
            with zipfile.ZipFile(apk, "w") as archive:
                for name in forbidden | allowed:
                    archive.writestr(name, "synthetic fixture\n")

            completed = subprocess.run(
                [sys.executable, str(APP_SCRIPT), str(apk)],
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 1, completed.stderr)
            result = json.loads(completed.stdout)
            self.assertEqual(set(result["forbiddenEntries"]), forbidden)
            self.assertEqual(result["missingCore"], [])

    def test_runtime_checker_blocks_archive_roots_and_allows_nested_code_names(self):
        forbidden = {
            "root/projects/.dsh/private.json",
            "BOT/state.json",
            "PLUGINS/plugin.js",
            "CONFIG/config.json",
            "sessions/state.json",
            "shared_prefs/state.xml",
            "recovery/state.json",
            "registry.json",
            "bots.json",
            "workspace.json",
        }
        allowed = {
            "node_modules/example/lib/sessions/index.js",
            "node_modules/example/lib/config/registry.json",
            "node_modules/example/lib/CONFIG/index.js",
            "root/projects/history/accounts.json",
            "opt/pd/app-tools.mjs",
        }
        self.assertTrue(all(runtime_checker.forbidden(name) for name in forbidden))
        self.assertFalse(any(runtime_checker.forbidden(name) for name in allowed))
        self.assertTrue(runtime_checker.forbidden("/sessions/state.json"))
        self.assertTrue(runtime_checker.forbidden("public/../../outside.js"))
        private_link = tarfile.TarInfo("opt/pd/private-link")
        private_link.type = tarfile.SYMTYPE
        private_link.linkname = "../../sessions/state.json"
        self.assertTrue(runtime_checker.forbidden_link(private_link))
        rootfs_link = tarfile.TarInfo("usr/bin/cc")
        rootfs_link.type = tarfile.SYMTYPE
        rootfs_link.linkname = "/usr/bin/gcc"
        self.assertFalse(runtime_checker.forbidden_link(rootfs_link))

        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "synthetic-runtime.tar.xz"
            with tarfile.open(archive, "w:xz") as stream:
                self.add_runtime_fixture_file(stream, "opt/pd/standard-questions.patch.yml")
                self.add_runtime_fixture_file(stream, "usr/share/doc/example/LICENSE", "public license\n")
                for name in allowed:
                    self.add_runtime_fixture_file(stream, name)
                link = tarfile.TarInfo("usr/bin/cc")
                link.type = tarfile.SYMTYPE
                link.linkname = "/usr/bin/gcc"
                stream.addfile(link)

            result = runtime_checker.inspect(archive)
            self.assertEqual(result["privateEntries"], [])
            self.assertTrue(result["standardPatchIsPublicTemplate"])
            self.assertTrue(result["passed"])

            rejected_archive = Path(temporary) / "rejected-runtime.tar.xz"
            with tarfile.open(rejected_archive, "w:xz") as stream:
                self.add_runtime_fixture_file(stream, "opt/pd/standard-questions.patch.yml")
                self.add_runtime_fixture_file(stream, "usr/share/doc/example/LICENSE", "public license\n")
                for name in forbidden:
                    self.add_runtime_fixture_file(stream, name)

            rejected = runtime_checker.inspect(rejected_archive)
            self.assertEqual(set(rejected["privateEntries"]), forbidden)
            self.assertFalse(rejected["passed"])

    @staticmethod
    def add_runtime_fixture_file(stream, name, content=None):
        if content is None:
            content = "id: preset-standard\nfile:///opt/pd/app-tools.mjs\n"
        data = content.encode("utf-8")
        member = tarfile.TarInfo(name)
        member.size = len(data)
        stream.addfile(member, io.BytesIO(data))


if __name__ == "__main__":
    unittest.main()
