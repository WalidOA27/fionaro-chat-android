/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class ImageCompressorTest : RobolectricTest() {
    private val context: Context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun `Strict resize mode downscales the longest side and keeps the aspect ratio`() = runTest {
        val file = createSourceFile("landscape.png", width = 2400, height = 1600)

        val result = imageCompressor().compressToTmpFile(
            inputStreamProvider = { file.inputStream() },
            resizeMode = ResizeMode.Strict(maxWidth = 1600, maxHeight = 1600),
            outputFormat = ImageOutputFormat.Jpeg(quality = 80),
        ).getOrThrow()

        assertThat(result.width).isEqualTo(1600)
        assertThat(result.height).isEqualTo(1066)
        assertThat(result.mimeType).isEqualTo(MimeTypes.Jpeg)
        assertThat(result.file.hasJpegMagicBytes()).isTrue()
    }

    @Test
    fun `Strict resize mode never upscales the image`() = runTest {
        val file = createSourceFile("small.png", width = 800, height = 600)

        val result = imageCompressor().compressToTmpFile(
            inputStreamProvider = { file.inputStream() },
            resizeMode = ResizeMode.Strict(maxWidth = 1600, maxHeight = 1600),
            outputFormat = ImageOutputFormat.Jpeg(quality = 80),
        ).getOrThrow()

        assertThat(result.width).isEqualTo(800)
        assertThat(result.height).isEqualTo(600)
    }

    @Test
    fun `PngOrJpeg output encodes an opaque bitmap as JPEG`() = runTest {
        val file = createSourceFile("opaque.png", width = 2200, height = 1200)

        val result = imageCompressor().compressToTmpFile(
            inputStreamProvider = { file.inputStream() },
            resizeMode = ResizeMode.Strict(maxWidth = 2048, maxHeight = 2048),
            outputFormat = ImageOutputFormat.PngOrJpeg(jpegQuality = 85),
        ).getOrThrow()

        assertThat(result.mimeType).isEqualTo(MimeTypes.Jpeg)
        assertThat(result.file.hasJpegMagicBytes()).isTrue()
        assertThat(result.file.hasPngMagicBytes()).isFalse()
        assertThat(result.width).isEqualTo(2048)
    }

    @Test
    fun `PngOrJpeg output keeps PNG when the bitmap has transparent pixels`() = runTest {
        val file = createSourceFile("transparent.png", width = 2200, height = 1200, transparentRect = Rect(10, 10, 200, 200))

        val result = imageCompressor().compressToTmpFile(
            inputStreamProvider = { file.inputStream() },
            resizeMode = ResizeMode.Strict(maxWidth = 2048, maxHeight = 2048),
            outputFormat = ImageOutputFormat.PngOrJpeg(jpegQuality = 85),
        ).getOrThrow()

        assertThat(result.mimeType).isEqualTo(MimeTypes.Png)
        assertThat(result.file.hasPngMagicBytes()).isTrue()
        assertThat(result.file.hasJpegMagicBytes()).isFalse()
        assertThat(result.width).isEqualTo(2048)
    }

    private fun TestScope.imageCompressor() = ImageCompressor(
        context = context,
        dispatchers = testCoroutineDispatchers(),
    )

    private fun createSourceFile(
        fileName: String,
        width: Int,
        height: Int,
        transparentRect: Rect? = null,
    ): File {
        val dir = File(context.cacheDir, "image_compressor_test_inputs").apply { mkdirs() }
        val bitmap = createTestBitmap(width = width, height = height, transparentRect = transparentRect)
        return File(dir, fileName).writeTestImage(bitmap = bitmap, format = Bitmap.CompressFormat.PNG)
    }
}
