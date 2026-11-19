package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchivePasswordException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ArchiveEntry
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.CrcMismatchListener
import io.github.yuroyami.archivekodec.archive.CrcPolicy
import io.github.yuroyami.archivekodec.archive.DuplicateNamePolicy
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.ReaderSource
import io.github.yuroyami.archivekodec.archive.decodeStoredName
import io.github.yuroyami.archivekodec.archive.readFully
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.readFullyInto
import io.github.yuroyami.archivekodec.archive.requireRange
import io.github.yuroyami.archivekodec.archive.issuedBy
import io.github.yuroyami.archivekodec.archive.resolveIssued
import io.github.yuroyami.archivekodec.archive.withEntryContext
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.SourceBuffer
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.CodecRegistry
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.deflate.InflateError
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.archivekodec.codec.deflate.InflateOptions
import io.github.yuroyami.archivekodec.codec.lzma.ZipLzma
import io.github.yuroyami.archivekodec.codec.ppmd.PpmdZip
import io.github.yuroyami.archivekodec.crypto.ArchiveEncryption
import io.github.yuroyami.archivekodec.crypto.ArchivePasswordProvider
import io.github.yuroyami.archivekodec.crypto.CryptoProvider
import io.github.yuroyami.archivekodec.crypto.WinZipAes
import io.github.yuroyami.archivekodec.crypto.ZipCrypto
import io.github.yuroyami.archivekodec.crypto.utf8
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.readI64Le
import io.github.yuroyami.archivekodec.util.DosTime
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.text.CharacterCodingException

/**
 * ZIP reader over a seekable [RandomAccessSource] (+ZIP64). Reads the central
 * directory at the tail (the authoritative record). [entries] also validates local
 * header names, methods, flags and declared payload extents; [read] rechecks the
 * local fields against the parsed directory before extracting one entry.
 * Prepended launchers and self-extracting prefixes are supported in ZIP and
 * ZIP64; stored relative offsets are reconciled with physical source positions.
 * Filename comparison uses exact stored bytes. Bit 11 selects strict UTF-8;
 * otherwise valid Unicode Path metadata takes precedence over [nameDecoder]
 * (CP437 by default). Conflicting valid Unicode names are malformed.
 * Masked local headers with
 * central-directory encryption are unsupported. By default this reader decodes STORE (0),
 * DEFLATE (8), Deflate64 (9), bzip2 (12), LZMA (14), Zstandard (93) and PPMd (98).
 * Supply [codecs] to add methods or replace engines. Other
 * methods appear in [entries], but [read] throws when no decoder is registered.
 * [ArchiveEntry.zipMethod] preserves the numeric method; unbound methods use a
 * `zip-method-N` codec identifier instead of STORE. Encryption flags or method 99
 * set [ArchiveEntry.isEncrypted] and [ArchiveEntry.encryption]. With a password from
 * [ArchiveOpenOptions.password], ZipCrypto and WinZip AES members are decrypted. WinZip AES
 * uses [ArchiveOpenOptions.crypto], and its authentication code is checked before any
 * decompression. A WinZip AES entry reports its underlying compression codec.
 * PKWARE strong encryption is refused.
 *
 * An entry whose sizes and CRC-32 appear only in the data descriptor after its data (flag
 * bit 3 with zeros in the central directory) lists with a size and compressed size of -1 and
 * no CRC-32. A read finds its end from the data: a stored entry by a scan for the descriptor,
 * a DEFLATE entry by the end of its stream. Only stored, DEFLATE and Deflate64 entries that
 * are not encrypted can be read this way.
 *
 * Security: local-header and compressed-data extents must be disjoint and end
 * before the central directory. Records that share one local header must agree on the
 * method, flags and sizes, and each read of one counts as its own read;
 * [read] enforces [ArchiveLimits] (size + ratio) and verifies
 * the central-directory output size and stored CRC-32, as [ArchiveOpenOptions.crcPolicy] says. Listing refuses unsafe names
 * unless [ArchiveLimits.rejectUnsafeNames] is off; names keep their stored spelling, so
 * join [io.github.yuroyami.archivekodec.security.PathGuard.sanitize]'s result when writing to disk.
 * [limits] caps the listing count and supplies the default policy for each read.
 *
 * Owns the supplied source; close this reader to release it, including after a
 * failed operation. This reader is not thread-safe. Keep it on one thread or
 * externally serialize all access, including [close]. The list from [entries]
 * must be treated as read-only; it may share the reader's cached metadata.
 */
