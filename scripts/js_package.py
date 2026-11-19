#!/usr/bin/env python3
"""Assemble the JavaScript and TypeScript distribution of the core module.

The Kotlin Toolchain links JavaScript only for apps, so the archivekodec-js app holds the
@JsExport facade over the core and links it. This script copies that output to archivekodec/build/dist/js/productionLibrary/ and adds
the entry files and the package.json that the Kotlin Gradle plugin wrote before:

  ArchiveKodec-KMP-archivekodec.mjs    the ES module to import
  ArchiveKodec-KMP-archivekodec.d.mts  its TypeScript declarations
"""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
LINKED = ROOT / "build/artifacts/CompiledWebArtifact/archivekodec-jsjsrelease/kotlin-output"
OUTPUT = ROOT / "archivekodec/build/dist/js/productionLibrary"
NAME = "ArchiveKodec-KMP-archivekodec"
# The npm package name. npm refuses capitals in the names of new packages.
PACKAGE = "archivekodec"
# The linked module that holds the @JsExport facade, and the app's entry file, which only runs its empty main.
LIBRARY = "kotlin_archivekodec_js"
APP_FILES = ("archivekodec-js.mjs",)


def version():
    text = (ROOT / "library.module-template.yaml").read_text()
    publishing = text.split("publishing:", 1)[1]
    return re.search(r"(?m)^\s+version:\s*([^\s#]+)", publishing).group(1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-build", action="store_true", help="use the existing link output")
    args = parser.parse_args()
    if not args.skip_build:
        kotlin = ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin")
        subprocess.run([str(kotlin), "task", ":archivekodec-js:linkJs"], cwd=ROOT, check=True)
    if not (LINKED / LIBRARY).is_dir():
        raise RuntimeError(f"No linked library at {LINKED / LIBRARY}. Run without --skip-build.")
    shutil.rmtree(OUTPUT, ignore_errors=True)
    shutil.copytree(LINKED, OUTPUT, ignore=lambda folder, names: [n for n in names if Path(folder) == LINKED and n in APP_FILES])
    # The linker writes one .d.mts per source file that declares exports. The entry file re-exports them all.
    declarations = sorted((OUTPUT / LIBRARY).rglob("*.export.d.mts"))
    if not declarations:
        raise RuntimeError(f"No TypeScript declarations in {OUTPUT / LIBRARY}")
    (OUTPUT / f"{NAME}.mjs").write_text("".join(
        f"export * from './{d.relative_to(OUTPUT).as_posix().removesuffix('.d.mts')}.mjs';\n" for d in declarations))
    (OUTPUT / f"{NAME}.d.mts").write_text("".join(
        f"export * from './{d.relative_to(OUTPUT).as_posix().removesuffix('.d.mts')}.mjs';\n" for d in declarations))
    if not re.fullmatch(r"(@[a-z0-9-~][a-z0-9-._~]*/)?[a-z0-9-~][a-z0-9-._~]*", PACKAGE) or len(PACKAGE) > 214:
        raise RuntimeError(f"{PACKAGE} is not a valid npm package name")
    package = {"name": PACKAGE, "version": version(), "main": f"{NAME}.mjs", "types": f"{NAME}.d.mts"}
    (OUTPUT / "package.json").write_text(json.dumps(package, indent=2) + "\n")
    print(f"Wrote {OUTPUT.relative_to(ROOT)}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (subprocess.CalledProcessError, RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
