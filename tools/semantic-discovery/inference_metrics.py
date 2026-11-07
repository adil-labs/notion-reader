"""Summarize numeric shipping diagnostics. Never copy source text into reports."""
import argparse
import json
import math
import re
from collections import defaultdict
from pathlib import Path

KINDS = ("embedding", "embeddingProbe", "proposal", "support", "label")
COUNTERS = {f"{kind}{suffix}" for kind in KINDS for suffix in ("Attempts", "Successes", "InvalidOutputs")}
COUNTERS |= {"passagesEmbedded", "supportReused", "supportFreshDecisions", "supportSupported",
             "supportUnsupported", "supportUncertain", "supportInvalidAssessments", "staleResultDiscards",
             "schedulerRestarts", "topicsAdmitted"}
DURATIONS = {f"{kind}Nanos" for kind in KINDS} | {
    "corpusReplayNanos", "canonicalAdmissionNanos", "firstPublishedTopicNanos"}
MEMORY = {"pssKb", "rssKb", "nativeHeapKb", "javaHeapKb"}
TAG = re.compile(r"\b(?:DiscoveryInference|DiscoveryWork|DiscoveryMemory)(?:\(\s*\d+\))?\s*:\s*(.*)$")
PAIR = re.compile(r"([A-Za-z][A-Za-z0-9]*)=(-?\d+)(?=\s|,|}|$)")


def distribution(values, divisor=1):
    if not values:
        return None
    ordered = sorted(value / divisor for value in values)
    return {"samples": len(ordered), "mean": sum(ordered) / len(ordered),
            "min": ordered[0], "p50": ordered[math.ceil(len(ordered) * .5) - 1],
            "p95": ordered[math.ceil(len(ordered) * .95) - 1], "max": ordered[-1]}


def summarize(lines):
    counters = defaultdict(int)
    durations = defaultdict(list)
    memory = defaultdict(list)
    malformed = 0
    for line in lines:
        match = TAG.search(line)
        if not match:
            continue
        message = match.group(1)
        parsed = dict(PAIR.findall(message))
        expected = {key for key in COUNTERS | DURATIONS | MEMORY if re.search(r"\b" + key + "=", message)}
        if any(key not in parsed or int(parsed[key]) < 0 for key in expected):
            malformed += 1
            continue
        for key in expected:
            value = int(parsed[key])
            if key in COUNTERS:
                counters[key] += value
            elif key in DURATIONS:
                durations[key].append(value)
            else:
                memory[key].append(value)
    reused = counters.get("supportReused", 0)
    fresh = counters.get("supportFreshDecisions", 0)
    admitted = counters.get("topicsAdmitted", 0)
    attempts = sum(counters.get(f"{kind}Attempts", 0) for kind in KINDS if kind != "embeddingProbe")
    inference_ns = sum(sum(durations.get(f"{kind}Nanos", [])) for kind in KINDS)
    observed = {kind for kind in KINDS if f"{kind}Attempts" in counters or f"{kind}Nanos" in durations}
    return {
        "scope": "Observed log interval only; missing samples are not zero-latency results.",
        "counters": dict(sorted(counters.items())),
        "latencyMilliseconds": {key: distribution(values, 1_000_000) for key, values in sorted(durations.items())},
        "memoryKiB": {key: distribution(values) for key, values in sorted(memory.items())},
        "supportCacheHitRate": reused / (reused + fresh) if reused + fresh else None,
        "inferenceAttemptsPerAdmittedTopic": attempts / admitted if admitted else None,
        "totalObservedNativeInferenceMillisecondsIncludingProbes": inference_ns / 1_000_000,
        "nativeDurationSamplesMatchAttempts": {
            kind: counters.get(f"{kind}Attempts", 0) == len(durations.get(f"{kind}Nanos", []))
            for kind in sorted(observed)},
        "malformedMetricLines": malformed,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = summarize(args.log.read_text(encoding="utf-8-sig").splitlines())
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
