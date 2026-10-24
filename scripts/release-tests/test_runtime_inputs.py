import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class RuntimeInputContractTests(unittest.TestCase):
    def test_pack_and_gradle_cover_runtime_fetch_and_entry_scripts(self):
        tree = ast.parse((ROOT / "android/tools/runtime/pack.py").read_text())
        assignment = next(node for node in tree.body if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "runtime_sources" for t in node.targets))
        packed = {item.value for item in assignment.value.generators[0].iter.elts}
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        match = re.search(r"val runtimeSources = listOf\((.*?)\)\.map", gradle, re.S)
        self.assertIsNotNone(match)
        checked = set(re.findall(r'"([^"\n]+)"', match.group(1)))
        self.assertEqual(packed, checked, "Packer and APK stale-runtime guard must agree")
        self.assertTrue({"fetch.sh", "build-runtime.sh"}.issubset(packed), "Fetch flags and build entry affect runtime provenance")


if __name__ == "__main__":
    unittest.main()
