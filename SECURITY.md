# Security policy

ArchiveKodec reads archives and compressed streams that often come from untrusted sources, so
a flaw in a decoder or a reader can be a security problem in every app that uses it.

## Supported versions

| Version | Supported |
| --- | --- |
| 0.2.0 (unreleased, `0.2.0-SNAPSHOT`) | Yes |
| 0.1.0 (`io.github.yuroyami:kitearchive`) | No |

Fixes land on `main` and ship in the next release.

## Report a vulnerability

Do not open a public issue for a vulnerability. Use **Report a vulnerability** on the
repository's Security tab, which opens a private advisory that only the maintainers can see.
Include:

- the version or commit,
- the input that triggers the problem, or a script that builds it,
- what happens (a crash, a hang, memory use, a file written outside the target folder) and on
  which platform.

You should get a first answer within a week. Once a fix is released, the advisory is published
with credit to the reporter, unless they ask otherwise.

## What counts

The library promises these properties, so any input that breaks one is in scope:

- **Limits hold.** No decoder or reader returns or allocates more than `ArchiveLimits` allows:
  `maxUncompressedBytes`, `maxCompressionRatio`, `maxEntries`, `maxSegments` and
  `maxDecoderMemory`. An allocation far larger than the input before a limit rejects it
  counts, as well as output past a limit.
- **Failures are exceptions.** Malformed input throws an `ArchiveException`, never another
  exception type, a crash, or an endless loop.
- **Names are checked.** With `rejectUnsafeNames` on (the default), listings refuse names that
  `PathGuard` considers unsafe, and `extract` never writes through a symbolic link.
- **Integrity checks run.** A stored CRC, checksum or authentication code that does not match
  fails the read, unless the caller chose a lenient policy.

Out of scope: slow but bounded decoding of inputs the limits allow, denial of service through
limits the caller raised or disabled (`ArchiveLimits.UNBOUNDED`), and the security of
algorithms the formats prescribe, such as ZipCrypto, which is weak by design.
