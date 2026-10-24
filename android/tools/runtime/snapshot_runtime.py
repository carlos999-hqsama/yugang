#!/usr/bin/env python3
"""Capture one validated runtime archive/manifest pair for APK packaging.

The packer owns the source directory and may replace it at any time.  This
script opens both source files before copying them, copies only the two
distribution names, re-hashes the private copy, and publishes it only after
the captured manifest matches the captured archive.  It deliberately does not
try to share a lock with pack.py: POSIX flock is not a portable cross-language
publication protocol.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import uuid
from pathlib import Path


ARCHIVE_NAME = "runtime.tar.xz"
MANIFEST_NAME = "runtime.json"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def open_regular(path: Path) -> tuple[int, os.stat_result]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        info = os.fstat(descriptor)
        if not stat.S_ISREG(info.st_mode):
            raise ValueError(f"Runtime input is not a regular file: {path}")
        return descriptor, info
    except BaseException:
        os.close(descriptor)
        raise


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def publish_directory(stage: Path, output: Path, backup_parent: Path | None = None) -> None:
    """Replace output as a directory while restoring the old dir on failure."""
    backup_root = backup_parent or output.parent
    output.parent.mkdir(parents=True, exist_ok=True)
    backup_root.mkdir(parents=True, exist_ok=True)
    backup = backup_root / f".{output.name}.previous-{os.getpid()}-{uuid.uuid4().hex}"
    had_output = os.path.lexists(output)
    if had_output:
        os.replace(output, backup)
    try:
        os.replace(stage, output)
    except BaseException:
        if had_output:
            os.replace(backup, output)
        raise
    if had_output:
        if backup.is_dir() and not backup.is_symlink():
            shutil.rmtree(backup)
        else:
            backup.unlink()


def snapshot_runtime(manifest_source: Path, archive_source: Path, output: Path) -> None:
    # Once both descriptors are open, a packer rename cannot mutate the bytes
    # seen here.  If it publishes between the two opens, the final hash check
    # rejects the mixed manifest/archive pair instead of packaging it.
    manifest_fd, _ = open_regular(manifest_source)
    try:
        archive_fd, _ = open_regular(archive_source)
    except BaseException:
        os.close(manifest_fd)
        raise

    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=f".{output.name}.stage-", dir=output.parent))
    try:
        with os.fdopen(manifest_fd, "rb", closefd=False) as source:
            manifest_bytes = source.read(16 * 1024 * 1024)
            if source.read(1):
                raise ValueError("Runtime manifest is too large")
        metadata = json.loads(manifest_bytes.decode("utf-8"))
        expected_hash = metadata.get("sha256")
        if not isinstance(expected_hash, str) or not SHA256.fullmatch(expected_hash):
            raise ValueError("Runtime manifest has no valid archive sha256")

        staged_archive = stage / ARCHIVE_NAME
        archive_flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0)
        archive_destination = os.open(staged_archive, archive_flags, 0o644)
        archive_stream = os.fdopen(archive_destination, "wb", closefd=True)
        try:
            with os.fdopen(archive_fd, "rb", closefd=False) as source, archive_stream as destination:
                shutil.copyfileobj(source, destination, length=1024 * 1024)
                destination.flush()
                os.fsync(destination.fileno())
        finally:
            if not archive_stream.closed:
                archive_stream.close()

        # Verify the private copy, not merely the source path that was checked
        # before this task started.  This is the mixed-pair publication gate.
        actual_hash = sha256_file(staged_archive)
        if actual_hash != expected_hash:
            raise ValueError(
                "Captured runtime archive does not match captured manifest "
                f"(expected {expected_hash}, got {actual_hash})"
            )

        staged_manifest = stage / MANIFEST_NAME
        manifest_destination = os.open(staged_manifest, archive_flags, 0o644)
        manifest_stream = os.fdopen(manifest_destination, "wb", closefd=True)
        try:
            with manifest_stream as destination:
                destination.write(manifest_bytes)
                destination.flush()
                os.fsync(destination.fileno())
        finally:
            if not manifest_stream.closed:
                manifest_stream.close()

        if {path.name for path in stage.iterdir()} != {ARCHIVE_NAME, MANIFEST_NAME}:
            raise AssertionError("Private runtime snapshot contains unexpected files")
        directory_fd = os.open(stage, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)

        publish_directory(stage, output)
    finally:
        os.close(manifest_fd)
        os.close(archive_fd)
        if stage.exists():
            shutil.rmtree(stage)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    snapshot_runtime(args.manifest, args.archive, args.output)


if __name__ == "__main__":
    main()
