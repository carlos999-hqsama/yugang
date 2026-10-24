#!/usr/bin/env python3
"""Complete the audited license gaps in an offline collector output directory.

The input directory is the output of collect-runtime-licenses.py and
collect-maven-licenses.py.  Only the exact audited package identities below
are completed; any missing or conflicting premise is an error.  Nothing is
fetched or built, and all writes stay inside that directory.
"""

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_LICENSE_TEXTS = SCRIPT_DIR / "license-texts"

APACHE2_SHA256 = "074e6e32c86a4c0ef8b3ed25b721ca23aca83df277cd88106ef7177c354615ff"
CC_BY_3_SHA256 = "e6bc9e9c474700b708f568bac9e5a8a9bcb2b1dad53442f5ba449fcb848b8e76"

GUAVA_COORDINATE = "com.google.guava:listenablefuture:1.0"
GUAVA_SOURCE_FILE = "com/google/common/util/concurrent/ListenableFuture.java"
GUAVA_LICENSE_FILE = "Apache-2.0.txt"
GUAVA_LICENSE = {
    "name": "Apache-2.0",
    "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
}

PRESET_NAME = "@deepseek-ai/dsh-agent-preset"
PRESET_VERSION = "0.2.0-rc.2"
PRESET_PATH = "opt/pd/dsh/node_modules/@deepseek-ai/dsh-agent-preset"
PRESET_LICENSE_FILE = f"{PRESET_PATH}/LICENSE"
LOCAL_TEMPLATES = {
    "@local/my-decoration": (
        "1.0.0",
        f"{PRESET_PATH}/skills/cordis-plugin-development/templates/decoration",
    ),
    "@local/demo-mcp": (
        "1.0.0",
        f"{PRESET_PATH}/skills/cordis-plugin-development/templates/mcp",
    ),
}

SPDX_EXCEPTIONS_NAME = "spdx-exceptions"
SPDX_EXCEPTIONS_VERSION = "2.5.0"
SPDX_EXCEPTIONS_LICENSE = "CC-BY-3.0"

MIT_TEXT_MARKERS = (
    "Permission is hereby granted, free of charge",
    'THE SOFTWARE IS PROVIDED "AS IS"',
)


class CompletionError(ValueError):
    """An audited license premise does not match the collected directory."""


def _fail(message: str) -> None:
    raise CompletionError(message)


def _load_inventory(path: Path, label: str) -> list[dict[str, object]]:
    if not path.is_file():
        _fail(f"{label} is missing: {path}")
    try:
        rows = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise CompletionError(f"{label} is not readable JSON: {path}: {error}") from error
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        _fail(f"{label} must be a JSON array of objects: {path}")
    return rows


def _find_one(
    rows: list[dict[str, object]], predicate, label: str
) -> dict[str, object]:
    matches = [row for row in rows if predicate(row)]
    if len(matches) != 1:
        _fail(f"{label}: expected exactly one matching entry, found {len(matches)}")
    return matches[0]


def _string_list(row: dict[str, object], field: str, label: str) -> list[str]:
    value = row.get(field, [])
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        _fail(f"{label}: {field} must be a list of strings")
    return value


def _contained(root: Path, relative: str, label: str) -> Path:
    candidate = Path(relative)
    if not relative or candidate.is_absolute() or ".." in candidate.parts:
        _fail(f"{label}: unsafe relative path: {relative!r}")
    target = root / candidate
    try:
        target.resolve().relative_to(root.resolve())
    except ValueError as error:
        raise CompletionError(f"{label}: path escapes the license directory: {relative}") from error
    return target


def _verified_legal_text(
    source_dir: Path, name: str, expected_sha256: str, label: str
) -> bytes:
    path = source_dir / name
    if not path.is_file() or path.is_symlink():
        _fail(f"{label}: bundled legal text is missing: {path}")
    try:
        data = path.read_bytes()
        text = data.decode("utf-8")
    except (OSError, UnicodeError) as error:
        raise CompletionError(f"{label}: bundled legal text is not UTF-8: {path}") from error
    digest = hashlib.sha256(data).hexdigest()
    if digest != expected_sha256:
        _fail(f"{label}: SHA-256 mismatch for {path}: {digest}")
    if not text.strip() or "\0" in text:
        _fail(f"{label}: bundled legal text is empty or contains NUL: {path}")
    return data


def _needs_copy(target: Path, data: bytes, label: str) -> bool:
    if target.is_symlink():
        _fail(f"{label}: refusing to replace a symlink: {target}")
    if target.exists():
        try:
            existing = target.read_bytes()
        except OSError as error:
            raise CompletionError(f"{label}: cannot read existing file: {target}") from error
        if existing != data:
            _fail(f"{label}: existing file has different content: {target}")
        return False
    return True


