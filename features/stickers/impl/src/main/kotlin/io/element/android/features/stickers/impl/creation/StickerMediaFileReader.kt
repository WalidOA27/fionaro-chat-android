/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.content.Context
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class StickerSoundFile(
    val file: File,
    val mimeType: String,
    val durationMs: Long,
)

/**
 * Copies a picked content [Uri] to a local cache file, and extracts the metadata needed to
 * build a sticker.
 */
interface StickerMediaFileReader {
    suspend fun readImage(uri: Uri): Result<StickerImage>
    suspend fun readAudio(uri: Uri): Result<StickerSoundFile>
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

    private fun extensionOf(mimeType: String): String {
        return when (mimeType.lowercase().substringBefore(';').trim()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            "audio/ogg" -> "ogg"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> mimeType.substringAfter('/', "bin").substringBefore(';').filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        }
    }

    private companion object {
        const val CACHE_DIRECTORY = "sticker_picker"
    }
}
