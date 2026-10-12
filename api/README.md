# API baseline records

Each published module keeps two reference files in its own `api` folder:

- `<module>/api/jvm/<module>.api` is the JVM ABI dump.
- `<module>/api/<module>.klib.api` is the klib ABI dump. It covers every non-JVM platform of the module: JS, Wasm, Apple, Linux, mingw and androidNative.

The Android artifact has no reference file of its own. Its classes come from the same code as the JVM classes, and the old Android dumps were byte-equal to the JVM dumps.

All modules use strict explicit API mode, so every new declaration needs an explicit visibility and type.

## Commands

The `abi-check` build plugin (`build-plugins/abi-check`) provides two checks and two commands:

```bash
./kotlin check abi             # compare the JVM classes with api/jvm/<module>.api
./kotlin check klibAbi         # compare the klibs with api/<module>.klib.api
./kotlin do updateAbi          # rewrite the JVM dumps
./kotlin do updateKlibAbi      # rewrite the klib dumps
```

The klib check and its update read the klibs of every platform in the `klibPlatforms` list of each `module.yaml`. Run `./kotlin build` first. Only macOS builds the Apple klibs, so the CI runs the klib check in the Apple lane and the JVM check in the portable lane.

No CI command updates a reference file. A public addition or removal needs a reviewed reference change in the same commit, and a note in the CHANGELOG.

## The 0.1.0 record

`published/0.1.0` is a frozen historical record. It comes from the Maven Central binaries of `io.github.yuroyami:kitearchive:0.1.0` and `io.github.yuroyami:kitearchive-io:0.1.0`. The folder holds the JVM and Android dumps and one merged klib dump for each module. The dumps use the Kotlin 2.4.10 ABI tools, the compiler version of those artifacts.

All 11 published binaries were downloaded and checked against the SHA-256 values in their Gradle module metadata before the dumps were made. `manifest.json` records their coordinates, URLs, sizes and hashes.

These binaries use the original `io.github.yuroyami.kitearchive` namespace. The rename to ArchiveKodec changed the public API, so these dumps do not describe the current ABI. Do not update them when you change the source. No current command writes them.

`integration-tests/abi/fetch-published.py` downloads the binaries again and checks their hashes:

```bash
python3 integration-tests/abi/fetch-published.py --destination build/abi/published-binaries
```
