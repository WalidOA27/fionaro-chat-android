/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl

import android.graphics.Bitmap
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.features.stickers.test.FakeStickerAnimationProcessor
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import java.io.File

class DefaultStickerImageProcessorTest : RobolectricTest() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val animationProcessor = FakeStickerAnimationProcessor()

    @Test
    fun `process - scales a large image to fit in 512x512 and encodes it as PNG`() = runTest {
        val sut = createProcessor()
        val input = temporaryFolder.writePng("large.png", width = 1024, height = 768)

        val result = sut.process(input, MimeTypes.Png)

        val sticker = result.getOrThrow()
        assertThat(sticker.width).isEqualTo(512)
        assertThat(sticker.height).isEqualTo(384)
        assertThat(sticker.mimeType).isEqualTo(MimeTypes.Png)
        assertThat(sticker.file).isNotEqualTo(input)
        assertThat(sticker.file.exists()).isTrue()
        assertThat(sticker.file.extension).isEqualTo("png")
    }

    @Test
    fun `process - an image smaller than 512px is not upscaled`() = runTest {
        val sut = createProcessor()
        val input = temporaryFolder.writePng("small.png", width = 100, height = 50)

        val result = sut.process(input, MimeTypes.Png)

        val sticker = result.getOrThrow()
        assertThat(sticker.width).isEqualTo(100)
        assertThat(sticker.height).isEqualTo(50)
    }

    @Test
    fun `process - a GIF which already fits is passed through unchanged`() = runTest {
        val sut = createProcessor()
        val input = temporaryFolder.writePng("small.gif", width = 64, height = 64)

        val result = sut.process(input, MimeTypes.Gif)

        val sticker = result.getOrThrow()
        assertThat(sticker.file).isEqualTo(input)
        assertThat(sticker.mimeType).isEqualTo(MimeTypes.Gif)
        assertThat(sticker.width).isEqualTo(64)
        assertThat(sticker.height).isEqualTo(64)
    }

    @Test
    fun `process - a GIF larger than 512px is re-encoded as an animated sticker`() = runTest {
        val sut = createProcessor()
        val input = temporaryFolder.writePng("large.gif", width = 1024, height = 64)

        val result = sut.process(input, MimeTypes.Gif)

        val sticker = result.getOrThrow()
        assertThat(animationProcessor.imageCalls).hasSize(1)
        assertThat(animationProcessor.imageCalls.single().imageFile).isEqualTo(input)
        assertThat(animationProcessor.imageCalls.single().mimeType).isEqualTo(MimeTypes.Gif)
        assertThat(sticker.mimeType).isEqualTo(MimeTypes.Gif)
    }

    @Test
    fun `process - a large WebP is still rejected`() = runTest {
        val sut = createProcessor()
        val input = temporaryFolder.writePng("large.webp", width = 1024, height = 64)

        val result = sut.process(input, MimeTypes.WebP)

        assertThat(result.isFailure).isTrue()
        assertThat(animationProcessor.imageCalls).isEmpty()
    }

    @Test
    fun `process - a missing file is rejected`() = runTest {
        val sut = createProcessor()
        val input = File(temporaryFolder.root, "missing.png")

        val result = sut.process(input, MimeTypes.Png)

        assertThat(result.isFailure).isTrue()
    }

    private fun TestScope.createProcessor() = DefaultStickerImageProcessor(
        context = RuntimeEnvironment.getApplication(),
        animationProcessor = animationProcessor,
        dispatchers = testCoroutineDispatchers(),
    )

    private fun TemporaryFolder.writePng(name: String, width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return newFile(name).apply {
            outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }.also {
            bitmap.recycle()
        }
    }
}
