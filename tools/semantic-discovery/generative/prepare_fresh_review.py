"""Bind a joint-library review worksheet to frozen source and actual shipping output.

No semantic judgments are inferred from family names, expected memberships or labels.
Use evaluate.py only after separately filling explicit source-based reviews.
"""
import argparse
import hashlib
import json
from pathlib import Path


def prepare(corpus_bytes, report_bytes, manifest):
    corpus_hash = hashlib.sha256(corpus_bytes).hexdigest()
    report = json.loads(report_bytes)
    source = json.loads(corpus_bytes)
    if (corpus_hash != manifest["corpusSha256"] or report.get("status") != "completed"
            or report["semantic"]["corpusSha256"] != corpus_hash
            or report["semantic"].get("stage") != "completed"):
        raise ValueError("Incomplete report or frozen corpus mismatch")
    rows = report["semantic"]["outputs"]
    if len(rows) != 1 or rows[0]["case_id"] != "joint-library":
        raise ValueError("Expected one joint-library output")
    passages, references = [], []
    families = set()
    for case in source["cases"]:
        if case["family"] in families or case["split"] != "heldout":
            raise ValueError("Duplicate family or non-held-out input")
        families.add(case["family"])
        passages.extend(dict(p, note_id=f'{case["id"]}:{p["note_id"]}') for p in case["passages"])
        references.extend(dict(r, source_family=case["family"]) for r in case["references"])
    if len({p["id"] for p in passages}) != len(passages):
        raise ValueError("Ambiguous source identity")
    row = rows[0]
    raw_topics = row.get("topics", [])
    topics = [dict(id=t["id"], label=t["label"] if t["label"] is not None else t["neutralDisplay"],
                   generated_name=t["label"], passage_ids=t["passage_ids"])
              for t in raw_topics if t["published"]]
    joint = dict(id="joint-library", family="fresh-v1-joint-library", split="heldout",
                 original_source_families=sorted(families), passages=passages, references=references)
    run = dict(identity=dict(model=manifest["labelPolicy"] + ":" + report["semantic"]["labelIdentity"],
                             prompt=report["semantic"]["labelIdentity"], corpus=corpus_hash,
                             rawReportSha256=hashlib.sha256(report_bytes).hexdigest(),
                             frozenApkSha256=manifest["apkSha256"], fixtureAuthorIsReviewer=True,
                             provenance="agent review required; independent human review absent"),
               outputs=[dict(case_id="joint-library", topics=topics)], reviews=[],
               observations=dict(sourceFamilies=len(families), sourcePassages=len(passages),
                                 pending=row.get("pending"), processingFailure=row.get("processingFailure"),
                                 error=row.get("error"), allCanonicalGroups=len(raw_topics),
                                 publishedGroups=len(topics), unnamedPublishedGroups=sum(t["generated_name"] is None for t in topics)))
    return dict(cases=[joint]), run


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output-prefix", type=Path, required=True)
    args = parser.parse_args()
    corpus, run = prepare(args.corpus.read_bytes(), args.report.read_bytes(), json.loads(args.manifest.read_text(encoding="utf-8-sig")))
    for suffix, value in (("-joint-cases.json", corpus), ("-review-input.json", run)):
        path = Path(str(args.output_prefix) + suffix)
        if path.exists():
            raise ValueError("Never overwrite an existing review artifact")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
