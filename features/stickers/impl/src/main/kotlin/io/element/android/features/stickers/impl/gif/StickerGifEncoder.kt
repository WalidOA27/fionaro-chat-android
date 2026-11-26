/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import java.io.ByteArrayOutputStream

/**
 * Small animated GIF89a encoder used for animated stickers.
 *
 * Every frame gets its own 256 color local color table computed by [GifQuantizer], and the
 * animation loops forever through the `NETSCAPE2.0` application extension (supported by
 * Element Web, Element X and iOS).
 *
 * The GIF structure and the LZW compressor follow the public domain "AnimatedGifEncoder",
 * "NeuQuant" and "LZWEncoder" classes by Kevin Weiner (FM Software).
 */
internal class StickerGifEncoder(
    private val width: Int,
    private val height: Int,
) {
    init {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            "Invalid GIF size ${width}x$height"
        }
    }

    private val output = ByteArrayOutputStream()
    private var headerWritten = false

    /**
     * The number of bytes written so far. Allows aborting an encoding as soon as it overflows
     * the sticker budget, without waiting for the remaining frames.
     */
    val encodedSize: Int get() = output.size()

    fun addFrame(argbPixels: IntArray, delayCentiseconds: Int) {
        require(argbPixels.size == width * height) {
            "Expected ${width * height} pixels, got ${argbPixels.size}"
        }
        require(delayCentiseconds >= 0) { "Invalid frame delay $delayCentiseconds" }
        if (!headerWritten) writeHeader()

        val hasTransparency = argbPixels.any { (it ushr 24) < TRANSPARENT_ALPHA_THRESHOLD }
        val palette = if (hasTransparency) {
            // Reserve the last index for transparency, quantizing only the visible pixels so
            // that a fully transparent background does not pollute the color table.
            val opaquePixels = argbPixels.filter { (it ushr 24) >= TRANSPARENT_ALPHA_THRESHOLD }.toIntArray()
            val colorsToQuantize = if (opaquePixels.isEmpty()) intArrayOf(OPAQUE_BLACK) else opaquePixels
            GifQuantizer.quantize(colorsToQuantize, MAX_COLORS - 1)
        } else {
            GifQuantizer.quantize(argbPixels, MAX_COLORS)
        }
        val indices = ByteArray(argbPixels.size) { index ->
            val color = argbPixels[index]
            if (hasTransparency && (color ushr 24) < TRANSPARENT_ALPHA_THRESHOLD) {
                TRANSPARENT_INDEX.toByte()
            } else {
                palette.indexOf(color).toByte()
            }
        }

        writeGraphicsControlExtension(delayCentiseconds, hasTransparency)
        writeImageDescriptor()
        writeColorTable(palette)
        GifLzwEncoder(pixels = indices, initCodeSize = CODE_SIZE).encode(output)
    }

    fun finish(): ByteArray {
        if (!headerWritten) writeHeader()
        output.write(TRAILER)
        return output.toByteArray()
    }

    private fun writeHeader() {
        headerWritten = true
        output.write(HEADER, 0, HEADER.size)
        writeShort(width)
        writeShort(height)
        // No global color table, 8 bits per primary color, no sort flag, no global table size.
        output.write(0x70)
        output.write(0x00) // background color index
        output.write(0x00) // pixel aspect ratio
        // NETSCAPE2.0 loop extension, 0 means "forever".
        output.write(0x21)
        output.write(0xFF)
        output.write(0x0B)
        output.write(LOOP_EXTENSION, 0, LOOP_EXTENSION.size)
        output.write(0x03)
        output.write(0x01)
        writeShort(LOOP_FOREVER)
        output.write(0x00)
    }

    private fun writeGraphicsControlExtension(delayCentiseconds: Int, hasTransparency: Boolean) {
        output.write(0x21)
        output.write(0xF9)
        output.write(0x04)
        // Disposal method 2 ("restore to background") for transparent frames so that the next
        // frame does not keep the previous one behind its transparent pixels, 1 otherwise.
        // The transparency flag is the lowest bit of the packed field.
        val packed = if (hasTransparency) DISPOSAL_RESTORE_TO_BACKGROUND or TRANSPARENCY_FLAG else DISPOSAL_DO_NOT_DISPOSE
        output.write(packed)
        writeShort(delayCentiseconds)
        output.write(if (hasTransparency) TRANSPARENT_INDEX else 0x00) // transparent color index
        output.write(0x00) // block terminator
    }

    private fun writeImageDescriptor() {
        output.write(0x2C)
        writeShort(0) // left
        writeShort(0) // top
        writeShort(width)
        writeShort(height)
        // Local color table, 256 entries, not interlaced.
        output.write(0x87)
    }

    private fun writeColorTable(palette: GifPalette) {
        for (index in 0 until MAX_COLORS) {
            val color = palette.colors.getOrElse(index) { 0 }
            output.write(color shr 16 and 0xFF)
            output.write(color shr 8 and 0xFF)
            output.write(color and 0xFF)
        }
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xFF)
        output.write(value shr 8 and 0xFF)
    }

    private companion object {
        const val MAX_DIMENSION = 4096
        const val MAX_COLORS = 256
        const val TRANSPARENT_INDEX = MAX_COLORS - 1
        const val TRANSPARENT_ALPHA_THRESHOLD = 128
        const val OPAQUE_BLACK = 0xFF000000.toInt()
        const val DISPOSAL_DO_NOT_DISPOSE = 0x04 // disposal method 1, shifted by 2
        const val DISPOSAL_RESTORE_TO_BACKGROUND = 0x08 // disposal method 2, shifted by 2
        const val TRANSPARENCY_FLAG = 0x01
        const val CODE_SIZE = 8
        const val LOOP_FOREVER = 0
        const val TRAILER = 0x3B
        val HEADER = "GIF89a".toByteArray(Charsets.US_ASCII)
        val LOOP_EXTENSION = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
    }
}
