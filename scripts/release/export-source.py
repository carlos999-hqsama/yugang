#!/usr/bin/env python3
"""Create a repeatable core-only public source tree, never Git history/data."""

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]

# These are the only release templates that may be copied wholesale. A private
# file placed beside them must never enter the export.
PUBLIC_TEMPLATES = (
    "AGENTS.md",
    "README.md",
    "README.en.md",
    "CORE_SPLIT.md",
    "BUILD.md",
    "ARCHITECTURE.md",
    "SECURITY.md",
    "DEPENDENCY_REVIEW.json",
    "DEPENDENCIES.cdx.json",
    "CONTRIBUTING.md",
    "THIRD_PARTY_NOTICES.md",
    "LICENSE",
    "NOTICE",
    "site/index.html",
)

SOURCE_ROOTS = (
    "android/app/src/main",
    "android/app/src/test",
    "android/app/src/androidTest",
    "android/tools/runtime",
    "android/gradle",
    "scripts",
)

EXTRA_SOURCE_FILES = (
    "android/gradlew",
    "android/gradle.properties",
    "android/build.gradle.kts",
    "android/settings.gradle.kts",
    "android/app/build.gradle.kts",
    "android/app/proguard-rules.pro",
    "site/images/yugang-concept.png",
    "site/images/chat.png",
    "site/images/plugin-guide.png",
)

# Business plugins and private companion services can never re-enter a core export.
FORBIDDEN_SOURCE_PREFIXES = (
    "plugins/",
    "mac/",
    "nas/",
    "server/",
    "deploy/",
    "android/app/src/main/assets/plugins/",
    "android/tools/runtime/overlay/opt/pd/skills/bili-agent/",
    "android/tools/runtime/overlay/opt/pd/skills/codex-monitor/",
    "android/tools/runtime/overlay/opt/pd/skills/mac-mini/",
    "android/tools/runtime/overlay/opt/pd/bili-tools.mjs",
    "android/tools/runtime/overlay/opt/pd/codex-tools.mjs",
    "android/tools/runtime/overlay/opt/pd/memory-tools.mjs",
    "android/tools/runtime/overlay/opt/pd/mini-tools.mjs",
    "android/app/src/main/assets/plugin-framework/v1/js/local-quota.js",
    "android/app/src/main/assets/plugin-framework/v1/js/mac-usage.js",
    "android/app/src/main/assets/plugin-framework/v1/js/monitor-settings.js",
    "android/app/src/main/assets/plugin-framework/v1/js/nas.js",
)

SKIP_DIRECTORIES = {
    "node_modules",
    "__pycache__",
    ".git",
    "build",
    ".gradle",
    ".kotlin",
    "screens",
    "data",
    "work",
    "outputs",
    "jniLibs",
    "never-export-runtime-data",
    "BOT",
    "PLUGINS",
    "CONFIG",
    "sessions",
    "shared_prefs",
    "recovery",
    ".dsh",
}

TEXT_SUFFIXES = {
    ".py",
    ".mjs",
    ".js",
    ".css",
    ".kt",
    ".kts",
    ".gradle",
    ".xml",
    ".json",
    ".yml",
    ".yaml",
    ".toml",
    ".txt",
    ".sh",
    ".svg",
    ".pro",
    ".properties",
    ".html",
    ".md",
    ".plist",
    ".example",
}

TEXT_FILE_NAMES = {
    "Dockerfile",
    "gradlew",
    "requirements.txt",
    "LICENSE",
    "NOTICE",
    ".dockerignore",
}

EXCLUDED_SOURCE_FILES = {
    "scripts/quality/detekt-findings.xml",
    "scripts/quality/lint-findings.xml",
}

