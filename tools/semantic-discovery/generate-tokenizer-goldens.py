"""Regenerate the fixed publisher-ID fixture with unpadded, untruncated content.

python tools/semantic-discovery/generate-tokenizer-goldens.py --tokenizer app/build/discovery-feasibility/tokenizer.json --output app/build/discovery-feasibility/tokenizer-golden.json
Diff the result before copying to the tracked tools and test-resource fixtures.
"""
import argparse
import hashlib
import json
from pathlib import Path
from tokenizers import Tokenizer

parser=argparse.ArgumentParser()
parser.add_argument("--tokenizer",type=Path,required=True)
parser.add_argument("--output",type=Path,required=True)
args=parser.parse_args()
manifest=json.loads(Path(__file__).with_name("artifacts.json").read_text())
expected=manifest["artifacts"]["tokenizer.json"]["sha256"]
assert hashlib.sha256(args.tokenizer.read_bytes()).hexdigest()==expected,"Publisher tokenizer checksum mismatch"
tokenizer=Tokenizer.from_file(str(args.tokenizer))
tokenizer.no_padding()
tokenizer.no_truncation()
seeds=json.loads(Path(__file__).with_name("tokenizer-golden.json").read_text(encoding="utf-8"))
result=[]
for item in seeds:
    encoded=tokenizer.encode(item["text"])
    result.append({"text":item["text"],"input_ids":encoded.ids,"attention_mask":encoded.attention_mask,"token_type_ids":encoded.type_ids})
args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
