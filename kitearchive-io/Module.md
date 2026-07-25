# Module kitearchive-io

Adapters between kotlinx-io and the byte arrays the core works on.

Four extension functions that read a `RawSource` into a `ByteArray`, and wrap
one as a `RandomAccessSource`. Add this only if you already use kotlinx-io; the
core does not need it.
