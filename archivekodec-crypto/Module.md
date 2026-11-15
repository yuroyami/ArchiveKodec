# Module archivekodec-crypto

`CryptographyKotlinProvider`, a `CryptoProvider` backed by
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin), in the package
`io.github.yuroyami.archivekodec.crypto.platform`.

This module is optional. The core's built-in `CryptoProvider.DEFAULT` already reads and
writes encrypted archives on every platform. This provider uses each platform's own AES,
HMAC and PBKDF2, which is faster where the processor has AES instructions:

- the JDK on the JVM and Android
- a bundled OpenSSL 3 on Linux, Windows and Android Native
- Apple's libraries on Apple targets

Pass it as `ArchiveOpenOptions.crypto` to read WinZip AES and 7z AES, or to `ZipEncryption`
to write WinZip AES.

Builds for every core target except JS, wasmJs and wasmWasi: the readers call the provider
synchronously, and browsers only offer the asynchronous WebCrypto. On those targets, use the
built-in provider.
