"""Developer-only topic calibration. Never imported or shipped by Android.

Reads only the synthetic corpus and pinned public model. Exports actual model
vectors for JVM algorithm tests; physical inference is verified independently.
"""
import argparse
import json
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifacts', type=Path, default=Path('app/build/discovery-feasibility'))
    parser.add_argument('--corpus', type=Path, default=Path(__file__).parent / 'topic-evaluation.json')
    parser.add_argument('--output', type=Path, default=Path('app/src/test/resources/discovery/topic-vectors.json'))
    args = parser.parse_args()
    corpus = json.loads(args.corpus.read_text(encoding='utf-8'))
    tokenizer = Tokenizer.from_file(str(args.artifacts / 'tokenizer.json'))
    tokenizer.no_padding(); tokenizer.no_truncation()
    session = ort.InferenceSession(str(args.artifacts / 'onnx/model_qint8_arm64.onnx'), providers=['CPUExecutionProvider'])
    for doc in corpus['documents']:
        for passage in doc['passages']:
            text = f"{doc['title']}\n{passage['heading']}\n{passage['text']}"
            encoded = tokenizer.encode(text)
            assert len(encoded.ids) <= 256
            feeds = {'input_ids': np.array([encoded.ids], dtype=np.int64),
                     'attention_mask': np.array([encoded.attention_mask], dtype=np.int64),
                     'token_type_ids': np.array([encoded.type_ids], dtype=np.int64)}
            out = session.run(None, {i.name: feeds[i.name] for i in session.get_inputs()})[0]
            mask = feeds['attention_mask'][:, :, None]
            pooled = (out * mask).sum(axis=1)[0] / mask.sum()
            passage['vector'] = (pooled / np.linalg.norm(pooled)).tolist()
            passage['input'] = text
    corpus['provenance'] = {'modelRevision': '1110a243fdf4706b3f48f1d95db1a4f5529b4d41',
                            'runtime': ort.__version__, 'dimensions': 384,
                            'purpose': 'Actual host CPU outputs; not physical-device parity evidence'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(corpus, separators=(',', ':')) + '\n')
    print(f"Wrote {sum(len(d['passages']) for d in corpus['documents'])} real model vectors to {args.output}")

if __name__ == '__main__':
    main()
