# Encrypted archives

The core reads encrypted ZIP and 7z archives and writes encrypted ZIP archives on every
platform, with no extra artifact. It has no 7z writer, so it cannot write encrypted 7z. Give
the reader a password. AES comes from `CryptoProvider.DEFAULT`, the built-in provider.

## Read

```kotlin
val options = ArchiveOpenOptions {
    password = ArchivePasswordProvider.of(secret)   // or a callback for each entry name
}
ArchiveKodec.open(bytes, null, options).use { reader ->
    for (entry in reader.entries()) println("${entry.name} ${entry.encryption}")
    val data = reader.read(reader.entries().first())
}
```

`ArchivePasswordProvider.of` takes a `String` or a `CharArray`. A callback gets the entry
name, or `null` for the encrypted header of a 7z archive, which is needed to list it at all.
A missing, empty or wrong password throws `ArchivePasswordException`.

ZIP hashes the password as UTF-8 and 7z as UTF-16LE, as 7-Zip does. The 7z key derivation is
capped at 2^24 SHA-256 rounds, and 7-Zip writes 2^19. A 7z reader derives the key once for
each password and salt, and reuses it for the header and every folder. `close()` wipes the
stored key.

## Write

`ZipWriter` writes WinZip AES (AE-2) for every file:

```kotlin
val password = "correct horse".toCharArray()
val zip = ZipWriter.write(files, ZipEncryption(password))
```

`ZipEncryption` keeps its own copy of the password, so you can clear yours afterwards. Its
`keyBits` is 128, 192 or 256, and the default is 256. AE-2 stores no CRC, so the checksum of
the plain data does not leak.

A reader that understands only PKWARE's traditional encryption can be given ZipCrypto, which
every ZIP tool reads. It is broken: a few known bytes of one file give away the keys. Ask for
it by name:

```kotlin
val zip = ZipWriter.write(files, ZipEncryption.insecureZipCrypto(password))
```

Python's `zipfile`, Info-ZIP `unzip` and 7-Zip read the result.

## Schemes

| Scheme | Read | Write | Notes |
| --- | --- | --- | --- |
| WinZip AES (AE-1 and AE-2; 128, 192 and 256 bits) | Yes | AE-2 | The HMAC-SHA1 tag is checked before decompression. A mismatch throws `ArchiveAuthenticationException` |
| ZipCrypto | Yes | With `ZipEncryption.insecureZipCrypto` | Broken. `entry.encryption` reports `ZIP_CRYPTO_INSECURE` |
| 7z AES-256 | Yes, with encrypted headers | No | No authentication, so a wrong password shows as data that fails to decode or to match its CRC |
| PKWARE strong encryption | No | No | Throws `ArchiveUnsupportedException` before any ciphertext is read |

RAR archives with encrypted members or headers are refused, as
[Format support](formats.md#archives) lists.

## The built-in provider

`CryptoProvider.DEFAULT` holds AES, HMAC-SHA1, PBKDF2 and a secure random source, all in
common Kotlin:

- The AES is a port of the constant-time AES of [BearSSL](https://bearssl.org). It uses no
  lookup tables and no branch on the key or the data, so its timing does not depend on secret
  values. This holds in practice on the JVM, Android, Kotlin/Native and Wasm. On Kotlin/JS it
  is best effort, because a JavaScript engine can change how it stores numbers while the code
  runs.
- Every platform runs the same code and gives the same bytes.
- Salts come from the operating system's secure random generator, as the
  [platform table](#platforms) shows.
- The provider holds no state, so threads can share it.

### Speed

The built-in AES runs in software, so it is much slower than AES that uses the processor's
AES instructions. One smoke run of `python3 benchmarks/run.py --suite crypto` on an Apple M2
MacBook Air, with OpenJDK 21.0.13 and Node 26.11.0, gave these numbers:

| Operation | JVM | Node |
| --- | ---: | ---: |
| AES-128 encryption | 54.1 MB/s | 47.3 MB/s |
| AES-256 encryption | 39.7 MB/s | 35.1 MB/s |
| AES-256 CBC decryption, as 7z uses it | 29.3 MB/s | 29.2 MB/s |
| HMAC-SHA1 | 209.6 MB/s | 175.3 MB/s |
| Write one stored 64 KiB WinZip AES-256 entry | 14.6 MB/s | 11.0 MB/s |
| Read one stored 64 KiB WinZip AES-256 entry | 14.5 MB/s | 11.0 MB/s |
| PBKDF2 for one WinZip AES-256 entry | 2.35 ms | 3.69 ms |
| Open a 7z AES archive with 2^19 key rounds | 58.6 ms | 94.3 ms |

On the same machine, the JDK's AES-256 ran at 1,305.5 MB/s and its HMAC-SHA1 at
1,190.3 MB/s, because the JDK uses the processor's AES and SHA instructions. Each WinZip AES
entry has its own salt, so each entry costs one PBKDF2 run, and that run takes most of the
time for small entries. Other work ran on the machine during the run, so treat the numbers as a
rough guide. [Benchmarks](benchmarks.md#encryption) describes the suite.

Every call blocks the calling thread until it ends. In a browser, read large encrypted
archives in a web worker, so that the page stays responsive.

## Faster AES with archivekodec-crypto

The optional `archivekodec-crypto` artifact supplies `CryptographyKotlinProvider`, in the
package `io.github.yuroyami.archivekodec.crypto.platform`. It runs the platform's own AES,
HMAC and PBKDF2 through [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin),
which is faster where the processor has AES instructions. Both providers give the same bytes,
so an archive that one writes, the other reads.

```kotlin
dependencies {
    implementation("io.github.yuroyami:archivekodec-crypto:0.2.0-SNAPSHOT")
}
```

```kotlin
val options = ArchiveOpenOptions {
    password = ArchivePasswordProvider.of(secret)
    crypto = CryptographyKotlinProvider()
}
val zip = ZipWriter.write(files, ZipEncryption(password, crypto = CryptographyKotlinProvider()))
```

`CryptoProvider` is a plain interface, so you can also pass your own implementation on any
target.

## Platforms

| Platform | Random source of the built-in provider | `CryptographyKotlinProvider` |
| --- | --- | --- |
| JVM, Android | `java.security.SecureRandom` | The JDK |
| iOS, macOS, tvOS, watchOS | `arc4random_buf` | Apple's libraries |
| Linux | `getrandom`, or `/dev/urandom` on kernels before 3.17 | OpenSSL 3, bundled |
| Windows (`mingwX64`) | `BCryptGenRandom` | OpenSSL 3, bundled |
| Android Native | `arc4random_buf` | OpenSSL 3, bundled |
| JS, wasmJs | `crypto.getRandomValues` | Not available: browsers offer only the asynchronous WebCrypto |
| wasmWasi | WASI `random_get` | Not available |

From Swift, pass `CryptoProviderCompanion.shared.DEFAULT` as the `crypto` argument of
`ZipEncryption`, because Objective-C has no default arguments. From JavaScript, `open` takes a
password and `zip` takes a password and a key size, as [JavaScript](javascript.md) lists.

### Apple apps and export rules

App Store Connect asks each app whether it uses encryption. The built-in provider is AES code
inside your app, not AES from Apple's operating system, and export rules can treat such an app
differently. If that matters for your app, pass `CryptographyKotlinProvider`, so that the AES
that runs comes from Apple's libraries. The built-in code still links into the app, because
`ArchiveOpenOptions` names it as the default. Check the rules that apply to your app. This
note is a pointer, not legal advice.
