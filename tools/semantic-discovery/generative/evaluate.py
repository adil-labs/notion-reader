"""Account for explicit reviewed judgments; never infer semantic truth from text."""
import argparse
import json
from pathlib import Path


def _unique(items, key, name):
    result = {}
    for item in items:
        identity = key(item)
        if identity in result:
            raise ValueError(f"duplicate {name}: {identity}")
        result[identity] = item
    return result


def _ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def evaluate(corpus, run, _include_partitions=True):
    cases = _unique(corpus["cases"], lambda c: c["id"], "case")
    families = {}
    references = {}
    eligible = {}
    for cid, case in cases.items():
        if families.setdefault(case["family"], case["split"]) != case["split"]:
            raise ValueError("family crosses evaluation partitions")
        passages = _unique(case["passages"], lambda p: p["id"], "passage")
        eligible[cid] = {pid for pid, p in passages.items() if p["eligible"]}
        refs = _unique(case["references"], lambda r: r["id"], "reference")
        if any(ref["provenance"] not in ("human", "agent", "provisional") for ref in refs.values()):
            raise ValueError("unknown reference provenance")
        references.update({(cid, rid): ref for rid, ref in refs.items()})
    identity = run["identity"]
    if not all(identity.get(k) for k in ("model", "prompt", "corpus")):
        raise ValueError("missing run identity")
    outputs = _unique(run["outputs"], lambda o: o["case_id"], "output")
    if outputs.keys() != cases.keys():
        raise ValueError("output coverage differs from selected corpus")
    topics = {}
    for cid, output in outputs.items():
        for tid, topic in _unique(output["topics"], lambda t: t["id"], "proposal").items():
            topics[(cid, tid)] = topic
    reviews = _unique(run["reviews"], lambda r: (r["case_id"], r["proposal_id"]), "review")
    if reviews.keys() - topics.keys():
        raise ValueError("review references unknown proposal")
    invalid = set()
    for key, topic in topics.items():
        support = topic["passage_ids"]
        if (not isinstance(topic["label"], str) or not topic["label"].strip() or not support
                or len(set(support)) != len(support)
                or not set(support).issubset(eligible[key[0]])):
            invalid.add(key)
    report = {"identity": identity, "cases": len(cases), "topics": len(topics),
              "invalid_topics": len(invalid), "invalid_topic_rate": _ratio(len(invalid), len(topics)),
              "unrated_topics": len(topics.keys() - reviews.keys())}
    for provenance in ("human", "agent", "provisional"):
        totals = dict(rated_topics=0, useful_topics=0, supported_labels=0,
                      claimed_memberships=0, supported_memberships=0)
        found = set()
        gold = {key for key, ref in references.items() if ref["provenance"] == provenance}
        for key, review in reviews.items():
            if review["provenance"] not in ("human", "agent", "provisional") or not review.get("reviewer"):
                raise ValueError("review must declare reviewer and provenance")
            if any(type(review[field]) is not bool for field in
                   ("topic_supported", "label_supported", "label_useful_specific")):
                raise ValueError("review judgments must be explicit booleans")
            reference = review.get("reference_id")
            if reference is not None and (key[0], reference) not in references:
                raise ValueError("unknown reference concept")
            supported = review["supported_passage_ids"]
            if (len(set(supported)) != len(supported)
                    or not set(supported).issubset(topics[key]["passage_ids"])
                    or not set(supported).issubset(eligible[key[0]])):
                raise ValueError("review claims an invalid supported membership")
            if review["provenance"] != provenance:
                continue
            totals["rated_topics"] += 1
            valid = key not in invalid
            useful = valid and review["topic_supported"]
            totals["useful_topics"] += int(useful)
            totals["supported_labels"] += int(useful and review["label_supported"] and review["label_useful_specific"])
            totals["claimed_memberships"] += len(topics[key]["passage_ids"])
            totals["supported_memberships"] += len(supported) if useful else 0
            if useful and (key[0], reference) in gold:
                found.add((key[0], reference))
        report[provenance] = dict(totals,
            topic_precision=_ratio(totals["useful_topics"], totals["rated_topics"]),
            label_precision=_ratio(totals["supported_labels"], totals["rated_topics"]),
            membership_precision=_ratio(totals["supported_memberships"], totals["claimed_memberships"]),
            reference_concepts=len(gold), recovered_concepts=len(found),
            concept_recall=_ratio(len(found), len(gold)),
            missed_concepts=[{"case_id": cid, "reference_id": rid} for cid, rid in sorted(gold - found)])
    if _include_partitions:
        report["partitions"] = {}
        for split in sorted({case["split"] for case in cases.values()}):
            selected = {cid for cid, case in cases.items() if case["split"] == split}
            report["partitions"][split] = evaluate(
                {"cases": [case for cid, case in cases.items() if cid in selected]},
                {"identity": identity,
                 "outputs": [output for cid, output in outputs.items() if cid in selected],
                 "reviews": [review for (cid, _), review in reviews.items() if cid in selected]},
                _include_partitions=False)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    args = parser.parse_args()
    report = evaluate(json.loads(args.cases.read_text(encoding="utf-8")),
                      json.loads(args.run.read_text(encoding="utf-8")))
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
