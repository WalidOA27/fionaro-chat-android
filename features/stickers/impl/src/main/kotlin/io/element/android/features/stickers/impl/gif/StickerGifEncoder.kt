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
 * Every frame gets its own local color table computed by [GifQuantizer], and the animation loops
 * forever through the `NETSCAPE2.0` application extension (supported by Element Web, Element X
 * and iOS).
 *
 * Opaque frames after the first one only write the bounding rectangle which changed since the
 * previous encoded frame, with disposal method 1 ("do not dispose"). Frames which contain
 * transparency keep writing the full frame with disposal method 2.
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
    private val quantizer = GifQuantizer()
    private val framePixels = IntArray(width * height)
    private val frameIndices = ByteArray(width * height)
    private val fullRect = FrameRect(left = 0, top = 0, width = width, height = height)
    private val unchangedRect = FrameRect(left = 0, top = 0, width = 1, height = 1)
    private var previousFrame: IntArray? = null
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

        val hasTransparency = argbPixels.any { it ushr 24 < TRANSPARENT_ALPHA_THRESHOLD }
        if (hasTransparency) {
            // The frame is restored to the background before the next one, so the following
            // frame cannot be a rectangle relative to the current canvas.
            previousFrame = null
            writeFrame(argbPixels, fullRect, delayCentiseconds, hasTransparency = true)
            return
        }

        val previous = previousFrame
        if (previous == null) {
            writeFrame(argbPixels, fullRect, delayCentiseconds, hasTransparency = false)
            previousFrame = argbPixels.copyOf()
            return
        }

        val changed = changedRect(previous, argbPixels)
        if (changed == null) {
            // Keep the frame delay without rewriting a full image: a single unchanged pixel
            // leaves the canvas as it is.
            writeFrame(argbPixels, unchangedRect, delayCentiseconds, hasTransparency = false)
        } else {
            writeFrame(argbPixels, changed, delayCentiseconds, hasTransparency = false)
        }
        argbPixels.copyInto(previous)
    }

    fun finish(): ByteArray {
        if (!headerWritten) writeHeader()
        output.write(TRAILER)
        return output.toByteArray()
    }

    private fun writeFrame(argbPixels: IntArray, rect: FrameRect, delayCentiseconds: Int, hasTransparency: Boolean) {
        val palette = if (hasTransparency) {
            // Reserve the last index for transparency, quantizing only the visible pixels so
            // that a fully transparent background does not pollute the color table.
            var opaqueCount = 0
            forEachRectPixel(argbPixels, rect) { color ->
                if (color ushr 24 >= TRANSPARENT_ALPHA_THRESHOLD) {
                    framePixels[opaqueCount++] = color
                }
            }
            if (opaqueCount == 0) {
                quantizer.quantize(OPAQUE_BLACK_PIXEL, 1, MAX_COLORS - 1)
            } else {
                quantizer.quantize(framePixels, opaqueCount, MAX_COLORS - 1)
            }
        } else {
            var count = 0
            forEachRectPixel(argbPixels, rect) { color ->
                framePixels[count++] = color
            }
            quantizer.quantize(framePixels, count, MAX_COLORS)
        }

        var index = 0
        forEachRectPixel(argbPixels, rect) { color ->
            frameIndices[index] = if (hasTransparency && color ushr 24 < TRANSPARENT_ALPHA_THRESHOLD) {
                TRANSPARENT_INDEX.toByte()
            } else {
                palette.indexOf(color).toByte()
            }
            index += 1
        }

        val tableSize = colorTableSize(palette.colors.size, hasTransparency)
        writeGraphicsControlExtension(delayCentiseconds, hasTransparency)
        writeImageDescriptor(rect, tableSize)
        writeColorTable(palette, tableSize)
        GifLzwEncoder(pixels = frameIndices, initCodeSize = CODE_SIZE, pixelCount = index).encode(output)
    }

    private inline fun forEachRectPixel(argbPixels: IntArray, rect: FrameRect, action: (Int) -> Unit) {
        for (y in 0 until rect.height) {
            val row = (rect.top + y) * width + rect.left
            for (x in 0 until rect.width) {
                action(argbPixels[row + x])
            }
        }
    }

    /**
     * The smallest rectangle which differs between [previous] and [current], or `null` when the
     * two frames are identical.
     */
    private fun changedRect(previous: IntArray, current: IntArray): FrameRect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (previous[row + x] != current[row + x]) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < 0) null else FrameRect(left = left, top = top, width = right - left + 1, height = bottom - top + 1)
    }

    private fun colorTableSize(colorCount: Int, hasTransparency: Boolean): Int {
        if (hasTransparency) return MAX_COLORS
        var tableSize = 2
        while (tableSize < colorCount) tableSize = tableSize shl 1
        return tableSize
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

    private fun writeImageDescriptor(rect: FrameRect, tableSize: Int) {
        output.write(0x2C)
        writeShort(rect.left)
        writeShort(rect.top)
        writeShort(rect.width)
        writeShort(rect.height)
        // Local color table of tableSize entries, not interlaced.
        val bits = Integer.numberOfTrailingZeros(tableSize)
        output.write(0x80 or bits - 1)
    }

    private fun writeColorTable(palette: GifPalette, tableSize: Int) {
        for (index in 0 until tableSize) {
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

    private data class FrameRect(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

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
        val OPAQUE_BLACK_PIXEL = intArrayOf(OPAQUE_BLACK)
        val HEADER = "GIF89a".toByteArray(Charsets.US_ASCII)
        val LOOP_EXTENSION = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)
    }
}
