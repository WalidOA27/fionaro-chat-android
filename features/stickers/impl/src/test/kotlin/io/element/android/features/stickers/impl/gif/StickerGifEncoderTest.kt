/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
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
            val red = index % 8 * 8
            val green = index / 8 % 8 * 8
            val blue = index / 64 % 4 * 8
            0xFF shl 24 or (red shl 16) or (green shl 8) or blue
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

        assertThrows(IllegalArgumentException::class.java) {
            encoder.addFrame(IntArray(3), delayCentiseconds = 8)
        }
    }

    private fun solidFrame(width: Int, height: Int, color: Int) = IntArray(width * height) { color }
}
