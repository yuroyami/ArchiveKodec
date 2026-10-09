#!/usr/bin/env python3
"""Run plain JavaScript and strict TypeScript consumers against the built npm module."""
import argparse
import glob
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
LIBRARY = ROOT / "archivekodec/build/dist/js/productionLibrary"
# The TypeScript compiler Kotlin/JS pins in kotlin-js-store/yarn.lock to check its own .d.mts.
TSC = ROOT / "build/js/node_modules/typescript/bin/tsc"


def node():
    """Node from PATH, else the one the Kotlin Gradle plugin downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(Path.home() / ".gradle/nodejs/node-*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError("No node executable on PATH or in ~/.gradle/nodejs")


def run(*args, cwd):
    print("Running " + " ".join(Path(str(a)).name for a in args), flush=True)
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if not args.skip_build:
        gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
        run(gradlew, "--console=plain", ":archivekodec:jsNodeProductionLibraryDistribution", "kotlinNpmInstall", cwd=ROOT)
    if not (LIBRARY / "ArchiveKodec-KMP-archivekodec.d.mts").is_file():
        raise RuntimeError(f"No TypeScript declarations in {LIBRARY}")
    if not TSC.is_file():
        raise RuntimeError(f"No TypeScript compiler at {TSC}; run the kotlinNpmInstall task")
    executable = node()
    with tempfile.TemporaryDirectory() as scratch:
        work = Path(scratch) / "consumer"
        shutil.copytree(LIBRARY, work)
        for name in ("plain.mjs", "typed.mts", "tsconfig.json"):
            shutil.copy(HERE / name, work / name)
        run(executable, "plain.mjs", cwd=work)
        run(executable, TSC, "-p", "tsconfig.json", cwd=work)
        run(executable, "typed.mjs", cwd=work)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
