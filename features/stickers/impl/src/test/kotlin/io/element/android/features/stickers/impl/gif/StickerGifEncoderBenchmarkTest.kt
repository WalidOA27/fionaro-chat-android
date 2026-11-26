/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

/**
 * Encodes a realistic amount of content through the real encoder to catch budget regressions
 * and to keep an eye on the encoding cost. It does not assert on timing.
 */
class StickerGifEncoderBenchmarkTest {
    @Test
    fun `encode - 60 noisy frames of moving content fit in the sticker budget`() {
        val width = 320
        val height = 180
        val frames = List(FRAME_COUNT) { frame -> noisyFrame(width, height, frame) }

        val samples = StickerGifEncodingPlan.steps.map { step ->
            val (scaledWidth, scaledHeight) = StickerGifEncodingPlan.scaledSize(width, height, step)
            val indices = StickerGifEncodingPlan.sampleFrameIndices(StickerGifEncodingPlan.SAMPLE_DURATION_MS, step)
            val encoder = StickerGifEncoder(scaledWidth, scaledHeight)
            indices.forEach { index ->
                encoder.addFrame(
                    scale(frames[index], width, height, scaledWidth, scaledHeight),
                    StickerGifEncodingPlan.frameDelayCentiseconds(step),
                )
            }
            GifEncodingSample(step, bytes = encoder.encodedSize, frameCount = indices.size)
        }
        val selected = GifSizePredictor.selectStep(samples) { step ->
            StickerGifEncodingPlan.frameCount(DURATION_MS, step)
        } ?: StickerGifEncodingPlan.steps.last()

        val (scaledWidth, scaledHeight) = StickerGifEncodingPlan.scaledSize(width, height, selected)
        val encoder = StickerGifEncoder(scaledWidth, scaledHeight)
        frames.forEach { frame ->
            encoder.addFrame(
                scale(frame, width, height, scaledWidth, scaledHeight),
                StickerGifEncodingPlan.frameDelayCentiseconds(selected),
            )
        }
        val bytes = encoder.finish()

        assertThat(bytes.size).isAtMost(StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES)
        assertThat(String(bytes.copyOfRange(0, 6), Charsets.US_ASCII)).isEqualTo("GIF89a")
    }

    private fun noisyFrame(width: Int, height: Int, frame: Int): IntArray {
        val random = Random(frame.toLong() * 31 + 7)
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            // A moving bright square plus per-pixel noise, so consecutive frames differ almost everywhere.
            val moving = if ((x + frame * 3) % 64 < 20 && (y + frame * 2) % 48 < 16) 0xFF else 0
            val noise = random.nextInt(0x40)
            val red = moving or x * 255 / width and 0xFF
            val green = y * 255 / height xor noise and 0xFF
            val blue = noise * 4 and 0xFF
            0xFF shl 24 or (red shl 16) or (green shl 8) or blue
        }
    }

    private fun scale(source: IntArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): IntArray {
        if (sourceWidth == width && sourceHeight == height) return source.copyOf()
        return IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            source[y * sourceHeight / height * sourceWidth + x * sourceWidth / width]
        }
    }

    private companion object {
        const val FRAME_COUNT = 60
        const val DURATION_MS = 6_000L
    }
}