# Binary formats are not admitted by extension. Each redistributed binary has
# to be named here so a newly dropped image, archive, key, or database cannot
# silently become public.
ADMITTED_BINARIES = {
    Path("site/images/yugang-concept.png"),
    Path("site/images/chat.png"),
    Path("site/images/plugin-guide.png"),
    Path("android/gradle/wrapper/gradle-wrapper.jar"),
    Path("android/app/src/main/assets/www/img/fish-atlas.png"),
    Path("android/app/src/main/res/drawable-nodpi/launcher_whale.png"),
    Path("android/app/src/main/res/drawable-nodpi/notification_whale.png"),
    Path("scripts/release/native-inputs/packages/proot_5.1.107.98_aarch64.deb"),
    Path("scripts/release/native-inputs/packages/libtalloc_2.5.0_aarch64.deb"),
    Path("scripts/release/native-inputs/packages/libandroid-shmem_0.7_aarch64.deb"),
    Path("scripts/release/native-inputs/corresponding-source/v5.1.107.98.zip"),
    Path("scripts/release/native-inputs/corresponding-source/talloc-2.5.0.tar.gz"),
    Path("scripts/release/native-inputs/corresponding-source/v0.7.tar.gz"),
    Path("scripts/release/native-inputs/corresponding-source/termux-packages-c1f3785a30ac61d7fa5360e807b069c50be03c1f.tar.gz"),
}

# This legacy seed is a public, synthetic test fixture consumed by the Store migration tests.
REQUIRED_TEST_FIXTURES = {
    Path("android/app/src/test/java/com/personaldashboard/app/BotWorkspaceStoreTest.kt"):
        Path("android/app/src/test/resources/bot-persona-retired.md"),
}
PUBLIC_TEST_FIXTURES = set(REQUIRED_TEST_FIXTURES.values())

# Companion root is API status, not a legacy HTML application.
RESTORED_PUBLIC_HTML = set()

GITIGNORE = """\
.gradle/
**/build/
android/.gradle/
android/local.properties
android/keystore/
android/runtime-assets/
android/app/src/main/jniLibs/
.rtbuild/
node_modules/
__pycache__/
*.pyc
*.sqlite*
.env
.env.*
*.env
*.jks
*.p12
*.pfx
BOT/
PLUGINS/
CONFIG/
sessions/
shared_prefs/
recovery/
.dsh/
data/
screens/
never-export-runtime-data/
work/
outputs/
"""


def _is_html_allowed(relative: Path) -> bool:
    return relative.parts[0] == "android" or relative in RESTORED_PUBLIC_HTML


def _validate_source(root: Path, relative: Path) -> Path:
    if any((root / parent).is_symlink() for parent in (relative, *relative.parents)):
        raise ValueError(f"Public source crosses a symbolic link: {relative}")
    return root / relative


def _source_candidates(root: Path):
    candidates = []
    for source_root in SOURCE_ROOTS:
        base = _validate_source(root, Path(source_root))
        if base.exists():
            candidates.extend(
                item.relative_to(root)
                for item in base.rglob("*")
                if item.is_file() and not item.is_symlink()
            )
    candidates.extend(Path(item) for item in EXTRA_SOURCE_FILES)

    selected = set()
    for relative in candidates:
        if relative.as_posix() in EXCLUDED_SOURCE_FILES:
            continue
        if relative.as_posix().startswith(FORBIDDEN_SOURCE_PREFIXES):
            continue
        if any(part in SKIP_DIRECTORIES for part in relative.parts):
            continue
        if relative.name in {"AGENTS.md", "README.md", "CLAUDE.md", ".DS_Store"}:
            continue
        if relative.suffix == ".md" and "assets" not in relative.parts and "overlay" not in relative.parts and relative not in PUBLIC_TEST_FIXTURES:
            continue
        if relative.name.startswith(".") and relative.name != ".dockerignore":
            continue
        if relative in ADMITTED_BINARIES:
            selected.add(relative)
            continue
        if relative.suffix not in TEXT_SUFFIXES and relative.name not in TEXT_FILE_NAMES:
            continue
        if relative.suffix == ".html" and not _is_html_allowed(relative):
            continue
        selected.add(relative)
    return sorted(selected)


REPLACE = []