public class ZipReader(
    source: RandomAccessSource,
    private val nameDecoder: ArchiveNameDecoder,
    override val limits: ArchiveLimits,
    /** Immutable decoder bindings for this reader; custom codecs must enforce the supplied limits. */
    public val codecs: CodecRegistry,
) : ArchiveReader {
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits) :
        this(source, nameDecoder, limits, CodecRegistry.DEFAULT)
    public constructor(source: RandomAccessSource, codecs: CodecRegistry) :
        this(source, ArchiveNameDecoder.CP437, ArchiveLimits.DEFAULT, codecs)
    public constructor(source: RandomAccessSource, limits: ArchiveLimits, codecs: CodecRegistry) :
        this(source, ArchiveNameDecoder.CP437, limits, codecs)
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, codecs: CodecRegistry) :
        this(source, nameDecoder, ArchiveLimits.DEFAULT, codecs)
    public constructor(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder) : this(source, nameDecoder, ArchiveLimits.DEFAULT)
    public constructor(source: RandomAccessSource) : this(source, ArchiveNameDecoder.CP437)
    public constructor(source: RandomAccessSource, limits: ArchiveLimits) : this(source, ArchiveNameDecoder.CP437, limits)

    /**
     * Read with [options]: its name decoder (CP437 when null), limits and codecs, plus the
     * [ArchiveOpenOptions.password] and [ArchiveOpenOptions.crypto] that encrypted members need.
     */
    public constructor(source: RandomAccessSource, options: ArchiveOpenOptions) :
        this(source, options.nameDecoder ?: ArchiveNameDecoder.CP437, options.limits, options.codecs) {
        passwords = options.password
        crypto = options.crypto
        crcPolicy = options.crcPolicy
        crcListener = options.crcMismatchListener
        duplicateNames = options.duplicateNames
    }

    private var passwords: ArchivePasswordProvider? = null
    private var crypto: CryptoProvider = CryptoProvider.DEFAULT
    private var crcPolicy: CrcPolicy = CrcPolicy.STRICT
    private var crcListener: CrcMismatchListener? = null
    private var duplicateNames: DuplicateNamePolicy = DuplicateNamePolicy.LAST_WINS

    /** How one read treats a CRC-32 mismatch; [mismatch] records one that did not throw. */
    private class CrcCheck(val mode: CrcMode) {
        var mismatch = false
    }

    private enum class CrcMode { STRICT, LENIENT, REPORT }

    private fun crcCheck() = CrcCheck(if (crcPolicy == CrcPolicy.LENIENT) CrcMode.LENIENT else CrcMode.STRICT)

    private class Member(
        val entry: ArchiveEntry,
        val nameBytes: ByteArray,
        val method: Int,
        val flags: Int,
        val localExtraLength: Int,
        /** WinZip AES fields from the 0x9901 extra field; [aesStrength] is 0 without one. */
        val aesVersion: Int = 0,
        val aesStrength: Int = 0,
        val aesMethod: Int = 0,
        /** The name in the local header; another record's name when two records share one header. */
        val localNameBytes: ByteArray = nameBytes,
        /** True when only the data descriptor after the data gives the sizes and the CRC-32. */
        val sizedByDescriptor: Boolean = false,
    )

    private class Directory(
        val entries: List<ArchiveEntry>,
        /** The member of each entry, in listing order. */
        val members: List<Member>,
        val byName: Map<String, ArchiveEntry>,
        /** The distinct local header offsets, sorted. */
        val headerOffsets: LongArray,
        val directoryStart: Long,
    ) {
        fun memberOf(owner: Any, listed: ArchiveEntry): Member = members[listed.slotIn(owner)]

        /** Where the entry whose local header is at [localOffset] must end: the next local header or the directory. */
        fun boundaryAfter(localOffset: Long): Long {
            var lo = 0
            var hi = headerOffsets.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (headerOffsets[mid] <= localOffset) lo = mid + 1 else hi = mid
            }
            return if (lo < headerOffsets.size) headerOffsets[lo] else directoryStart
        }
    }

    private val ownedSource = ReaderSource<Directory>(source)
    private val source: RandomAccessSource get() = ownedSource.source

    /** Describe the owned source without listing or extracting members. */
    override fun toString(): String = "ZipReader(source=$ownedSource)"

    override val format: ArchiveFormat = ArchiveFormat.ZIP

    @Throws(ArchiveException::class)
    override fun entries(): List<ArchiveEntry> = directory().entries

    private fun directory(): Directory = ownedSource.metadata { parseCentralDirectory() }

    @Throws(ArchiveException::class)
    override fun close(): Unit = ownedSource.close()

    /**
     * Read an entry this reader listed, or an unchanged copy of one; altered metadata
     * and entries from elsewhere are rejected.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveClosedException if the reader is closed.
     * @throws ArchiveFormatException for an entry this reader did not list, changed entry metadata, malformed headers, failed integrity checks,
     *   or malformed or truncated compressed data. For such data the codec's own exception, for example
     *   [io.github.yuroyami.archivekodec.codec.deflate.InflateException], is the cause.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException for an unsupported encryption scheme, ZIP method or feature.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     * @throws ArchiveException if the underlying source or directory listing reports another archive failure.
     */
    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "ZIP")
        return readListed(directory.memberOf(this, listed), limits, crcCheck())
    }

    @Throws(ArchiveException::class)
    override fun read(entry: ArchiveEntry, sink: ByteSink, limits: ArchiveLimits): Long = readTo(entry, sink, limits, crcCheck())

    /**
     * True when [entry] decodes whole and matches its CRC-32, or has none, and false when its
     * CRC-32 does not match. The bytes are not kept: the output of stored, DEFLATE and Deflate64
     * entries that are not encrypted and whose sizes are in the central directory streams
     * through the check, so memory does not grow with the output. DEFLATE and Deflate64 input
     * is still read whole.
     *
     * @throws ArchiveException for the other failures documented by [read].
     */
    @Throws(ArchiveException::class)
    public fun verify(entry: ArchiveEntry, limits: ArchiveLimits = this.limits): Boolean {
        val check = CrcCheck(CrcMode.REPORT)
        readTo(entry, ByteSink { _, _, _ -> }, limits, check)
        return !check.mismatch
    }

    /** Check whether [entry] matches its CRC-32 with this reader's [limits]; see the overload with limits. */
    @Throws(ArchiveException::class)
    public fun verify(entry: ArchiveEntry): Boolean = verify(entry, limits)

    @Throws(ArchiveException::class)
    override fun entry(name: String): ArchiveEntry? = directory().byName[name]

    @Throws(ArchiveException::class, IllegalArgumentException::class)
    override fun readPrefix(entry: ArchiveEntry, maxBytes: Int, limits: ArchiveLimits): ByteArray {
        require(maxBytes >= 0) { "maxBytes must not be negative, not $maxBytes" }
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "ZIP")
        val member = directory.memberOf(this, listed)
        val method = member.method
        val builtIn = when (method) {
            0 -> Codecs.STORE
            8 -> Codecs.DEFLATE
            9 -> Codecs.DEFLATE64
            else -> null
        }
        if (listed.isEncrypted || member.flags and 0x20 != 0 || builtIn == null || codecs.byZipMethod(method) !== builtIn) {
            return super.readPrefix(entry, maxBytes, limits)
        }
        // A prefix that covers the whole entry is the whole read, with all its checks.
        if (!member.sizedByDescriptor && maxBytes >= listed.size) return readListed(member, limits, crcCheck())
        if (maxBytes == 0) return ByteArray(0)
        return withEntryContext(listed.name, listed.dataOffset) { prefix(directory, member, maxBytes, limits) }
    }

    private fun prefix(directory: Directory, member: Member, maxBytes: Int, limits: ArchiveLimits): ByteArray {
        val entry = member.entry
        val source = ownedSource.source
        val dataStart = validateLocalHeader(member, source.readFully(entry.dataOffset, 30), source)
        val available = if (member.sizedByDescriptor) directory.boundaryAfter(entry.dataOffset) - dataStart else entry.compressedSize
        if (member.method == 0) {
            if (member.sizedByDescriptor) {
                // A descriptor within reach ends a short entry; the prefix then checks it whole.
                val span = source.readFullyChecked(dataStart, minOf(available, maxBytes.toLong() + DESCRIPTOR_MAX))
                storedDescriptor(span, hasZip64Extra(member))?.let { found ->
                    if (found.size <= maxBytes) {
                        val data = span.copyOf(found.size.toInt())
                        limits.checkOutputSize(data.size.toLong())
                        checkCrc(crcCheck(), member, found.crc, Crc32.of(data), dataStart)
                        return data
                    }
                }
                val n = minOf(maxBytes.toLong(), available).toInt()
                limits.checkOutputSize(n.toLong())
                return span.copyOf(n)
            }
            val n = minOf(maxBytes.toLong(), available)
            limits.checkOutputSize(n)
            return source.readFullyChecked(dataStart, n)
        }
        // DEFLATE and Deflate64: read the compressed data a span at a time, so a header costs little.
        var span = minOf(available, 2L * maxBytes + 1024)
        while (true) {
            val comp = source.readFullyChecked(dataStart, span)
            val options = InflateOptions {
                this.limits = limits
                allowTrailingData = true
                stopAfterBytes = maxBytes.toLong()
            }
            val result = try {
                Inflate.decodeRange(comp, 0, comp.size, options, "deflate") { output, listener ->
                    Inflate.inflateInto(comp, 0, comp.size, output, deflate64 = member.method == 9, onBlock = listener)
                }
            } catch (error: InflateException) {
                if (error.error == InflateError.DATA_DID_NOT_TERMINATE && span < available) {
                    span = minOf(available, span * 2)
                    continue
                }
                throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
            } catch (error: ArchiveException) {
                throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
            }
            if (!result.isStopped) {
                // The whole stream fit in the prefix, so check it as a read would.
                if (member.sizedByDescriptor) {
                    checkDescriptorAfter(member, comp, result.inputConsumed, result.output, dataStart, crcCheck())
                } else {
                    throw ArchiveFormatException("ZIP: output size ${result.output.size} differs from declared size ${entry.size}", dataStart, entry.name)
                }
            }
            return result.output
        }
    }

    private fun readTo(entry: ArchiveEntry, sink: ByteSink, limits: ArchiveLimits, check: CrcCheck): Long {
        withEntryContext(entry.name, entry.dataOffset) { ownedSource.source }
        val directory = directory()
        val listed = directory.entries.resolveIssued(this, entry, "ZIP")
        val member = directory.memberOf(this, listed)
        val method = member.method
        // Only the built-in engines stream. A registry that replaces one decodes in memory, through it.
        val builtIn = when (method) {
            0 -> Codecs.STORE
            8 -> Codecs.DEFLATE
            9 -> Codecs.DEFLATE64
            else -> null
        }
        if (listed.isEncrypted || member.flags and 0x20 != 0 || builtIn == null || codecs.byZipMethod(method) !== builtIn ||
            member.sizedByDescriptor
        ) {
            val bytes = readListed(member, limits, check)
            if (bytes.isNotEmpty()) sink.write(bytes, 0, bytes.size)
            return bytes.size.toLong()
        }
        return withEntryContext(listed.name, listed.dataOffset) { stream(member, sink, limits, check) }
    }

    /**
     * Apply [check]'s mode to a CRC-32 mismatch: throw, tell the listener, or record it. ZipCrypto
     * stays strict under [CrcPolicy.LENIENT], since a mismatch there is how a wrong password shows.
     */
    private fun checkCrc(check: CrcCheck, member: Member, expected: Long, actual: Long, dataStart: Long) {
        if (expected == actual) return
        val entry = member.entry
        val strict = check.mode == CrcMode.STRICT ||
            (check.mode == CrcMode.LENIENT && entry.encryption == ArchiveEncryption.ZIP_CRYPTO_INSECURE)
        if (strict) throw ArchiveFormatException("ZIP: CRC-32 mismatch", dataStart, entry.name)
        check.mismatch = true
        if (check.mode == CrcMode.LENIENT) crcListener?.onMismatch(entry, expected, actual)
    }

    /** Stream a stored, DEFLATE or Deflate64 member, checking its size and CRC-32 at the end. */
    private fun stream(member: Member, sink: ByteSink, limits: ArchiveLimits, check: CrcCheck): Long {
        val entry = member.entry
        val source = ownedSource.source
        val dataStart = validateLocalHeader(member, source.readFully(entry.dataOffset, 30), source)
        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.compressedSize, entry.size)
        val crc = Crc32()
        val checked = ByteSink { bytes, offset, length ->
            crc.update(bytes, offset, length)
            sink.write(bytes, offset, length)
        }
        val written = if (member.method == 0) {
            if (entry.compressedSize != entry.size) {
                throw ArchiveFormatException("ZIP: stored compressed size ${entry.compressedSize} differs from output size ${entry.size}", dataStart, entry.name)
            }
            val chunk = ByteArray(minOf(entry.size, 1L shl 20).toInt())
            var done = 0L
            while (done < entry.size) {
                val n = minOf(chunk.size.toLong(), entry.size - done).toInt()
                source.readFullyInto(dataStart + done, chunk, n)
                checked.write(chunk, 0, n)
                done += n
            }
            done
        } else {
            // The compressed span is read as the decoder needs it, so neither side is held whole.
            // Output is held to the declared size, whose ratio was checked above, so the
            // decoder's running ratio check, against the bytes read so far, would add nothing.
            source.requireRange(dataStart, entry.compressedSize)
            var position = dataStart
            val end = dataStart + entry.compressedSize
            val input = SourceBuffer(ByteSource { into, offset, length ->
                if (position == end) -1 else {
                    val n = minOf(length.toLong(), end - position).toInt()
                    source.readFullyInto(position, into, n, offset)
                    position += n
                    n
                }
            })
            val output = Inflate.Output(limits.copy { maxCompressionRatio = Int.MAX_VALUE }, Int.MAX_VALUE,
                expectedSize = entry.size, sink = checked, reserveInputSize = false)
            try {
                Inflate.inflateFrom(input, output, deflate64 = member.method == 9)
            } catch (error: ArchiveException) {
                throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
            }
            if (!input.atEnd()) throw ArchiveFormatException("deflate: trailing bytes after stream", dataStart + input.streamPosition, entry.name)
            output.finish()
        }
        if (written != entry.size) throw ArchiveFormatException("ZIP: output size $written differs from declared size ${entry.size}", dataStart, entry.name)
        entry.crc32?.let { expected -> checkCrc(check, member, expected, crc.value(), dataStart) }
        return written
    }

    private fun readListed(member: Member, limits: ArchiveLimits, check: CrcCheck): ByteArray = withEntryContext(member.entry.name, member.entry.dataOffset) {
        val entry = member.entry
        val source = ownedSource.source
        val lh = source.readFully(entry.dataOffset, 30)
        val dataStart = validateLocalHeader(member, lh, source)
        val encryption = entry.encryption
        if (entry.isEncrypted && (encryption == null || encryption == ArchiveEncryption.UNSUPPORTED)) {
            val field = if (member.method == 99) 8 else 6
            throw ArchiveUnsupportedException("ZIP: this encryption scheme is not supported", entry.dataOffset + field, entry.name)
        }
        if (member.flags and 0x20 != 0) {
            throw ArchiveUnsupportedException("ZIP compressed patched data is not supported", entry.dataOffset + 6, entry.name)
        }
        if (member.sizedByDescriptor) return@withEntryContext readByDescriptor(member, dataStart, limits, check)

        limits.checkOutputSize(entry.size)
        limits.checkRatio(entry.compressedSize, entry.size)

        val method = if (member.aesStrength != 0) member.aesMethod else member.method
        val codecId = codecs.idForZipMethod(method)
            ?: throw ArchiveUnsupportedException("ZIP method $method not supported", entry.dataOffset + 8, entry.name)
        val codec = codecs.byZipMethod(method)
            ?: throw ArchiveUnsupportedException("ZIP method $method ($codecId) not implemented yet", entry.dataOffset + 8, entry.name)
        if (!entry.isEncrypted && method == 0 && entry.compressedSize != entry.size) {
            throw ArchiveFormatException("ZIP: stored compressed size ${entry.compressedSize} differs from output size ${entry.size}", dataStart, entry.name)
        }
        // Ask for the password before reading any ciphertext.
        val password = if (entry.isEncrypted) {
            val field = entry.dataOffset + if (member.method == 99) 8 else 6
            val password = passwords?.password(entry.name)
                ?: throw ArchivePasswordException("ZIP: entry is encrypted and no password was supplied", field, entry.name)
            if (member.aesStrength != 0 && password.isEmpty()) {
                throw ArchivePasswordException("ZIP: entry is encrypted with WinZip AES and the password is empty", field, entry.name)
            }
            password
        } else null
        val stored = source.readFullyChecked(dataStart, entry.compressedSize)
        val comp = if (password != null) decrypt(member, lh, stored, dataStart, password) else stored
        if (method == 0 && comp.size.toLong() != entry.size) {
            throw ArchiveFormatException("ZIP: stored compressed size ${comp.size} differs from output size ${entry.size}", dataStart, entry.name)
        }
        // ZipCrypto has no authentication, so a wrong password that passes its one-byte check
        // surfaces as undecodable data or a CRC mismatch; report that as a password problem.
        if (encryption == ArchiveEncryption.ZIP_CRYPTO_INSECURE) {
            try {
                return@withEntryContext decode(member, codec, method, comp, dataStart, limits, check)
            } catch (error: ArchiveException) {
                if (error is DecompressionBombException) throw error
                throw ArchivePasswordException("ZIP: wrong password or corrupt data", dataStart, entry.name, error)
            }
        }
        return@withEntryContext decode(member, codec, method, comp, dataStart, limits, check)
    }

    /** A data descriptor's fields: the CRC-32, the compressed size and the size. */
    private class Descriptor(val crc: Long, val compressed: Long, val size: Long)

    /** True when the entry's local header carries a ZIP64 extra field, so its descriptor has 8-byte sizes. */
    private fun hasZip64Extra(member: Member): Boolean {
        if (member.localExtraLength < 4) return false
        val extra = ownedSource.source.readFully(member.entry.dataOffset + 30 + member.localNameBytes.size, member.localExtraLength)
        var pos = 0
        while (extra.size - pos >= 4) {
            if (readU16Le(extra, pos) == 0x0001) return true
            pos += 4 + readU16Le(extra, pos + 2)
        }
        return false
    }

    /**
     * The stored entry's descriptor in [span]: the first place whose size fields equal the
     * distance from the start and whose CRC-32 matches the bytes before it, with or without the
     * signature. Failing that, the first signed descriptor whose sizes match, so a damaged entry
     * reads as a CRC-32 mismatch. Null when neither exists.
     */
    private fun storedDescriptor(span: ByteArray, zip64: Boolean): Descriptor? {
        val width = if (zip64) 8 else 4
        fun size(at: Int): Long = if (zip64) readI64Le(span, at) else readU32Le(span, at)
        val crc = Crc32()
        var crcTo = 0
        var damaged: Descriptor? = null
        var p = 0
        while (p + 4 + 2 * width <= span.size) {
            val signed = p + 8 + 2 * width <= span.size && readU32Le(span, p) == ZipFormat.DATA_DESCRIPTOR &&
                size(p + 8) == p.toLong() && size(p + 8 + width) == p.toLong()
            val bare = p > 0 && size(p + 4) == p.toLong() && size(p + 4 + width) == p.toLong()
            if (signed || bare) {
                crc.update(span, crcTo, p - crcTo)
                crcTo = p
                val actual = crc.value()
                if (signed && readU32Le(span, p + 4) == actual) return Descriptor(actual, p.toLong(), p.toLong())
                if (bare && readU32Le(span, p) == actual) return Descriptor(actual, p.toLong(), p.toLong())
                if (signed && damaged == null) damaged = Descriptor(readU32Le(span, p + 4), p.toLong(), p.toLong())
            }
            p++
        }
        return damaged
    }

    /** Check the descriptor at [at] in [comp] against a DEFLATE stream that took [at] bytes and gave [output]. */
    private fun checkDescriptorAfter(member: Member, comp: ByteArray, at: Int, output: ByteArray, dataStart: Long, check: CrcCheck) {
        val entry = member.entry
        val width = if (hasZip64Extra(member)) 8 else 4
        fun parse(base: Int): Descriptor? {
            if (base + 4 + 2 * width > comp.size) return null
            fun size(p: Int): Long = if (width == 8) readI64Le(comp, p) else readU32Le(comp, p)
            return Descriptor(readU32Le(comp, base), size(base + 4), size(base + 4 + width))
        }
        val candidates = listOfNotNull(
            if (at + 4 <= comp.size && readU32Le(comp, at) == ZipFormat.DATA_DESCRIPTOR) parse(at + 4) else null,
            parse(at),
        )
        if (candidates.isEmpty()) throw ArchiveFormatException("ZIP: missing data descriptor after the data", dataStart + at, entry.name)
        val found = candidates.firstOrNull { it.compressed == at.toLong() && it.size == output.size.toLong() }
            ?: throw ArchiveFormatException("ZIP: data descriptor sizes differ from the data", dataStart + at, entry.name)
        checkCrc(check, member, found.crc, Crc32.of(output), dataStart)
    }

    /** Read an entry that only its data descriptor sizes; see the class KDoc. */
    private fun readByDescriptor(member: Member, dataStart: Long, limits: ArchiveLimits, check: CrcCheck): ByteArray {
        val entry = member.entry
        val source = ownedSource.source
        if (entry.isEncrypted) {
            throw ArchiveUnsupportedException("ZIP: an encrypted entry sized only by its data descriptor is not supported", entry.dataOffset + 6, entry.name)
        }
        val method = member.method
        val builtIn = when (method) {
            0 -> Codecs.STORE
            8 -> Codecs.DEFLATE
            9 -> Codecs.DEFLATE64
            else -> null
        }
        if (builtIn == null || codecs.byZipMethod(method) !== builtIn) {
            throw ArchiveUnsupportedException("ZIP method $method cannot be read without its sizes", entry.dataOffset + 8, entry.name)
        }
        val available = directory().boundaryAfter(entry.dataOffset) - dataStart
        if (method == 0) {
            val window = minOf(available, limits.maxUncompressedBytes + DESCRIPTOR_MAX, Int.MAX_VALUE - 8L)
            val span = source.readFullyChecked(dataStart, window)
            val found = storedDescriptor(span, hasZip64Extra(member))
            if (found == null) {
                if (window < available) limits.checkOutputSize(window)
                throw ArchiveFormatException("ZIP: no data descriptor matches the stored data", dataStart, entry.name)
            }
            limits.checkOutputSize(found.size)
            val data = span.copyOf(found.size.toInt())
            checkCrc(check, member, found.crc, Crc32.of(data), dataStart)
            return data
        }
        val comp = source.readFullyChecked(dataStart, available)
        val options = InflateOptions {
            this.limits = limits
            allowTrailingData = true
        }
        val result = try {
            Inflate.decodeRange(comp, 0, comp.size, options, "deflate") { output, listener ->
                Inflate.inflateInto(comp, 0, comp.size, output, deflate64 = method == 9, onBlock = listener)
            }
        } catch (error: ArchiveException) {
            throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
        }
        checkDescriptorAfter(member, comp, result.inputConsumed, result.output, dataStart, check)
        return result.output
    }

    private fun decrypt(member: Member, local: ByteArray, stored: ByteArray, dataStart: Long, password: CharArray): ByteArray {
        val entry = member.entry
        try {
            return if (member.aesStrength != 0) {
                WinZipAes.decrypt(crypto, password, member.aesStrength, stored)
            } else {
                val check = ((entry.crc32 ?: 0L) ushr 24).toInt() and 0xFF
                val timeCheck = if (member.flags and 0x08 != 0) readU16Le(local, 10) ushr 8 else null
                ZipCrypto(password.utf8()).decrypt(stored, check, timeCheck)
            }
        } catch (error: ArchiveException) {
            throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun decode(member: Member, codec: Codec, method: Int, comp: ByteArray, dataStart: Long, limits: ArchiveLimits, check: CrcCheck): ByteArray {
        val entry = member.entry
        val out = try {
            when {
                codec === Codecs.DEFLATE -> Inflate.inflateExact(comp, limits, entry.size)
                codec === Codecs.DEFLATE64 -> Inflate.inflateExact(comp, limits, entry.size, deflate64 = true)
                // Method 14 has ZIP's own LZMA header rather than the .lzma one.
                codec === Codecs.LZMA && method == 14 -> ZipLzma.decompress(comp, limits, entry.size)
                codec === Codecs.PPMD && method == 98 -> PpmdZip.decompress(comp, limits, entry.size)
                // readFullyChecked allocated this result; no source storage is shared.
                codec === Codecs.STORE -> comp
                else -> codec.decompress(comp, limits)
            }
        } catch (error: ArchiveException) {
            throw error.withEntryContext(entry.name, dataStart, relativeOffset = true)
        }

        limits.checkOutputSize(out.size.toLong())
        limits.checkRatio(entry.compressedSize, out.size.toLong())
        if (out.size.toLong() != entry.size) {
            throw ArchiveFormatException("ZIP: output size ${out.size} differs from declared size ${entry.size}", dataStart, entry.name)
        }

        // WinZip AE-2 stores no CRC; its authentication code covers the data instead.
        if (member.aesVersion != 2) {
            entry.crc32?.let { expected -> checkCrc(check, member, expected, Crc32.of(out), dataStart) }
        }
        return out
    }

    private fun validateLocalHeader(member: Member, local: ByteArray, source: RandomAccessSource): Long =
        withEntryContext(member.entry.name, member.entry.dataOffset) {
            val entry = member.entry
            val offset = entry.dataOffset
            if (readU32Le(local, 0) != ZipFormat.LOC) {
                throw ArchiveFormatException("ZIP: invalid local file header", offset, entry.name)
            }
            val flags = readU16Le(local, 6)
            if ((flags or member.flags) and 0x2000 != 0) {
                throw ArchiveUnsupportedException("ZIP masked local headers with central directory encryption are not supported", offset + 6, entry.name)
            }
            if (flags != member.flags) {
                throw ArchiveFormatException("ZIP: local flags differ from the central directory", offset + 6, entry.name)
            }
            if (readU16Le(local, 8) != member.method) {
                throw ArchiveFormatException("ZIP: local method differs from the central directory", offset + 8, entry.name)
            }
            val nameLength = readU16Le(local, 26)
            if (nameLength != member.localNameBytes.size) {
                throw ArchiveFormatException("ZIP: local filename length differs from the central directory", offset + 26, entry.name)
            }
            val extraLength = readU16Le(local, 28)
            if (extraLength != member.localExtraLength) {
                throw ArchiveFormatException("ZIP: local extra length changed since directory validation", offset + 28, entry.name)
            }
            source.requireRange(offset + 30, (nameLength + extraLength).toLong())
            if (!source.readFully(offset + 30, nameLength).contentEquals(member.localNameBytes)) {
                throw ArchiveFormatException("ZIP: local filename differs from the central directory", offset + 30, entry.name)
            }
            val dataStart = offset + 30 + nameLength + extraLength
            // An entry sized by its descriptor ends at the next local header, which bounds it already.
            if (!member.sizedByDescriptor) source.requireRange(dataStart, entry.compressedSize)
            dataStart
        }

    private fun validateMemberExtents(members: Collection<Member>, directoryOffset: Long) {
        val ordered = members.sortedBy { it.entry.dataOffset }
        for (index in ordered.indices) {
            val member = ordered[index]
            val next = ordered.getOrNull(index + 1)
            val boundary = next?.entry?.dataOffset ?: directoryOffset
            // validateLocalHeader bounded the fixed header, variable fields and
            // compressed range against the source; their sum cannot overflow.
            val end = member.entry.dataOffset + 30 + member.localNameBytes.size +
                member.localExtraLength + member.entry.compressedSize.coerceAtLeast(0)
            if (end > boundary) {
                val target = if (next == null) "central directory" else "next local header"
                throw ArchiveFormatException("ZIP: entry extent ending at $end overlaps $target at $boundary", boundary, member.entry.name)
            }
        }
    }

    private fun parseCentralDirectory(): Directory {
        val size = source.size
        if (size < 22) throw ArchiveFormatException("ZIP: file is too small", 0)

        val end = ZipEndRecord.find(source)
            ?: throw ArchiveFormatException("ZIP: missing end-of-central-directory", maxOf(0, size - 65557))
        val tail = end.bytes
        val eocd = 0
        val eocdOffset = end.offset
        fun requireDiskZero(disk: Long, offset: Long, name: String? = null) {
            if (disk != 0L) throw ArchiveUnsupportedException("ZIP: multi-disk archives are not supported (disk $disk)", offset, name)
        }

        var cdEntries = readU16Le(tail, eocd + 10).toLong()
        var cdSize = readU32Le(tail, eocd + 12)
        var cdOffset = readU32Le(tail, eocd + 16)
        var directoryEnd = eocdOffset
        var prefixSize = 0L

        // A present ZIP64 locator is authoritative even without classic sentinels.
        // The classic count 65535 also occurs without ZIP64, including wrapped counts.
        val locPos = directoryEnd - 20
        val locator = if (locPos >= 0) source.readFully(locPos, 20) else null
        val hasZip64 = locator != null && readU32Le(locator, 0) == ZipFormat.Z64_LOC
        for (field in listOf(4, 6)) {
            val disk = readU16Le(tail, eocd + field).toLong()
            if (!(hasZip64 && disk == 0xFFFFL)) requireDiskZero(disk, eocdOffset + field)
        }
        if (!hasZip64 && (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL)) {
            throw ArchiveFormatException("ZIP: missing ZIP64 locator", locPos)
        }
        if (hasZip64) {
            checkNotNull(locator)
            requireDiskZero(readU32Le(locator, 4), locPos + 4)
            val disks = readU32Le(locator, 16)
            if (disks == 0L) throw ArchiveFormatException("ZIP: invalid disk count 0 in ZIP64 locator", locPos + 16)
            if (disks != 1L) throw ArchiveUnsupportedException("ZIP: multi-disk archives are not supported ($disks disks)", locPos + 16)
            val relativeEnd = readI64Le(locator, 8)
            val record = ZipEndRecord.zip64(source, relativeEnd, locPos)
            val z64Off = record.offset
            val z64 = record.bytes
            prefixSize = z64Off - relativeEnd
            val recordSize = readI64Le(z64, 4)
            if (recordSize < 44 || recordSize > locPos - z64Off - 12) {
                throw ArchiveFormatException("ZIP: invalid ZIP64 end record size $recordSize", z64Off + 4)
            }
            requireDiskZero(readU32Le(z64, 16), z64Off + 16)
            requireDiskZero(readU32Le(z64, 20), z64Off + 20)
            cdEntries = readI64Le(z64, 32)
            val diskEntries = readI64Le(z64, 24)
            if (diskEntries != cdEntries) {
                throw ArchiveFormatException("ZIP: per-disk count $diskEntries differs from total count $cdEntries", z64Off + 24)
            }
            cdSize = readI64Le(z64, 40)
            cdOffset = ZipEndRecord.shift(readI64Le(z64, 48), prefixSize, z64Off + 48)
            directoryEnd = z64Off
        } else {
            val diskEntries = readU16Le(tail, eocd + 8).toLong()
            if (diskEntries != cdEntries) {
                throw ArchiveFormatException("ZIP: per-disk count $diskEntries differs from total count $cdEntries", eocdOffset + 8)
            }
        }
        source.requireRange(cdOffset, cdSize)
        if (cdOffset > directoryEnd || cdSize > directoryEnd - cdOffset) {
            throw ArchiveFormatException("ZIP: central directory overlaps end records", cdOffset)
        }
        if (!hasZip64) {
            val stored = cdOffset
            cdOffset = ZipEndRecord.classicDirectory(source, stored, cdSize, directoryEnd)
            prefixSize = cdOffset - stored
        }
        if (cdEntries < 0 || cdEntries > Int.MAX_VALUE || cdEntries > cdSize / 46) {
            throw ArchiveFormatException("ZIP: invalid directory count $cdEntries for $cdSize bytes", cdOffset)
        }

        // Reject an excessive declared count before loading directory bytes. Classic
        // counts may wrap, so the per-record check below is still required.
        limits.checkEntryCount(cdEntries.toInt())
        val cd = source.readFullyChecked(cdOffset, cdSize)
        val entries = ArrayList<ArchiveEntry>()
        val members = ArrayList<Member>()
        val byOffset = HashMap<Long, Member>()
        val byName = HashMap<String, ArchiveEntry>()
        var p = 0
        var n = 0L
        while (p < cd.size) {
            if (cd.size - p >= 4 && readU32Le(cd, p) == ZipFormat.CEN_SIGNATURE) {
                if (cd.size - p < 6 || readU16Le(cd, p + 4) != cd.size - p - 6) {
                    throw ArchiveFormatException("ZIP: invalid central directory digital signature length", cdOffset + p)
                }
                // Recognize and bound the optional record; this does not authenticate it.
                p = cd.size
                break
            }
            if (p > cd.size - 46) {
                throw ArchiveFormatException("ZIP: truncated central directory at entry $n of $cdEntries", cdOffset + p)
            }
            if (readU32Le(cd, p) != ZipFormat.CEN) throw ArchiveFormatException("ZIP: invalid central directory signature", cdOffset + p)
            limits.checkEntryCount(entries.size + 1)
            val flags = readU16Le(cd, p + 8)
            val method = readU16Le(cd, p + 10)
            val crc = readU32Le(cd, p + 16)
            var compSize = readU32Le(cd, p + 20)
            var uncompSize = readU32Le(cd, p + 24)
            val nameLen = readU16Le(cd, p + 28)
            val extraLen = readU16Le(cd, p + 30)
            val commentLen = readU16Le(cd, p + 32)
            var startDisk = readU16Le(cd, p + 34).toLong()
            var diskFieldOffset = cdOffset + p + 34
            var localOffset = readU32Le(cd, p + 42)
            var localOffsetField = cdOffset + p + 42
            val recordSize = 46 + nameLen + extraLen + commentLen
            if (recordSize > cd.size - p) {
                throw ArchiveFormatException("ZIP: central directory fields exceed record at entry $n", cdOffset + p)
            }
            val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
            val nameOffset = cdOffset + p + 46
            val utf8 = flags and 0x0800 != 0
            var name = nameBytes.decodeToString() // diagnostics until decoding is resolved
            val centralUnicode = if (!utf8) {
                unicodeName(cd, p + 46 + nameLen, p + 46 + nameLen + extraLen, nameBytes, cdOffset, name)
            } else null

            val extendedTime = extendedTimestamp(cd, p + 46 + nameLen, p + 46 + nameLen + extraLen)
            val modified = extendedTime ?: DosTime.toEpochSeconds(readU16Le(cd, p + 14), readU16Le(cd, p + 12))
            // Version-made-by host 3 is Unix; its st_mode sits in the high external-attribute bits.
            val unixMode = if (readU16Le(cd, p + 4) shr 8 == 3) (readU32Le(cd, p + 38) ushr 16).toInt().takeIf { it != 0 } else null
            val comment = if (commentLen == 0) null else {
                val commentStart = p + 46 + nameLen + extraLen
                val bytes = cd.copyOfRange(commentStart, commentStart + commentLen)
                (if (utf8) ArchiveNameDecoder.UTF8 else nameDecoder).decodeStoredName(bytes, cdOffset + commentStart)
            }

            val zip64Output = uncompSize == 0xFFFFFFFFL
            val zip64Compressed = compSize == 0xFFFFFFFFL
            val zip64Offset = localOffset == 0xFFFFFFFFL
            val zip64Disk = startDisk == 0xFFFFL
            if (zip64Output || zip64Compressed || zip64Offset || zip64Disk) {
                var ep = p + 46 + nameLen
                val extraStart = ep
                val extraEnd = ep + extraLen
                var found = false
                while (ep < extraEnd) {
                    if (extraEnd - ep < 4) throw ArchiveFormatException("ZIP: truncated extra field header", cdOffset + ep, name)
                    val id = readU16Le(cd, ep)
                    val sz = readU16Le(cd, ep + 2)
                    var dp = ep + 4
                    if (sz > extraEnd - dp) throw ArchiveFormatException("ZIP: truncated extra field", cdOffset + ep, name)
                    if (id == 0x0001) { // ZIP64 extended info: fields present in this order
                        if (found) throw ArchiveFormatException("ZIP: duplicate required ZIP64 extra field", cdOffset + ep, name)
                        found = true
                        val needed = (if (zip64Output) 8 else 0) + (if (zip64Compressed) 8 else 0) +
                            (if (zip64Offset) 8 else 0) + (if (zip64Disk) 4 else 0)
                        if (sz < needed) throw ArchiveFormatException("ZIP: truncated ZIP64 entry fields", cdOffset + dp, name)
                        fun signedValue(field: String): Long {
                            val value = readI64Le(cd, dp)
                            if (value < 0) throw ArchiveFormatException("ZIP: ZIP64 $field exceeds Long", cdOffset + dp, name)
                            dp += 8
                            return value
                        }
                        if (zip64Output) uncompSize = signedValue("uncompressed size")
                        if (zip64Compressed) compSize = signedValue("compressed size")
                        if (zip64Offset) {
                            localOffsetField = cdOffset + dp
                            localOffset = signedValue("local header offset")
                        }
                        if (zip64Disk) {
                            diskFieldOffset = cdOffset + dp
                            startDisk = readU32Le(cd, dp)
                        }
                    }
                    ep += 4 + sz
                }
                if (!found) throw ArchiveFormatException("ZIP: missing required ZIP64 extra field", cdOffset + extraStart, name)
            }

            requireDiskZero(startDisk, diskFieldOffset, name)
            if (uncompSize < 0) throw ArchiveFormatException("ZIP: uncompressed size exceeds Long", cdOffset + p, name)
            localOffset = ZipEndRecord.shift(localOffset, prefixSize, localOffsetField, name)
            if (localOffset > cdOffset || cdOffset - localOffset < 30) {
                throw ArchiveFormatException("ZIP: local header overlaps the central directory", cdOffset, name)
            }
            val local = source.readFully(localOffset, 30)
            if (readU32Le(local, 0) != ZipFormat.LOC) {
                throw ArchiveFormatException("ZIP: invalid local file header", localOffset, name)
            }
            // Records that share one local header read the same bytes, so they must agree on how.
            val sharing = byOffset[localOffset]
            if (sharing != null && (sharing.method != method || sharing.flags != flags ||
                    sharing.entry.compressedSize.coerceAtLeast(0) != compSize || sharing.entry.size.coerceAtLeast(0) != uncompSize)
            ) {
                throw ArchiveFormatException("ZIP: records that share the local header at $localOffset disagree on method, flags or sizes", localOffset, name)
            }
            val sizedByDescriptor = flags and 0x08 != 0 && compSize == 0L && uncompSize == 0L && crc == 0L

            val aes = if (method == 99) winZipAes(cd, p + 46 + nameLen, p + 46 + nameLen + extraLen, cdOffset, name) else null
            val dataMethod = aes?.get(2) ?: method
            val provisional = ArchiveEntry(name, if (sizedByDescriptor) -1 else uncompSize, false) {
                compressedSize = if (sizedByDescriptor) -1 else compSize
                codec = codecs.idForZipMethod(dataMethod) ?: CodecId("zip-method-$dataMethod")
                isEncrypted = flags and 0x41 != 0 || method == 99
                encryption = when {
                    method == 99 -> when (aes?.get(1)) {
                        1 -> ArchiveEncryption.WINZIP_AES_128
                        2 -> ArchiveEncryption.WINZIP_AES_192
                        3 -> ArchiveEncryption.WINZIP_AES_256
                        else -> ArchiveEncryption.UNSUPPORTED
                    }
                    flags and 0x40 != 0 -> ArchiveEncryption.UNSUPPORTED
                    flags and 0x01 != 0 -> ArchiveEncryption.ZIP_CRYPTO_INSECURE
                    else -> null
                }
                crc32 = if (sizedByDescriptor) null else crc
                lastModifiedEpochSeconds = modified
                this.unixMode = unixMode
                this.comment = comment
            }.withZipMethod(method).locatedAt(localOffset) // local header offset; resolved to data in read()
            val localExtraLength = readU16Le(local, 28)
            val localNameBytes = sharing?.localNameBytes ?: nameBytes
            validateLocalHeader(
                Member(provisional, nameBytes, method, flags, localExtraLength, localNameBytes = localNameBytes, sizedByDescriptor = sizedByDescriptor),
                local, source,
            )
            val localUnicode = if (!utf8 && localExtraLength > 0) {
                val extraOffset = localOffset + 30 + nameLen
                val extra = source.readFully(extraOffset, localExtraLength)
                unicodeName(extra, 0, extra.size, nameBytes, extraOffset, name)
            } else null
            if (centralUnicode != null && localUnicode != null && centralUnicode != localUnicode) {
                throw ArchiveFormatException("ZIP: conflicting local and central Unicode Path names", nameOffset, name)
            }
            name = if (utf8) ArchiveNameDecoder.UTF8.decodeStoredName(nameBytes, nameOffset)
                else centralUnicode ?: localUnicode ?: nameDecoder.decodeStoredName(nameBytes, nameOffset)
            val entry = provisional.copy { this.name = name; isDirectory = name.endsWith("/") }.withNameBytes(nameBytes)
            val member = Member(
                entry, nameBytes, method, flags, localExtraLength, aes?.get(0) ?: 0, aes?.get(1) ?: 0, aes?.get(2) ?: 0,
                localNameBytes = localNameBytes, sizedByDescriptor = sizedByDescriptor,
            )
            if (duplicateNames == DuplicateNamePolicy.REJECT && name in byName) {
                throw ArchiveFormatException("ZIP: two entries are named '$name'", nameOffset, name)
            }
            if (sharing == null) byOffset[localOffset] = member
            byName[name] = entry
            members.add(member)
            entries.add(entry)
            p += 46 + nameLen + extraLen + commentLen
            n++
        }
        val countForComparison = if (hasZip64) n else n and 0xFFFFL
        if (countForComparison != cdEntries) {
            throw ArchiveFormatException("ZIP: central directory count $n differs from declared count $cdEntries", cdOffset)
        }
        validateMemberExtents(byOffset.values, cdOffset)
        val offsets = byOffset.keys.toLongArray().also { it.sort() }
        return Directory(entries.issuedBy(this, limits), members, byName, offsets, cdOffset)
    }

    private companion object {
        /** The longest data descriptor: signature, CRC-32 and two 8-byte sizes. */
        const val DESCRIPTOR_MAX = 24L
    }

    /**
     * The WinZip AES extra field (0x9901) between [start] and [end] as (vendor version,
     * strength, actual method), or null when absent or malformed; reading then refuses it.
     */
    private fun winZipAes(bytes: ByteArray, start: Int, end: Int, base: Long, name: String): IntArray? {
        var pos = start
        while (end - pos >= 4) {
            val id = readU16Le(bytes, pos)
            val length = readU16Le(bytes, pos + 2)
            val data = pos + 4
            if (length > end - data) return null
            if (id == 0x9901) {
                if (length < 7 || bytes[data + 2] != 'A'.code.toByte() || bytes[data + 3] != 'E'.code.toByte()) return null
                val version = readU16Le(bytes, data)
                val strength = bytes[data + 4].toInt() and 0xFF
                if (version !in 1..2 || strength !in 1..3) return null
                return intArrayOf(version, strength, readU16Le(bytes, data + 5))
            }
            pos = data + length
        }
        return null
    }

    /**
     * Modification time from an Info-ZIP extended-timestamp field (0x5455) in [bytes]
     * between [start] and [end], or null. This optional field never fails a listing:
     * malformed framing or a field without a modification time falls back to DOS time.
     */
    private fun extendedTimestamp(bytes: ByteArray, start: Int, end: Int): Long? {
        var pos = start
        while (end - pos >= 4) {
            val id = readU16Le(bytes, pos)
            val length = readU16Le(bytes, pos + 2)
            val data = pos + 4
            if (length > end - data) return null
            if (id == 0x5455 && length >= 5 && bytes[data].toInt() and 1 != 0) {
                return readU32Le(bytes, data + 1).toInt().toLong() // signed 32-bit Unix time
            }
            pos = data + length
        }
        return null
    }

    /** Optional Unicode payload failures fall back; broken framing and ambiguity do not. */
    private fun unicodeName(
        bytes: ByteArray, start: Int, end: Int, storedName: ByteArray, sourceOffset: Long, diagnosticName: String,
    ): String? {
        var pos = start
        var result: String? = null
        var nameCrc: Long? = null
        while (pos < end) {
            if (end - pos < 4) throw ArchiveFormatException("ZIP: truncated extra field header", sourceOffset + pos, diagnosticName)
            val id = readU16Le(bytes, pos)
            val length = readU16Le(bytes, pos + 2)
            val data = pos + 4
            if (length > end - data) throw ArchiveFormatException("ZIP: truncated extra field", sourceOffset + pos, diagnosticName)
            if (id == 0x7075 && length >= 5 && bytes[data].toInt() == 1) {
                val crc = nameCrc ?: Crc32.of(storedName).also { nameCrc = it }
                if (readU32Le(bytes, data + 1) == crc) {
                    val candidate = try {
                        bytes.decodeToString(data + 5, data + length, throwOnInvalidSequence = true)
                    } catch (_: CharacterCodingException) { null }
                    if (candidate != null) {
                        if (result != null && result != candidate) {
                            throw ArchiveFormatException("ZIP: conflicting Unicode Path extra fields", sourceOffset + pos, diagnosticName)
                        }
                        result = candidate
                    }
                }
            }
            pos = data + length
        }
        return result
    }
}
