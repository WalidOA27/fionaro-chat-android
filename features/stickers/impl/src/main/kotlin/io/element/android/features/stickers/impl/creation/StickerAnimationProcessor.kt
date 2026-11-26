/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.gif.GifEncodingStep
import io.element.android.features.stickers.impl.gif.StickerGifEncoder
import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan
import io.element.android.features.stickers.impl.gif.firstResultWithinBudget
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Turns a video or an animated image into an animated GIF sticker.
 */
interface StickerAnimationProcessor {
    /**
     * Extracts the [startMs, startMs + durationMs] window of [videoFile] at increasing frame
     * rates and encodes it as a looping GIF of at most 1 MiB.
     *
     * [onProgress] is called from a background thread with a value in `[0, 1]`.
     */
    suspend fun processVideo(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
        onProgress: (Float) -> Unit = {},
    ): Result<StickerImage>

    /**
     * Re-encodes an animated image (typically a GIF too large for the sticker constraints) as a
     * looping GIF of at most 1 MiB.
     *
     * [onProgress] is called from a background thread with a value in `[0, 1]`.
     */
    suspend fun processAnimatedImage(
        imageFile: File,
        mimeType: String,
        onProgress: (Float) -> Unit = {},
    ): Result<StickerImage>
}

@ContributesBinding(AppScope::class)
class DefaultStickerAnimationProcessor(
    @ApplicationContext private val context: Context,
    private val animatedImageDecoder: AnimatedImageDecoder,
    private val dispatchers: CoroutineDispatchers,
) : StickerAnimationProcessor {
    override suspend fun processVideo(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): Result<StickerImage> = withContext(dispatchers.io) {
        runCatchingExceptions {
            require(durationMs > 0) { "The animation must last at least 1 ms" }
            val encoded = try {
                encodeWithinBudget { step ->
                    encodeVideo(videoFile, startMs, durationMs, step, onProgress)
                } ?: throw StickerAnimationException.TooComplex()
            } catch (exception: StickerAnimationException) {
                throw exception
            } catch (exception: Exception) {
                throw StickerAnimationException.MediaUnreadable(exception)
            }
            writeSticker(encoded)
        }
    }

    override suspend fun processAnimatedImage(
        imageFile: File,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): Result<StickerImage> = withContext(dispatchers.io) {
        runCatchingExceptions {
            require(mimeType.lowercase().substringBefore(';').trim() == MimeTypes.Gif) {
                "Only GIF animations can be re-encoded, was $mimeType"
            }
            val encoded = try {
                encodeWithinBudget { step ->
                    encodeGif(imageFile, step, onProgress)
                } ?: throw StickerAnimationException.TooComplex()
            } catch (exception: StickerAnimationException) {
                throw exception
            } catch (exception: Exception) {
                throw StickerAnimationException.MediaUnreadable(exception)
            }
            writeSticker(encoded)
        }
    }

    private fun encodeWithinBudget(encode: (GifEncodingStep) -> EncodedAnimation?): EncodedAnimation? {
        return firstResultWithinBudget(encode = encode, sizeOf = { it.bytes.size })
    }

    private fun encodeVideo(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
        step: GifEncodingStep,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val firstFrame = retriever.getFrameAtTime(startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Unable to read the first video frame")
            val (width, height) = StickerGifEncodingPlan.scaledSize(firstFrame.width, firstFrame.height, step)
            val frameCount = StickerGifEncodingPlan.frameCount(durationMs, step)
            val delay = StickerGifEncodingPlan.frameDelayCentiseconds(step)
            val encoder = StickerGifEncoder(width = width, height = height)

            var previousPixels = firstFrame.toPixels(width, height)
            encoder.addFrame(previousPixels, delay)
            onProgress(1f / frameCount)
            if (encoder.encodedSize > StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES) return null

            for (index in 1 until frameCount) {
                val timeMs = startMs + index * 1000L / step.framesPerSecond
                val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                val pixels = bitmap?.toPixels(width, height) ?: previousPixels
                previousPixels = pixels
                encoder.addFrame(pixels, delay)
                onProgress((index + 1f) / frameCount)
                // Stop as soon as this step is known to overflow the sticker budget.
                if (encoder.encodedSize > StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES) return null
            }
            return EncodedAnimation(encoder.finish(), width, height)
        } finally {
            retriever.release()
        }
    }

    private fun encodeGif(
        imageFile: File,
        step: GifEncodingStep,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation? {
        val bytes = imageFile.readBytes()
        val animation = animatedImageDecoder.decode(bytes) ?: error("Unable to decode the animated image")
        require(animation.width > 0 && animation.height > 0) { "Invalid animated image size" }
        val durationMs = if (animation.durationMs > 0) {
            animation.durationMs.toLong().coerceAtMost(StickerGifEncodingPlan.MAX_ANIMATION_DURATION_MS)
        } else {
            1_000L
        }
        val (width, height) = StickerGifEncodingPlan.scaledSize(animation.width, animation.height, step)
        val frameCount = StickerGifEncodingPlan.frameCount(durationMs, step)
        val delay = StickerGifEncodingPlan.frameDelayCentiseconds(step)
        val encoder = StickerGifEncoder(width = width, height = height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val scaleX = width.toFloat() / animation.width
            val scaleY = height.toFloat() / animation.height
            for (index in 0 until frameCount) {
                bitmap.eraseColor(Color.TRANSPARENT)
                canvas.save()
                canvas.scale(scaleX, scaleY)
                animation.drawFrame(canvas, timeMs = (index * 1000L / step.framesPerSecond).toInt())
                canvas.restore()
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                encoder.addFrame(pixels, delay)
                onProgress((index + 1f) / frameCount)
                if (encoder.encodedSize > StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES) return null
            }
        } finally {
            bitmap.recycle()
        }
        return EncodedAnimation(encoder.finish(), width, height)
    }

    private fun Bitmap.toPixels(width: Int, height: Int): IntArray {
        val scaled = if (this.width == width && this.height == height) {
            this
        } else {
            Bitmap.createScaledBitmap(this, width, height, true).also { this@toPixels.recycle() }
        }
        return try {
            IntArray(width * height).also { scaled.getPixels(it, 0, width, 0, 0, width, height) }
        } finally {
            scaled.recycle()
        }
    }

    private fun writeSticker(encoded: EncodedAnimation): StickerImage {
        val directory = File(context.cacheDir, STICKER_ANIMATIONS_DIRECTORY).apply { mkdirs() }
        val output = File(directory, "${UUID.randomUUID()}.gif")
        output.writeBytes(encoded.bytes)
        return StickerImage(
            file = output,
            mimeType = MimeTypes.Gif,
            width = encoded.width,
            height = encoded.height,
        )
    }

    private data class EncodedAnimation(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val STICKER_ANIMATIONS_DIRECTORY = "sticker_animations"
    }
}

/**
 * Typed failures of [StickerAnimationProcessor], so that callers can show the right message
 * without matching exception strings.
 */
sealed class StickerAnimationException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * The animation is valid, but every encoding fallback still exceeds the sticker budget.
     */
    class TooComplex : StickerAnimationException("The animation is too complex for a sticker")

    /**
     * The source could not be read, decoded or extracted, whatever the encoding budget.
     */
    class MediaUnreadable(cause: Throwable? = null) : StickerAnimationException("The media cannot be used as a sticker", cause)
}
