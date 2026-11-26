/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import com.google.common.truth.Truth.assertThat
import io.element.android.features.stickers.impl.gif.GifEncodingSample
import io.element.android.features.stickers.impl.gif.GifEncodingStep
import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StickerAnimationBudgetTest {
    private val budget = StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES
    private val steps = StickerGifEncodingPlan.steps

    @Test
    fun `encode - uses the step predicted to fit in a single full pass`() = runTest {
        val source = FakeAnimationSource(sampleBytes = { 1_000 }, encodeBytes = { _, _ -> 1_000 })
        val progress = mutableListOf<Float>()

        val result = encodeWithinBudget(source, durationMs = 6_000, onProgress = progress::add)

        assertThat(result).isNotNull()
        assertThat(source.encodes).containsExactly(steps.first() to 6_000L)
        assertThat(progress).isInOrder()
        assertThat(progress.last()).isEqualTo(1f)
    }

    @Test
    fun `encode - keeps the exact sample size when it already covers the whole animation`() = runTest {
        val source = FakeAnimationSource(
            sampleBytes = { budget },
            sampleFrames = { step -> StickerGifEncodingPlan.frameCount(6_000, step) },
            encodeBytes = { _, _ -> 1_000 },
        )

        val result = encodeWithinBudget(source, durationMs = 6_000)

        assertThat(result).isNotNull()
        assertThat(source.encodes).containsExactly(steps.first() to 6_000L)
    }

    @Test
    fun `encode - retries the next step once when the full encode overflows`() = runTest {
        val source = FakeAnimationSource(
            sampleBytes = { step -> if (step == steps[0]) 2_000_000 else 1_000 },
            encodeBytes = { step, _ -> if (step == steps[0] || step == steps[1]) budget + 1 else 1_000 },
        )

        val result = encodeWithinBudget(source, durationMs = 6_000)

        assertThat(result).isNotNull()
        assertThat(source.encodes).containsExactly(
            steps[1] to 6_000L,
            steps[2] to 6_000L,
        ).inOrder()
    }

    @Test
    fun `encode - shortens the animation with the smallest step after the retry`() = runTest {
        val source = FakeAnimationSource(
            sampleBytes = { 2_000_000 },
            encodeBytes = { _, duration -> if (duration == 4_000L) 1_000 else budget + 1 },
        )

        val result = encodeWithinBudget(source, durationMs = 6_000)

        assertThat(result).isNotNull()
        assertThat(source.encodes).containsExactly(
            steps.last() to 6_000L,
            steps.last() to 4_000L,
        ).inOrder()
    }

    @Test
    fun `encode - tries 4 then 3 seconds before giving up`() = runTest {
        val source = FakeAnimationSource(
            sampleBytes = { 2_000_000 },
            encodeBytes = { _, duration -> if (duration == 3_000L) 1_000 else budget + 1 },
        )

        val result = encodeWithinBudget(source, durationMs = 6_000)

        assertThat(result).isNotNull()
        assertThat(source.encodes).containsExactly(
            steps.last() to 6_000L,
            steps.last() to 4_000L,
            steps.last() to 3_000L,
        ).inOrder()
    }

    @Test
    fun `encode - returns null when every attempt overflows`() = runTest {
        val source = FakeAnimationSource(sampleBytes = { 2_000_000 }, encodeBytes = { _, _ -> budget + 1 })

        val result = encodeWithinBudget(source, durationMs = 6_000)

        assertThat(result).isNull()
        assertThat(source.encodes).containsExactly(
            steps.last() to 6_000L,
            steps.last() to 4_000L,
            steps.last() to 3_000L,
        ).inOrder()
    }

    @Test
    fun `encode - skips the duration fallbacks when the animation is already short`() = runTest {
        val source = FakeAnimationSource(sampleBytes = { 2_000_000 }, encodeBytes = { _, _ -> budget + 1 })

        val result = encodeWithinBudget(source, durationMs = 2_000)

        assertThat(result).isNull()
        assertThat(source.encodes).containsExactly(
            steps.last() to 2_000L,
        )
    }
}

private class FakeAnimationSource(
    private val sampleBytes: (GifEncodingStep) -> Int,
    private val encodeBytes: (GifEncodingStep, Long) -> Int?,
    private val sampleFrames: (GifEncodingStep) -> Int = { step ->
        StickerGifEncodingPlan.frameCount(StickerGifEncodingPlan.SAMPLE_DURATION_MS, step)
    },
) : AnimationSource {
    val encodes = mutableListOf<Pair<GifEncodingStep, Long>>()

    override suspend fun sample(
        steps: List<GifEncodingStep>,
        sampleDurationMs: Long,
        onProgress: (Float) -> Unit,
    ): List<GifEncodingSample> {
        onProgress(0.5f)
        return steps.map { step -> GifEncodingSample(step, bytes = sampleBytes(step), frameCount = sampleFrames(step)) }
    }

    override suspend fun encode(
        step: GifEncodingStep,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation? {
        encodes.add(step to durationMs)
        onProgress(0.5f)
        val size = encodeBytes(step, durationMs) ?: return null
        return EncodedAnimation(bytes = ByteArray(size), width = 1, height = 1)
    }
}
