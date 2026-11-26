/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import java.io.OutputStream

/**
 * GIF LZW compressor.
 *
 * This is a Kotlin port of the public domain `LZWEncoder` class shipped with
 * "AnimatedGifEncoder" by Kevin Weiner (FM Software), itself derived from GIFCOMPR
 * by Spencer Thomas and Jim McKie. Keeping the original hashing implementation avoids
 * the off-by-one code size bugs that plague hand written GIF compressors.
 */
internal class GifLzwEncoder(
    private val pixels: ByteArray,
    private val initCodeSize: Int,
    private val pixelCount: Int = pixels.size,
) {
    private val accum = ByteArray(256)
    private var accumulatedCount = 0
    private var currentAccum = 0
    private var currentBits = 0

    private var initialBits = 0
    private var initialBitsField = 0
    private var codeSize = 0
    private var maxCode = 0
    private var freeEntry = 0
    private var clearFlag = false
    private var clearCode = 0
    private var endOfInformationCode = 0

    private val hashTable = IntArray(HASH_SIZE)
    private val codeTable = IntArray(HASH_SIZE)
    private var remaining = 0
    private var currentPixel = 0

    fun encode(output: OutputStream) {
        // Initial code size byte.
        output.write(initCodeSize)
        remaining = pixelCount
        currentPixel = 0
        compress(initCodeSize + 1, output)
        // Block terminator.
        output.write(0)
    }

    // The nested continue/break mirror the original C implementation, splitting them would
    // make the compressor much harder to compare with the reference.
    @Suppress("LoopWithTooManyJumpStatements")
    private fun compress(initialBits: Int, output: OutputStream) {
        initialBitsField = initialBits
        clearFlag = false
        codeSize = initialBits
        maxCode = maxCode(codeSize)
        clearCode = 1 shl initialBits - 1
        endOfInformationCode = clearCode + 1
        freeEntry = clearCode + 2
        accumulatedCount = 0

        var entry = nextPixel()
        var hashShift = 0
        var probe = HASH_SIZE
        while (probe < 65_536) {
            hashShift += 1
            probe *= 2
        }
        hashShift = 8 - hashShift
        clearHash()
        outputCode(clearCode, output)

        var current: Int
        outer@ while (true) {
            current = nextPixel()
            if (current == END_OF_PIXELS) break
            val key = (current shl MAX_BITS) + entry
            var index = current shl hashShift xor entry
            if (hashTable[index] == key) {
                entry = codeTable[index]
                continue
            } else if (hashTable[index] >= 0) {
                var displacement = HASH_SIZE - index
                if (index == 0) displacement = 1
                do {
                    index -= displacement
                    if (index < 0) index += HASH_SIZE
                    if (hashTable[index] == key) {
                        entry = codeTable[index]
                        continue@outer
                    }
                } while (hashTable[index] >= 0)
            }
            outputCode(entry, output)
            entry = current
            if (freeEntry < MAX_MAX_CODE) {
                codeTable[index] = freeEntry
                freeEntry += 1
                hashTable[index] = key
            } else {
                clearBlock(output)
            }
        }
        outputCode(entry, output)
        outputCode(endOfInformationCode, output)
    }

    private fun outputCode(code: Int, output: OutputStream) {
        if (currentBits > 0) {
            currentAccum = currentAccum or (code shl currentBits)
        } else {
            currentAccum = code
        }
        currentBits += codeSize
        while (currentBits >= 8) {
            writeByte(currentAccum and 0xFF, output)
            currentAccum = currentAccum ushr 8
            currentBits -= 8
        }
        if (freeEntry > maxCode || clearFlag) {
            if (clearFlag) {
                codeSize = initialBitsField
                maxCode = maxCode(codeSize)
                clearFlag = false
            } else {
                codeSize += 1
                maxCode = if (codeSize == MAX_BITS) MAX_MAX_CODE else maxCode(codeSize)
            }
        }
        if (code == endOfInformationCode) {
            flushBytes(output)
        }
    }

    private fun clearBlock(output: OutputStream) {
        clearHash()
        freeEntry = clearCode + 2
        clearFlag = true
        outputCode(clearCode, output)
    }

    private fun clearHash() {
        hashTable.fill(-1)
    }

    private fun nextPixel(): Int {
        if (remaining == 0) return END_OF_PIXELS
        remaining -= 1
        val pixel = pixels[currentPixel].toInt()
        currentPixel += 1
        return pixel and 0xFF
    }

    private fun writeByte(value: Int, output: OutputStream) {
        accum[accumulatedCount] = value.toByte()
        accumulatedCount += 1
        if (accumulatedCount >= 254) {
            flushBytes(output)
        }
    }

    private fun flushBytes(output: OutputStream) {
        if (accumulatedCount > 0) {
            output.write(accumulatedCount)
            output.write(accum, 0, accumulatedCount)
            accumulatedCount = 0
        }
    }

    private companion object {
        const val MAX_BITS = 12
        const val MAX_MAX_CODE = 1 shl MAX_BITS
        const val HASH_SIZE = 5003
        const val END_OF_PIXELS = -1

        fun maxCode(bits: Int): Int = (1 shl bits) - 1
    }
}
