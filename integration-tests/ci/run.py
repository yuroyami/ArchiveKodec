#!/usr/bin/env python3
"""Run the same verification commands locally and in the hosted platform lanes."""
import argparse
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ("archivekodec", "archivekodec-io")


def tests(*tasks):
    """Test tasks for both modules, and the report directories they must fill."""
    return ([f":{module}:{task}" for module in MODULES for task in tasks],
            [(module, task) for module in MODULES for task in tasks])


def compiles(*targets):
    """Main compilations for targets this host can build but not execute."""
    return [f":{module}:compileKotlin{target}" for module in MODULES for target in targets]


PORTABLE_TESTS = tests("jvmTest", "jsNodeTest", "wasmJsNodeTest", "wasmWasiNodeTest", "linuxX64Test",
                       "testAndroidHostTest")
APPLE_TESTS = tests("iosSimulatorArm64Test", "macosArm64Test", "tvosSimulatorArm64Test",
                    "watchosSimulatorArm64Test")
WINDOWS_TESTS = tests("mingwX64Test")
LANES = {
    "portable": (
        [":checkKotlinAbi", *PORTABLE_TESTS[0],
         *compiles("LinuxArm64", "AndroidNativeArm32", "AndroidNativeArm64", "AndroidNativeX86",
                   "AndroidNativeX64"),
         ":archivekodec:jvmJar", ":archivekodec-io:jvmJar",
         ":archivekodec:bundleAndroidMainAar", ":archivekodec-io:bundleAndroidMainAar",
         ":archivekodec:jsNodeProductionLibraryDistribution", ":io-consumer-test:compileJava"],
        PORTABLE_TESTS[1],
    ),
    "apple": (
        [":checkLegacyAbi", *APPLE_TESTS[0],
         *compiles("IosArm64", "TvosArm64", "WatchosArm32", "WatchosArm64", "WatchosDeviceArm64"),
         "swiftConsumerTest"],
        APPLE_TESTS[1],
    ),
    "windows": (WINDOWS_TESTS[0], WINDOWS_TESTS[1]),
}
CANARY = ROOT / "archivekodec/src/jvmTest/kotlin/io/github/yuroyami/archivekodec/CiFailureCanaryTest.kt"
CANARY_SOURCE = """package io.github.yuroyami.archivekodec
import kotlin.test.Test
import kotlin.test.fail
class CiFailureCanaryTest {
    @Test fun deliberateFailure() { fail("Deliberate CI gate canary") }
}
"""


def verify_reports(reports):
    for module, task in reports:
        files = list((ROOT / module / "build/test-results" / task).glob("TEST-*.xml"))
        if not files:
            raise RuntimeError(f"No test reports for {module}:{task}")
        suites = [ET.parse(path).getroot() for path in files]
        totals = {key: sum(int(suite.get(key, "0")) for suite in suites)
                  for key in ("tests", "failures", "errors", "skipped")}
        if totals["failures"] or totals["errors"] or totals["tests"] <= totals["skipped"]:
            raise RuntimeError(f"No passing test execution for {module}:{task}: {totals}")
        print(f"{module}:{task}: {totals}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("lane", choices=LANES)
    parser.add_argument("--failure-canary", action="store_true")
    args = parser.parse_args()
    if args.failure_canary and args.lane != "portable":
        parser.error("The failure canary runs in the portable lane")
    tasks, reports = LANES[args.lane]
    inserted = False
    try:
        if args.failure_canary:
            # Exclusive creation protects an existing local file; never commit this fixture.
            with CANARY.open("x") as output:
                inserted = True
                output.write(CANARY_SOURCE)
        gradlew = ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
        subprocess.run([
            str(gradlew), "--console=plain", "--no-daemon", "--no-parallel", "--max-workers=2",
            "-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8 -XX:+UseParallelGC",
            "-Pkotlin.compiler.execution.strategy=in-process", *tasks,
        ], cwd=ROOT, check=True)
        verify_reports(reports)
        if args.failure_canary:
            raise RuntimeError("The deliberate failing test was not rejected by the gate")
    finally:
        if inserted:
            CANARY.unlink()


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
