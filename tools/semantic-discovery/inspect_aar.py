"""Read-only native packaging inspection of a downloaded ONNX Runtime AAR."""
import argparse
import hashlib
import json
import struct
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("aar")
args = parser.parse_args()
with zipfile.ZipFile(args.aar) as archive:
    libraries = []
    for name in archive.namelist():
        if not name.endswith(".so"):
            continue
        data = archive.read(name)
        assert data[:4] == b"\x7fELF"
        bits = data[4]
        endian = "<" if data[5] == 1 else ">"
        if bits == 2:
            phoff = struct.unpack_from(endian + "Q", data, 32)[0]
            size, count = struct.unpack_from(endian + "HH", data, 54)
            align_offset, align_format = 48, "Q"
        else:
            phoff = struct.unpack_from(endian + "I", data, 28)[0]
            size, count = struct.unpack_from(endian + "HH", data, 42)
            align_offset, align_format = 28, "I"
        alignments = [struct.unpack_from(endian + align_format, data, phoff + i*size + align_offset)[0] for i in range(count) if struct.unpack_from(endian + "I", data, phoff + i*size)[0] == 1]
        libraries.append({"name": name, "bytes": len(data), "load_segment_alignments": alignments, "elf_16k_aligned": all(a >= 16384 for a in alignments)})
    print(json.dumps({"aar_sha256": hashlib.sha256(open(args.aar,"rb").read()).hexdigest(), "manifest": archive.read("AndroidManifest.xml").decode(), "license_entries": [name for name in archive.namelist() if "license" in name.lower() or "notice" in name.lower()], "libraries": libraries}, indent=2))
