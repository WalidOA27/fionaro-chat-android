/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.api.StickerImageProcessor
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.timeline.item.event.MAX_STICKER_IMAGE_SIZE
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StickerSoundFile(
    val file: File,
    val mimeType: String,
    val durationMs: Long,
)

/**
 * A picked video, ready to be turned into an animated sticker.
 */
data class StickerVideoSource(
    val file: File,
    val mimeType: String,
    val durationMs: Long,
    /**
     * Whether the video has an AAC audio track, the only format which can be copied into the
     * sticker sound.
     */
    val hasAacAudioTrack: Boolean,
)

/**
 * Copies a picked content [Uri] to a local cache file, and extracts the metadata needed to
 * build a sticker.
 */
interface StickerMediaFileReader {
    suspend fun readImage(uri: Uri): Result<StickerImage>
    suspend fun readAudio(uri: Uri): Result<StickerSoundFile>
    suspend fun readVideo(uri: Uri): Result<StickerVideoSource>

    /**
     * Extracts the video frame at [positionMs] into a PNG file, used as the preview of the
     * animation start position.
     */
    suspend fun readVideoThumbnail(video: StickerVideoSource, positionMs: Long): Result<File>
}

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultStickerMediaFileReader(
    @ApplicationContext private val context: Context,
    private val stickerImageProcessor: StickerImageProcessor,
    private val dispatchers: CoroutineDispatchers,
) : StickerMediaFileReader {
    override suspend fun readImage(uri: Uri): Result<StickerImage> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val mimeType = resolveMimeType(uri, fallback = MimeTypes.Png)
            val file = copyToCache(uri, extensionOf(mimeType))
            stickerImageProcessor.process(file, mimeType).getOrThrow()
        }
    }

    override suspend fun readAudio(uri: Uri): Result<StickerSoundFile> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val mimeType = resolveMimeType(uri, fallback = MimeTypes.Mp3)
            val file = copyToCache(uri, extensionOf(mimeType))
            StickerSoundFile(
                file = file,
                mimeType = mimeType,
                durationMs = extractDuration(file),
            )
        }
    }

    override suspend fun readVideo(uri: Uri): Result<StickerVideoSource> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val mimeType = resolveMimeType(uri, fallback = MimeTypes.Mp4)
            val file = copyToCache(uri, extensionOf(mimeType))
            StickerVideoSource(
                file = file,
                mimeType = mimeType,
                durationMs = extractDuration(file),
                hasAacAudioTrack = hasAacAudioTrack(file),
            )
        }
    }

    override suspend fun readVideoThumbnail(video: StickerVideoSource, positionMs: Long): Result<File> =
        withContext(dispatchers.io) {
            runCatchingExceptions {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.file.absolutePath)
                    val bitmap = retriever.getFrameAtTime(
                        positionMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: error("Unable to read the video frame at $positionMs ms")
                    try {
                        writeThumbnail(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    retriever.release()
                }
            }
        }

    private fun resolveMimeType(uri: Uri, fallback: String): String {
        val fromResolver = context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
        if (fromResolver != null) return fromResolver
        val extension = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")
        return extension?.takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
            ?: fallback
    }

    private fun copyToCache(uri: Uri, extension: String): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        val input = context.contentResolver.openInputStream(uri) ?: error("Unable to open $uri")
        input.use { source ->
            target.outputStream().use { destination ->
                source.copyTo(destination)
            }
        }
        return target
    }

    private fun extractDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun hasAacAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == AAC_MIME_TYPE
            }
        } catch (exception: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    private fun writeThumbnail(bitmap: Bitmap): File {
        val scaled = fitInSticker(bitmap)
        try {
            val directory = File(context.cacheDir, THUMBNAILS_DIRECTORY).apply { mkdirs() }
            val output = File(directory, "${UUID.randomUUID()}.png")
            output.outputStream().use { stream ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Unable to encode the video thumbnail"
                }
            }
            return output
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun fitInSticker(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= MAX_STICKER_IMAGE_SIZE) return bitmap
        val ratio = MAX_STICKER_IMAGE_SIZE.toFloat() / longestSide
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun extensionOf(mimeType: String): String {
        return when (mimeType.lowercase().substringBefore(';').trim()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/3gpp" -> "3gp"
            "video/quicktime" -> "mov"
            "audio/ogg" -> "ogg"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> mimeType.substringAfter('/', "bin").substringBefore(';').filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "sticker_picker"
        const val THUMBNAILS_DIRECTORY = "sticker_video_thumbnails"
        const val AAC_MIME_TYPE = "audio/mp4a-latm"
    }
}
