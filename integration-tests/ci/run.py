#!/usr/bin/env python3
"""Run the same verification commands locally and in the hosted platform lanes."""
import argparse
import io
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TESTED = ("archivekodec", "archivekodec-io", "archivekodec-crypto")
KOTLIN = ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin")


def platforms(*names):
    return [arg for name in names for arg in ("-p", name)]


PORTABLE_BUILD = ("jvm", "android", "js", "wasmJs", "wasmWasi", "linuxX64", "linuxArm64",
                  "androidNativeArm32", "androidNativeArm64", "androidNativeX86", "androidNativeX64")
# linuxX64 tests run only on a Linux host. The CI runs this lane on Linux.
PORTABLE_TESTS = ("jvm", "android", "wasmJs") + (("linuxX64",) if sys.platform.startswith("linux") else ())
APPLE_TESTS = ("iosSimulatorArm64", "macosArm64", "tvosSimulatorArm64", "watchosSimulatorArm64")
APPLE_DEBUG_FRAMEWORKS = ("iosSimulatorArm64", "iosArm64", "iosX64")
CANARY = ROOT / "archivekodec/test@jvm/io/github/yuroyami/archivekodec/CiFailureCanaryTest.kt"
CANARY_SOURCE = """package io.github.yuroyami.archivekodec
import kotlin.test.Test
import kotlin.test.fail
class CiFailureCanaryTest {
    @Test fun deliberateFailure() { fail("Deliberate CI gate canary") }
}
"""
# Java 8, the floor of kotlin-stdlib and kotlinx-io. See the README's Install section.
MAX_CLASS_MAJOR = 52
MODULE_NAMES = {"archivekodec": "io.github.yuroyami.archivekodec",
                "archivekodec-io": "io.github.yuroyami.archivekodec.io"}


def run(*args):
    print("Running " + " ".join(Path(str(a)).name if i == 0 else str(a) for i, a in enumerate(args)), flush=True)
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True)


def kotlin(*args):
    run(KOTLIN, *args)


def script(path, *args):
    run(sys.executable, ROOT / path, *args)


def verify_reports(targets):
    """Fail when a JVM or Android test report is missing, has a failure, or has no passing test.

    The toolchain writes JUnit XML reports for these two platforms only. For the others, the exit
    code of `./kotlin test` is the gate.
    """
    for module in TESTED:
        for target in targets:
            files = list((ROOT / "build/reports" / module / target).glob("TEST-*.xml"))
            if not files:
                raise RuntimeError(f"No test reports for {module} {target}")
            suites = [ET.parse(path).getroot() for path in files]
            totals = {key: sum(int(suite.get(key, "0")) for suite in suites)
                      for key in ("tests", "failures", "errors", "skipped")}
            if totals["failures"] or totals["errors"] or totals["tests"] <= totals["skipped"]:
                raise RuntimeError(f"No passing test execution for {module} {target}: {totals}")
            print(f"{module} {target}: {totals}", flush=True)


def check_readme_version():
    """Fail when the README or the docs name an artifact version other than the release in progress."""
    template = (ROOT / "library.module-template.yaml").read_text()
    version = re.search(r"(?m)^\s+version:\s*([^\s#]+)", template.split("publishing:", 1)[1]).group(1)
    release = version.removesuffix("-SNAPSHOT")
    coordinate = re.compile(r"""io\.github\.yuroyami:(archivekodec[a-z-]*):([0-9][^"\s)`]*)""")
    for page in (ROOT / "README.md", ROOT / "docs/index.md"):
        found = coordinate.findall(page.read_text())
        if not found:
            raise RuntimeError(f"{page.name} names no archivekodec coordinates")
        stale = [item for item in found if item[1] != release]
        if stale:
            raise RuntimeError(f"{page.name} coordinates {stale} do not name release {release}")
        print(f"{page.name}: {len(found)} coordinates name {release}", flush=True)


def class_majors(files):
    """(name, class file major version) for each class, read from (name, bytes reader) pairs."""
    for name, read in files:
        if name.endswith(".class") and not name.endswith("module-info.class"):
            header = read()[:8]
            if header[:4] != b"\xca\xfe\xba\xbe":
                raise RuntimeError(f"{name} is not a class file")
            yield name, int.from_bytes(header[6:8], "big")


def verify_bytecode():
    """Fail when a JVM or Android class is newer than Java 8, or a jar lacks its module descriptor."""
    for module, module_name in MODULE_NAMES.items():
        jar_path = ROOT / f"build/tasks/_{module}_jarJvm/{module}-jvm.jar"
        with zipfile.ZipFile(jar_path) as jar:
            descriptor = jar.read("module-info.class")
            if module_name.encode() not in descriptor:
                raise RuntimeError(f"{jar_path.name} has no module-info.class for {module_name}")
            checked = {"jvm": list(class_majors((n, lambda n=n: jar.read(n)) for n in jar.namelist()))}
        android = ROOT / f"build/artifacts/CompiledJvmArtifact/{module}androiddebug/kotlin-output"
        files = [path for path in android.rglob("*.class")]
        checked["android"] = list(class_majors((str(p.relative_to(android)), p.read_bytes) for p in files))
        for target, majors in checked.items():
            if not majors:
                raise RuntimeError(f"No classes in the {module} {target} binary")
            newest = max(majors, key=lambda item: item[1])
            if newest[1] > MAX_CLASS_MAJOR:
                raise RuntimeError(f"{module} {target}: {newest[0]} has class major {newest[1]}, "
                                   f"above the Java 8 floor ({MAX_CLASS_MAJOR})")
            print(f"{module} {target}: {len(majors)} classes, newest major {newest[1]}", flush=True)


def portable():
    if "linuxX64" not in PORTABLE_TESTS:
        print("Skipping the linuxX64 tests: they run only on a Linux host", flush=True)
    kotlin("build", *platforms(*PORTABLE_BUILD))
    kotlin("test", *[arg for module in TESTED for arg in ("-m", module)], *platforms(*PORTABLE_TESTS))
    verify_reports(("jvm", "android"))
    script("integration-tests/web-tests/run.py")
    kotlin("check", "abi", "version")
    check_readme_version()
    verify_bytecode()
    script("integration-tests/js-consumer/run.py")


def apple():
    # The klib ABI check reads the klibs of every platform, and only macOS builds the Apple ones.
    kotlin("build")
    kotlin("test", *[arg for module in TESTED for arg in ("-m", module)], *platforms(*APPLE_TESTS))
    kotlin("check", "klibAbi")
    script("integration-tests/swift-consumer/run.py")
    script("scripts/apple_frameworks.py", "--build-type", "release")
    script("integration-tests/swift-package/package.py")
    run("swift", "test", "--package-path", ROOT / "integration-tests/swift-package")


def windows():
    kotlin("test", *[arg for module in TESTED for arg in ("-m", module)], *platforms("mingwX64"))


LANES = {"portable": portable, "apple": apple, "windows": windows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("lane", choices=LANES)
    parser.add_argument("--failure-canary", action="store_true")
    args = parser.parse_args()
    if args.failure_canary and args.lane != "portable":
        parser.error("The failure canary runs in the portable lane")
    inserted = False
    try:
        if args.failure_canary:
            # Exclusive creation protects an existing local file; never commit this fixture.
            with CANARY.open("x") as output:
                inserted = True
                output.write(CANARY_SOURCE)
        LANES[args.lane]()
        if args.failure_canary:
            raise RuntimeError("The deliberate failing test was not rejected by the gate")
    finally:
        if inserted:
            CANARY.unlink()


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError, KeyError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
