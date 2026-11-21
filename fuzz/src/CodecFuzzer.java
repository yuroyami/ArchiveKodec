import io.github.yuroyami.archivekodec.ArchiveException;
import io.github.yuroyami.archivekodec.codec.Codec;
import io.github.yuroyami.archivekodec.codec.Codecs;
import io.github.yuroyami.archivekodec.security.ArchiveLimits;

/**
 * Decodes the input with the codec named by the {@code fuzz.codec} system property. A decoder
 * may only return output within the limits or throw an {@link ArchiveException}; any other
 * exception or error, including running out of memory, is a finding.
 */
public final class CodecFuzzer {
    private static final ArchiveLimits LIMITS = FuzzLimits.LIMITS;
    private static final Codec CODEC = Codecs.byName(System.getProperty("fuzz.codec", "deflate"));

    public static void fuzzerTestOneInput(byte[] data) {
        if (CODEC == null) throw new IllegalStateException("unknown fuzz.codec " + System.getProperty("fuzz.codec"));
        try {
            byte[] out = CODEC.decompress(data, LIMITS);
            if (out.length > LIMITS.getMaxUncompressedBytes()) throw new AssertionError("output past the limit: " + out.length);
        } catch (ArchiveException expected) {
            // Damage and limits are reported this way.
        }
    }
}
