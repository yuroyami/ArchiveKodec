# Encrypted archives

ZIP and 7z archives with a password need two options: the password and, for AES, a
`CryptoProvider`. The core has no AES of its own, because a table-driven AES in Kotlin is slow
and leaks key bits through cache timing. `archivekodec-crypto` supplies
`CryptographyKotlinProvider`, which runs the platform's own AES through
[cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin): the JDK, a bundled
OpenSSL 3 or Apple's libraries.

```kotlin
dependencies {
    implementation("io.github.yuroyami:archivekodec-crypto:0.2.0")
}
```

## Read

```kotlin
val options = ArchiveOpenOptions {
    password = ArchivePasswordProvider.of(secret)   // or a callback for each entry name
    crypto = CryptographyKotlinProvider()
}
ArchiveKodec.open(bytes, null, options).use { reader ->
    for (entry in reader.entries()) println("${entry.name} ${entry.encryption}")
    val data = reader.read(reader.entries().first())
}
```

`ArchivePasswordProvider.of` takes a `String` or a `CharArray`. A callback gets the entry
name, or `null` for the encrypted header of a 7z archive, which is needed to list it at all.
A missing or wrong password throws `ArchivePasswordException`.

ZIP hashes the password as UTF-8 and 7z as UTF-16LE, as 7-Zip does. The 7z key derivation is
capped at 2^24 SHA-256 rounds, and 7-Zip writes 2^19.

## Write

`ZipWriter` writes WinZip AES-256 (AE-2) for every file:

```kotlin
val password = "correct horse".toCharArray()
val zip = ZipWriter.write(files, ZipEncryption(password, CryptographyKotlinProvider()))
```

`ZipEncryption` keeps its own copy of the password, so you can clear yours afterwards. Its
`keyBits` defaults to 256.

## Schemes

| Scheme | Read | Write | Notes |
| --- | --- | --- | --- |
| WinZip AES (AE-1 and AE-2; 128, 192 and 256 bits) | Yes | AE-2 | The HMAC-SHA1 tag is checked before decompression. A mismatch throws `ArchiveAuthenticationException` |
| ZipCrypto | Yes, with no provider | No | Broken. `entry.encryption` reports `ZIP_CRYPTO_INSECURE` |
| 7z AES-256 | Yes, with encrypted headers | No | No authentication, so a wrong password shows as data that fails to decode or to match its CRC |
| PKWARE strong encryption | No | No | Throws `ArchiveUnsupportedException` before any ciphertext is read |

RAR archives with encrypted members or headers are refused, as
[Format support](formats.md#archives) lists.

## Platforms

`archivekodec-crypto` builds for every target of the core except JS, wasmJs and wasmWasi. The
readers call the provider synchronously, and a browser offers only the asynchronous
WebCrypto. `CryptoProvider` is a plain interface, so you can pass your own implementation on
any target.
