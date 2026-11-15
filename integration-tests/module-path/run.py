#!/usr/bin/env python3
"""Start Java with the core and crypto jars on the module path, the way a Java module path consumer does.

Java refuses to start when two modules hold the same package. Run `./kotlin build -p jvm` first: this script reads
the jars that it writes.
"""
import glob
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CACHE = Path.home() / ("Library/Caches/JetBrains/Kotlin" if sys.platform == "darwin" else ".cache/JetBrains/Kotlin")
JARS = [ROOT / "build/tasks/_archivekodec_jarJvm/archivekodec-jvm.jar",
        ROOT / "build/tasks/_archivekodec-crypto_jarJvm/archivekodec-crypto-jvm.jar"]


def kotlin_version():
    text = (ROOT / "library.module-template.yaml").read_text()
    return re.search(r"(?m)^\s+version:\s*([0-9][^\s#]*)\s*(?:#.*)?$", text.split("kotlin:", 1)[1]).group(1)


def java():
    """The JDK that the Kotlin Toolchain downloaded, else java from PATH. The check needs Java 11 or later."""
    downloaded = sorted(glob.glob(str(CACHE / "extract.cache/*OpenJDK*/*/bin/java")) +
                        glob.glob(str(CACHE / "extract.cache/*OpenJDK*/*/Contents/Home/bin/java")))
    if downloaded:
        return downloaded[-1]
    found = shutil.which("java")
    if found:
        return found
    raise RuntimeError(f"No java executable in {CACHE} or on PATH")


def main():
    version = kotlin_version()
    stdlib = CACHE / f".m2.cache/org/jetbrains/kotlin/kotlin-stdlib/{version}/kotlin-stdlib-{version}.jar"
    for jar in JARS + [stdlib]:
        if not jar.is_file():
            raise RuntimeError(f"Missing {jar}. Run `./kotlin build -p jvm` first.")
    module_path = ":".join(str(jar) for jar in JARS + [stdlib]) if sys.platform != "win32" else ";".join(str(jar) for jar in JARS + [stdlib])
    command = [java(), "--module-path", module_path, "--add-modules", "ALL-MODULE-PATH", str(HERE / "ModulePathCheck.java")]
    print("Running java --module-path ... ModulePathCheck.java", flush=True)
    subprocess.run(command, cwd=ROOT, check=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
