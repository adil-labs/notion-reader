"""Content-free audit of private discovery judgments and paired evaluation runs.

Legacy editorial decisions remain provisional unless each case records explicit
human confirmation. This audit cannot establish reviewer authenticity; it checks
recorded provenance and never infers it from a filename or aggregate header.
"""

import argparse
from collections import Counter
import json
import math
from pathlib import Path


DECISIONS = {"Keep", "Rename", "Merge", "Reference-only", "ReferenceOnly", "Withhold",
             "Merge/Refine", "Merge/Keep", "Merge/Withhold", "Merge/Rename"}
METRICS = {"published", "emerging", "withheld", "falsePublicationRate", "subjectRecall",
           "membershipPrecision", "labelUsefulness", "reviewedSubjects", "reviewedMemberships"}


def audit_judgments(document):
    cases = document.get("cases", [])
    if not isinstance(cases, list):
        raise ValueError("Cases must be a list")
    declared = document.get("totalCandidates")
    if declared is not None and declared != len(cases):
        raise ValueError("Declared candidate count disagrees with cases")
    identities = [str(case.get("caseId", case.get("clusterId", ""))) for case in cases]
    if any(not identity for identity in identities) or len(set(identities)) != len(identities):
        raise ValueError("Cases require unique nonempty identities")
    counts = Counter()
    human_decisions = Counter()
    all_decisions = Counter()
    for case in cases:
        decision = case.get("decision")
        if decision is None or decision == "":
            counts["unrated"] += 1
            continue
        if decision not in DECISIONS:
            raise ValueError("Unknown review decision")
        all_decisions[decision] += 1
        confirmed = (case.get("judgmentOrigin") == "human"
                     and case.get("reviewerConfirmed") is True
                     and isinstance(case.get("reviewedAt"), str)
                     and bool(case["reviewedAt"].strip()))
        counts["confirmedHuman" if confirmed else "provisional"] += 1
        if confirmed:
            human_decisions[decision] += 1
    human_count = counts["confirmedHuman"]
    return {"totalCandidates": len(cases),
            **{key: counts[key] for key in ("confirmedHuman", "provisional", "unrated")},
            "decisionCounts": dict(sorted(all_decisions.items())),
            "declaredSummaryMatches": document.get("summary") == dict(all_decisions)
                if "summary" in document else None,
            "humanKeepRate": ((human_decisions["Keep"] + human_decisions["Rename"]) / human_count
                              if human_count else None),
            "qualityGate": "unmeasured",
            "caution": "Keep rate is a reviewed-decision statistic, not missed-topic recall or membership accuracy."}


def compare_runs(baseline, candidate):
    fingerprint = baseline.get("corpusFingerprint")
    if not isinstance(fingerprint, str) or not fingerprint or fingerprint != candidate.get("corpusFingerprint"):
        raise ValueError("Baseline and candidate require the same nonempty corpus fingerprint")
    for run in (baseline, candidate):
        calibration = run.get("calibrationFamilies")
        held_out = run.get("heldOutFamilies")
        if not calibration or not held_out or set(calibration) & set(held_out):
            raise ValueError("Nonempty disjoint Note-family partitions required")
        if set(run.get("exposedFamilies", [])) & set(held_out):
            raise ValueError("Previously exposed families cannot be held-out evidence")
        metrics = run.get("metrics", {})
        if not isinstance(metrics, dict) or any(key not in METRICS or isinstance(value, bool)
                or not isinstance(value, (int, float)) or not math.isfinite(value)
                for key, value in metrics.items()):
            raise ValueError("Only known finite numeric metrics may enter the content-free report")
    for key in ("calibrationFamilies", "heldOutFamilies"):
        if set(baseline[key]) != set(candidate[key]):
            raise ValueError("Both engines must use identical partitions")
    # Counts and scores are reported separately; this tool never certifies human or Android gates.
    return {"sameCorpus": True, "samePartitions": True,
            "baselineMetrics": baseline.get("metrics", {}),
            "candidateMetrics": candidate.get("metrics", {}),
            "qualityGate": "unmeasured", "androidGate": "unmeasured"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("judgments", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--candidate", type=Path)
    args = parser.parse_args()
    if bool(args.baseline) != bool(args.candidate):
        parser.error("Provide both baseline and candidate")
    report = {"judgments": audit_judgments(json.loads(args.judgments.read_text(encoding="utf-8-sig")))}
    if args.baseline:
        report["comparison"] = compare_runs(json.loads(args.baseline.read_text(encoding="utf-8-sig")),
                                            json.loads(args.candidate.read_text(encoding="utf-8-sig")))
    # Only counts and gate states from the review; no case IDs, titles, excerpts or reviewer identities.
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
