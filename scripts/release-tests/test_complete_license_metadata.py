import importlib.util
import io
import json
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path


RELEASE = Path(__file__).parents[1] / "release"
SCRIPT = RELEASE / "complete-license-metadata.py"
RENDER_SCRIPT = RELEASE / "render-notices.py"
LICENSE_TEXTS = RELEASE / "license-texts"

spec = importlib.util.spec_from_file_location("complete_license_metadata", SCRIPT)
complete_license_metadata = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(complete_license_metadata)

render_spec = importlib.util.spec_from_file_location("render_notices_for_completion", RENDER_SCRIPT)
render_notices = importlib.util.module_from_spec(render_spec)
assert render_spec.loader is not None
render_spec.loader.exec_module(render_notices)

PRESET_PATH = "opt/pd/dsh/node_modules/@deepseek-ai/dsh-agent-preset"
DECORATION_PATH = (
    PRESET_PATH + "/skills/cordis-plugin-development/templates/decoration"
)
MCP_PATH = PRESET_PATH + "/skills/cordis-plugin-development/templates/mcp"
SPDX_PATH = "usr/local/lib/node_modules/npm/node_modules/spdx-exceptions"
GUAVA_ARTIFACT = "maven/com.google.guava_listenablefuture_1.0/sources.jar"

MIT_TEXT = """MIT License

Copyright (c) 2026 DeepSeek

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""

APACHE_SOURCE = """/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.common.util.concurrent;

