#!/usr/bin/env python3
"""Run the shared JVM/Node CPU-time harness and check its versioned smoke baseline."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "benchmarks/baseline.json"
RESULT_PREFIX = "BENCHMARK_RESULT "
META_PREFIX = "BENCHMARK_META "
COMMON_OPERATIONS = {"inflate", "crc32", "crc32c", "adler32", "crc64", "xxhash32",
                     "deflate", "lz4.encode", "lz4.decode", "snappy.encode", "snappy.decode"}
JDK_OPERATIONS = {"jdk.inflate", "jdk.crc32", "jdk.crc32c", "jdk.adler32",
                  "jdk.deflate.1", "jdk.deflate.6"}
# The crypto suite has no committed baseline. Throughput cases report MB/s, key derivations ms per call.
CRYPTO_THROUGHPUT = {"aes128.ecb", "aes256.ecb", "aes256.cbc.decrypt", "hmac.sha1",
                     "winzip.aes256.write", "winzip.aes256.read"}
CRYPTO_JDK = {"jdk.aes256.ecb", "jdk.hmac.sha1"}
CRYPTO_PER_CALL = {"pbkdf2.sha1.1000", "sevenzip.aes.open"}


def key(row):
    return row["platform"], row["operation"], row["corpus"], row["bytes"]


def validate(report):
    """Reject partial runs, duplicate measurements, invalid timers or corpus drift."""
    profile = report["profile"]
    if profile not in ("smoke", "full"):
        raise ValueError(f"Unknown profile: {profile}")
    sizes = (100, 1024, 65536) if profile == "smoke" else (100, 1024, 65536, 4194304)
    expected = set()
    for target in ("jvm", "js"):
        operations = COMMON_OPERATIONS | (JDK_OPERATIONS if target == "jvm" else set())
        for size in sizes:
            for corpus in ("text", "random", "runs"):
                expected.update((target, operation, corpus, size) for operation in operations)
            expected.add((target, "inflate", "stored", size))
            if target == "jvm":
                expected.add((target, "jdk.inflate", "stored", size))
    if report["schema"] != 1 or report["corpus_version"] != 1:
        raise ValueError("Unknown report or corpus version")
    rows = report["results"]
    actual = {key(row) for row in rows}
    if len(actual) != len(rows) or actual != expected:
        raise ValueError(f"Incomplete or duplicate results: {len(rows)} rows, expected {len(expected)}; "
                         f"missing {sorted(expected - actual)}, unexpected {sorted(actual - expected)}")
    digests = {}
    for row in rows:
        speed = row["mb_per_second"]
        times = row["cpu_ns_per_call"]
        if (not math.isfinite(speed) or speed <= 0 or row["iterations"] < 1
                or row["warmup_calls"] < max(8, min(20000, 4194304 // row["bytes"]))
                or not math.isfinite(row["warmup_cpu_ns"])
                or row["warmup_cpu_ns"] < (100_000_000 if profile == "smoke" else 250_000_000)
                or len(times) != (3 if profile == "smoke" else 5)
                or any(not math.isfinite(value) or value <= 0 for value in times)
                or not math.isclose(speed, row["bytes"] * 1000 / min(times), rel_tol=1e-9)):
            raise ValueError(f"Invalid measurement: {key(row)}")
        corpus_key = row["corpus"], row["bytes"]
        previous = digests.setdefault(corpus_key, row["crc32"])
        if previous != row["crc32"]:
            raise ValueError(f"JVM/Node corpora differ: {corpus_key}")
    return {key(row): row for row in rows}


def compare(report, baseline):
    current = validate(report)
    previous = validate(baseline)
    if report["profile"] != baseline["profile"]:
        raise ValueError("The report and baseline profiles differ")
    # Shared runners and CPU-time accounting differ substantially. This catches
    # order-of-magnitude regressions; it is not a precise performance guarantee.
    minimum_ratio = baseline["minimum_ratio"]
    if not 0 < minimum_ratio <= 1:
        raise ValueError("Baseline minimum_ratio must be in (0, 1]")
    failures = []
    for identity, row in current.items():
        old = previous[identity]
        if row["crc32"] != old["crc32"]:
            raise ValueError(f"Corpus changed without a new baseline: {identity}")
        ratio = row["mb_per_second"] / old["mb_per_second"]
        if ratio < minimum_ratio:
            failures.append(f"{'/'.join(map(str, identity))}: {ratio:.3f}x baseline, "
                            f"minimum {minimum_ratio:.3f}x")
    if failures:
        raise ValueError("Benchmark smoke regression:\n" + "\n".join(failures))
    return min(row["mb_per_second"] / previous[identity]["mb_per_second"]
               for identity, row in current.items())


def validate_crypto(report):
    """Reject a crypto run with missing operations or invalid timings."""
    sizes = (65536,) if report["profile"] == "smoke" else (65536, 4194304)
    expected = set()
    for target in ("jvm", "js"):
        throughput = CRYPTO_THROUGHPUT | (CRYPTO_JDK if target == "jvm" else set())
        expected.update((target, operation, size) for operation in throughput for size in sizes)
        expected.update((target, operation, 0) for operation in CRYPTO_PER_CALL)
    rows = report["results"]
    actual = {(row["platform"], row["operation"], row["bytes"]) for row in rows}
    if len(actual) != len(rows) or actual != expected:
        raise ValueError(f"Incomplete or duplicate crypto results: missing {sorted(expected - actual)}, "
                         f"unexpected {sorted(actual - expected)}")
    for row in rows:
        value = row.get("mb_per_second") if row["bytes"] else row.get("ms_per_call")
        if value is None or not math.isfinite(value) or value <= 0:
            raise ValueError(f"Invalid crypto measurement: {row['platform']} {row['operation']}")


def print_crypto(report):
    rows = {(row["platform"], row["operation"], row["bytes"]): row for row in report["results"]}
    print(f"{'Operation':<22}{'Bytes':>9}  {'JVM':>14}  {'Node':>14}")
    for operation, size in sorted({(o, b) for _, o, b in rows}, key=lambda item: (item[1] == 0, item[1], item[0])):
        cells = []
        for target in ("jvm", "js"):
            row = rows.get((target, operation, size))
            if row is None:
                cells.append("-")
            elif size:
                cells.append(f"{row['mb_per_second']:.1f} MB/s")
            else:
                cells.append(f"{row['ms_per_call']:.2f} ms")
        print(f"{operation:<22}{size if size else '-':>9}  {cells[0]:>14}  {cells[1]:>14}")


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    cache = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")
    downloaded = sorted(cache.glob("extract.cache/*node-v*/bin/node"))
    if downloaded:
        return str(downloaded[-1])
    raise RuntimeError(f"No node executable on PATH or in {cache}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("smoke", "full"), default="smoke")
    parser.add_argument("--suite", choices=("codecs", "crypto"), default="codecs",
                        help="codecs (the default, with the committed baseline) or crypto (encryption costs, no baseline)")
    parser.add_argument("--check", action="store_true", help="Compare smoke results with the committed baseline")
    parser.add_argument("--record-baseline", action="store_true", help="Deliberately replace the smoke baseline")
    parser.add_argument("--output", type=Path, help="Where to save the report (default: benchmarks/build/<suite>.json, "
                                                     "or results.json for the codecs suite)")
    args = parser.parse_args()
    if args.output is None:
        args.output = ROOT / ("benchmarks/build/results.json" if args.suite == "codecs" else f"benchmarks/build/{args.suite}.json")
    if args.suite != "codecs" and (args.check or args.record_baseline):
        parser.error("Only the codecs suite has a baseline")
    if args.profile != "smoke" and (args.check or args.record_baseline):
        parser.error("Baseline checks and updates require the smoke profile")
    if args.check and args.record_baseline:
        parser.error("Checking and replacing the baseline are separate operations")
    kotlin = str(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"))
    environment = dict(os.environ, BENCHMARK_PROFILE=args.profile, BENCHMARK_SUITE=args.suite,
                       KOTLIN_CLI_NO_WELCOME_BANNER="1")
    # Finish both builds before timing, then run each runtime on its own,
    # so neither measures CPU contention from the compiler or the other runtime.
    subprocess.run([kotlin, "build", "-m", "benchmarks-jvm"], cwd=ROOT, env=environment, check=True)
    subprocess.run([kotlin, "task", ":benchmarks-js:linkJs"], cwd=ROOT, env=environment, check=True)
    runs = {
        "JVM": [kotlin, "run", "-m", "benchmarks-jvm", "--jvm-args=-Xmx2g"],
        "Node": [node(), str(ROOT / "build/artifacts/CompiledWebArtifact/benchmarks-jsjsrelease/kotlin-output/benchmarks-js.mjs")],
    }
    results, metadata = [], []
    for name, command in runs.items():
        with subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True, bufsize=1) as process:
            for line in process.stdout:
                print(line, end="", flush=True)
                if line.startswith(RESULT_PREFIX):
                    results.append(json.loads(line[len(RESULT_PREFIX):]))
                elif line.startswith(META_PREFIX):
                    metadata.append(json.loads(line[len(META_PREFIX):]))
            if process.wait():
                raise RuntimeError(f"The {name} benchmarks failed with exit code {process.returncode}")
    if ({row["platform"] for row in metadata} != {"jvm", "js"} or len(metadata) != 2
            or any(row["profile"] != args.profile or row["corpus_version"] != 1 for row in metadata)):
        raise ValueError("Missing or inconsistent JVM/Node run metadata")
    report = {
        "schema": 1, "corpus_version": 1, "profile": args.profile,
        "recorded_at": datetime.now(timezone.utc).isoformat(),
        "os": platform.platform(), "machine": platform.machine(),
        "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)),
        "runtimes": metadata, "results": results,
    }
    if args.suite == "crypto":
        report["suite"] = "crypto"
        validate_crypto(report)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Saved {len(results)} measurements to {args.output}")
        print_crypto(report)
        return
    validate(report)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Saved {len(results)} measurements to {args.output}")
    if args.check:
        ratio = compare(report, json.loads(BASELINE.read_text()))
        print(f"Smoke baseline passed; lowest throughput ratio {ratio:.3f}x")
    if args.record_baseline:
        report["minimum_ratio"] = 0.1
        BASELINE.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Replaced {BASELINE}; review and document these measurements before committing")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
