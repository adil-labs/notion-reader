"""Checks inspection code against both compatible and intentionally bad ELF fixtures."""
import pathlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import json


class NativeInspectionTest(unittest.TestCase):
    def testReportsBothFourAndSixteenKiBAlignments(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = pathlib.Path(directory) / "test.aar"
            with zipfile.ZipFile(archive, "w") as target:
                target.writestr("AndroidManifest.xml", "<manifest/>")
                for alignment in (4096, 16384):
                    elf = bytearray(120)
                    elf[:6] = b"\x7fELF\x02\x01"
                    struct.pack_into("<Q",elf,32,64)
                    struct.pack_into("<HH",elf,54,56,1)
                    struct.pack_into("<I",elf,64,1)
                    struct.pack_into("<Q",elf,112,alignment)
                    target.writestr(f"jni/test/lib{alignment}.so",elf)
            report=json.loads(subprocess.check_output([sys.executable,str(pathlib.Path(__file__).with_name("inspect_aar.py")),str(archive)]))
            self.assertEqual([False, True], [item["elf_16k_aligned"] for item in report["libraries"]])


if __name__ == "__main__":
    unittest.main()
