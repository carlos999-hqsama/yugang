#!/usr/bin/env python3
"""Render collected license texts as a self-contained offline notice page."""

import argparse
import hashlib
import html
import json
import re
import tarfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LICENSE_NAME = re.compile(
    r"(?i)^(licen[sc]e|copying|copyright|notice|third.party.notices)([._-].*)?$"
)
# REUSE stores license texts as LICENSES/<SPDX-id>.txt.  Only the direct
# children of a directory named LICENSES qualify; arbitrary documentation .txt
# files must not become license notices.
SPDX_LICENSE_NAME = re.compile(
    r"^(?:LicenseRef-[A-Za-z0-9.-]+|[A-Za-z0-9][A-Za-z0-9.+-]*)\.txt$"
)
NOT_SPDX_LICENSE_NAMES = {
    name.casefold()
    for name in (
        "AUTHORS",
        "CHANGELOG",
        "CHANGES",
        "CONTRIBUTING",
        "CONTRIBUTORS",
        "COPYING",
        "COPYRIGHT",
        "DISCLAIMER",
        "INSTALL",
        "LEGAL",
        "LICENCE",
        "LICENSE",
        "MANIFEST",
        "NEWS",
        "NOTES",
        "NOTICE",
        "README",
        "SECURITY",
        "TERMS",
        "THIRD-PARTY",
        "TODO",
    )
}
METADATA_SUFFIXES = {".jar", ".json", ".xml", ".html"}
BINARY_LICENSE_SUFFIXES = {
    ".pdf",
    ".png",
    ".webp",
    ".jpg",
    ".jpeg",
    ".gif",
    ".zip",
    ".tar",
    ".gz",
    ".xz",
    ".bz2",
    ".7z",
    ".so",
    ".dylib",
    ".dll",
    ".exe",
    ".class",
    ".jar",
}

