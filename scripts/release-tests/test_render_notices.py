import hashlib
import importlib.util
import io
import json
import re
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "release" / "render-notices.py"
spec = importlib.util.spec_from_file_location("render_notices", SCRIPT)
render_notices = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(render_notices)


class RenderNoticesTests(unittest.TestCase):
    def test_rejects_auxiliary_pdf_and_keeps_textual_main_license(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            licenses = base / "licenses"
            native = base / "native"
            extra = base / "extra"
            licenses.mkdir()
            native.mkdir()
            extra.mkdir()

            (licenses / "runtime-packages.json").write_text(
                json.dumps([{"name": "node", "version": "24.22.0", "license": "MIT"}]),
                encoding="utf-8",
            )
            (licenses / "maven-inventory.json").write_text(
                json.dumps(
                    [
                        {
                            "coordinate": "com.example:fixture:1.0",
                            "licenses": [{"name": "MIT"}],
                        }
                    ]
                ),
                encoding="utf-8",
            )
            (licenses / "texts").mkdir()
            (licenses / "texts" / "invalid.txt").write_bytes(b"license \xff text")

            source_archive = extra / "sqlite3_3.46.1.orig-www.tar.xz"
            archive_bytes = io.BytesIO()
            with tarfile.open(fileobj=archive_bytes, mode="w:xz") as archive:
                main_license = b"SQLite is in the public domain.\nMain Debian copyright text.\n"
                self._add_tar(archive, "debian/copyright", main_license)
                auxiliary_pdf = b"%PDF-1.4\n\x00binary auxiliary material"
                self._add_tar(archive, "www/copyright-release.pdf", auxiliary_pdf)
            source_archive.write_bytes(archive_bytes.getvalue())
            archive_digest = hashlib.sha256(source_archive.read_bytes()).hexdigest()

            project_root = base / "project"
            project_root.mkdir()
            (project_root / "LICENSE").write_text("GPL fixture text\n", encoding="utf-8")
            output = base / "notices.html"
            result = render_notices.render_notices(
                licenses, native, extra, output, project_root=project_root
            )

            page = output.read_bytes()
            self.assertNotIn(b"\0", page)
            self.assertNotIn(b"%PDF-1.4", page)
            self.assertIn("SQLite is in the public domain.".encode("utf-8"), page)
            self.assertIn("GPL fixture text".encode("utf-8"), page)
            self.assertIn(b"com.example:fixture:1.0", page)
            self.assertEqual(
                hashlib.sha256(source_archive.read_bytes()).hexdigest(), archive_digest
            )

            bodies = re.findall(rb"<pre>(.*?)</pre>", page, flags=re.DOTALL)
            self.assertTrue(bodies)
            self.assertTrue(all(body.strip() for body in bodies))
            skipped_names = {item["name"] for item in result["skipped"]}
            self.assertIn(
                "sqlite3_3.46.1.orig-www.tar.xz/www/copyright-release.pdf", skipped_names
            )
            self.assertIn("texts/invalid.txt", skipped_names)
            pdf_report = next(
                item
                for item in result["skipped"]
                if item["name"].endswith("copyright-release.pdf")
            )
            self.assertIn("binary auxiliary", pdf_report["reason"])

    def test_preserves_traditional_license_page_breaks(self):
        skipped = []
        self.assertEqual(render_notices._decode_license("COPYING.LIB", b"LGPL page one\fLGPL page two", skipped), "LGPL page one\nLGPL page two")
        self.assertEqual(skipped, [])

    def test_rejects_binary_license_inside_zip(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            licenses = base / "licenses"
            native = base / "native"
            extra = base / "extra"
            licenses.mkdir()
            native.mkdir()
            extra.mkdir()
            (licenses / "runtime-packages.json").write_text("[]", encoding="utf-8")
            (licenses / "maven-inventory.json").write_text("[]", encoding="utf-8")

            archive_path = native / "fixture-sources.zip"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("LICENSE.txt", "text license\n")
                archive.writestr("COPYING.pdf", b"%PDF-1.4\x00")
            project_root = base / "project"
            project_root.mkdir()
            (project_root / "LICENSE").write_text("GPL fixture text\n", encoding="utf-8")
            output = base / "notices.html"
            result = render_notices.render_notices(
                licenses, native, extra, output, project_root=project_root
            )
            self.assertIn(b"text license", output.read_bytes())
            self.assertEqual(result["skipped"], [
                {"name": "fixture-sources.zip/COPYING.pdf", "reason": "binary auxiliary license material"}
            ])


    def test_archive_reuse_licenses_keep_source_names_and_reject_other_text(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            licenses = base / "licenses"
            native = base / "native"
            extra = base / "extra"
            licenses.mkdir()
            native.mkdir()
            extra.mkdir()
            (licenses / "runtime-packages.json").write_text("[]", encoding="utf-8")
            (licenses / "maven-inventory.json").write_text("[]", encoding="utf-8")

            archive_path = native / "glib-2.90.0.tar.xz"
            archive_bytes = io.BytesIO()
            with tarfile.open(fileobj=archive_bytes, mode="w:xz") as archive:
                self._add_tar(
                    archive,
                    "glib-2.90.0/LICENSES/LGPL-2.1-or-later.txt",
                    b"LGPL fixture from the REUSE directory.\n",
                )
                self._add_tar(
                    archive,
                    "glib-2.90.0/subprojects/gvdb/LICENSES/LicenseRef-custom.txt",
                    b"Custom LicenseRef fixture text.\n",
                )
                self._add_tar(
                    archive,
                    "glib-2.90.0/LICENSES/Binary-1.0.txt",
                    b"binary license \xff text",
                )
                self._add_tar(
                    archive,
                    "glib-2.90.0/LICENSES/README.txt",
                    b"ordinary documentation must not become a license\n",
                )
                self._add_tar(
                    archive,
                    "glib-2.90.0/LICENSES/Apache-2.0.pdf",
                    b"%PDF-1.4\nauxiliary binary material",
                )
                self._add_tar(
                    archive,
                    "glib-2.90.0/docs/release-notes.txt",
                    b"ordinary documentation outside LICENSES\n",
                )
            archive_path.write_bytes(archive_bytes.getvalue())

            project_root = base / "project"
            project_root.mkdir()
            (project_root / "LICENSE").write_text("GPL fixture text\n", encoding="utf-8")
            output = base / "notices.html"
            result = render_notices.render_notices(
                licenses, native, extra, output, project_root=project_root
            )

            page = output.read_bytes()
            self.assertIn(b"LGPL fixture from the REUSE directory.", page)
            self.assertIn(b"Custom LicenseRef fixture text.", page)
            self.assertIn(
                b"glib-2.90.0.tar.xz/glib-2.90.0/LICENSES/LGPL-2.1-or-later.txt",
                page,
            )
            self.assertIn(
                b"glib-2.90.0.tar.xz/glib-2.90.0/subprojects/gvdb/LICENSES/LicenseRef-custom.txt",
                page,
            )
            self.assertNotIn(b"ordinary documentation", page)
            self.assertNotIn(b"%PDF-1.4", page)
            skipped_names = {item["name"] for item in result["skipped"]}
            self.assertIn(
                "glib-2.90.0.tar.xz/glib-2.90.0/LICENSES/Binary-1.0.txt",
                skipped_names,
            )

    def test_renders_inherited_license_and_verified_source_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            licenses = base / "licenses"
            native = base / "native"
            extra = base / "extra"
            licenses.mkdir()
            native.mkdir()
            extra.mkdir()
            parent = "opt/pd/dsh/node_modules/@deepseek-ai/dsh-agent-preset"
            (licenses / "runtime-packages.json").write_text(
                json.dumps(
                    [
                        {
                            "path": parent + "/templates/example",
                            "name": "@local/example",
                            "version": "1.0.0",
                            "license": "MIT",
                            "licenseInheritedFrom": parent,
                            "licenseFiles": [parent + "/LICENSE"],
                        }
                    ]
                ),
                encoding="utf-8",
            )
            (licenses / "maven-inventory.json").write_text(
                json.dumps(
                    [
                        {
                            "coordinate": "com.google.guava:listenablefuture:1.0",
                            "licenses": [{"name": "Apache-2.0"}],
                            "sourceArtifact": "maven/guava/sources.jar",
                            "licenseSource": {
                                "file": "com/google/common/util/concurrent/ListenableFuture.java",
                                "copyright": "Copyright (C) 2007 The Guava Authors",
                            },
                        }
                    ]
                ),
                encoding="utf-8",
            )
            project_root = base / "project"
            project_root.mkdir()
            (project_root / "LICENSE").write_text("GPL fixture text\n", encoding="utf-8")
            output = base / "notices.html"
            render_notices.render_notices(
                licenses, native, extra, output, project_root=project_root
            )

            page = output.read_text(encoding="utf-8")
            self.assertIn("继承自 " + parent, page)
            self.assertIn(parent + "/LICENSE", page)
            self.assertIn("源码头 maven/guava/sources.jar!" + "com/google/common/util/concurrent/ListenableFuture.java", page)
            self.assertIn("Copyright (C) 2007 The Guava Authors", page)

    @staticmethod
    def _add_tar(archive: tarfile.TarFile, name: str, data: bytes) -> None:
        info = tarfile.TarInfo(name)
        info.size = len(data)
        archive.addfile(info, io.BytesIO(data))


if __name__ == "__main__":
    unittest.main()
