"""Read-only inspection of the published bundle: creates token evidence, never model files.

Requires flatbuffers and sentencepiece. Container layout follows
google-ai-edge/LiteRT-LM v0.18.1 schema/core/litertlm_header_schema.fbs and
python/litert_lm_builder/litertlm_peek.py. Token metadata follows
runtime/proto/embedding_metadata.proto and token.proto at the same tag.
"""
import argparse
import hashlib
import json
import random
import struct
from pathlib import Path

import sentencepiece as spm
from sentencepiece import sentencepiece_model_pb2
from flatbuffers.table import Table
from flatbuffers.number_types import Uint64Flags, Uint8Flags


def inspect(bundle):
    assert hashlib.sha256(bundle).hexdigest() == "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
    assert bundle[:8] == b"LITERTLM"
    root = Table(bundle, 32 + struct.unpack_from("<I", bundle, 32)[0])
    sections = Table(bundle, root.Indirect(root.Pos + root.Offset(6)))
    objects = sections.Offset(4)
    payloads = {}
    for index in range(sections.VectorLen(objects)):
        section = Table(bundle, sections.Indirect(sections.Vector(objects) + index * 4))
        def field(slot, kind):
            offset = section.Offset(slot)
            return section.Get(kind, section.Pos + offset) if offset else 0
        begin, end, kind = field(6, Uint64Flags), field(8, Uint64Flags), field(10, Uint8Flags)
        assert 0 <= begin < end <= len(bundle)
        if kind in (4, 8):
            assert kind not in payloads
            payloads[kind] = bundle[begin:end]
            if kind == 4:
                assert (begin, end) == (32768, 4721781)
    processor = spm.SentencePieceProcessor(model_proto=payloads[4])
    proto = sentencepiece_model_pb2.ModelProto()
    proto.ParseFromString(payloads[4])
    user_defined = [i for i, piece in enumerate(proto.pieces) if piece.type == 4]
    assert user_defined == list(range(4, 238)) + list(range(255968, 262144))
    assert [(i, p.piece) for i, p in enumerate(proto.pieces) if p.type == 3] == [(0, "<pad>"), (1, "<eos>"), (2, "<bos>")]
    # TokenUnion(TokenIds([2])) and TokenUnion(TokenIds([1])) in embedding metadata.
    assert b"\x12\x05\x0a\x03\x0a\x01\x02" in payloads[8]
    assert b"\x1a\x05\x0a\x03\x0a\x01\x01" in payloads[8]
    texts = ["", "Hello world", "naïve café 🐱 中文\n👨‍👩‍👧‍👦", "<bos><eos>", "<unk>",
        "a<pad>b", "<mask>[multimodal]<unused0>", "\n" * 40, "x\t\t  y"]
    rng = random.Random(17)
    pieces = ["Hello", " world", "中文", "🦊", "\n", "é", "<unused0>", "<bos>", "<pad>", "<unk>", "<mask>", "<0xFF>", "\u200d", "\t", " "]
    texts += ["".join(rng.choices(pieces, k=15)) for _ in range(100)]
    prefix = "task: clustering | text: "
    boundaries = []
    for count in (8183, 8184, 8185):
        text = " token" * count
        actual = [2] + processor.encode(prefix + text.strip()) + [1]
        boundaries.append(dict(repeats=count, tokens=len(actual)))
    return dict(bundleSha256=hashlib.sha256(bundle).hexdigest(),
        sentencePieceSha256=hashlib.sha256(payloads[4]).hexdigest(),
        verifiedCases=len(texts), userDefinedTokens=len(user_defined), bos=2, eos=1,
        boundaries=boundaries,
        cases=[dict(text=text, ids=processor.encode(text),
            utf16Offsets=[[len(text.encode("utf-8")[:piece.begin].decode("utf-8").encode("utf-16-le")) // 2,
                           len(text.encode("utf-8")[:piece.end].decode("utf-8").encode("utf-16-le")) // 2]
                          for piece in processor.encode(text, return_type="proto").pieces],
            instructedIds=[2] + processor.encode(prefix + text.strip()) + [1]) for text in texts])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("build/litert-migration/model"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inspect((args.directory / "embeddinggemma-2-text-270m.litertlm").read_bytes())
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"Verified {result['verifiedCases']} cases, bundled user-defined count {result['userDefinedTokens']}; boundaries {result['boundaries']}")
