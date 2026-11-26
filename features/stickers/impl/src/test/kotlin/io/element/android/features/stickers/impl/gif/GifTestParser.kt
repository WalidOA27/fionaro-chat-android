/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

internal data class ParsedGif(
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
                            pendingDelay = data[1].toInt() and 0xFF or (data[2].toInt() and 0xFF shl 8)
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
                                        loopCount = data[1].toInt() and 0xFF or (data[2].toInt() and 0xFF shl 8)
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
                            val colorCount = 1 shl (packed and 0x07) + 1
                            palette = IntArray(colorCount) { colorIndex ->
                                val offset = index + colorIndex * 3
                                val r = bytes[offset].toInt() and 0xFF
                                val g = bytes[offset + 1].toInt() and 0xFF
                                val b = bytes[offset + 2].toInt() and 0xFF
                                0xFF shl 24 or (r shl 16) or (g shl 8) or b
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
            this[offset].toInt() and 0xFF or (this[offset + 1].toInt() and 0xFF shl 8)

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

internal data class ParsedFrame(
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

internal object LzwDecoder {
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
                bitBuffer = bitBuffer or (data[byteIndex].toInt() and 0xFF shl bitCount)
                bitCount += 8
                byteIndex += 1
            }
            val code = bitBuffer and (1 shl codeSize) - 1
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
                        if (dictionary.size == 1 shl codeSize && codeSize < 12) {
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
