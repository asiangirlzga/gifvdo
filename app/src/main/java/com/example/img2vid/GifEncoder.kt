package com.example.img2vid

import java.io.OutputStream
import java.util.Arrays

/**
 * Minimal streaming GIF89a encoder. Frames are written straight to the stream,
 * so memory use does not grow with the number of frames (safe for 1000+ images).
 * Uses one global 256-colour RGB332 palette with ordered dithering.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val delayCs: Int,
) {
    private val indices = ByteArray(width * height)
    private val block = ByteArray(255)
    private var blockLen = 0
    private var cur = 0
    private var bits = 0
    private var codeSize = 9
    private var maxCode = 511
    private var next = 258
    private val hashKeys = IntArray(HASH_SIZE)
    private val hashVals = IntArray(HASH_SIZE)

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(width)
        writeShort(height)
        out.write(0xF7) // global colour table, 256 entries
        out.write(0)    // background
        out.write(0)    // aspect
        for (i in 0 until 256) {
            out.write(((i shr 5) and 7) * 255 / 7)
            out.write(((i shr 2) and 7) * 255 / 7)
            out.write((i and 3) * 255 / 3)
        }
        // Loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
    }

    fun addFrame(pixels: IntArray) {
        quantize(pixels)
        // Graphic control extension
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04) // dispose: leave in place, no transparency
        writeShort(delayCs)
        out.write(0); out.write(0)
        // Image descriptor
        out.write(0x2C)
        writeShort(0); writeShort(0); writeShort(width); writeShort(height)
        out.write(0)
        encodeLzw()
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun quantize(p: IntArray) {
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = p[i]
                val t = BAYER[((y and 3) shl 2) or (x and 3)]
                val r = (((px shr 16) and 255) + t * 2 + 1).coerceAtMost(255)
                val g = (((px shr 8) and 255) + t * 2 + 1).coerceAtMost(255)
                val b = ((px and 255) + t * 4 + 2).coerceAtMost(255)
                indices[i] = ((r and 0xE0) or ((g and 0xE0) shr 3) or (b shr 6)).toByte()
                i++
            }
        }
    }

    private fun encodeLzw() {
        out.write(8)
        resetTable()
        emit(CLEAR, false)
        var prefix = indices[0].toInt() and 255
        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 255
            val key = (prefix shl 8) or c
            var h = ((prefix shl 4) xor c) and HASH_MASK
            var found = -1
            while (true) {
                val k = hashKeys[h]
                if (k == -1) break
                if (k == key) { found = hashVals[h]; break }
                h = (h + 1) and HASH_MASK
            }
            if (found >= 0) {
                prefix = found
                continue
            }
            emit(prefix, true)
            if (next < 4096) {
                hashKeys[h] = key
                hashVals[h] = next++
            } else {
                emit(CLEAR, false)
                resetTable()
            }
            prefix = c
        }
        emit(prefix, true)
        emit(EOI, true)
        if (bits > 0) writeByte(cur and 255)
        cur = 0; bits = 0
        flushBlock()
        out.write(0)
    }

    private fun resetTable() {
        Arrays.fill(hashKeys, -1)
        codeSize = 9
        maxCode = 511
        next = 258
    }

    private fun emit(code: Int, checkGrow: Boolean) {
        cur = cur or (code shl bits)
        bits += codeSize
        while (bits >= 8) {
            writeByte(cur and 255)
            cur = cur ushr 8
            bits -= 8
        }
        if (checkGrow && next > maxCode) {
            if (codeSize < 12) {
                codeSize++
                maxCode = (1 shl codeSize) - 1
            } else {
                maxCode = 4096
            }
        }
    }

    private fun writeByte(b: Int) {
        block[blockLen++] = b.toByte()
        if (blockLen == 255) flushBlock()
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }

    private fun writeShort(v: Int) {
        out.write(v and 255)
        out.write((v shr 8) and 255)
    }

    private companion object {
        const val CLEAR = 256
        const val EOI = 257
        const val HASH_SIZE = 8192
        const val HASH_MASK = HASH_SIZE - 1
        val BAYER = intArrayOf(
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5,
        )
    }
}
