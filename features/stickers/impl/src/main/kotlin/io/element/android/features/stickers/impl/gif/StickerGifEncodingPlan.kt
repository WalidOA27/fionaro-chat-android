/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One attempt at encoding an animated sticker: the size of the longest side in pixels and
 * the frame rate to sample the source at.
 */
internal data class GifEncodingStep(
    val longestSidePx: Int,
    val framesPerSecond: Int,
)

/**
 * The encoding budget of an animated sticker and the ordered attempts used to stay within it.
 *
 * The candidates are sampled first (see [GifSizePredictor]), then the first step predicted to
 * fit is encoded in full. Stickers are displayed at about 256 px by the clients, so keeping the
 * resolution high and the file at most [MAX_FILE_SIZE_BYTES] matters more than a tiny size.
 */
internal object StickerGifEncodingPlan {
    const val MAX_FILE_SIZE_BYTES = 3 * 1024 * 1024
    const val MAX_ANIMATION_DURATION_MS = 6_000L
    const val MAX_COLORS = 256

    /**
     * Only the beginning of the animation is encoded to choose a step, which bounds the cost of
     * the sampling pass.
     */
    const val SAMPLE_DURATION_MS = 1_000L

    /**
     * The sampling pass reads the source at the fastest candidate frame rate, then each step
     * picks its own frames from it.
     */
    const val SAMPLE_FRAMES_PER_SECOND = 10

    val steps: List<GifEncodingStep> = listOf(
        GifEncodingStep(longestSidePx = 384, framesPerSecond = 10),
        GifEncodingStep(longestSidePx = 320, framesPerSecond = 10),
        GifEncodingStep(longestSidePx = 256, framesPerSecond = 10),
        GifEncodingStep(longestSidePx = 256, framesPerSecond = 8),
        GifEncodingStep(longestSidePx = 200, framesPerSecond = 8),
        GifEncodingStep(longestSidePx = 160, framesPerSecond = 6),
    )

    /**
     * When even the smallest step overflows, the animation is shortened to these durations,
     * keeping the selected start position.
     */
    val fallbackDurationsMs: List<Long> = listOf(4_000L, 3_000L)

    fun frameCount(durationMs: Long, framesPerSecond: Int): Int {
        if (durationMs <= 0) return 1
        return max(1, ceil(durationMs.toDouble() * framesPerSecond / 1000.0).toInt())
    }

    fun frameCount(durationMs: Long, step: GifEncodingStep): Int = frameCount(durationMs, step.framesPerSecond)

    /**
     * GIF frame delays are expressed in hundredths of a second.
     */
    fun frameDelayCentiseconds(step: GifEncodingStep): Int = 1_000 / step.framesPerSecond / 10

    /**
     * The indices, in the [SAMPLE_FRAMES_PER_SECOND] sample, of the frames encoded for [step].
     */
    fun sampleFrameIndices(sampleDurationMs: Long, step: GifEncodingStep): List<Int> {
        val decodedCount = frameCount(sampleDurationMs, SAMPLE_FRAMES_PER_SECOND)
        return (0 until frameCount(sampleDurationMs, step)).map { index ->
            (index * SAMPLE_FRAMES_PER_SECOND.toDouble() / step.framesPerSecond)
                .roundToInt()
                .coerceIn(0, decodedCount - 1)
        }
    }

    /**
     * Scales [sourceWidth]x[sourceHeight] so that the longest side fits in the step, without
     * ever upscaling the source.
     */
    fun scaledSize(sourceWidth: Int, sourceHeight: Int, step: GifEncodingStep): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source size must be positive" }
        val longestSide = min(step.longestSidePx, max(sourceWidth, sourceHeight))
        return if (sourceWidth >= sourceHeight) {
            val height = (sourceHeight.toDouble() * longestSide / sourceWidth).roundToInt().coerceAtLeast(1)
            longestSide to height
        } else {
            val width = (sourceWidth.toDouble() * longestSide / sourceHeight).roundToInt().coerceAtLeast(1)
            width to longestSide
        }
    }

    fun fitsInBudget(sizeBytes: Int): Boolean = sizeBytes <= MAX_FILE_SIZE_BYTES

    /**
     * The durations to try for a source of [durationMs], longest first: the requested duration
     * and then the shortened fallbacks which still fit in it.
     */
    fun durationsToTry(durationMs: Long): List<Long> {
        require(durationMs > 0) { "The animation must last at least 1 ms" }
        return (listOf(durationMs) + fallbackDurationsMs.map { min(it, durationMs) }).distinct()
    }

    /**
     * The [startMs, startMs + durationMs] window of a video used for the sticker animation.
     */
    fun videoWindow(startMs: Long, videoDurationMs: Long): Pair<Long, Long> = window(startMs, videoDurationMs, MAX_ANIMATION_DURATION_MS)

    /**
     * The [startMs, startMs + durationMs] window of a video used for the sticker sound.
     */
    fun audioWindow(startMs: Long, videoDurationMs: Long): Pair<Long, Long> = window(startMs, videoDurationMs, 10_000L)

    /**
     * The last position at which a full 6 second window still fits in the video.
     */
    fun maxStartMs(videoDurationMs: Long): Long = (videoDurationMs - MAX_ANIMATION_DURATION_MS).coerceIn(0, videoDurationMs)

    private fun window(startMs: Long, videoDurationMs: Long, maxDurationMs: Long): Pair<Long, Long> {
        val safeStart = startMs.coerceIn(0, max(0, videoDurationMs))
        val remaining = (videoDurationMs - safeStart).coerceAtLeast(1)
        return safeStart to min(maxDurationMs, remaining)
    }
}

/**
 * The size of a sample encoding of [step], used to predict the size of the full animation.
 */
internal data class GifEncodingSample(
    val step: GifEncodingStep,
    val bytes: Int,
    val frameCount: Int,
)

/**
 * Predicts the size of a full animation from a sample of its first frames, so that only one full
 * encode is needed in the common case.
 */
internal object GifSizePredictor {
    /**
     * Encoded frames are not all the same size, the last frames of a video often have more
     * motion than the first ones, hence the safety margin.
     */
    const val SAFETY_FACTOR = 1.15

    fun predictFullSize(sampleBytes: Int, sampleFrames: Int, totalFrames: Int): Long {
        require(sampleFrames > 0) { "A sample needs at least one frame" }
        if (sampleBytes <= 0 || totalFrames <= sampleFrames) return sampleBytes.toLong()
        return ceil(sampleBytes.toDouble() * totalFrames / sampleFrames * SAFETY_FACTOR).toLong()
    }

    /**
     * The first of [samples] whose extrapolated full size fits in [budgetBytes], or `null` when
     * every one of them is predicted too large.
     */
    fun selectStep(
        samples: List<GifEncodingSample>,
        budgetBytes: Int = StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES,
        totalFrameCount: (GifEncodingStep) -> Int,
    ): GifEncodingStep? = samples
        .firstOrNull { sample ->
            predictFullSize(sample.bytes, sample.frameCount, totalFrameCount(sample.step)) <= budgetBytes
        }
        ?.step
}