def _copy_text(source: Path, destination: Path) -> None:
    text = source.read_bytes().decode("utf-8")
    if "\0" in text:
        raise ValueError(f"NUL byte in public text source: {source}")
    # Third-party declarations are generated from public dependency inputs.
    # Preserve copyright holders, license terms and provenance verbatim.
    if source.name not in {"third-party-notices.html", "third-party-inventory.json"}:
        if source.name == "export-source.py" and source.parent.name == "release":
            # The public exporter has no developer-specific replacement policy.
            # Otherwise sanitizing the sanitizer changes its meaning on re-export.
            text = re.sub(r"(?m)^REPLACE = .*$", "REPLACE = []", text)
            text = re.sub(r"(?m)^[ \t]+text = re\.sub\(r'\(\?i\).*$\n?", "", text)
        else:
            for old, new in REPLACE:
                text = text.replace(old, new)
        text = re.sub(r'/Users/[A-Za-z0-9._-]+', '/home/example', text)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text, encoding="utf-8", newline="")
    destination.chmod(source.stat().st_mode & 0o777)


def _copy_binary(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    destination.chmod(source.stat().st_mode & 0o777)


def _copy_templates(root: Path, destination: Path) -> None:
    templates = root / "release"
    for name in PUBLIC_TEMPLATES:
        source = templates / name
        if not source.is_file():
            source = root / name
        if source.is_symlink() or not source.is_file():
            raise FileNotFoundError(f"Missing required public template: {name}")
        _validate_source(root, source.relative_to(root))
        _copy_text(source, destination / name)


def _validate_native_inputs(root: Path) -> None:
    lock = root / "scripts/release/native-lock.json"
    if lock.is_symlink() or not lock.is_file():
        raise FileNotFoundError("Missing regular native-lock.json")
    spec = json.loads(lock.read_text(encoding="utf-8"))
    base = Path("scripts/release/native-inputs")
    required = [(base / "packages" / Path(item["url"]).name, item["sha256"]) for item in spec["packages"]]
    required += [(base / "corresponding-source" / Path(item["source"]).name, item["sourceSha256"]) for item in spec["packages"]]
    required.append((base / "corresponding-source" / ("termux-packages-" + spec["termuxCommit"] + ".tar.gz"), spec["termuxSha256"]))
    for relative, expected in required:
        source = root / relative
        if relative not in ADMITTED_BINARIES or any((root / part).is_symlink() for part in (relative, *relative.parents)) or not source.is_file():
            raise ValueError(f"Missing regular admitted native input: {relative}")
        with source.open("rb") as stream:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
        if actual != expected:
            raise ValueError(f"Native input checksum mismatch: {relative}")


def _validate_test_fixtures(root: Path) -> None:
    for consumer, relative in REQUIRED_TEST_FIXTURES.items():
        if not (root / consumer).is_file():
            continue
        source = root / relative
        if not source.is_file() or any((root / part).is_symlink() for part in (relative, *relative.parents)):
            raise ValueError(f"Missing regular required test fixture: {relative}")


def export_tree(root: Path, destination: Path) -> dict[str, object]:
    root = root.resolve()
    destination = destination.resolve()
    if destination.exists():
        raise FileExistsError("Destination must be new; previous releases are never overwritten")

    _validate_test_fixtures(root)
    _validate_native_inputs(root)
    destination.mkdir(parents=True)
    for relative in _source_candidates(root):
        source = _validate_source(root, relative)
        if source.is_symlink() or not source.is_file():
            raise ValueError(f"Selected source is not a regular file: {relative}")
        target = destination / relative
        if relative in ADMITTED_BINARIES:
            _copy_binary(source, target)
        else:
            _copy_text(source, target)

    _copy_templates(root, destination)
    (destination / ".gitignore").write_text(GITIGNORE, encoding="utf-8")

    files = sorted(item.relative_to(destination) for item in destination.rglob("*") if item.is_file())
    manifest = {
        "description": "Core-only public source export; no history, credentials, business plugins, companion services or user data.",
        "files": [item.as_posix() for item in files],
    }
    (destination / "SOURCE_MANIFEST.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    return {"destination": str(destination), "sourceFiles": len(files)}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(export_tree(ROOT, args.destination), ensure_ascii=False))


if __name__ == "__main__":
    main()
