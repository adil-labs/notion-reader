"""Lexical cross-partition contamination audit, never a semantic-quality verdict."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import unicodedata

from public_corpus import digest, encoded, verify_frozen


def tokens(text):
    return re.findall(r"\w+", unicodedata.normalize("NFKC", text).casefold())


def audit_leakage(notes, verified=False):
    ids, families = set(), {}
    shingles, paragraphs = [], []
    for note in notes:
        identity, family, split = note["id"], note["family"], note["partition"]
        if not identity or identity in ids or not family or split not in {"calibration", "held_out"} or (
                family in families and families[family] != split):
            raise ValueError("Invalid Note identity or family split")
        ids.add(identity); families[family] = split
        words = [tokens(p["text"]) for section in note["sections"] for p in section["paragraphs"]]
        shingles.append({hashlib.blake2b(" ".join(p[i:i+5]).encode(), digest_size=16).digest()
                         for p in words for i in range(max(0, len(p) - 4))})
        paragraphs.append({hashlib.sha256(" ".join(p).encode()).digest() for p in words if len(p) >= 40})
    near, shared, contaminated = [], [], set()
    risk_pairs = set()
    considered = 0
    for i, left in enumerate(notes):
        for j in range(i + 1, len(notes)):
            considered += 1
            right = notes[j]
            overlap = len(shingles[i] & shingles[j])
            union = len(shingles[i]) + len(shingles[j]) - overlap
            smaller = min(len(shingles[i]), len(shingles[j]))
            jaccard = overlap / union if union else 0
            containment = overlap / smaller if smaller else 0
            paragraph_matches = len(paragraphs[i] & paragraphs[j])
            cross = left["partition"] != right["partition"]
            pair = {"leftNoteId": left["id"], "rightNoteId": right["id"], "crossPartition": cross}
            duplicate = jaccard >= .65 or smaller >= 200 and containment >= .85
            if duplicate:
                near.append({**pair, "jaccard": jaccard, "containment": containment,
                             "leftShingles": len(shingles[i]), "rightShingles": len(shingles[j])})
            if paragraph_matches:
                shared.append({**pair, "paragraphMatches": paragraph_matches})
            if cross and (duplicate or paragraph_matches):
                risk_pairs.add((left["id"], right["id"]))
                contaminated.update(n["family"] for n in (left, right) if n["partition"] == "held_out")
    expected = len(notes) * (len(notes) - 1) // 2
    if considered != expected:
        raise ValueError("Incomplete pair audit")
    complete = verified and len(notes) == 500
    return {"schemaVersion": 1, "corpusSha256": digest(notes), "notes": len(notes), "completeCorpus": complete,
            "pairsConsidered": considered, "expectedPairs": expected,
            "parameters": {"normalization": "NFKC-casefold-word-tokens", "shingleWords": 5,
                           "shingleDigestBits": 128, "jaccardThreshold": .65,
                           "containmentThreshold": .85, "containmentMinimumShingles": 200,
                           "sharedParagraphMinimumWords": 40},
            "nearDuplicatePairs": near, "sharedParagraphPairs": shared, "crossPartitionRiskPairs": len(risk_pairs),
            "contaminatedHeldOutFamilies": sorted(contaminated),
            "excludedHeldOutNoteIds": sorted(n["id"] for n in notes if n["partition"] == "held_out" and n["family"] in contaminated),
            "leakageGate": "incomplete" if not complete else "reviewRequired" if risk_pairs else "noLexicalRiskDetected",
            "humanQualityGate": "unmeasured",
            "scope": "Lexical sample audit; no proof of semantic family independence, paraphrase detection or human quality acceptance"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--lock", type=Path)
    parser.add_argument("--progress", action="store_true", help="Diagnostic only; can never pass the complete-corpus gate")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.progress:
        notes = json.loads((args.corpus_dir / "capture-progress.json").read_text(encoding="utf-8"))
        report = audit_leakage(notes)
    else:
        if args.lock is None:
            raise SystemExit("A frozen external lock is required")
        corpus = json.loads((args.corpus_dir / "corpus.json").read_text(encoding="utf-8"))
        lock = json.loads(args.lock.read_text(encoding="utf-8"))
        if lock["notes"] != 500 or corpus["manifest"] != lock:
            raise ValueError("Complete external/embedded corpus lock mismatch")
        verify_frozen(corpus["notes"], lock)
        report = audit_leakage(corpus["notes"], verified=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".partial")
    temporary.write_bytes(encoded(report)); temporary.replace(args.output)
    print(f"Lexical audit notes={report['notes']}, pairs={report['pairsConsidered']}, cross-partition risks={report['crossPartitionRiskPairs']}, gate={report['leakageGate']}; human quality unmeasured")
