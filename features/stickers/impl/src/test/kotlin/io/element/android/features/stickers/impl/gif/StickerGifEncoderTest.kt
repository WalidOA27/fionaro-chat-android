/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StickerGifEncoderTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `encode - writes a GIF89a header with the loop extension and a trailer`() {
        val encoder = StickerGifEncoder(width = 4, height = 4)
        encoder.addFrame(solidFrame(4, 4, red), delayCentiseconds = 8)

        val gif = encoder.finish()
        val parsed = ParsedGif.parse(gif)

        assertThat(String(gif.copyOfRange(0, 6), Charsets.US_ASCII)).isEqualTo("GIF89a")
        assertThat(gif.last().toInt() and 0xFF).isEqualTo(0x3B)
        assertThat(parsed.width).isEqualTo(4)
        assertThat(parsed.height).isEqualTo(4)
        assertThat(parsed.loopCount).isEqualTo(0)
        assertThat(parsed.frames).hasSize(1)
        assertThat(parsed.frames.single().delayCentiseconds).isEqualTo(8)
    }

    @Test
    fun `encode - writes one image per frame with its own delay`() {
        val encoder = StickerGifEncoder(width = 4, height = 4)
        encoder.addFrame(solidFrame(4, 4, red), delayCentiseconds = 8)
        encoder.addFrame(solidFrame(4, 4, blue), delayCentiseconds = 10)
        encoder.addFrame(solidFrame(4, 4, green), delayCentiseconds = 12)

        val parsed = ParsedGif.parse(encoder.finish())

        assertThat(parsed.frames).hasSize(3)
        assertThat(parsed.frames.map { it.delayCentiseconds }).containsExactly(8, 10, 12).inOrder()
        assertThat(parsed.frames.map { it.pixelCount }).containsExactly(16, 16, 16)
    }

    @Test
    fun `encode - compresses each frame losslessly`() {
        val width = 8
        val height = 8
        // Two well separated colors, so the palette must keep them exactly.
        val pixels = IntArray(width * height) { index ->
            if ((index / width + index % width) % 2 == 0) red else blue
        }
        val encoder = StickerGifEncoder(width = width, height = height)

        encoder.addFrame(pixels, delayCentiseconds = 8)
        val parsed = ParsedGif.parse(encoder.finish())

        val frame = parsed.frames.single()
        assertThat(frame.palette.toList()).containsAtLeast(red, blue)
        val decodedPixels = frame.decodedIndices.map { frame.palette[it] }
        assertThat(decodedPixels).isEqualTo(pixels.toList())
    }

    @Test
    fun `encode - keeps the palette exact for up to 256 distinct colors`() {
        val width = 16
        val height = 16
        val encoder = StickerGifEncoder(width = width, height = height)
        // 8 * 8 * 4 colors, each channel value on a 5 bits boundary so quantization keeps it exact.
        val distinctPixels = IntArray(width * height) { index ->
            val red = ((index % 8) * 8) shl 16
            val green = (((index / 8) % 8) * 8) shl 8
            val blue = ((index / 64) % 4) * 8
            (0xFF shl 24) or red or green or blue
        }

        encoder.addFrame(distinctPixels, delayCentiseconds = 8)
        val frame = ParsedGif.parse(encoder.finish()).frames.single()

        assertThat(frame.palette.toSet()).hasSize(256)
        assertThat(frame.decodedIndices.map { frame.palette[it] }).isEqualTo(distinctPixels.toList())
        assertThat(frame.palette.toList()).doesNotContain(white)
    }

    @Test
    fun `encode - an empty animation is only a header and a trailer`() {
        val gif = StickerGifEncoder(width = 2, height = 2).finish()

        val parsed = ParsedGif.parse(gif)
        assertThat(parsed.frames).isEmpty()
        assertThat(parsed.loopCount).isEqualTo(0)
    }

    @Test
    fun `encode - exposes the current encoded size before finishing`() {
        val encoder = StickerGifEncoder(width = 8, height = 8)
        val emptySize = encoder.encodedSize
        encoder.addFrame(solidFrame(8, 8, red), delayCentiseconds = 8)

        assertThat(encoder.encodedSize).isGreaterThan(emptySize)
    }

    @Test
    fun `encode - rejects a frame with the wrong pixel count`() {
        val encoder = StickerGifEncoder(width = 4, height = 4)

        val result = runCatching { encoder.addFrame(IntArray(3), delayCentiseconds = 8) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun solidFrame(width: Int, height: Int, color: Int) = IntArray(width * height) { color }
}

private class ParsedGif(
    val width: Int,
    val height: Int,
    val loopCount: Int?,
    val frames: List<ParsedFrame>,
) {
    companion object {
        fun parse(bytes: ByteArray): ParsedGif {
            var index = 6 // header
            val width = bytes.uint16(index)
            val height = bytes.uint16(index + 2)
            index += 7 // logical screen descriptor
            var loopCount: Int? = null
            var pendingDelay = 0
            val frames = mutableListOf<ParsedFrame>()
            while (index < bytes.size) {
                when (val block = bytes[index].toInt() and 0xFF) {
                    0x3B -> break
                    0x21 -> {
                        val label = bytes[index + 1].toInt() and 0xFF
                        index += 2
                        if (label == 0xF9) {
                            val size = bytes[index].toInt() and 0xFF
                            val data = bytes.copyOfRange(index + 1, index + 1 + size)
                            pendingDelay = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                            index += 1 + size
                            index = bytes.skipSubBlocks(index)
                        } else {
                            if (label == 0xFF) {
                                val size = bytes[index].toInt() and 0xFF
                                val app = String(bytes.copyOfRange(index + 1, index + 1 + size), Charsets.US_ASCII)
                                index += 1 + size
                                if (app == "NETSCAPE2.0") {
                                    val subSize = bytes[index].toInt() and 0xFF
                                    val data = bytes.copyOfRange(index + 1, index + 1 + subSize)
                                    if (data.size >= 3 && data[0].toInt() == 1) {
                                        loopCount = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
                                    }
                                }
                            }
                            index = bytes.skipSubBlocks(index)
                        }
                    }
                    0x2C -> {
                        val left = bytes.uint16(index + 1)
                        val top = bytes.uint16(index + 3)
                        val frameWidth = bytes.uint16(index + 5)
                        val frameHeight = bytes.uint16(index + 7)
                        val packed = bytes[index + 9].toInt() and 0xFF
                        index += 10
                        var palette = IntArray(0)
                        if (packed and 0x80 != 0) {
                            val colorCount = 1 shl ((packed and 0x07) + 1)
                            palette = IntArray(colorCount) { colorIndex ->
                                val offset = index + colorIndex * 3
                                val r = bytes[offset].toInt() and 0xFF
                                val g = bytes[offset + 1].toInt() and 0xFF
                                val b = bytes[offset + 2].toInt() and 0xFF
                                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            index += colorCount * 3
                        }
                        val minCodeSize = bytes[index].toInt() and 0xFF
                        index += 1
                        val (data, nextIndex) = bytes.readSubBlocks(index)
                        index = nextIndex
                        val decoded = LzwDecoder.decode(minCodeSize, data)
                        frames.add(
                            ParsedFrame(
                                left = left,
                                top = top,
                                width = frameWidth,
                                height = frameHeight,
                                palette = palette,
                                decodedIndices = decoded,
                                delayCentiseconds = pendingDelay,
                            )
                        )
                    }
                    else -> error("Unexpected GIF block 0x${block.toString(16)} at $index")
                }
            }
            return ParsedGif(width, height, loopCount, frames)
        }

        private fun ByteArray.uint16(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

        private fun ByteArray.skipSubBlocks(start: Int): Int {
            var index = start
            while (true) {
                val size = this[index].toInt() and 0xFF
                index += 1
                if (size == 0) return index
                index += size
            }
        }

        private fun ByteArray.readSubBlocks(start: Int): Pair<ByteArray, Int> {
            var index = start
            val output = ArrayList<Byte>()
            while (true) {
                val size = this[index].toInt() and 0xFF
                index += 1
                if (size == 0) break
                for (i in 0 until size) output.add(this[index + i])
                index += size
            }
            return output.toByteArray() to index
        }
    }
}

private data class ParsedFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val palette: IntArray,
    val decodedIndices: List<Int>,
    val delayCentiseconds: Int,
) {
    val pixelCount: Int get() = decodedIndices.size
}

private object LzwDecoder {
    fun decode(minCodeSize: Int, data: ByteArray): List<Int> {
        val clearCode = 1 shl minCodeSize
        val endCode = clearCode + 1
        var codeSize = minCodeSize + 1
        var dictionary = initialDictionary(clearCode, endCode)
        var previous: IntArray? = null
        val output = mutableListOf<Int>()
        var bitBuffer = 0
        var bitCount = 0
        var byteIndex = 0
        while (true) {
            while (bitCount < codeSize) {
                if (byteIndex >= data.size) return output
                bitBuffer = bitBuffer or ((data[byteIndex].toInt() and 0xFF) shl bitCount)
                bitCount += 8
                byteIndex += 1
            }
            val code = bitBuffer and ((1 shl codeSize) - 1)
            bitBuffer = bitBuffer ushr codeSize
            bitCount -= codeSize

            when {
                code == clearCode -> {
                    dictionary = initialDictionary(clearCode, endCode)
                    codeSize = minCodeSize + 1
                    previous = null
                }
                code == endCode -> return output
                else -> {
                    val entry = when {
                        code < dictionary.size -> dictionary[code]
                        previous != null && code == dictionary.size -> previous + previous[0]
                        else -> error("Invalid LZW code $code")
                    }
                    output.addAll(entry.toList())
                    if (previous != null && dictionary.size < 4096) {
                        dictionary.add(previous + entry[0])
                        if (dictionary.size == (1 shl codeSize) && codeSize < 12) {
                            codeSize += 1
                        }
                    }
                    previous = entry
                }
            }
        }
    }

    private fun initialDictionary(clearCode: Int, endCode: Int): MutableList<IntArray> {
        val dictionary = MutableList(clearCode + 2) { intArrayOf(it) }
        dictionary[clearCode] = intArrayOf()
        dictionary[endCode] = intArrayOf()
        return dictionary
    }
}
