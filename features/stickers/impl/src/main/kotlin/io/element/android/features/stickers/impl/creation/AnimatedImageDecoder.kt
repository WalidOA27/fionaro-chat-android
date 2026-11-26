/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.graphics.Canvas
import android.graphics.Movie
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding

/**
 * Decodes an animated image so that its frames can be read one at a time.
 *
 * `android.graphics.Movie` has been the only animation decoder available since API 24, this
 * indirection keeps [DefaultStickerAnimationProcessor] testable on the JVM.
 */
interface AnimatedImageDecoder {
    fun decode(bytes: ByteArray): DecodedAnimatedImage?
}

interface DecodedAnimatedImage {
    val width: Int
    val height: Int

    /**
     * The total duration of the animation in milliseconds.
     */
    val durationMs: Int

    /**
     * Draws the frame at [timeMs] on [canvas], which is expected to be scaled by the caller.
     */
    fun drawFrame(canvas: Canvas, timeMs: Int)
}

@ContributesBinding(AppScope::class)
class DefaultAnimatedImageDecoder : AnimatedImageDecoder {
    override fun decode(bytes: ByteArray): DecodedAnimatedImage? {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return MovieDecodedAnimatedImage(movie)
    }
}

private class MovieDecodedAnimatedImage(
    private val movie: Movie,
) : DecodedAnimatedImage {
    override val width: Int get() = movie.width()
    override val height: Int get() = movie.height()
    override val durationMs: Int get() = movie.duration()

    override fun drawFrame(canvas: Canvas, timeMs: Int) {
        movie.setTime(timeMs)
        movie.draw(canvas, 0f, 0f)
    }
}
