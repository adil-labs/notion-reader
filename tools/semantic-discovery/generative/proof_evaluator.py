"""Temporarily remove one shipping guard, prove its tests fail, always restore it."""
from pathlib import Path
import re
import subprocess
import sys


def run_suite(directory):
    return subprocess.run([sys.executable, "-m", "unittest", "-v", "test_evaluate"],
                          cwd=directory, capture_output=True, text=True)


def main():
    directory = Path(__file__).resolve().parent
    target = directory / "evaluate.py"
    original = target.read_bytes()
    text = original.decode("utf-8")
    guard = 'or not set(support).issubset(eligible[key[0]])'
    if text.count(guard) != 1:
        raise RuntimeError("Expected exactly one eligible-source guard in shipping evaluator")
    baseline = run_suite(directory)
    if baseline.returncode != 0:
        raise RuntimeError("Baseline suite is not green:\n" + baseline.stderr)
    try:
        target.write_text(text.replace(guard, "or False"), encoding="utf-8")
        broken = run_suite(directory)
        evidence = broken.stdout + broken.stderr
        failures = set(re.findall(r"^(test\w+) \([^\n]+\) \.\.\. FAIL$", evidence, re.MULTILINE))
        expected = {"testFabricatedCitationCannotReceiveAUsefulTopicScore",
                    "testRevokedPassageInvalidatesOutput"}
        if broken.returncode != 1 or failures != expected or "... ERROR" in evidence:
            raise RuntimeError("Guard removal failed to produce exactly the intended failures:\n" + evidence)
    finally:
        target.write_bytes(original)
    restored = run_suite(directory)
    if restored.returncode != 0:
        raise RuntimeError("Restored suite is not green:\n" + restored.stderr)
    print("Shipping guard removed: exactly fabricated-citation and revoked-source checks failed.")
    print("Original bytes restored; full suite passed before and after the proof.")


if __name__ == "__main__":
    main()
