/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import io.element.android.features.stickers.impl.gif.GifEncodingSample
import io.element.android.features.stickers.impl.gif.GifEncodingStep
import io.element.android.features.stickers.impl.gif.GifSizePredictor
import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan

/**
 * An animation which has been encoded and fits in the sticker budget.
 */
internal data class EncodedAnimation(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    val sizeBytes: Int get() = bytes.size
}

/**
 * A media source which can encode itself as an animated sticker: a window of a video or an
 * animated image. Implementations decode their frames only once per attempt.
 */
internal interface AnimationSource {
    /**
     * Encodes the first [sampleDurationMs] at each of [steps] to predict the size of the full
     * animation. Implementations should decode the sampled frames only once for all the steps.
     */
    suspend fun sample(
        steps: List<GifEncodingStep>,
        sampleDurationMs: Long,
        onProgress: (Float) -> Unit,
    ): List<GifEncodingSample>

    /**
     * Encodes [durationMs] at [step], returning `null` as soon as the budget is exceeded.
     */
    suspend fun encode(
        step: GifEncodingStep,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation?
}

/**
 * Reports the progress of the whole encoding job. Values are clamped and only reported once they
 * increase, so that the sampling, retries and duration fallbacks all move the progress forward.
 */
internal class JobProgress(private val onProgress: (Float) -> Unit) {
    private var last = 0f

    fun report(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped <= last) return
        last = clamped
        onProgress(clamped)
    }
}

/**
 * Samples [durationMs] of [source] at every step, then encodes the first step predicted to fit in
 * [budgetBytes] in full. When the prediction is wrong the next step is tried once, then the
 * animation is shortened with the smallest step. Returns `null` when every attempt overflows.
 */
internal suspend fun encodeWithinBudget(
    source: AnimationSource,
    durationMs: Long,
    budgetBytes: Int = StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES,
    steps: List<GifEncodingStep> = StickerGifEncodingPlan.steps,
    onProgress: (Float) -> Unit = {},
): EncodedAnimation? {
    val progress = JobProgress(onProgress)
    val sampleDurationMs = minOf(StickerGifEncodingPlan.SAMPLE_DURATION_MS, durationMs)
    val samples = source.sample(steps, sampleDurationMs) { fraction ->
        progress.report(SAMPLING_END * fraction)
    }
    val predicted = GifSizePredictor.selectStep(samples, budgetBytes = budgetBytes) { step ->
        StickerGifEncodingPlan.frameCount(durationMs, step)
    }
    val selectedIndex = predicted
        ?.let { step -> steps.indexOf(step).takeIf { it >= 0 } }
        ?: steps.lastIndex

    var result = encodeCandidates(source, steps.drop(selectedIndex).take(MAX_FULL_ENCODINGS), durationMs, budgetBytes, progress)
    if (result == null) {
        result = encodeShortenedDurations(source, steps.last(), durationMs, budgetBytes, progress)
    }
    progress.report(1f)
    return result
}

private suspend fun encodeCandidates(
    source: AnimationSource,
    candidates: List<GifEncodingStep>,
    durationMs: Long,
    budgetBytes: Int,
    progress: JobProgress,
): EncodedAnimation? {
    candidates.forEachIndexed { index, step ->
        val start = if (index == 0) SAMPLING_END else RETRY_START
        val end = if (index == 0) RETRY_START else RETRY_END
        val result = source.encode(step, durationMs) { fraction ->
            progress.report(start + (end - start) * fraction)
        }
        if (result != null && result.sizeBytes <= budgetBytes) return result
    }
    return null
}

private suspend fun encodeShortenedDurations(
    source: AnimationSource,
    step: GifEncodingStep,
    durationMs: Long,
    budgetBytes: Int,
    progress: JobProgress,
): EncodedAnimation? {
    val durations = StickerGifEncodingPlan.durationsToTry(durationMs).drop(1)
    val range = 1f - RETRY_END
    durations.forEachIndexed { index, duration ->
        val start = RETRY_END + range * index / durations.size
        val end = RETRY_END + range * (index + 1) / durations.size
        val result = source.encode(step, duration) { fraction ->
            progress.report(start + (end - start) * fraction)
        }
        if (result != null && result.sizeBytes <= budgetBytes) return result
    }
    return null
}

/** The predicted step and, when the prediction is wrong, one fallback step. */
private const val MAX_FULL_ENCODINGS = 2
private const val SAMPLING_END = 0.2f
private const val RETRY_START = 0.6f
private const val RETRY_END = 0.85f
