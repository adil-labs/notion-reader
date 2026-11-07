"""Capture numeric inference events and process memory from one explicitly selected device."""
import argparse
import json
import re
import subprocess
import threading
import time
from pathlib import Path

from inference_metrics import COUNTERS, DURATIONS, MEMORY, PAIR, TAG, summarize


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--seconds", type=int, default=300)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 3600:
        parser.error("seconds must be between 1 and 3600")
    args.output.mkdir(parents=True, exist_ok=True)
    command = ["adb", "-s", args.serial]

    def adb(*parts):
        return subprocess.check_output(command + list(parts), text=True, encoding="utf-8", errors="replace", timeout=20).strip()

    metadata = {"serial": args.serial, "model": adb("shell", "getprop", "ro.product.model"),
                "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
                "fingerprint": adb("shell", "getprop", "ro.build.fingerprint"),
                "startedUnixSeconds": time.time(), "requestedSeconds": args.seconds}
    (args.output / "device.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    lock = threading.Lock()
    log_path = args.output / "metrics.log"
    allowed = COUNTERS | DURATIONS | MEMORY
    with log_path.open("w", encoding="utf-8", buffering=1) as log:
        process = subprocess.Popen(command + ["logcat", "-T", "1", "-v", "epoch", "-s",
            "DiscoveryInference:D", "DiscoveryWork:D", "*:S"], stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")

        def consume():
            for line in process.stdout:
                match = TAG.search(line)
                if match is None:
                    continue
                pairs = [(key, value) for key, value in PAIR.findall(match.group(1)) if key in allowed]
                if pairs:
                    # Retain only known numeric keys; never persist arbitrary application logs.
                    with lock:
                        log.write(f"{time.time():.3f} D DiscoveryWork: " + " ".join(f"{k}={v}" for k, v in pairs) + "\n")

        reader = threading.Thread(target=consume, daemon=True)
        reader.start()
        deadline = time.monotonic() + args.seconds
        try:
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Device log capture exited before the requested interval")
                memory = adb("shell", "dumpsys", "meminfo", "com.example.notionreader")
                fields = {}
                for key, pattern in {"pssKb": r"TOTAL PSS:\s*(\d+)", "rssKb": r"TOTAL RSS:\s*(\d+)",
                                     "nativeHeapKb": r"Native Heap:\s*(\d+)", "javaHeapKb": r"Java Heap:\s*(\d+)"}.items():
                    match = re.search(pattern, memory)
                    if match:
                        fields[key] = int(match.group(1))
                if fields:
                    with lock:
                        log.write(f"{time.time():.3f} D DiscoveryMemory: " + " ".join(f"{k}={v}" for k, v in fields.items()) + "\n")
                time.sleep(min(5, max(0, deadline - time.monotonic())))
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=5)
    report = summarize(log_path.read_text(encoding="utf-8").splitlines())
    report["device"] = metadata
    report["finishedUnixSeconds"] = time.time()
    (args.output / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