public interface ListenableFuture<V> {}
"""


class CompleteLicenseMetadataTests(unittest.TestCase):
    def test_completes_only_audited_gaps_and_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            licenses = self._build_fixture(Path(temporary))
            before = self._snapshot(licenses)

            result = complete_license_metadata.complete_license_metadata(
                licenses, LICENSE_TEXTS
            )

            runtime = json.loads((licenses / "runtime-packages.json").read_text())
            maven = json.loads((licenses / "maven-inventory.json").read_text())
            by_name = {row["name"]: row for row in runtime if "name" in row}
            guava = next(
                row
                for row in maven
                if row["coordinate"] == "com.google.guava:listenablefuture:1.0"
            )

            self.assertEqual(guava["licenses"], [complete_license_metadata.GUAVA_LICENSE])
            self.assertEqual(guava["licenseFiles"], ["Apache-2.0.txt"])
            self.assertEqual(
                guava["licenseSource"],
                {
                    "file": "com/google/common/util/concurrent/ListenableFuture.java",
                    "copyright": "Copyright (C) 2007 The Guava Authors",
                },
            )
            self.assertEqual(
                (licenses / "maven/com.google.guava_listenablefuture_1.0/Apache-2.0.txt").read_bytes(),
                (LICENSE_TEXTS / "Apache-2.0.txt").read_bytes(),
            )

            spdx = by_name["spdx-exceptions"]
            self.assertEqual(spdx["license"], "CC-BY-3.0")
            self.assertEqual(spdx["licenseFiles"], [SPDX_PATH + "/CC-BY-3.0.txt"])
            self.assertEqual(
                (licenses / "texts" / SPDX_PATH / "CC-BY-3.0.txt").read_bytes(),
                (LICENSE_TEXTS / "CC-BY-3.0.txt").read_bytes(),
            )

            for package, path in (
                ("@local/my-decoration", DECORATION_PATH),
                ("@local/demo-mcp", MCP_PATH),
            ):
                child = by_name[package]
                self.assertEqual(child["path"], path)
                self.assertEqual(child["license"], "MIT")
                self.assertEqual(child["licenseInheritedFrom"], PRESET_PATH)
                self.assertEqual(child["licenseFiles"], [PRESET_PATH + "/LICENSE"])

            untouched = by_name["@local/other-template"]
            self.assertNotIn("license", untouched)
            self.assertEqual(untouched["licenseFiles"], [])
            self.assertNotIn("licenseInheritedFrom", untouched)
            self.assertNotIn("license", by_name["unlicensed-runtime"])

            self.assertEqual(
                result["licenseInheritedFrom"],
                [
                    {"package": "@local/my-decoration", "from": PRESET_PATH},
                    {"package": "@local/demo-mcp", "from": PRESET_PATH},
                ],
            )
            self.assertEqual(
                set(result["copiedLegalTexts"]),
                {
                    "maven/com.google.guava_listenablefuture_1.0/Apache-2.0.txt",
                    "texts/" + SPDX_PATH + "/CC-BY-3.0.txt",
                },
            )

            after_first = self._snapshot(licenses)
            self.assertNotEqual(before, after_first)
            result_again = complete_license_metadata.complete_license_metadata(
                licenses, LICENSE_TEXTS
            )
            self.assertEqual(after_first, self._snapshot(licenses))
            self.assertEqual(result_again["copiedLegalTexts"], [])

            page = self._render(licenses, Path(temporary))
            text = page.read_text(encoding="utf-8")
            self.assertIn("@local/my-decoration", text)
            self.assertIn("继承自 " + PRESET_PATH, text)
            self.assertIn(PRESET_PATH + "/LICENSE", text)
            self.assertIn(
                "源码头 " + GUAVA_ARTIFACT
                + "!com/google/common/util/concurrent/ListenableFuture.java",
                text,
            )
            self.assertIn("Copyright (C) 2007 The Guava Authors", text)
            self.assertIn("Apache License\nVersion 2.0", text)
            self.assertIn("Attribution 3.0 Unported", text)
            self.assertIn("Copyright (c) 2026 DeepSeek", text)

    def test_rejects_unverified_guava_header_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            licenses = self._build_fixture(Path(temporary))
            self._write_source_jar(
                licenses / GUAVA_ARTIFACT,
                "/*\n * Copyright (C) 2007 The Guava Authors\n * GPL-2.0-only\n */\n",
            )
            before = self._snapshot(licenses)

            with self.assertRaisesRegex(
                complete_license_metadata.CompletionError, "source header"
            ):
                complete_license_metadata.complete_license_metadata(licenses, LICENSE_TEXTS)

            self.assertEqual(before, self._snapshot(licenses))
            self.assertFalse(
                (licenses / "maven/com.google.guava_listenablefuture_1.0/Apache-2.0.txt").exists()
            )

    def test_rejects_text_hash_mismatch_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            licenses = self._build_fixture(base)
            text_dir = base / "texts"
            text_dir.mkdir()
            shutil.copyfile(LICENSE_TEXTS / "CC-BY-3.0.txt", text_dir / "CC-BY-3.0.txt")
            (text_dir / "Apache-2.0.txt").write_text("not the audited Apache text\n")
            before = self._snapshot(licenses)

            with self.assertRaisesRegex(
                complete_license_metadata.CompletionError, "SHA-256 mismatch"
            ):
                complete_license_metadata.complete_license_metadata(licenses, text_dir)

            self.assertEqual(before, self._snapshot(licenses))

    def test_rejects_missing_or_non_mit_parent_license_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            licenses = self._build_fixture(Path(temporary))
            parent_text = licenses / "texts" / PRESET_PATH / "LICENSE"
            parent_text.write_text("Apache License\n", encoding="utf-8")
            before = self._snapshot(licenses)

            with self.assertRaisesRegex(
                complete_license_metadata.CompletionError, "not recognizable MIT"
            ):
                complete_license_metadata.complete_license_metadata(licenses, LICENSE_TEXTS)

            self.assertEqual(before, self._snapshot(licenses))
            runtime = json.loads((licenses / "runtime-packages.json").read_text())
            child = next(row for row in runtime if row["name"] == "@local/my-decoration")
            self.assertNotIn("licenseInheritedFrom", child)

    def test_rejects_local_template_outside_locked_parent_without_writing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            licenses = self._build_fixture(Path(temporary))
            path = licenses / "runtime-packages.json"
            runtime = json.loads(path.read_text())
            next(row for row in runtime if row["name"] == "@local/demo-mcp")[
                "path"
            ] = "opt/pd/dsh/node_modules/other-package/templates/mcp"
            path.write_text(json.dumps(runtime), encoding="utf-8")
            before = self._snapshot(licenses)

            with self.assertRaisesRegex(
                complete_license_metadata.CompletionError, "expected exactly one"
            ):
                complete_license_metadata.complete_license_metadata(licenses, LICENSE_TEXTS)

            self.assertEqual(before, self._snapshot(licenses))

    def test_rejects_package_path_escaping_output_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            licenses = self._build_fixture(base)
            path = licenses / "runtime-packages.json"
            runtime = json.loads(path.read_text())
            next(row for row in runtime if row["name"] == "spdx-exceptions")[
                "path"
            ] = "../outside"
            path.write_text(json.dumps(runtime), encoding="utf-8")
            before = self._snapshot(licenses)

            with self.assertRaisesRegex(
                complete_license_metadata.CompletionError, "unsafe relative path"
            ):
                complete_license_metadata.complete_license_metadata(licenses, LICENSE_TEXTS)

            self.assertEqual(before, self._snapshot(licenses))
            self.assertFalse((base / "outside").exists())

    def test_rejects_unexpected_target_version_instead_of_guessing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            licenses = self._build_fixture(Path(temporary))
            path = licenses / "runtime-packages.json"
            runtime = json.loads(path.read_text())
            next(row for row in runtime if row["name"] == "spdx-exceptions")[
                "version"
            ] = "2.4.0"
            path.write_text(json.dumps(runtime), encoding="utf-8")
            before = self._snapshot(licenses)

            with self.assertRaisesRegex(
                complete_license_metadata.CompletionError, "expected exactly one"
            ):
                complete_license_metadata.complete_license_metadata(licenses, LICENSE_TEXTS)

            self.assertEqual(before, self._snapshot(licenses))

    def _build_fixture(self, base: Path) -> Path:
        licenses = base / "licenses"
        licenses.mkdir()
        (licenses / "maven/com.google.guava_listenablefuture_1.0").mkdir(parents=True)
        (licenses / "texts" / PRESET_PATH).mkdir(parents=True)
        (licenses / "texts" / PRESET_PATH / "LICENSE").write_text(
            MIT_TEXT, encoding="utf-8"
        )
        self._write_source_jar(licenses / GUAVA_ARTIFACT, APACHE_SOURCE)

        runtime = [
            {
                "path": PRESET_PATH,
                "name": "@deepseek-ai/dsh-agent-preset",
                "version": "0.2.0-rc.2",
                "license": "MIT",
                "licenseFiles": [PRESET_PATH + "/LICENSE"],
            },
            {
                "path": DECORATION_PATH,
                "name": "@local/my-decoration",
                "version": "1.0.0",
                "licenseFiles": [],
            },
            {
                "path": MCP_PATH,
                "name": "@local/demo-mcp",
                "version": "1.0.0",
                "licenseFiles": [],
            },
            {
                "path": PRESET_PATH + "/skills/cordis-plugin-development/templates/other",
                "name": "@local/other-template",
                "version": "1.0.0",
                "licenseFiles": [],
            },
            {
                "path": SPDX_PATH,
                "name": "spdx-exceptions",
                "version": "2.5.0",
                "license": "CC-BY-3.0",
                "licenseFiles": [],
            },
            {
                "path": "opt/pd/dsh/node_modules/unlicensed-runtime",
                "name": "unlicensed-runtime",
                "version": "1.0.0",
                "licenseFiles": [],
            },
        ]
        maven = [
            {
                "coordinate": "com.google.guava:listenablefuture:1.0",
                "licenses": [],
                "licenseFiles": [],
                "sourceArtifact": GUAVA_ARTIFACT,
            },
            {
                "coordinate": "com.example:unlicensed:1.0",
                "licenses": [],
                "licenseFiles": [],
                "sourceArtifact": "maven/com.example_unlicensed_1.0/sources.jar",
            },
        ]
        (licenses / "runtime-packages.json").write_text(
            json.dumps(runtime, indent=2) + "\n", encoding="utf-8"
        )
        (licenses / "maven-inventory.json").write_text(
            json.dumps(maven, indent=2) + "\n", encoding="utf-8"
        )
        return licenses

    @staticmethod
    def _write_source_jar(path: Path, source: str) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(
                "com/google/common/util/concurrent/ListenableFuture.java", source
            )

    @staticmethod
    def _snapshot(root: Path) -> dict[str, bytes]:
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in sorted(root.rglob("*"))
            if path.is_file()
        }

    @staticmethod
    def _render(licenses: Path, base: Path) -> Path:
        native = base / "native"
        extra = base / "extra"
        native.mkdir(exist_ok=True)
        extra.mkdir(exist_ok=True)
        project = base / "project"
        project.mkdir(exist_ok=True)
        (project / "LICENSE").write_text("GPL fixture text\n", encoding="utf-8")
        output = base / "notices.html"
        render_notices.render_notices(licenses, native, extra, output, project_root=project)
        return output


if __name__ == "__main__":
    unittest.main()
