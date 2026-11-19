import io.github.yuroyami.archivekodec.ArchiveException;
import io.github.yuroyami.archivekodec.ArchiveKodec;
import io.github.yuroyami.archivekodec.archive.ArchiveEntry;
import io.github.yuroyami.archivekodec.archive.FormatRegistry;
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions;
import io.github.yuroyami.archivekodec.archive.ArchiveReader;
import io.github.yuroyami.archivekodec.crypto.ArchivePasswordProvider;

/**
 * Opens the input as the archive format named by the {@code fuzz.format} system property, or
 * whatever the sniffer detects when it is unset, then lists it and reads, verifies and
 * re-reads every entry. Only {@link ArchiveException} may escape.
 */
public final class ArchiveFuzzer {
    private static final String FORMAT = System.getProperty("fuzz.format");
    private static final ArchiveOpenOptions OPTIONS = options();

    private static ArchiveOpenOptions options() {
        ArchiveOpenOptions.Builder builder = new ArchiveOpenOptions.Builder();
        builder.setLimits(FuzzLimits.LIMITS);
        // A password lets the fuzzer reach the decryption paths too.
        builder.setPassword(ArchivePasswordProvider.Companion.of("password"));
        return builder.build();
    }

    public static void fuzzerTestOneInput(byte[] data) {
        try {
            ArchiveReader reader = ArchiveKodec.open(data, FormatRegistry.DEFAULT, FORMAT, OPTIONS);
            try {
                for (ArchiveEntry entry : reader.entries()) {
                    try {
                        reader.read(entry);
                    } catch (ArchiveException expected) {
                        // One damaged member must not hide the others, or break them.
                    }
                }
                // A second pass reads through whatever state the first one left behind.
                for (ArchiveEntry entry : reader.entries()) {
                    try {
                        reader.read(entry);
                    } catch (ArchiveException expected) {
                    }
                }
            } finally {
                reader.close();
            }
        } catch (ArchiveException expected) {
        }
    }
}
