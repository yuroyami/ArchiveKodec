# API baseline records

Each library module has three current reference files: `api/jvm/<module>.api`,
`api/android/<module>.api` and `api/<module>.klib.api`. The core klib dump covers
JS and all three iOS targets; the IO dump covers all three iOS targets. These
references were first recorded before the visibility cleanup in #79. They still
include `ByteArrayBuilder`, `InflateError.fromPuff`, `SPACE_EXHAUSTED` and the raw
LZ4 block functions so later removals are visible in a commit diff.

`checkPortableAbi` reads the actual JVM JARs and Android AAR classes using Kotlin's
public Build Tools ABI API. Android has its own reference because AGP's external
Android target is not enumerated by Kotlin 2.4.10's legacy Android collector.
`checkKotlinAbi` combines those checks with the Kotlin Gradle plugin's complete
JVM/JS/iOS checks. `checkLegacyAbi` is an aggregate command retained for the plan
in #78; it checks the current references, rather than comparing the renamed
library against an incompatible old namespace.

The portable CI lane requires `checkPortableAbi`. The Apple lane requires
`checkLegacyAbi`; unsupported-target inference is disabled. No CI command updates
reference files. A public addition or removal requires a reviewed reference
change in the same commit and corresponding release notes.

```bash
./gradlew checkPortableAbi
./gradlew checkKotlinAbi
./gradlew updateKotlinAbi
```

The complete check/update requires macOS with the Apple toolchain and Android
SDK. Module `check` tasks also require their ABI checks, and root `check` runs
both module checks and the complete matrix.

## Released 0.1.0

`published/0.1.0` is an immutable historical record made from the actual Maven
Central binaries for `io.github.yuroyami:kitearchive:0.1.0` and
`io.github.yuroyami:kitearchive-io:0.1.0`. It contains separate JVM/Android dumps
and a merged klib dump for each module. All 11 published binaries were downloaded
and verified against the SHA-256 values in their Gradle module metadata before
extraction. The manifest records their coordinates, exact URLs, sizes and hashes.
The dumps use Kotlin 2.4.10 ABI tools, the compiler version of those artifacts.

These binaries use the original `io.github.yuroyami.kitearchive` namespace. The
ArchiveKodec rename and subsequent fixes already changed the public surface;
these historical dumps are not presented as today's ABI. Do not update them when
changing the current source.

To reproduce the historical records from the published artifacts:

```bash
python3 integration-tests/abi/fetch-published.py --destination build/abi/published-binaries
./gradlew recordPublishedAbi -PpublishedAbiInputDir=build/abi/published-binaries
```

The recording task verifies the full target matrix and hashes again before
writing the historical dumps. Reproducing a frozen release must leave its files
unchanged; inspect `git diff -- api/published/0.1.0` after running it.
