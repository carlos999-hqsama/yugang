import gzip
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "release/normalize-source-archive.py"
spec = importlib.util.spec_from_file_location("normalize_source_archive", SCRIPT)
normalizer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(normalizer)


class SourceArchiveMetadataTests(unittest.TestCase):
    def test_content_is_preserved_without_local_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "local-filename.tar.gz"
            destination = Path(directory) / "public.tar.gz"
            name = "sources/" + "long-name-" * 20 + ".txt"
            data = b"public upstream source\n"
            with tarfile.open(source, "w:gz") as archive:
                info = tarfile.TarInfo(name)
                info.size = len(data)
                info.uid, info.gid = 501, 20
                info.uname, info.gname = "synthetic-owner", "synthetic-group"
                info.mtime = 123456789
                info.pax_headers = {"comment": "synthetic-private-path", "atime": "1234"}
                archive.addfile(info, io.BytesIO(data))
            self.assertEqual(normalizer.normalize(source, destination), 1)
            header = destination.read_bytes()[:10]
            self.assertEqual(header[3] & 8, 0)  # No gzip original filename.
            self.assertEqual(header[4:8], b"\0" * 4)
            with tarfile.open(destination, "r:gz") as archive:
                item = archive.getmembers()[0]
                self.assertEqual(archive.extractfile(item).read(), data)
                self.assertEqual(item.name, name)
                self.assertEqual((item.uid, item.gid, item.mtime), (0, 0, 0))
                self.assertEqual((item.uname, item.gname), ("", ""))
                self.assertEqual(item.pax_headers, {"path": name})
            with gzip.open(destination) as archive:
                self.assertNotIn(b"synthetic-private-path", archive.read())
            with self.assertRaises(FileExistsError):
                normalizer.normalize(source, destination)

    def test_unsafe_members_fail_without_publishing_partial_output(self):
        for name, kind in [("../private.txt", tarfile.REGTYPE), ("/private.txt", tarfile.REGTYPE), ("linked", tarfile.SYMTYPE)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "input.tar.gz"
                destination = Path(directory) / "output.tar.gz"
                with tarfile.open(source, "w:gz") as archive:
                    info = tarfile.TarInfo(name)
                    info.type = kind
                    archive.addfile(info)
                with self.assertRaises(ValueError):
                    normalizer.normalize(source, destination)
                self.assertFalse(destination.exists())
                self.assertEqual(sorted(p.name for p in Path(directory).iterdir()), ["input.tar.gz"])