def _write_json_if_changed(path: Path, rows: list[dict[str, object]]) -> None:
    rendered = json.dumps(rows, indent=2, ensure_ascii=False) + "\n"
    if path.read_text(encoding="utf-8") == rendered:
        return
    path.write_text(rendered, encoding="utf-8", newline="")


def _complete_guava(
    licenses: Path,
    maven: list[dict[str, object]],
    apache_text: bytes,
    copies: list[tuple[Path, bytes]],
) -> None:
    row = _find_one(
        maven,
        lambda item: item.get("coordinate") == GUAVA_COORDINATE,
        "Guava ListenableFuture inventory",
    )
    artifact_value = row.get("sourceArtifact")
    if not isinstance(artifact_value, str) or not artifact_value:
        _fail("Guava ListenableFuture: sourceArtifact is missing")
    artifact = _contained(licenses, artifact_value, "Guava ListenableFuture sourceArtifact")
    if not artifact.is_file():
        _fail(f"Guava ListenableFuture: source artifact is missing: {artifact}")

    try:
        with zipfile.ZipFile(artifact) as archive:
            source_bytes = archive.read(GUAVA_SOURCE_FILE)
        source = source_bytes.decode("utf-8")
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile) as error:
        raise CompletionError(
            f"Guava ListenableFuture: cannot verify {GUAVA_SOURCE_FILE} in {artifact}: {error}"
        ) from error

    header = "\n".join(source.splitlines()[:16])
    copyright_match = re.search(
        r"(?m)^\s*\*\s*(Copyright \(C\) \d{4} The Guava Authors)\s*$", header
    )
    required_header = (
        "Licensed under the Apache License, Version 2.0",
        "http://www.apache.org/licenses/LICENSE-2.0",
        "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND",
    )
    if copyright_match is None or not all(item in header for item in required_header):
        _fail(
            "Guava ListenableFuture: source header does not contain the expected "
            "Apache-2.0 declaration and Guava copyright"
        )
    copyright_notice = copyright_match.group(1)

    existing_licenses = row.get("licenses", [])
    if not isinstance(existing_licenses, list) or not all(
        isinstance(item, dict) for item in existing_licenses
    ):
        _fail("Guava ListenableFuture: licenses must be a list of objects")
    existing_names = {str(item.get("name") or "") for item in existing_licenses}
    apache_names = {
        "Apache-2.0",
        "Apache License, Version 2.0",
        "The Apache Software License, Version 2.0",
    }
    if existing_licenses:
        if not existing_names & apache_names:
            _fail(
                "Guava ListenableFuture: existing license metadata conflicts with "
                "the verified Apache-2.0 source header"
            )
    else:
        row["licenses"] = [dict(GUAVA_LICENSE)]

    expected_source = {"file": GUAVA_SOURCE_FILE, "copyright": copyright_notice}
    existing_source = row.get("licenseSource")
    if existing_source is not None and existing_source != expected_source:
        _fail("Guava ListenableFuture: existing licenseSource conflicts with verified source")
    row["licenseSource"] = expected_source

    license_files = _string_list(row, "licenseFiles", "Guava ListenableFuture")
    if GUAVA_LICENSE_FILE not in license_files:
        license_files.append(GUAVA_LICENSE_FILE)
    row["licenseFiles"] = license_files

    target = artifact.parent / GUAVA_LICENSE_FILE
    if _needs_copy(target, apache_text, "Guava ListenableFuture Apache-2.0 text"):
        copies.append((target, apache_text))


def _complete_spdx_exceptions(
    licenses: Path,
    runtime: list[dict[str, object]],
    cc_by_text: bytes,
    copies: list[tuple[Path, bytes]],
) -> None:
    row = _find_one(
        runtime,
        lambda item: item.get("name") == SPDX_EXCEPTIONS_NAME
        and item.get("version") == SPDX_EXCEPTIONS_VERSION,
        "spdx-exceptions 2.5.0 inventory",
    )
    if row.get("license") != SPDX_EXCEPTIONS_LICENSE:
        _fail(
            "spdx-exceptions 2.5.0: expected declared license "
            f"{SPDX_EXCEPTIONS_LICENSE}, found {row.get('license')!r}"
        )
    package_path = row.get("path")
    if not isinstance(package_path, str) or not package_path:
        _fail("spdx-exceptions 2.5.0: package path is missing")
    _contained(licenses, package_path, "spdx-exceptions package path")
    license_file = f"{package_path}/CC-BY-3.0.txt"

    license_files = _string_list(row, "licenseFiles", "spdx-exceptions 2.5.0")
    if license_files and license_file not in license_files:
        _fail(
            "spdx-exceptions 2.5.0: existing licenseFiles conflict with the "
            "missing declared CC-BY-3.0 body"
        )
    if license_file not in license_files:
        license_files.append(license_file)
    row["licenseFiles"] = license_files

    target = _contained(licenses, f"texts/{license_file}", "spdx-exceptions license text")
    if _needs_copy(target, cc_by_text, "spdx-exceptions CC-BY-3.0 text"):
        copies.append((target, cc_by_text))


