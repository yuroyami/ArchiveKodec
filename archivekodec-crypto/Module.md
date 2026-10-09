# Module archivekodec-crypto

`CryptographyKotlinProvider`, the core's `CryptoProvider` backed by
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin). It uses each
platform's AES, HMAC and PBKDF2: the JDK on the JVM and Android, a bundled OpenSSL 3 on
Linux, Windows and Android Native, and Apple's libraries on Apple targets. Pass it as
`ArchiveOpenOptions.crypto` to read WinZip AES and 7z AES, or to `ZipEncryption` to write
WinZip AES.

Builds for every core target except JS, wasmJs and wasmWasi: the readers call the
provider synchronously, and browsers only offer the asynchronous WebCrypto.
