#!/usr/bin/env python3
"""Run the JS and wasmWasi tests in Node.

`./kotlin test` cannot run these two platforms. This script links the tests with the toolchain, then runs them:
JS through runner.mjs, and wasmWasi through the test entry point that the module exports.
"""
import argparse
import glob
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
MODULES = ("archivekodec", "archivekodec-io")
ARTIFACTS = ROOT / "build/artifacts/CompiledWebArtifact"
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")


def node():
    """Node from PATH, else the one the Kotlin Toolchain downloaded."""
    found = shutil.which("node")
    if found:
        return found
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*node-v*/bin/node")))
    if downloaded:
        return downloaded[-1]
    raise RuntimeError(f"No node executable on PATH or in {CACHE}")


def run(*args, capture=False):
    print("Running " + " ".join(str(a) for a in args[:3]) + (" ..." if len(args) > 3 else ""), flush=True)
    result = subprocess.run([str(a) for a in args], cwd=ROOT, text=True, check=not capture,
                            stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    return result


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def js_tests(module, executable):
    kotlin("task", f":{module}:linkJsTest")
    entry = ARTIFACTS / f"{module}jsTestrelease/kotlin-output/{module}_test.mjs"
    run(executable, HERE / "runner.mjs", entry)


def wasi_tests(module, executable):
    kotlin("task", f":{module}:linkWasmWasiTestDebug")
    entry = ARTIFACTS / f"{module}wasmWasiTestdebug/kotlin-output/{module}_test.mjs"
    script = f"const tests = await import({entry.as_uri()!r}); tests.startUnitTests();"
    result = run(executable, "--input-type=module", "-e", script, capture=True)
    # kotlin-test for wasmWasi reports in the TeamCity service message format.
    output = result.stdout
    finished = len(re.findall(r"##teamcity\[testFinished ", output))
    failed = re.findall(r"##teamcity\[testFailed name='([^']*)'", output)
    ignored = len(re.findall(r"##teamcity\[testIgnored ", output))
    for name in failed:
        print(f"Failed {name}", flush=True)
    print(f"{module} wasmWasi: {finished - len(failed)} passed, {len(failed)} failed, {ignored} ignored", flush=True)
    if result.returncode != 0 or failed or finished == 0:
        print(output[-4000:], flush=True)
        raise RuntimeError(f"wasmWasi tests of {module} failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=("js", "wasmWasi"), action="append",
                        help="run one platform; repeat it for both (default: both)")
    args = parser.parse_args()
    platforms = args.platform or ["js", "wasmWasi"]
    executable = node()
    for module in MODULES:
        if "js" in platforms:
            js_tests(module, executable)
        if "wasmWasi" in platforms:
            wasi_tests(module, executable)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
