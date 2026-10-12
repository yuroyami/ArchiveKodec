# Module archivekodec-io

Files and kotlinx-io for the core. Add it when you read archives from disk or already use
kotlinx-io.

- `FileRandomAccessSource` opens a file and reads it on demand, so a large archive never sits
  in memory.
- `extract` writes chosen entries to sinks, with progress, cancellation and `PromptedPasswords`
  for encrypted entries.
- Six adapters read a `RawSource` into a `ByteArray`, wrap one as a `RandomAccessSource`, and
  compress or decompress between a `RawSource` and a `ByteArray` or a `RawSink`.

Builds for Android, JVM, JS, wasmJs, wasmWasi, iOS, macOS (arm64), tvOS, watchOS, Linux,
mingwX64 and androidNative, the same targets as the core. In a browser and on WASI,
`FileRandomAccessSource` throws `ArchiveUnsupportedException`.
