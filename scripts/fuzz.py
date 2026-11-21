#!/usr/bin/env python3
"""Fuzz the core's decoders and readers with Jazzer on the JVM.

Builds the core's JVM jar, downloads a pinned Jazzer and checks its SHA-256, compiles the
harnesses in fuzz/src, and runs one target. Inputs that make a harness throw anything but an
ArchiveException, or use more memory than the limits allow, are saved as crash-* files in the
output folder; keep each one as a test fixture once it is fixed.

  python3 scripts/fuzz.py archive                    # every archive format, detected
  python3 scripts/fuzz.py archive --format zip       # one format, chosen by id
  python3 scripts/fuzz.py codec --codec zstd --seconds 3600
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JAZZER_VERSION = "0.24.0"
JAZZER = {
    "jazzer": "5b04a75bfd3dedd1ef38366561d0182e6cfb9bf4aed2dd3dc811fa6922ddc8bb",
    "jazzer-api": "71f8492434a612bb4d6be26b1fcd408d284204637df8dd5570805fca48968da4",
}
CORE_JAR = ROOT / "build/tasks/_archivekodec_jarJvm/archivekodec-jvm.jar"
WORK = ROOT / "build/fuzz"


def run(*args, **kwargs):
    print("Running " + " ".join(str(a) for a in args[:4]) + (" ..." if len(args) > 4 else ""), flush=True)
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def jazzer_jar(name):
    path = WORK / "jazzer" / f"{name}-{JAZZER_VERSION}.jar"
    if not path.is_file():
        path.parent.mkdir(parents=True, exist_ok=True)
        url = f"https://repo1.maven.org/maven2/com/code-intelligence/{name}/{JAZZER_VERSION}/{name}-{JAZZER_VERSION}.jar"
        with urllib.request.urlopen(url) as response:
            data = response.read()
        digest = hashlib.sha256(data).hexdigest()
        if digest != JAZZER[name]:
            raise RuntimeError(f"{name} {JAZZER_VERSION} has SHA-256 {digest}, expected {JAZZER[name]}")
        path.write_bytes(data)
    return path


def kotlin_stdlib():
    """The kotlin-stdlib jar the toolchain resolved for the build."""
    cache = Path.home() / ".cache/JetBrains/Kotlin/.m2.cache/org/jetbrains/kotlin/kotlin-stdlib"
    jars = sorted(p for p in cache.glob("*/kotlin-stdlib-*.jar") if "sources" not in p.name and "javadoc" not in p.name)
    if not jars:
        raise RuntimeError(f"No kotlin-stdlib jar under {cache}; run ./kotlin build -m archivekodec -p jvm first")
    return jars[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("target", choices=("archive", "codec"))
    parser.add_argument("--format", help="archive: a format id such as zip or seven_zip (default: detect)")
    parser.add_argument("--codec", default="deflate", help="codec: a codec id such as zstd (default: deflate)")
    parser.add_argument("--seconds", type=int, default=600, help="how long to run (default: 600)")
    parser.add_argument("--seeds", type=Path, action="append", default=[], help="a folder of seed inputs; repeatable")
    parser.add_argument("--skip-build", action="store_true", help="use the existing core jar")
    args = parser.parse_args()

    if not args.skip_build:
        kotlin = ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin")
        run(kotlin, "build", "-m", "archivekodec", "-p", "jvm", cwd=ROOT)
    if not CORE_JAR.is_file():
        raise RuntimeError(f"No core jar at {CORE_JAR}. Run without --skip-build.")
    stdlib = kotlin_stdlib()
    jazzer, api = jazzer_jar("jazzer"), jazzer_jar("jazzer-api")

    classes = WORK / "classes"
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    sources = sorted((ROOT / "fuzz/src").glob("*.java"))
    run("javac", "-d", classes, "-cp", f"{CORE_JAR}:{stdlib}:{api}", *sources)

    name = f"archive-{args.format or 'any'}" if args.target == "archive" else f"codec-{args.codec}"
    corpus = WORK / "corpus" / name
    output = WORK / "findings" / name
    corpus.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    properties = [f"-Dfuzz.format={args.format}"] if args.format else []
    if args.target == "codec":
        properties = [f"-Dfuzz.codec={args.codec}"]
    target_class = "ArchiveFuzzer" if args.target == "archive" else "CodecFuzzer"
    run(
        "java", "-cp", jazzer, "com.code_intelligence.jazzer.Jazzer",
        f"--cp={CORE_JAR}:{stdlib}:{classes}",
        f"--target_class={target_class}",
        "--instrumentation_includes=io.github.yuroyami.archivekodec.**",
        *(f"--jvm_args={p}" for p in properties),
        f"-max_total_time={args.seconds}",
        "-rss_limit_mb=4096",
        f"-artifact_prefix={output}/",
        corpus, *args.seeds,
        cwd=WORK,
    )


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(f"The fuzzer stopped with exit code {error.returncode}; see the findings folder", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
