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
 * The first step which produces a GIF of at most [MAX_FILE_SIZE_BYTES] is kept, the next one is
 * only tried when the previous result is too large.
 */
internal object StickerGifEncodingPlan {
    const val MAX_FILE_SIZE_BYTES = 1024 * 1024
    const val MAX_ANIMATION_DURATION_MS = 6_000L
    const val MAX_COLORS = 256

    val steps: List<GifEncodingStep> = listOf(
        GifEncodingStep(longestSidePx = 512, framesPerSecond = 12),
        GifEncodingStep(longestSidePx = 384, framesPerSecond = 10),
        GifEncodingStep(longestSidePx = 320, framesPerSecond = 10),
        GifEncodingStep(longestSidePx = 256, framesPerSecond = 8),
    )

    fun frameCount(durationMs: Long, step: GifEncodingStep): Int {
        if (durationMs <= 0) return 1
        return max(1, ceil(durationMs.toDouble() * step.framesPerSecond / 1000.0).toInt())
    }

    /**
     * GIF frame delays are expressed in hundredths of a second.
     */
    fun frameDelayCentiseconds(step: GifEncodingStep): Int = 1_000 / step.framesPerSecond / 10

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
 * Tries [encode] with each step in order and keeps the first result which fits in
 * [budgetBytes]. Returns `null` when every step overflows the budget or cannot be encoded.
 */
internal fun <T> firstResultWithinBudget(
    encode: (GifEncodingStep) -> T?,
    sizeOf: (T) -> Int,
    steps: List<GifEncodingStep> = StickerGifEncodingPlan.steps,
    budgetBytes: Int = StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES,
): T? {
    for (step in steps) {
        val result = encode(step) ?: continue
        if (sizeOf(result) <= budgetBytes) return result
    }
    return null
}
