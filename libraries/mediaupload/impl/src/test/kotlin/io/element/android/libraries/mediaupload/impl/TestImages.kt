/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.File

/**
 * Creates a deterministic, non trivially compressible bitmap of the given size.
 *
 * A slow gradient is blended with a pseudo random noise so that re-encoding the image at a different quality
 * produces a different file size. If [transparentRect] is not null, the pixels inside it are fully transparent.
 */
internal fun createTestBitmap(
    width: Int,
    height: Int,
    transparentRect: Rect? = null,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    var seed = 0x5EED_1234
    for (y in 0 until height) {
        for (x in 0 until width) {
            seed = seed * 1_664_525 + 1_013_903
            val noise = (seed ushr 16).and(0xFF)
            val red = (x * 255 / width + noise / 4).and(0xFF)
            val green = (y * 255 / height + noise / 4).and(0xFF)
            val blue = ((x + y) * 255 / (width + height) + noise / 4).and(0xFF)
            val alpha = if (transparentRect?.contains(x, y) == true) 0x00 else 0xFF
            pixels[y * width + x] = (alpha shl 24).or(red shl 16).or(green shl 8).or(blue)
        }
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

internal fun File.writeTestImage(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 90): File {
    outputStream().use { outputStream ->
        bitmap.compress(format, quality, outputStream)
    }
    bitmap.recycle()
    return this
}

internal fun File.imageBounds(): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    return options.outWidth to options.outHeight
}

internal fun File.longestSide(): Int = imageBounds().let { (width, height) -> maxOf(width, height) }

private val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal fun File.hasJpegMagicBytes(): Boolean = readMagicBytes(jpegMagic.size).contentEquals(jpegMagic)

internal fun File.hasPngMagicBytes(): Boolean = readMagicBytes(pngMagic.size).contentEquals(pngMagic)

private fun File.readMagicBytes(count: Int): ByteArray = inputStream().use { it.readNBytes(count) }
