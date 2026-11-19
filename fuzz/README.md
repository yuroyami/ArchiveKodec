# Fuzzing

Coverage-guided fuzz harnesses for the core, run with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
on the JVM. They check the promises in [SECURITY.md](../SECURITY.md): a decoder or reader either
returns output within `ArchiveLimits` or throws an `ArchiveException`. Any other exception, an
endless loop, or memory past the fuzzer's cap is a finding.

| Harness | What it does |
| --- | --- |
| `CodecFuzzer` | Decodes the input with one codec, chosen with `-Dfuzz.codec=<id>` |
| `ArchiveFuzzer` | Opens the input as an archive (detected, or `-Dfuzz.format=<id>`), lists it, and reads every entry twice, so state a failed read leaves behind is exercised |

Both use small limits (16 MiB of output, 64 MiB of decoder memory), so a broken limit shows long
before the 4 GiB memory cap. `ArchiveFuzzer` supplies the password `password`, to reach the
decryption paths.

```bash
python3 scripts/fuzz.py archive --seconds 3600 --seeds path/to/sample/archives
python3 scripts/fuzz.py codec --codec zstd
```

The script builds the core's JVM jar, downloads Jazzer 0.24.0 from Maven Central and checks its
SHA-256, and keeps the corpus and findings under `build/fuzz/`. A finding is a `crash-*` file.
Once it is fixed, add it as a regression test that runs on every platform.

The release bar for 1.0, set in [#154](https://github.com/yuroyami/ArchiveKodec/issues/154), is
30 days of continuous fuzzing with no open finding.