PAGE_HEADER = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>开源许可证</title><style>body{margin:auto;max-width:900px;padding:20px;font:14px/1.65 system-ui;color:#252725}h1{font-size:24px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.6 monospace}summary{cursor:pointer;padding:12px 0;border-bottom:1px solid #ddd;overflow-wrap:anywhere}td{padding:5px;vertical-align:top;overflow-wrap:anywhere}table{width:100%;border-collapse:collapse}small{color:#666}</style><h1>开源许可证</h1><p>宿主对话与通用插件底座：GPL-3.0-only。第三方组件保留各自许可证。原生 PRoot、talloc、Android shmem、Debian、Node、npm 与 Android 依赖的声明如下。相同正文合并展示，来源名称全部保留。</p><p>完整对应源码及构建脚本随本版本发布材料提供。共享库可使用源代码重新构建及替换；修改版本可自行签名安装。目录隔离不构成恶意代码安全沙箱。</p>"""


def _record_skipped(skipped: list[dict[str, str]], name: str, reason: str) -> None:
    skipped.append({"name": name, "reason": reason})


def _decode_license(name: str, data: bytes, skipped: list[dict[str, str]]) -> str | None:
    suffix = Path(name).suffix.lower()
    if suffix in BINARY_LICENSE_SUFFIXES:
        _record_skipped(skipped, name, "binary auxiliary license material")
        return None
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        _record_skipped(skipped, name, "not strict UTF-8 license text")
        return None
    if not text.strip():
        _record_skipped(skipped, name, "empty license text")
        return None
    if "\0" in text:
        _record_skipped(skipped, name, "contains NUL byte")
        return None
    # Traditional GPL/LGPL texts use form-feed as a page separator.
    text = text.replace("\f", "\n")
    control_chars = sum(
        1
        for char in text
        if ord(char) < 32 and char not in {"\t", "\n", "\r"}
    )
    if control_chars:
        _record_skipped(skipped, name, f"contains {control_chars} unexpected control character(s)")
        return None
    return text


def _license_provenance(row: dict[str, object]) -> str:
    parts: list[str] = []
    inherited = row.get("licenseInheritedFrom")
    if inherited:
        detail = f"继承自 {inherited}"
        files = row.get("licenseFiles")
        if isinstance(files, list) and files:
            detail += "（" + ", ".join(str(item) for item in files) + "）"
        parts.append(detail)
    source = row.get("licenseSource")
    if isinstance(source, dict):
        file = source.get("file")
        location = str(file) if file else ""
        artifact = row.get("sourceArtifact")
        if artifact and location:
            location = f"{artifact}!{location}"
        if location:
            parts.append(f"源码头 {location}")
        copyright_text = source.get("copyright")
        if copyright_text:
            parts.append(str(copyright_text))
    elif source:
        parts.append(str(source))
    return "；".join(parts)


def _add_license(
    groups: dict[str, dict[str, object]], name: str, text: str
) -> None:
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    entry = groups.setdefault(digest, {"names": [], "text": text})
    names = entry["names"]
    names.append(name)


def _is_reuse_license_member(name: str) -> bool:
    relative = Path(name)
    if relative.parent.name != "LICENSES" or not SPDX_LICENSE_NAME.match(relative.name):
        return False
    return relative.stem.casefold() not in NOT_SPDX_LICENSE_NAMES


def _is_archive_license_member(name: str) -> bool:
    relative = Path(name)
    if len(relative.parts) <= 3 and LICENSE_NAME.match(relative.name):
        return True
    return _is_reuse_license_member(name)


def _archive_members(path: Path, skipped: list[dict[str, str]]):
    def is_license(name):
        return _is_archive_license_member(name)
    try:
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                for name in sorted(archive.namelist()):
                    if not name.endswith("/") and is_license(name):
                        yield name, archive.read(name)
        elif tarfile.is_tarfile(path):
            # Source archives can be hundreds of MB. Stream once and read only
            # licenses, never seek backwards through sorted compressed members.
            with tarfile.open(path, mode="r|*") as archive:
                for member in archive:
                    if not member.isfile() or not is_license(member.name):
                        continue
                    stream = archive.extractfile(member)
                    if stream is not None:
                        yield member.name, stream.read()
    except (OSError, EOFError, tarfile.ReadError, tarfile.StreamError, zipfile.BadZipFile):
        _record_skipped(skipped, str(path), "unreadable source archive")


def render_notices(
    licenses: Path,
    native_sources: Path,
    extra_sources: Path,
    output: Path,
    project_root: Path = ROOT,
) -> dict[str, object]:
    groups: dict[str, dict[str, object]] = {}
    skipped: list[dict[str, str]] = []

    for path in sorted(licenses.rglob("*")):
        if not path.is_file() or path.suffix.lower() in METADATA_SUFFIXES:
            continue
        text = _decode_license(
            path.relative_to(licenses).as_posix(), path.read_bytes(), skipped
        )
        if text is not None:
            _add_license(groups, path.relative_to(licenses).as_posix(), text)

    for directory in (native_sources, extra_sources):
        for path in sorted(directory.rglob("*")):
            if not path.is_file() or path.suffix == ".part":
                continue
            for member_name, data in _archive_members(path, skipped):
                display_name = f"{path.name}/{member_name}"
                text = _decode_license(display_name, data, skipped)
                if text is not None:
                    _add_license(groups, display_name, text)

    runtime = json.loads((licenses / "runtime-packages.json").read_text(encoding="utf-8"))
    maven = json.loads((licenses / "maven-inventory.json").read_text(encoding="utf-8"))
    project_license = _decode_license(
        "Yugang (鱼缸) / GPL-3.0-only",
        (project_root / "LICENSE").read_bytes(),
        skipped,
    )
    if project_license is None:
        raise ValueError("Project GPL license is not valid UTF-8 text")
    _add_license(groups, "Yugang (鱼缸) / GPL-3.0-only", project_license)

    rows = "".join(
        "<tr><td>"
        + html.escape(str(row["name"]))
        + "</td><td>"
        + html.escape(str(row["version"]))
        + "</td><td>"
        + html.escape(str(row.get("license", "See retained package notices")))
        + "</td><td>"
        + html.escape(_license_provenance(row))
        + "</td></tr>"
        for row in runtime
    )
    rows += "".join(
        "<tr><td>"
        + html.escape(row["coordinate"])
        + "</td><td>Android</td><td>"
        + html.escape(", ".join(item["name"] or "" for item in row["licenses"]))
        + "</td><td>"
        + html.escape(_license_provenance(row))
        + "</td></tr>"
        for row in maven
    )
    body = (
        '<details><summary>实际运行时与 Android 组件清单</summary><table>'
        "<tr><th>组件</th><th>版本</th><th>许可证</th><th>许可来源</th></tr>"
        + rows
        + "</table></details>"
    )
    for entry in groups.values():
        names = entry["names"]
        text = entry["text"]
        assert isinstance(text, str) and text.strip()
        suffix = f" · {len(names)} 处相同声明" if len(names) > 1 else ""
        body += (
            "<details><summary>"
            + html.escape(names[0])
            + html.escape(suffix)
            + "</summary><small>"
            + html.escape("\n".join(names))
            + "</small><pre>"
            + html.escape(text)
            + "</pre></details>"
        )

    page = PAGE_HEADER + body + "</html>"
    if "\0" in page:
        raise ValueError("Rendered notice page contains a NUL byte")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(page, encoding="utf-8", newline="")

    return {
        "licenseTexts": sum(len(entry["names"]) for entry in groups.values()),
        "uniqueLicenseTexts": len(groups),
        "runtimePackages": len(runtime),
        "androidDependencies": len(maven),
        "skippedAuxiliaryFiles": len(skipped),
        "skipped": skipped,
        "htmlBytes": output.stat().st_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("licenses", type=Path)
    parser.add_argument("native_sources", type=Path)
    parser.add_argument("extra_sources", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = render_notices(
        args.licenses,
        args.native_sources,
        args.extra_sources,
        args.output,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
