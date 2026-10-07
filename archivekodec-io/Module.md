# Module archivekodec-io

Adapters between kotlinx-io and the byte arrays the core works on.

Four extension functions that read a `RawSource` into a `ByteArray`, and wrap
one as a `RandomAccessSource`. Add this only if you already use kotlinx-io; the
core does not need it.

Builds for Android, JVM, JS, wasmJs, wasmWasi, iOS, macOS (arm64), tvOS, watchOS,
Linux, mingwX64 and androidNative. Both modules share this target list.
