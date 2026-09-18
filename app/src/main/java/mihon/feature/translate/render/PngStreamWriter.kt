package mihon.feature.translate.render

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * Minimal streaming PNG (RGB, 8 bit) encoder: rows are written as they are produced, so a page
 * taller than what fits in one bitmap can be encoded band by band.
 */
class PngStreamWriter(
    output: OutputStream,
    private val width: Int,
    private val height: Int,
) : Closeable {

    private val out = DataOutputStream(output)
    private val idat = IdatStream()
    private val deflater = Deflater(6)
    private val zip = DeflaterOutputStream(idat, deflater, 64 * 1024)
    private var previous = ByteArray(width * 3)
    private var current = ByteArray(width * 3)
    private val filtered = Array(3) { ByteArray(width * 3) }
    private var rowsWritten = 0

    init {
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().apply {
            val d = DataOutputStream(this)
            d.writeInt(width)
            d.writeInt(height)
            d.writeByte(8) // bit depth
            d.writeByte(2) // color type RGB
            d.writeByte(0) // compression
            d.writeByte(0) // filter
            d.writeByte(0) // interlace
        }.toByteArray()
        writeChunk("IHDR", ihdr)
    }

    /** Writes [rowCount] rows of ARGB pixels, [width] per row, starting at [offset]. */
    fun writeRows(argb: IntArray, offset: Int, rowCount: Int) {
        require(rowsWritten + rowCount <= height) { "Too many rows" }
        for (row in 0 until rowCount) {
            val base = offset + row * width
            for (x in 0 until width) {
                val c = argb[base + x]
                current[x * 3] = (c shr 16).toByte()
                current[x * 3 + 1] = (c shr 8).toByte()
                current[x * 3 + 2] = c.toByte()
            }
            writeFilteredRow()
            val tmp = previous
            previous = current
            current = tmp
            rowsWritten++
        }
    }

    /** Picks the cheapest of None / Sub / Up filters for this row (minimum sum of absolute values). */
    private fun writeFilteredRow() {
        val n = width * 3
        val none = current
        val sub = filtered[1]
        val up = filtered[2]
        var costNone = 0L
        var costSub = 0L
        var costUp = 0L
        for (i in 0 until n) {
            val cur = current[i].toInt() and 0xFF
            val left = if (i >= 3) current[i - 3].toInt() and 0xFF else 0
            val above = if (rowsWritten > 0) previous[i].toInt() and 0xFF else 0
            val s = (cur - left) and 0xFF
            val u = (cur - above) and 0xFF
            sub[i] = s.toByte()
            up[i] = u.toByte()
            costNone += signedCost(cur)
            costSub += signedCost(s)
            costUp += signedCost(u)
        }
        when {
            costSub <= costNone && costSub <= costUp -> {
                zip.write(1)
                zip.write(sub, 0, n)
            }
            costUp <= costNone -> {
                zip.write(2)
                zip.write(up, 0, n)
            }
            else -> {
                zip.write(0)
                zip.write(none, 0, n)
            }
        }
    }

    private fun signedCost(v: Int): Int = abs(if (v > 127) v - 256 else v)

    override fun close() {
        check(rowsWritten == height) { "Expected $height rows, got $rowsWritten" }
        zip.finish()
        idat.flushChunk()
        deflater.end()
        writeChunk("IEND", ByteArray(0))
        out.flush()
    }

    private fun writeChunk(type: String, data: ByteArray, length: Int = data.size) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(length)
        out.write(typeBytes)
        out.write(data, 0, length)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data, 0, length)
        out.writeInt(crc.value.toInt())
    }

    /** Buffers compressed bytes and emits them as IDAT chunks of bounded size. */
    private inner class IdatStream : OutputStream() {
        private val buffer = ByteArray(256 * 1024)
        private var size = 0

        override fun write(b: Int) {
            if (size == buffer.size) flushChunk()
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (size == buffer.size) flushChunk()
                val n = minOf(remaining, buffer.size - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n
                o += n
                remaining -= n
            }
        }

        fun flushChunk() {
            if (size > 0) {
                writeChunk("IDAT", buffer, size)
                size = 0
            }
        }
    }
}
