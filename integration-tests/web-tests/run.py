#!/usr/bin/env python3
"""Run the JS, wasmJs and wasmWasi tests in Node.

`./kotlin test` cannot run the JS and wasmWasi tests, and runs the wasmJs tests only in headless Chrome, which has no
file system for the Node file code of archivekodec-io. This script links the tests with the toolchain, then runs them:
JS and wasmJs through runner.mjs, and wasmWasi through the test entry point that the module exports.
"""
import argparse
import glob
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
MODULES = ("archivekodec", "archivekodec-io")
PLATFORMS = ("js", "wasmJs", "wasmWasi")
ARTIFACTS = ROOT / "build/artifacts/CompiledWebArtifact"
# The link task and the artifact folder of each platform that runs through runner.mjs.
RUNNER_LINKS = {"js": ("linkJsTest", "jsTestrelease"), "wasmJs": ("linkWasmJsTestDebug", "wasmJsTestdebug")}


def cache_root():
    """The toolchain's shared cache folder, found the way the toolchain finds it."""
    for variable in ("KOTLIN_SHARED_CACHE_DIR", "AMPER_SHARED_CACHE_DIR"):
        if os.environ.get(variable):
            return Path(os.environ[variable])
    if sys.platform == "darwin":
        return Path.home() / "Library/Caches/JetBrains/Kotlin"
    if sys.platform == "win32":
        return Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData/Local") / "JetBrains/Kotlin"
    return Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache") / "JetBrains/Kotlin"


def node():
    """The newest Node the Kotlin Toolchain downloaded, else Node from PATH.

    `./kotlin test -p wasmJs` downloads the toolchain's Node. It comes first because the wasmWasi test entry point
    calls `WASI.finalizeBindings`, which older Node releases such as 22 lack.
    """
    def version(path):
        return tuple(int(part) for part in re.search(r"node-v(\d+)\.(\d+)\.(\d+)", path).groups())

    cache = cache_root()
    executable = "node.exe" if sys.platform == "win32" else "bin/node"
    downloaded = sorted(glob.glob(str(cache / "extract.cache/*node-v*" / executable)), key=version)
    if downloaded:
        return downloaded[-1]
    found = shutil.which("node")
    if found:
        return found
    raise RuntimeError(f"No node executable in {cache} or on PATH")


def run(*args, capture=False):
    print("Running " + " ".join(str(a) for a in args[:3]) + (" ..." if len(args) > 3 else ""), flush=True)
    result = subprocess.run([str(a) for a in args], cwd=ROOT, text=True, check=not capture,
                            stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT if capture else None)
    return result


def kotlin(*args):
    run(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"), *args)


def runner_tests(module, platform, executable):
    task, folder = RUNNER_LINKS[platform]
    kotlin("task", f":{module}:{task}")
    entry = ARTIFACTS / f"{module}{folder}/kotlin-output/{module}_test.mjs"
    print(f"{module} {platform}:", flush=True)
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
    parser.add_argument("--platform", choices=PLATFORMS, action="append",
                        help="run one platform; repeat it for more (default: all three)")
    args = parser.parse_args()
    platforms = args.platform or PLATFORMS
    executable = node()
    for module in MODULES:
        for platform in PLATFORMS:
            if platform not in platforms:
                continue
            if platform == "wasmWasi":
                wasi_tests(module, executable)
            else:
                runner_tests(module, platform, executable)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
