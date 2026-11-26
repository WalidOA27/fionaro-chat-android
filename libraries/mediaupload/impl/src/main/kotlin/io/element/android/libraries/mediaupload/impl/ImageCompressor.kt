/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.bitmap.calculateInSampleSize
import io.element.android.libraries.androidutils.bitmap.resizeToMax
import io.element.android.libraries.androidutils.bitmap.rotateToExifMetadataOrientation
import io.element.android.libraries.androidutils.file.createTmpFile
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Quality used when encoding a PNG. The value is ignored by [Bitmap.CompressFormat.PNG], which is lossless.
 */
private const val PNG_QUALITY = 100

private const val ALPHA_SHIFT = 24
private const val OPAQUE_ALPHA = 0xFF

@Inject
class ImageCompressor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) {
    /**
     * Decodes the [inputStream] into a [Bitmap] and applies the needed transformations (rotation, scale) based on [resizeMode], then writes it into a
     * temporary file using [outputFormat], [orientation] and the quality defined by [outputFormat].
     * @return a [Result] containing the resulting [ImageCompressionResult] with the temporary [File] and some metadata.
     */
    suspend fun compressToTmpFile(
        inputStreamProvider: () -> InputStream,
        resizeMode: ResizeMode,
        outputFormat: ImageOutputFormat,
        orientation: Int = ExifInterface.ORIENTATION_UNDEFINED,
    ): Result<ImageCompressionResult> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val compressedBitmap = compressToBitmap(inputStreamProvider, resizeMode, orientation).getOrThrow()
            val (format, mimeType, quality) = when (outputFormat) {
                is ImageOutputFormat.Jpeg -> Triple(Bitmap.CompressFormat.JPEG, MimeTypes.Jpeg, outputFormat.quality)
                is ImageOutputFormat.PngOrJpeg -> if (compressedBitmap.hasTransparentPixels()) {
                    Triple(Bitmap.CompressFormat.PNG, MimeTypes.Png, PNG_QUALITY)
                } else {
                    Triple(Bitmap.CompressFormat.JPEG, MimeTypes.Jpeg, outputFormat.jpegQuality)
                }
            }
            // Encode bitmap to the destination temporary file
            val tmpFile = context.createTmpFile(extension = mimeTypeToCompressFileExtension(mimeType))
            tmpFile.outputStream().use {
                compressedBitmap.compress(format, quality, it)
            }
            val result = ImageCompressionResult(
                file = tmpFile,
                width = compressedBitmap.width,
                height = compressedBitmap.height,
                size = tmpFile.length(),
                mimeType = mimeType,
            )
            compressedBitmap.recycle()
            result
        }
    }

    /**
     * Decodes the inputStream from [inputStreamProvider] into a [Bitmap] and applies the needed transformations (rotation, scale)
     * based on [resizeMode] and [orientation].
     * @return a [Result] containing the resulting [Bitmap].
     */
    fun compressToBitmap(
        inputStreamProvider: () -> InputStream,
        resizeMode: ResizeMode,
        orientation: Int,
    ): Result<Bitmap> = runCatchingExceptions {
        val options = BitmapFactory.Options()
        // Decode bounds
        inputStreamProvider().use { input ->
            calculateDecodingScale(input, resizeMode, options)
        }
        // Decode the actual bitmap
        inputStreamProvider().use { input ->
            // Now read the actual image and rotate it to match its metadata
            options.inJustDecodeBounds = false
            val decodedBitmap = BitmapFactory.decodeStream(input, null, options)
                ?: error("Decoding Bitmap from InputStream failed")
            val rotatedBitmap = decodedBitmap.rotateToExifMetadataOrientation(orientation)
            if (resizeMode is ResizeMode.Strict) {
                rotatedBitmap.resizeToMax(resizeMode.maxWidth, resizeMode.maxHeight)
            } else {
                rotatedBitmap
            }
        }
    }

    private fun calculateDecodingScale(
        inputStream: InputStream,
        resizeMode: ResizeMode,
        options: BitmapFactory.Options
    ) {
        when (resizeMode) {
            is ResizeMode.Approximate -> {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeStream(inputStream, null, options)
                options.inSampleSize = options.calculateInSampleSize(resizeMode.desiredWidth, resizeMode.desiredHeight)
            }
            is ResizeMode.Strict -> {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeStream(inputStream, null, options)
                // Sampling must keep enough pixels on the longest side so that the exact scale below doesn't lose sharpness.
                options.inSampleSize = options.calculateInSampleSizeForLongestSide(
                    desiredLongestSide = maxOf(resizeMode.maxWidth, resizeMode.maxHeight),
                )
            }
            is ResizeMode.None -> return
        }
    }
}

/**
 * Returns true if at least one pixel of the bitmap is not fully opaque.
 * [Bitmap.hasAlpha] only tells whether the bitmap *may* contain transparent pixels (and is not reliable for decoded
 * bitmaps), so the pixels are always checked.
 */
private fun Bitmap.hasTransparentPixels(): Boolean {
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        if (row.any { it ushr ALPHA_SHIFT != OPAQUE_ALPHA }) return true
    }
    return false
}

/**
 * Calculates the largest power of two that keeps the longest side of the decoded image greater than or equal to [desiredLongestSide].
 * The bounds must have been read into this [BitmapFactory.Options] beforehand.
 */
private fun BitmapFactory.Options.calculateInSampleSizeForLongestSide(desiredLongestSide: Int): Int {
    var inSampleSize = 1
    val longestSide = maxOf(outWidth, outHeight)
    while (longestSide / (inSampleSize * 2) >= desiredLongestSide) {
        inSampleSize *= 2
    }
    return inSampleSize
}

data class ImageCompressionResult(
    val file: File,
    val width: Int,
    val height: Int,
    val size: Long,
    val mimeType: String,
)

sealed interface ImageOutputFormat {
    /**
     * Always encodes the bitmap as JPEG using [quality].
     */
    data class Jpeg(val quality: Int) : ImageOutputFormat

    /**
     * Encodes the bitmap as PNG if it contains at least one transparent pixel, or as JPEG using [jpegQuality] otherwise.
     */
    data class PngOrJpeg(val jpegQuality: Int) : ImageOutputFormat
}

sealed interface ResizeMode {
    data object None : ResizeMode
    data class Approximate(val desiredWidth: Int, val desiredHeight: Int) : ResizeMode
    data class Strict(val maxWidth: Int, val maxHeight: Int) : ResizeMode
}
