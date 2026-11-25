/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.api.StickerImageProcessor
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.timeline.item.event.MAX_STICKER_IMAGE_SIZE
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@ContributesBinding(AppScope::class)
class DefaultStickerImageProcessor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : StickerImageProcessor {
    override suspend fun process(inputFile: File, mimeType: String): Result<StickerImage> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val normalizedMimeType = mimeType.lowercase().substringBefore(';').trim()
            val bytes = inputFile.readBytes()
            val bounds = decodeBounds(bytes) ?: error("Unable to decode image bounds")

            if (normalizedMimeType == MimeTypes.Gif || normalizedMimeType == MimeTypes.WebP) {
                // Animated images cannot be re-encoded without losing the animation, only
                // accept them when they already satisfy the sticker constraints.
                val fitsInSticker = bounds.outWidth in 1..MAX_STICKER_IMAGE_SIZE &&
                    bounds.outHeight in 1..MAX_STICKER_IMAGE_SIZE
                val isSmallEnough = bytes.size <= MAX_PASSTHROUGH_FILE_SIZE_IN_BYTES
                check(fitsInSticker && isSmallEnough) {
                    "Animated sticker must fit in ${MAX_STICKER_IMAGE_SIZE}x$MAX_STICKER_IMAGE_SIZE px and be at most " +
                        "$MAX_PASSTHROUGH_FILE_SIZE_IN_BYTES bytes"
                }
                return@runCatchingExceptions StickerImage(
                    file = inputFile,
                    mimeType = normalizedMimeType,
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                )
            }

            val bitmap = decodeScaledBitmap(bytes) ?: error("Unable to decode image")
            try {
                val outputFile = newOutputFile()
                outputFile.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Unable to encode the sticker as PNG"
                    }
                }
                StickerImage(
                    file = outputFile,
                    mimeType = MimeTypes.Png,
                    width = bitmap.width,
                    height = bitmap.height,
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun decodeBounds(bytes: ByteArray): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decodeScaledBitmap(bytes: ByteArray): Bitmap? {
        val bounds = decodeBounds(bytes) ?: return null
        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val (targetWidth, targetHeight) = fitInSticker(bitmap.width, bitmap.height)
        return if (targetWidth == bitmap.width && targetHeight == bitmap.height) {
            bitmap
        } else {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        }
    }

    private fun newOutputFile(): File {
        val directory = File(context.cacheDir, STICKER_IMAGES_DIRECTORY).apply { mkdirs() }
        return File(directory, "${UUID.randomUUID()}.png")
    }

    private fun computeSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var longestSide = maxOf(width, height)
        while (longestSide / 2 >= MAX_STICKER_IMAGE_SIZE) {
            sampleSize *= 2
            longestSide /= 2
        }
        return sampleSize
    }

    private fun fitInSticker(width: Int, height: Int): Pair<Int, Int> {
        val longestSide = maxOf(width, height)
        if (longestSide <= MAX_STICKER_IMAGE_SIZE) return width to height
        val ratio = MAX_STICKER_IMAGE_SIZE.toFloat() / longestSide
        return (width * ratio).toInt().coerceAtLeast(1) to (height * ratio).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val STICKER_IMAGES_DIRECTORY = "sticker_images"
        const val MAX_PASSTHROUGH_FILE_SIZE_IN_BYTES = 1024 * 1024
    }
}
