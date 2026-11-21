import io.github.yuroyami.archivekodec.security.ArchiveLimits;

/** Limits small enough that a limit violation shows long before the fuzzer's memory cap. */
final class FuzzLimits {
    static final ArchiveLimits LIMITS = limits();

    private static ArchiveLimits limits() {
        ArchiveLimits.Builder builder = new ArchiveLimits.Builder();
        builder.setMaxUncompressedBytes(16L << 20);
        builder.setMaxDecoderMemory(64L << 20);
        builder.setMaxEntries(10_000);
        return builder.build();
    }

    private FuzzLimits() {}
}
