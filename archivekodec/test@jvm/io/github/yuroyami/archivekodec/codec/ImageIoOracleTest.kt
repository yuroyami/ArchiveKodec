package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.lzw.Lzw
import io.github.yuroyami.archivekodec.codec.lzw.LzwOptions
import io.github.yuroyami.archivekodec.codec.packbits.PackBits
import io.github.yuroyami.archivekodec.codec.packbits.PackBitsOptions
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The JDK's GIF and TIFF plugins as an outside oracle: the LZW and PackBits decoders read what
 * ImageIO writes, and ImageIO reads what the encoders write.
 */
class ImageIoOracleTest {

    private val width = 211
    private val height = 157

    /** Smooth bands with noise, so the LZW table fills and clears several times. */
    private fun pixels(levels: Int, seed: Int): ByteArray {
        val random = Random(seed)
        return ByteArray(width * height) {
            val x = it % width
            val y = it / width
            (((x / 9 + y / 5) + if (random.nextInt(4) == 0) random.nextInt(levels) else 0) % levels).toByte()
        }
    }

    @Test
    fun theDecoderReadsTheGifThatImageIoWrites() {
        for (bits in listOf(2, 4, 8)) {
            val indices = pixels(1 shl bits, bits)
            val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, palette(bits))
            image.raster.setDataElements(0, 0, width, height, indices)
            val writer = ImageIO.getImageWritersByFormatName("gif").next()
            val param = writer.defaultWriteParam.apply { progressiveMode = ImageWriteParam.MODE_DISABLED }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            val (minimumCodeSize, data) = gifImageData(out.toByteArray())
            val options = LzwOptions.gif(minimumCodeSize).copy { expectedSize = indices.size.toLong() }
            assertContentEquals(indices.map { it.toInt() and 0xFF }, Lzw.decode(data, options).output.map { it.toInt() and 0xFF }, "$bits bits")
        }
    }

    @Test
    fun imageIoReadsTheGifThatTheEncoderWrites() {
        for (bits in listOf(2, 3, 8)) {
            val indices = pixels(1 shl bits, 10 + bits)
            val minimumCodeSize = maxOf(2, bits)
            val data = Lzw.encode(indices, LzwOptions.gif(minimumCodeSize))
            val gif = ByteArrayOutputStream()
            gif.write("GIF89a".encodeToByteArray())
            gif.write(le16(width)); gif.write(le16(height))
            gif.write(0xF0 or (bits - 1)); gif.write(0); gif.write(0)
            for (i in 0 until (1 shl bits)) repeat(3) { gif.write(i * 255 / ((1 shl bits) - 1)) }
            gif.write(0x2C); gif.write(le16(0)); gif.write(le16(0)); gif.write(le16(width)); gif.write(le16(height)); gif.write(0)
            gif.write(minimumCodeSize)
            var p = 0
            while (p < data.size) {
                val n = minOf(255, data.size - p)
                gif.write(n); gif.write(data, p, n)
                p += n
            }
            gif.write(0); gif.write(0x3B)
            val image = ImageIO.read(ByteArrayInputStream(gif.toByteArray()))
            val read = ByteArray(width * height)
            image.raster.getDataElements(0, 0, width, height, read)
            assertContentEquals(indices, read, "$bits bits")
        }
    }

    @Test
    fun theDecodersReadTheTiffStripsThatImageIoWrites() {
        val gray = pixels(256, 77)
        val image = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        image.raster.setDataElements(0, 0, width, height, gray)
        for (compression in listOf("LZW", "PackBits")) {
            val writer = ImageIO.getImageWritersByFormatName("tiff").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionType = compression
            }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(image, null, null), param)
            }
            val tiff = Tiff(out.toByteArray())
            assertEquals(if (compression == "LZW") 5 else 32773, tiff.tag(259)[0])
            assertEquals(1, tiff.tag(317).firstOrNull() ?: 1, "no predictor")
            val offsets = tiff.tag(273)
            val counts = tiff.tag(279)
            val rowsPerStrip = tiff.tag(278).firstOrNull() ?: height
            val decoded = ByteArrayOutputStream()
            for (s in offsets.indices) {
                val rows = minOf(rowsPerStrip, height - s * rowsPerStrip)
                val expected = (rows * width).toLong()
                val strip = if (compression == "LZW") {
                    Lzw.decode(tiff.bytes, offsets[s], counts[s], LzwOptions { expectedSize = expected }).output
                } else {
                    PackBits.decode(tiff.bytes, offsets[s], counts[s], PackBitsOptions { expectedSize = expected }).output
                }
                decoded.write(strip)
            }
            assertContentEquals(gray, decoded.toByteArray(), compression)
        }
    }

    @Test
    fun imageIoReadsTheTiffThatTheEncodersWrite() {
        val gray = pixels(256, 91)
        for ((compression, strip) in listOf(5 to Lzw.encode(gray), 32773 to PackBits.encode(gray))) {
            // Little-endian TIFF, one strip, eight tags.
            val tags = listOf(256 to width, 257 to height, 258 to 8, 259 to compression, 262 to 1, 273 to 0, 278 to height, 279 to strip.size)
            val ifdSize = 2 + tags.size * 12 + 4
            val stripOffset = 8 + ifdSize
            val buffer = ByteBuffer.allocate(stripOffset + strip.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("II".encodeToByteArray()).putShort(42).putInt(8).putShort(tags.size.toShort())
            for ((tag, value) in tags) {
                if (tag in setOf(258, 259, 262)) {
                    buffer.putShort(tag.toShort()).putShort(3).putInt(1).putShort(value.toShort()).putShort(0)
                } else {
                    buffer.putShort(tag.toShort()).putShort(4).putInt(1).putInt(if (tag == 273) stripOffset else value)
                }
            }
            buffer.putInt(0).put(strip)
            val image = ImageIO.read(ByteArrayInputStream(buffer.array()))
            val read = (image.raster.dataBuffer as DataBufferByte).data
            assertContentEquals(gray, read, "compression $compression")
        }
    }

    private fun palette(bits: Int): IndexColorModel {
        val n = 1 shl bits
        val v = ByteArray(n) { (it * 255 / (n - 1)).toByte() }
        return IndexColorModel(bits, n, v, v.reversedArray(), v)
    }

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    /** The minimum code size and the joined sub-blocks of the first image of a GIF. */
    private fun gifImageData(gif: ByteArray): Pair<Int, ByteArray> {
        var p = 13
        val flags = gif[10].toInt() and 0xFF
        if (flags and 0x80 != 0) p += 3 * (1 shl ((flags and 7) + 1))
        while (true) {
            when (gif[p].toInt() and 0xFF) {
                0x21 -> {
                    p += 2
                    while (gif[p].toInt() != 0) p += (gif[p].toInt() and 0xFF) + 1
                    p++
                }
                0x2C -> {
                    val local = gif[p + 9].toInt() and 0xFF
                    check(local and 0x40 == 0) { "interlaced" }
                    p += 10
                    if (local and 0x80 != 0) p += 3 * (1 shl ((local and 7) + 1))
                    val minimumCodeSize = gif[p++].toInt() and 0xFF
                    val data = ByteArrayOutputStream()
                    while (gif[p].toInt() != 0) {
                        val n = gif[p].toInt() and 0xFF
                        data.write(gif, p + 1, n)
                        p += n + 1
                    }
                    return minimumCodeSize to data.toByteArray()
                }
                else -> error("unexpected GIF block ${gif[p]}")
            }
        }
    }

    /** The first IFD of a TIFF, enough to find its strips. */
    private class Tiff(val bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        private val tags = HashMap<Int, List<Int>>()

        init {
            val ifd = buffer.getInt(4)
            val n = buffer.getShort(ifd).toInt() and 0xFFFF
            for (i in 0 until n) {
                val e = ifd + 2 + i * 12
                val tag = buffer.getShort(e).toInt() and 0xFFFF
                val type = buffer.getShort(e + 2).toInt()
                val count = buffer.getInt(e + 4)
                val size = if (type == 3) 2 else 4
                val at = if (count * size <= 4) e + 8 else buffer.getInt(e + 8)
                tags[tag] = List(count) { if (type == 3) buffer.getShort(at + it * 2).toInt() and 0xFFFF else buffer.getInt(at + it * 4) }
            }
        }

        fun tag(id: Int): List<Int> = tags[id] ?: emptyList()
    }
}
