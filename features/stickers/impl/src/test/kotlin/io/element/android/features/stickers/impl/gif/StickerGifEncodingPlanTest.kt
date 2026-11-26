/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StickerGifEncodingPlanTest {
    @Test
    fun `plan - tries 384 at 10 fps first, then smaller and slower steps`() {
        val steps = StickerGifEncodingPlan.steps

        assertThat(steps).containsExactly(
            GifEncodingStep(longestSidePx = 384, framesPerSecond = 10),
            GifEncodingStep(longestSidePx = 320, framesPerSecond = 10),
            GifEncodingStep(longestSidePx = 256, framesPerSecond = 10),
            GifEncodingStep(longestSidePx = 256, framesPerSecond = 8),
            GifEncodingStep(longestSidePx = 200, framesPerSecond = 8),
            GifEncodingStep(longestSidePx = 160, framesPerSecond = 6),
        ).inOrder()
    }

    @Test
    fun `plan - is capped at 6 seconds and 3 MiB`() {
        assertThat(StickerGifEncodingPlan.MAX_ANIMATION_DURATION_MS).isEqualTo(6_000)
        assertThat(StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES).isEqualTo(3 * 1024 * 1024)
    }

    @Test
    fun `frameCount - follows the step frame rate`() {
        val fastest = StickerGifEncodingPlan.steps.first()
        val slowest = StickerGifEncodingPlan.steps.last()

        assertThat(StickerGifEncodingPlan.frameCount(6_000, fastest)).isEqualTo(60)
        assertThat(StickerGifEncodingPlan.frameCount(6_000, slowest)).isEqualTo(36)
        assertThat(StickerGifEncodingPlan.frameCount(1_234, slowest)).isEqualTo(8)
        assertThat(StickerGifEncodingPlan.frameCount(100, fastest)).isEqualTo(1)
        assertThat(StickerGifEncodingPlan.frameCount(0, fastest)).isEqualTo(1)
    }

    @Test
    fun `frameDelayCentiseconds - follows the step frame rate`() {
        assertThat(StickerGifEncodingPlan.steps.map { StickerGifEncodingPlan.frameDelayCentiseconds(it) })
            .containsExactly(10, 10, 10, 12, 12, 16)
            .inOrder()
    }

    @Test
    fun `scaledSize - fits the longest side and keeps the aspect ratio`() {
        val first = StickerGifEncodingPlan.steps.first()

        assertThat(StickerGifEncodingPlan.scaledSize(1920, 1080, first)).isEqualTo(384 to 216)
        assertThat(StickerGifEncodingPlan.scaledSize(1080, 1920, first)).isEqualTo(216 to 384)
        assertThat(StickerGifEncodingPlan.scaledSize(100, 400, StickerGifEncodingPlan.steps[1])).isEqualTo(80 to 320)
    }

    @Test
    fun `scaledSize - never upscales a small source`() {
        assertThat(StickerGifEncodingPlan.scaledSize(200, 120, StickerGifEncodingPlan.steps.first())).isEqualTo(200 to 120)
        assertThat(StickerGifEncodingPlan.scaledSize(1, 300, StickerGifEncodingPlan.steps.last())).isEqualTo(1 to 160)
    }

    @Test
    fun `fitsInBudget - accepts exactly 3 MiB`() {
        val budget = StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES

        assertThat(StickerGifEncodingPlan.fitsInBudget(budget)).isTrue()
        assertThat(StickerGifEncodingPlan.fitsInBudget(budget + 1)).isFalse()
    }

    @Test
    fun `durationsToTry - shortens 6 seconds to 4 then 3 seconds from the same start`() {
        assertThat(StickerGifEncodingPlan.durationsToTry(6_000))
            .containsExactly(6_000L, 4_000L, 3_000L)
            .inOrder()
        assertThat(StickerGifEncodingPlan.durationsToTry(5_000))
            .containsExactly(5_000L, 4_000L, 3_000L)
            .inOrder()
        assertThat(StickerGifEncodingPlan.durationsToTry(3_500))
            .containsExactly(3_500L, 3_000L)
            .inOrder()
        assertThat(StickerGifEncodingPlan.durationsToTry(3_000)).containsExactly(3_000L)
        assertThat(StickerGifEncodingPlan.durationsToTry(2_000)).containsExactly(2_000L)
    }

    @Test
    fun `sampleFrameIndices - picks evenly spaced frames of a 10 fps sample`() {
        assertThat(StickerGifEncodingPlan.sampleFrameIndices(1_000, GifEncodingStep(384, 10)))
            .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
            .inOrder()
        assertThat(StickerGifEncodingPlan.sampleFrameIndices(1_000, GifEncodingStep(256, 8)))
            .containsExactly(0, 1, 3, 4, 5, 6, 8, 9)
            .inOrder()
        assertThat(StickerGifEncodingPlan.sampleFrameIndices(1_000, GifEncodingStep(160, 6)))
            .containsExactly(0, 2, 3, 5, 7, 8)
            .inOrder()
        assertThat(StickerGifEncodingPlan.sampleFrameIndices(500, GifEncodingStep(160, 6)))
            .containsExactly(0, 2, 3)
            .inOrder()
    }

    @Test
    fun `videoWindow - starts at the requested position and lasts at most 6 seconds`() {
        assertThat(StickerGifEncodingPlan.videoWindow(startMs = 0, videoDurationMs = 2_500)).isEqualTo(0L to 2_500L)
        assertThat(StickerGifEncodingPlan.videoWindow(startMs = 0, videoDurationMs = 20_000)).isEqualTo(0L to 6_000L)
        assertThat(StickerGifEncodingPlan.videoWindow(startMs = 18_000, videoDurationMs = 20_000)).isEqualTo(18_000L to 2_000L)
        assertThat(StickerGifEncodingPlan.videoWindow(startMs = 20_000, videoDurationMs = 20_000)).isEqualTo(20_000L to 1L)
    }

    @Test
    fun `audioWindow - starts at the requested position and lasts at most 10 seconds`() {
        assertThat(StickerGifEncodingPlan.audioWindow(startMs = 0, videoDurationMs = 4_000)).isEqualTo(0L to 4_000L)
        assertThat(StickerGifEncodingPlan.audioWindow(startMs = 2_000, videoDurationMs = 30_000)).isEqualTo(2_000L to 10_000L)
        assertThat(StickerGifEncodingPlan.audioWindow(startMs = 29_500, videoDurationMs = 30_000)).isEqualTo(29_500L to 500L)
        assertThat(StickerGifEncodingPlan.audioWindow(startMs = 30_000, videoDurationMs = 30_000)).isEqualTo(30_000L to 1L)
    }

    @Test
    fun `videoDuration - exposes the maximum start position for a 6 second window`() {
        assertThat(StickerGifEncodingPlan.maxStartMs(20_000)).isEqualTo(14_000)
        assertThat(StickerGifEncodingPlan.maxStartMs(6_000)).isEqualTo(0)
        assertThat(StickerGifEncodingPlan.maxStartMs(1_000)).isEqualTo(0)
    }

    @Test
    fun `predictFullSize - extrapolates the sample with a safety factor`() {
        assertThat(GifSizePredictor.predictFullSize(sampleBytes = 100_000, sampleFrames = 10, totalFrames = 60))
            .isEqualTo(690_000L)
    }

    @Test
    fun `predictFullSize - keeps the exact size when the sample already covers everything`() {
        assertThat(GifSizePredictor.predictFullSize(sampleBytes = 100_000, sampleFrames = 10, totalFrames = 6))
            .isEqualTo(100_000L)
        assertThat(GifSizePredictor.predictFullSize(sampleBytes = 0, sampleFrames = 10, totalFrames = 60)).isEqualTo(0L)
    }

    @Test
    fun `selectStep - keeps the first step whose full size is predicted to fit`() {
        val samples = listOf(
            GifEncodingSample(GifEncodingStep(384, 10), bytes = 1_000_000, frameCount = 10),
            GifEncodingSample(GifEncodingStep(320, 10), bytes = 100_000, frameCount = 10),
            GifEncodingSample(GifEncodingStep(160, 6), bytes = 10_000, frameCount = 6),
        )

        val selected = GifSizePredictor.selectStep(samples) { step -> StickerGifEncodingPlan.frameCount(6_000, step) }

        // 1 MB * 60 / 10 * 1.15 = 6.9 MB, too large. 100 KB * 6 * 1.15 = 690 KB, fits.
        assertThat(selected).isEqualTo(GifEncodingStep(320, 10))
    }

    @Test
    fun `selectStep - returns null when every step is predicted too large`() {
        val samples = StickerGifEncodingPlan.steps.map { step ->
            GifEncodingSample(step, bytes = 2_000_000, frameCount = 10)
        }

        val selected = GifSizePredictor.selectStep(samples) { step -> StickerGifEncodingPlan.frameCount(6_000, step) }

        assertThat(selected).isNull()
    }
}
