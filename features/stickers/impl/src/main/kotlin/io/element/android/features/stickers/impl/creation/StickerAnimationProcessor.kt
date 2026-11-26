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
import android.os.Build
import androidx.annotation.RequiresApi
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.gif.GifEncodingSample
import io.element.android.features.stickers.impl.gif.GifEncodingStep
import io.element.android.features.stickers.impl.gif.StickerGifEncoder
import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a video or an animated image into an animated GIF sticker.
 */
interface StickerAnimationProcessor {
    /**
     * Extracts the [startMs, startMs + durationMs] window of [videoFile] and encodes it as a
     * looping GIF of at most 3 MiB. The window is shortened when even the smallest encoding step
     * overflows.
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
     * looping GIF of at most 3 MiB.
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
            val encoded = mapEncodingErrors {
                encodeVideo(videoFile, startMs, durationMs, onProgress)
            } ?: throw StickerAnimationException.TooComplex()
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
            val encoded = mapEncodingErrors {
                encodeAnimatedImage(imageFile, onProgress)
            } ?: throw StickerAnimationException.TooComplex()
            writeSticker(encoded)
        }
    }

    private suspend fun <T> mapEncodingErrors(block: suspend () -> T): T = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: StickerAnimationException) {
        throw exception
    } catch (exception: Exception) {
        throw StickerAnimationException.MediaUnreadable(exception)
    }

    private suspend fun encodeVideo(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val source = VideoAnimationSource(
                retriever = retriever,
                setup = VideoSetup.from(retriever),
                startMs = startMs,
            )
            return encodeWithinBudget(source, durationMs, onProgress = onProgress)
        } finally {
            retriever.release()
        }
    }

    private suspend fun encodeAnimatedImage(imageFile: File, onProgress: (Float) -> Unit): EncodedAnimation? {
        val bytes = imageFile.readBytes()
        val animation = animatedImageDecoder.decode(bytes) ?: error("Unable to decode the animated image")
        require(animation.width > 0 && animation.height > 0) { "Invalid animated image size" }
        val durationMs = if (animation.durationMs > 0) {
            animation.durationMs.toLong().coerceAtMost(StickerGifEncodingPlan.MAX_ANIMATION_DURATION_MS)
        } else {
            1_000L
        }
        return encodeWithinBudget(AnimatedImageSource(animation), durationMs, onProgress = onProgress)
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

    private companion object {
        const val STICKER_ANIMATIONS_DIRECTORY = "sticker_animations"
    }
}

/**
 * The frame rate and duration of the source video, used to decode frames by index on API 28+.
 */
private data class VideoSetup(
    val frameCount: Int,
    val durationMs: Long,
) {
    companion object {
        fun from(retriever: MediaMetadataRetriever) = VideoSetup(
            frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0,
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
        )
    }
}

/**
 * A window of a video. The frames needed by the animation are decoded sequentially, once per
 * encoding attempt, instead of seeking back to a keyframe for every frame.
 */
private class VideoAnimationSource(
    private val retriever: MediaMetadataRetriever,
    private val setup: VideoSetup,
    private val startMs: Long,
) : AnimationSource {
    override suspend fun sample(
        steps: List<GifEncodingStep>,
        sampleDurationMs: Long,
        onProgress: (Float) -> Unit,
    ): List<GifEncodingSample> {
        val sampleFrameRate = StickerGifEncodingPlan.SAMPLE_FRAMES_PER_SECOND
        val sampleFrameCount = StickerGifEncodingPlan.frameCount(sampleDurationMs, sampleFrameRate)
        val indicesPerStep = steps.map { step -> StickerGifEncodingPlan.sampleFrameIndices(sampleDurationMs, step) }
        val encoders = arrayOfNulls<SampleEncoder>(steps.size)
        val encodedCounts = IntArray(steps.size)
        var sawFrame = false
        readFrames(sampleDurationMs, sampleFrameRate) { index, bitmap ->
            if (bitmap != null) {
                sawFrame = true
                if (encoders[0] == null) {
                    steps.forEachIndexed { stepIndex, step ->
                        encoders[stepIndex] = SampleEncoder.create(bitmap.width, bitmap.height, step)
                    }
                }
                steps.forEachIndexed { stepIndex, step ->
                    if (index in indicesPerStep[stepIndex]) {
                        val sampleEncoder = encoders[stepIndex]
                        if (sampleEncoder != null) {
                            bitmap.readScaledPixels(sampleEncoder.width, sampleEncoder.height, sampleEncoder.pixels)
                            sampleEncoder.encoder.addFrame(sampleEncoder.pixels, sampleEncoder.delayCentiseconds)
                            encodedCounts[stepIndex] += 1
                        }
                    }
                }
            }
            onProgress((index + 1f) / sampleFrameCount)
            true
        }
        if (!sawFrame) error("Unable to read the video frames")
        return steps.mapIndexedNotNull { index, step ->
            encoders[index]?.let { GifEncodingSample(step, bytes = it.encoder.encodedSize, frameCount = encodedCounts[index]) }
        }
    }

    override suspend fun encode(
        step: GifEncodingStep,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation? {
        val totalFrames = StickerGifEncodingPlan.frameCount(durationMs, step)
        val delay = StickerGifEncodingPlan.frameDelayCentiseconds(step)
        var encoder: StickerGifEncoder? = null
        var pixels: IntArray? = null
        var width = 0
        var height = 0
        var overflow = false
        readFrames(durationMs, step.framesPerSecond) { index, bitmap ->
            if (bitmap != null) {
                if (encoder == null) {
                    val size = StickerGifEncodingPlan.scaledSize(bitmap.width, bitmap.height, step)
                    width = size.first
                    height = size.second
                    encoder = StickerGifEncoder(width, height)
                    pixels = IntArray(width * height)
                }
                bitmap.readScaledPixels(width, height, requireNotNull(pixels))
            }
            val currentEncoder = encoder
            val currentPixels = pixels
            if (currentEncoder != null && currentPixels != null) {
                currentEncoder.addFrame(currentPixels, delay)
                onProgress((index + 1f) / totalFrames)
                overflow = currentEncoder.encodedSize > StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES
            }
            !overflow
        }
        val currentEncoder = encoder ?: error("Unable to read the video frames")
        return if (overflow) null else EncodedAnimation(currentEncoder.finish(), width, height)
    }

    private suspend fun readFrames(
        durationMs: Long,
        framesPerSecond: Int,
        onFrame: (index: Int, bitmap: Bitmap?) -> Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && setup.frameCount > 1 && setup.durationMs > 0) {
            val indices = VideoFrameIndices.sourceIndices(setup.frameCount, setup.durationMs, startMs, durationMs, framesPerSecond)
            if (indices.isNotEmpty()) {
                readIndexedFrames(indices, onFrame)
                return
            }
        }
        readTimedFrames(durationMs, framesPerSecond, onFrame)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun readIndexedFrames(indices: IntArray, onFrame: (index: Int, bitmap: Bitmap?) -> Boolean) {
        var cursor = 0
        var batchStart = indices[0]
        while (cursor < indices.size) {
            currentCoroutineContext().ensureActive()
            val batchEnd = minOf(batchStart + FRAME_BATCH_SIZE, setup.frameCount)
            val batch = retriever.getFramesAtIndex(batchStart, batchEnd - batchStart)
            if (batch.isEmpty()) error("Unable to read the video frames at $batchStart")
            val available = batchStart + batch.size
            var shouldContinue = true
            while (shouldContinue && cursor < indices.size && indices[cursor] < available) {
                currentCoroutineContext().ensureActive()
                shouldContinue = onFrame(cursor, batch[indices[cursor] - batchStart])
                cursor += 1
            }
            for (bitmap in batch) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (!shouldContinue) return
            batchStart = available
        }
    }

    private suspend fun readTimedFrames(
        durationMs: Long,
        framesPerSecond: Int,
        onFrame: (index: Int, bitmap: Bitmap?) -> Boolean,
    ) {
        val totalFrames = StickerGifEncodingPlan.frameCount(durationMs, framesPerSecond)
        for (index in 0 until totalFrames) {
            currentCoroutineContext().ensureActive()
            val timeMs = startMs + index * 1000L / framesPerSecond
            val bitmap = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            val shouldContinue = onFrame(index, bitmap)
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            if (!shouldContinue) return
        }
    }

    private companion object {
        /**
         * Decoding frames by index still allocates every returned bitmap, so they are read in
         * small batches to bound the memory used by a 1080p video.
         */
        const val FRAME_BATCH_SIZE = 8
    }
}

private data class SampleEncoder(
    val encoder: StickerGifEncoder,
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val delayCentiseconds: Int,
) {
    companion object {
        fun create(sourceWidth: Int, sourceHeight: Int, step: GifEncodingStep): SampleEncoder {
            val (width, height) = StickerGifEncodingPlan.scaledSize(sourceWidth, sourceHeight, step)
            return SampleEncoder(
                encoder = StickerGifEncoder(width, height),
                pixels = IntArray(width * height),
                width = width,
                height = height,
                delayCentiseconds = StickerGifEncodingPlan.frameDelayCentiseconds(step),
            )
        }
    }
}

/**
 * An animated image (GIF) decoded frame by frame. Drawing a frame is cheap, so sampling a step
 * redraws the first second at the size of that step.
 */
private class AnimatedImageSource(
    private val animation: DecodedAnimatedImage,
) : AnimationSource {
    override suspend fun sample(
        steps: List<GifEncodingStep>,
        sampleDurationMs: Long,
        onProgress: (Float) -> Unit,
    ): List<GifEncodingSample> {
        val samples = ArrayList<GifEncodingSample>(steps.size)
        steps.forEachIndexed { stepIndex, step ->
            val (width, height) = StickerGifEncodingPlan.scaledSize(animation.width, animation.height, step)
            val frameCount = StickerGifEncodingPlan.frameCount(sampleDurationMs, step)
            val delay = StickerGifEncodingPlan.frameDelayCentiseconds(step)
            val encoder = StickerGifEncoder(width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            try {
                val canvas = Canvas(bitmap)
                for (frame in 0 until frameCount) {
                    currentCoroutineContext().ensureActive()
                    drawFrame(bitmap, canvas, step, frame)
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                    encoder.addFrame(pixels, delay)
                    onProgress((stepIndex + (frame + 1f) / frameCount) / steps.size)
                }
            } finally {
                bitmap.recycle()
            }
            samples.add(GifEncodingSample(step, bytes = encoder.encodedSize, frameCount = frameCount))
        }
        return samples
    }

    override suspend fun encode(
        step: GifEncodingStep,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): EncodedAnimation? {
        val (width, height) = StickerGifEncodingPlan.scaledSize(animation.width, animation.height, step)
        val frameCount = StickerGifEncodingPlan.frameCount(durationMs, step)
        val delay = StickerGifEncodingPlan.frameDelayCentiseconds(step)
        val encoder = StickerGifEncoder(width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        try {
            val canvas = Canvas(bitmap)
            for (frame in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                drawFrame(bitmap, canvas, step, frame)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                encoder.addFrame(pixels, delay)
                onProgress((frame + 1f) / frameCount)
                if (encoder.encodedSize > StickerGifEncodingPlan.MAX_FILE_SIZE_BYTES) return null
            }
        } finally {
            bitmap.recycle()
        }
        return EncodedAnimation(encoder.finish(), width, height)
    }

    private fun drawFrame(bitmap: Bitmap, canvas: Canvas, step: GifEncodingStep, frameIndex: Int) {
        bitmap.eraseColor(Color.TRANSPARENT)
        canvas.save()
        canvas.scale(bitmap.width.toFloat() / animation.width, bitmap.height.toFloat() / animation.height)
        animation.drawFrame(canvas, timeMs = (frameIndex * 1000L / step.framesPerSecond).toInt())
        canvas.restore()
    }
}

private fun Bitmap.readScaledPixels(width: Int, height: Int, destination: IntArray) {
    require(destination.size == width * height) { "Expected ${width * height} pixels, got ${destination.size}" }
    if (this.width == width && this.height == height) {
        getPixels(destination, 0, width, 0, 0, width, height)
        return
    }
    val scaled = Bitmap.createScaledBitmap(this, width, height, true)
    try {
        scaled.getPixels(destination, 0, width, 0, 0, width, height)
    } finally {
        scaled.recycle()
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
