import io.github.yuroyami.archivekodec.crypto.CryptoProvider;
import java.util.Arrays;

/**
 * Runs with the core and crypto jars on the Java module path. Java refuses to start when two modules
 * hold the same package, so reaching main() shows that the two jars share none.
 */
public class ModulePathCheck {
    public static void main(String[] args) throws Exception {
        Module core = CryptoProvider.class.getModule();
        check(core.isNamed() && core.getName().equals("io.github.yuroyami.archivekodec"), "the core is not its named module: " + core);
        // Loaded without initialization, so the cryptography-kotlin jars need not be present.
        Class<?> platform = Class.forName(
            "io.github.yuroyami.archivekodec.crypto.platform.CryptographyKotlinProvider", false, ModulePathCheck.class.getClassLoader());
        Module crypto = platform.getModule();
        check(crypto.isNamed() && crypto != core, "the crypto jar is not a module of its own: " + crypto);
        check(CryptoProvider.class.isAssignableFrom(platform), "CryptographyKotlinProvider does not implement CryptoProvider");
        // FIPS 197 appendix C.1, through the built-in provider of the named core module.
        byte[] key = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        byte[] cipher = CryptoProvider.DEFAULT.aesEcbEncrypt(key, hex("00112233445566778899aabbccddeeff"));
        check(Arrays.equals(cipher, hex("69c4e0d86a7b0430d8cdb78070b4c55a")), "the built-in AES gave the wrong block");
        System.out.println("Module path: " + core.getName() + " and " + crypto.getName() + " load together");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
