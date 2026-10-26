"""Calibration against explicit existing reference judgments; never fresh acceptance."""
import argparse
import hashlib
import json
from pathlib import Path


def score(corpus, judgments, origin_bytes, report):
    data = report["nameOnlyQueries"]
    origin = json.loads(origin_bytes)
    if report.get("status") != "completed" or data["originSha256"] != hashlib.sha256(origin_bytes).hexdigest():
        raise ValueError("Incomplete or mismatched query experiment")
    topics = {t["id"]: t for t in origin["outputs"][0]["topics"]}
    queries = {q["topicId"]: q for q in data["queries"]}
    if len(queries) != len(data["queries"]) or queries.keys() != topics.keys():
        raise ValueError("Missing or duplicate topic query")
    passages = {p["id"] for c in corpus["cases"] for p in c["passages"] if p["eligible"]}
    references = {r["id"]: set(r["passage_ids"]) for c in corpus["cases"] for r in c["references"]}
    reviews = {r["proposal_id"]: r for r in judgments["reviews"]}
    if reviews.keys() != topics.keys():
        raise ValueError("Missing explicit topic judgments")
    for tid, query in queries.items():
        if topics[tid]["label"] is None:
            if "skipped" not in query or "candidates" in query:
                raise ValueError("Unnamed query was silently invented")
        else:
            ids = [p["passageId"] for p in query["candidates"]]
            if query.get("query") != topics[tid]["label"] or len(ids) != len(set(ids)) or set(ids) != passages:
                raise ValueError("Incomplete source ranking or substituted query")
    rows = []
    for threshold in (.85, .88, .90, .92, .94, .96):
        claimed = correct = 0
        recovered = set()
        recovered_members = set()
        for tid, query in queries.items():
            ref = reviews[tid]["reference_id"]
            if "skipped" in query:
                continue
            accepted = {p["passageId"] for p in query["candidates"] if p["score"] >= threshold}
            positives = accepted & references.get(ref, set())
            claimed += len(accepted)
            correct += len(positives)
            if positives:
                recovered.add(ref)
                recovered_members.update((ref, pid) for pid in positives)
        rows.append(dict(threshold=threshold, retrievedMemberships=claimed, referenceSupportedMemberships=correct,
                         precision=correct / claimed if claimed else None,
                         recoveredSubjects=len(recovered), expectedSubjects=len(references),
                         subjectRecall=len(recovered) / len(references),
                         uniqueRecoveredExpectedMemberships=len(recovered_members),
                         expectedMemberships=sum(len(p) for p in references.values())))
    return dict(scope="seen-corpus name-only calibration against previously explicit source references; not independent new semantic acceptance",
                acceptance=False, corpusSha256=data["corpusSha256"], originSha256=data["originSha256"],
                queryCount=len(queries), skippedNames=sum("skipped" in q for q in queries.values()),
                queryProcessingMillis=sum(q.get("elapsedMillis", 0) for q in queries.values()), thresholds=rows)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("corpus", "judgments", "origin", "report"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    result = score(json.loads(args.corpus.read_text()), json.loads(args.judgments.read_text()),
                   args.origin.read_bytes(), json.loads(args.report.read_text()))
    print(json.dumps(result, indent=2))
