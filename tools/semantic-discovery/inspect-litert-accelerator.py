"""Read-only proof that published SM8750 and CPU bundles share tokenizer/embedding metadata."""
import argparse
import hashlib
import json
import struct
from pathlib import Path
from flatbuffers.table import Table
from flatbuffers.number_types import Uint64Flags, Uint8Flags


def sections(path, expected):
    data = Path(path).read_bytes()
    assert hashlib.sha256(data).hexdigest() == expected
    assert data[:8] == b"LITERTLM"
    root = Table(data, 32 + struct.unpack_from("<I", data, 32)[0])
    items = Table(data, root.Indirect(root.Pos + root.Offset(6)))
    offset = items.Offset(4)
    result = {}
    interfaces = []
    for index in range(items.VectorLen(offset)):
        item = Table(data, items.Indirect(items.Vector(offset) + index * 4))
        def field(slot, kind):
            pos = item.Offset(slot)
            return item.Get(kind, item.Pos + pos) if pos else 0
        begin, end, kind = field(6, Uint64Flags), field(8, Uint64Flags), field(10, Uint8Flags)
        assert 0 <= begin < end <= len(data)
        if kind in (4, 8):
            result[kind] = {"offset": begin, "bytes": end - begin,
                            "sha256": hashlib.sha256(data[begin:end]).hexdigest()}
        if kind == 3 and data[begin + 4:begin + 8] == b"TFL3":
            model = Table(data, begin + struct.unpack_from("<I", data, begin)[0])
            graphs = model.Offset(8)
            for graph_index in range(model.VectorLen(graphs)):
                graph = Table(data, model.Indirect(model.Vector(graphs) + graph_index * 4))
                name_offset = graph.Offset(12)
                name = graph.String(graph.Pos + name_offset).decode() if name_offset else ""
                if not name.startswith("encoder_1x"):
                    continue
                tensors = graph.Offset(4)
                io_types = {}
                for slot, label in ((6, "inputs"), (8, "outputs")):
                    indices = graph.Offset(slot)
                    types = []
                    for io_index in range(graph.VectorLen(indices)):
                        tensor_index = struct.unpack_from("<i", data, graph.Vector(indices) + io_index * 4)[0]
                        tensor = Table(data, graph.Indirect(graph.Vector(tensors) + tensor_index * 4))
                        type_offset = tensor.Offset(6)
                        # TFLite schema TensorType.FLOAT32 is zero, including omitted defaults.
                        types.append(tensor.Get(Uint8Flags, tensor.Pos + type_offset) if type_offset else 0)
                    assert types and all(value == 0 for value in types), (name, label, types)
                    io_types[label] = types
                interfaces.append({"signature": name, **io_types})
    assert interfaces
    return result, interfaces


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("cpu")
    parser.add_argument("npu")
    args = parser.parse_args()
    cpu, cpu_io = sections(args.cpu, "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb")
    npu, npu_io = sections(args.npu, "b5615709768db4ca4fb4f04514e7e41b0680ccb12dd5f90371193f7fd12e7c99")
    assert cpu.keys() == npu.keys() == {4, 8}
    for kind in cpu:
        assert cpu[kind]["sha256"] == npu[kind]["sha256"], (kind, cpu, npu)
    print(json.dumps({"cpu": cpu, "npu": npu, "metadataMatches": True,
                      "cpuFloat32Interfaces": cpu_io, "npuFloat32Interfaces": npu_io}, indent=2))
