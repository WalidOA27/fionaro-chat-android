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
    fun `plan - tries 512 at 12 fps first, then smaller and slower steps`() {
        val steps = StickerGifEncodingPlan.steps

        assertThat(steps.map { it.longestSidePx }).containsExactly(512, 384, 320, 256).inOrder()
        assertThat(steps.map { it.framesPerSecond }).containsExactly(12, 10, 10, 8).inOrder()
    }

    @Test
    fun `plan - is capped at 6 seconds and 1 MiB`() {
        assertThat(StickerGifEncodingPlan.MAX_ANIMATION_DURATION_MS).isEqualTo(6_000)
        assertThat(StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES).isEqualTo(1024 * 1024)
    }

    @Test
    fun `frameCount - follows the step frame rate`() {
        val fast = StickerGifEncodingPlan.steps.first()

        assertThat(StickerGifEncodingPlan.frameCount(6_000, fast)).isEqualTo(72)
        assertThat(StickerGifEncodingPlan.frameCount(1_234, StickerGifEncodingPlan.steps.last())).isEqualTo(10)
        assertThat(StickerGifEncodingPlan.frameCount(100, fast)).isEqualTo(2)
        assertThat(StickerGifEncodingPlan.frameCount(0, fast)).isEqualTo(1)
    }

    @Test
    fun `frameDelayCentiseconds - follows the step frame rate`() {
        assertThat(StickerGifEncodingPlan.steps.map { StickerGifEncodingPlan.frameDelayCentiseconds(it) })
            .containsExactly(8, 10, 10, 12)
            .inOrder()
    }

    @Test
    fun `scaledSize - fits the longest side and keeps the aspect ratio`() {
        val step = StickerGifEncodingPlan.steps.first()

        assertThat(StickerGifEncodingPlan.scaledSize(1920, 1080, step)).isEqualTo(512 to 288)
        assertThat(StickerGifEncodingPlan.scaledSize(1080, 1920, step)).isEqualTo(288 to 512)
        assertThat(StickerGifEncodingPlan.scaledSize(100, 400, StickerGifEncodingPlan.steps[1])).isEqualTo(96 to 384)
    }

    @Test
    fun `scaledSize - never upscales a small source`() {
        assertThat(StickerGifEncodingPlan.scaledSize(200, 120, StickerGifEncodingPlan.steps.first())).isEqualTo(200 to 120)
        assertThat(StickerGifEncodingPlan.scaledSize(1, 300, StickerGifEncodingPlan.steps.last())).isEqualTo(1 to 256)
    }

    @Test
    fun `fitsInBudget - accepts exactly 1 MiB`() {
        val budget = StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES

        assertThat(StickerGifEncodingPlan.fitsInBudget(budget)).isTrue()
        assertThat(StickerGifEncodingPlan.fitsInBudget(budget + 1)).isFalse()
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
}
