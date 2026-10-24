#!/usr/bin/env python3
"""Remove local filesystem metadata from a corresponding-source tar.gz."""
import argparse
import gzip
import os
from pathlib import Path, PurePosixPath
import tarfile
import tempfile


def normalize(source: Path, destination: Path) -> int:
    if destination.exists():
        raise FileExistsError("Destination must be new")
    count = 0
    seen = set()
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as raw:
            temporary = Path(raw.name)
            # A normal gzip writer can otherwise preserve the local input filename.
            with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=1) as compressed:
                with tarfile.open(fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as output:
                    with tarfile.open(source, mode="r|gz") as archive:
                        for item in archive:
                            path = PurePosixPath(item.name)
                            if not item.isfile() or path.is_absolute() or ".." in path.parts or not path.parts:
                                raise ValueError("Only regular, relative source members are admitted")
                            name = path.as_posix()
                            if name in seen:
                                raise ValueError("Duplicate source member")
                            seen.add(name)
                            # Construct a clean header; never copy uname/gname or PAX attributes.
                            clean = tarfile.TarInfo(name)
                            clean.size = item.size
                            clean.mode = 0o644
                            with archive.extractfile(item) as content:
                                output.addfile(clean, content)
                            count += 1
        os.replace(temporary, destination)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    print(f"Normalized {normalize(arguments.source, arguments.destination)} source members")