def _complete_local_templates(
    licenses: Path, runtime: list[dict[str, object]]
) -> list[dict[str, str]]:
    parent = _find_one(
        runtime,
        lambda item: item.get("name") == PRESET_NAME
        and item.get("version") == PRESET_VERSION
        and item.get("path") == PRESET_PATH,
        "locked dsh-agent-preset parent",
    )
    if parent.get("license") != "MIT":
        _fail("locked dsh-agent-preset parent: expected MIT license")
    parent_files = _string_list(parent, "licenseFiles", "locked dsh-agent-preset parent")
    if parent_files.count(PRESET_LICENSE_FILE) != 1:
        _fail(
            "locked dsh-agent-preset parent: licenseFiles must reference exactly "
            f"{PRESET_LICENSE_FILE}"
        )
    parent_text_path = _contained(
        licenses, f"texts/{PRESET_LICENSE_FILE}", "dsh-agent-preset parent LICENSE"
    )
    if not parent_text_path.is_file() or parent_text_path.is_symlink():
        _fail(f"locked dsh-agent-preset parent: LICENSE text is missing: {parent_text_path}")
    try:
        parent_text = parent_text_path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise CompletionError(
            f"locked dsh-agent-preset parent: LICENSE is not UTF-8: {parent_text_path}"
        ) from error
    if "\0" in parent_text or not all(marker in parent_text for marker in MIT_TEXT_MARKERS):
        _fail("locked dsh-agent-preset parent: LICENSE is not recognizable MIT text")

    inherited: list[dict[str, str]] = []
    for package, (version, expected_path) in LOCAL_TEMPLATES.items():
        row = _find_one(
            runtime,
            lambda item, package=package, version=version, expected_path=expected_path: (
                item.get("name") == package
                and item.get("version") == version
                and item.get("path") == expected_path
            ),
            f"local template {package}",
        )
        existing_license = row.get("license")
        if existing_license is not None and existing_license != "MIT":
            _fail(f"{package}: existing license conflicts with parent MIT inheritance")
        inherited_from = row.get("licenseInheritedFrom")
        if inherited_from is not None and inherited_from != PRESET_PATH:
            _fail(f"{package}: existing licenseInheritedFrom is not the locked parent")
        child_files = _string_list(row, "licenseFiles", package)
        if child_files and child_files != [PRESET_LICENSE_FILE]:
            _fail(f"{package}: existing licenseFiles conflict with parent LICENSE reference")

        row["license"] = "MIT"
        row["licenseInheritedFrom"] = PRESET_PATH
        row["licenseFiles"] = [PRESET_LICENSE_FILE]
        inherited.append({"package": package, "from": PRESET_PATH})
    return inherited


def complete_license_metadata(
    licenses: Path, license_texts: Path = DEFAULT_LICENSE_TEXTS
) -> dict[str, object]:
    licenses = licenses.resolve()
    if not licenses.is_dir():
        _fail(f"license directory does not exist: {licenses}")
    runtime_path = licenses / "runtime-packages.json"
    maven_path = licenses / "maven-inventory.json"
    runtime = _load_inventory(runtime_path, "runtime-packages.json")
    maven = _load_inventory(maven_path, "maven-inventory.json")

    apache_text = _verified_legal_text(
        license_texts, "Apache-2.0.txt", APACHE2_SHA256, "Apache-2.0"
    )
    cc_by_text = _verified_legal_text(
        license_texts, "CC-BY-3.0.txt", CC_BY_3_SHA256, "CC-BY-3.0"
    )

    copies: list[tuple[Path, bytes]] = []
    _complete_guava(licenses, maven, apache_text, copies)
    _complete_spdx_exceptions(licenses, runtime, cc_by_text, copies)
    inherited = _complete_local_templates(licenses, runtime)

    for target, data in copies:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    _write_json_if_changed(runtime_path, runtime)
    _write_json_if_changed(maven_path, maven)

    return {
        "guava": GUAVA_COORDINATE,
        "spdxExceptions": f"{SPDX_EXCEPTIONS_NAME}@{SPDX_EXCEPTIONS_VERSION}",
        "licenseInheritedFrom": inherited,
        "copiedLegalTexts": [
            target.relative_to(licenses).as_posix() for target, _ in copies
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("licenses", type=Path)
    args = parser.parse_args()
    try:
        result = complete_license_metadata(args.licenses)
    except CompletionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
