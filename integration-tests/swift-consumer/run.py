#!/usr/bin/env python3
"""Build and run independent Swift consumers against the iOS simulator frameworks."""
import argparse
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def run(*args, capture=False, env=None):
    print("Running " + " ".join(str(a) for a in args[:3]), flush=True)
    return subprocess.run([str(a) for a in args], cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, env=env).stdout


def verify_header(path, selectors, boolean_selectors=()):
    text = path.read_text()
    if "asAutoCloseable" in text:
        raise RuntimeError(f"Kotlin cleanup adapter unexpectedly exported to Objective-C in {path}")
    for selector in selectors:
        methods = [line for line in text.splitlines()
                   if re.match(r"^[-+] \([^)]*\)" + re.escape(selector) + r"(?:\b|:)", line)]
        if not methods or any("(NSError *" not in line for line in methods):
            raise RuntimeError(f"Missing NSError bridge for {selector} in {path}")
    for selector in boolean_selectors:
        methods = [line for line in text.splitlines()
                   if re.match(r"^[-+] \(BOOL\)" + re.escape(selector) + r"(?:\b|:)", line)]
        if not methods or any("NSError" in line for line in methods):
            raise RuntimeError(f"Missing non-throwing Boolean bridge for {selector} in {path}")
    print(f"Verified {len(selectors)} throwing selector groups in {path.name}", flush=True)
    if boolean_selectors:
        print(f"Verified {len(boolean_selectors)} non-throwing Boolean selectors in {path.name}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise RuntimeError("Swift consumer execution requires macOS arm64 with an iOS simulator runtime")
    if not args.skip_build:
        tasks = [f":{module}:linkDebugFramework{target}"
                 for module in ("archivekodec", "archivekodec-io")
                 for target in ("IosSimulatorArm64", "IosArm64", "IosX64")]
        run("./gradlew", *tasks, "--max-workers=2")
    core = ROOT / "archivekodec/build/bin/iosSimulatorArm64/debugFramework"
    io = ROOT / "archivekodec-io/build/bin/iosSimulatorArm64/debugFramework"
    core_selectors = [
        "openSource", "sniffSource", "entriesAndReturnError", "closeAndReturnError", "readEntry", "readOffset", "readFully",
        "compressData", "decompressData", "decompressInput", "inflateInput", "encodeData",
        "blockDecompressSrc", "blockCompressData", "frameDecompressInput", "frameCompressData",
        "decompressSrc", "sanitizeEntryName", "checkOutputSizeBytes", "checkRatioCompressedBytes",
        "checkEntryCountCount", "writeFiles", "appendB", "appendBytes", "updateData", "hashData", "decodeBytes",
    ]
    io_selectors = ["readAllBytes", "toRandomAccessSource", "compress", "decompress"]
    for target in ("iosSimulatorArm64", "iosArm64", "iosX64"):
        verify_header(ROOT / f"archivekodec/build/bin/{target}/debugFramework/ArchiveKodec.framework/Headers/ArchiveKodec.h", core_selectors,
                      boolean_selectors=("isRootDirectoryEntry",))
        verify_header(ROOT / f"archivekodec-io/build/bin/{target}/debugFramework/ArchiveKodecIo.framework/Headers/ArchiveKodecIo.h", io_selectors)
    sdk = run("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path", capture=True).strip()
    output = ROOT / "build/swift-consumer"
    output.mkdir(parents=True, exist_ok=True)
    closing_source = output / "ClosingSource.o"
    run("xcrun", "--sdk", "iphonesimulator", "clang", "-isysroot", sdk,
        "-target", "arm64-apple-ios15.0-simulator", "-fobjc-arc", "-Wall", "-Werror",
        "-F", core, "-c", HERE / "ClosingSource.m", "-o", closing_source)
    for name, framework in (("CoreSmoke", core), ("IoSmoke", io)):
        extra = ["-import-objc-header", HERE / "ClosingSource.h", closing_source] if name == "CoreSmoke" else []
        run("xcrun", "--sdk", "iphonesimulator", "swiftc", "-sdk", sdk,
            "-target", "arm64-apple-ios15.0-simulator", "-warnings-as-errors",
            "-F", framework, "-Xlinker", "-rpath", "-Xlinker", framework,
            *extra, HERE / (name + ".swift"), "-o", output / name)
    runtimes = json.loads(run("xcrun", "simctl", "list", "runtimes", "-j", capture=True))["runtimes"]
    available = [r for r in runtimes if r.get("isAvailable") and ".iOS-" in r["identifier"]]
    if not available:
        raise RuntimeError("No available iOS simulator runtime")
    runtime = max(available, key=lambda r: tuple(int(p) for p in r["version"].split(".")))
    device_type = next(d["identifier"] for d in runtime["supportedDeviceTypes"] if d["productFamily"] == "iPhone")
    device = run("xcrun", "simctl", "create", "ArchiveKodec Swift consumer", device_type,
                 runtime["identifier"], capture=True).strip()
    try:
        run("xcrun", "simctl", "boot", device)
        run("xcrun", "simctl", "bootstatus", device, "-b")
        for name in ("CoreSmoke", "IoSmoke"):
            run("xcrun", "simctl", "spawn", device, output / name)
    finally:
        subprocess.run(["xcrun", "simctl", "shutdown", device], check=False, stdout=subprocess.DEVNULL)
        run("xcrun", "simctl", "delete", device)
    print("Swift consumer gate passed", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
