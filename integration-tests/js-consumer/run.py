#!/usr/bin/env python3
"""Run plain JavaScript and strict TypeScript consumers against the built npm module."""
import argparse
import glob
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
LIBRARY = ROOT / "archivekodec/build/dist/js/productionLibrary"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")
# A pinned TypeScript compiler, installed into the build folder on first use.
TYPESCRIPT = "typescript@5.9.3"
TOOLS = ROOT / "build/js"
TSC = TOOLS / "node_modules/typescript/bin/tsc"


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def npm(executable):
    """npm from PATH, else the npm that ships next to node."""
    found = shutil.which("npm")
    if found:
        return [found]
    script = Path(executable).resolve().parents[1] / "lib/node_modules/npm/bin/npm-cli.js"
    if script.is_file():
        return [executable, script]
    raise RuntimeError("No npm executable on PATH or next to node")


def run(*args, cwd):
    print("Running " + " ".join(Path(str(a)).name for a in args), flush=True)
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if not args.skip_build:
        run(sys.executable, ROOT / "scripts/js_package.py", cwd=ROOT)
    if not (LIBRARY / "ArchiveKodec-KMP-archivekodec.d.mts").is_file():
        raise RuntimeError(f"No TypeScript declarations in {LIBRARY}")
    executable = node()
    if not TSC.is_file():
        TOOLS.mkdir(parents=True, exist_ok=True)
        run(*npm(executable), "install", "--no-save", "--no-package-lock", "--prefix", TOOLS, TYPESCRIPT, cwd=ROOT)
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
