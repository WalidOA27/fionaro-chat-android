/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan

/**
 * Maps the frames of a sticker animation onto the frame indices of the source video, so that the
 * frames can be decoded sequentially with `MediaMetadataRetriever.getFramesAtIndex` instead of
 * seeking back to the previous keyframe for every frame.
 */
internal object VideoFrameIndices {
    /**
     * The source frame index of each of the [durationMs] frames sampled at [framesPerSecond] from
     * [startMs], or an empty array when the source frame rate is not known.
     *
     * Indices are not necessarily strictly increasing when the source is slower than the sampling
     * rate: the same source frame is then used for several output frames.
     */
    fun sourceIndices(
        sourceFrameCount: Int,
        videoDurationMs: Long,
        startMs: Long,
        durationMs: Long,
        framesPerSecond: Int,
    ): IntArray {
        if (sourceFrameCount <= 0 || videoDurationMs <= 0) return IntArray(0)
        return IntArray(StickerGifEncodingPlan.frameCount(durationMs, framesPerSecond)) { index ->
            val timeMs = startMs + index * 1000L / framesPerSecond
            val sourceIndex = (timeMs * sourceFrameCount + videoDurationMs / 2) / videoDurationMs
            sourceIndex.coerceIn(0, sourceFrameCount - 1L).toInt()
        }
    }
}
