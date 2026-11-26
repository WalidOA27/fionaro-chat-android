/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.graphics.Canvas
import com.google.common.truth.Truth.assertThat
import io.element.android.features.stickers.impl.gif.ParsedGif
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment

class StickerAnimationProcessorTest : RobolectricTest() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val decoder = FakeAnimatedImageDecoder()

    @Test
    fun `processAnimatedImage - re-encodes a large GIF as a sticker sized animation`() = runTest {
        decoder.image = FakeDecodedAnimatedImage(width = 700, height = 400, durationMs = 160)
        val input = temporaryFolder.newFile("large.gif").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sut = createProcessor()
        val progress = mutableListOf<Float>()

        val result = sut.processAnimatedImage(input, MimeTypes.Gif) { progress.add(it) }

        val sticker = result.getOrThrow()
        assertThat(sticker.mimeType).isEqualTo(MimeTypes.Gif)
        assertThat(sticker.width).isEqualTo(384)
        assertThat(sticker.height).isEqualTo(219)
        assertThat(sticker.file.length()).isAtMost(3L * 1024L * 1024L)
        assertThat(progress).isNotEmpty()
        assertThat(progress.last()).isEqualTo(1f)

        val bytes = sticker.file.readBytes()
        assertThat(String(bytes.copyOfRange(0, 6), Charsets.US_ASCII)).isEqualTo("GIF89a")
        assertThat(bytes.last().toInt() and 0xFF).isEqualTo(0x3B)
        val parsed = ParsedGif.parse(bytes)
        assertThat(parsed.width).isEqualTo(384)
        assertThat(parsed.height).isEqualTo(219)
        assertThat(parsed.frames).hasSize(2)
        assertThat(parsed.loopCount).isEqualTo(0)
    }

    @Test
    fun `processAnimatedImage - a GIF which already fits is not resized`() = runTest {
        decoder.image = FakeDecodedAnimatedImage(width = 100, height = 50, durationMs = 500)
        val input = temporaryFolder.newFile("small.gif").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sut = createProcessor()

        val sticker = sut.processAnimatedImage(input, MimeTypes.Gif).getOrThrow()

        assertThat(sticker.width).isEqualTo(100)
        assertThat(sticker.height).isEqualTo(50)
    }

    @Test
    fun `processAnimatedImage - rejects a file which cannot be decoded`() = runTest {
        decoder.image = null
        val input = temporaryFolder.newFile("broken.gif").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val sut = createProcessor()

        val result = sut.processAnimatedImage(input, MimeTypes.Gif)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(StickerAnimationException.MediaUnreadable::class.java)
    }

    @Test
    fun `processAnimatedImage - rejects a mime type which is not a GIF`() = runTest {
        decoder.image = FakeDecodedAnimatedImage(width = 100, height = 50, durationMs = 500)
        val input = temporaryFolder.newFile("image.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sut = createProcessor()

        val result = sut.processAnimatedImage(input, MimeTypes.WebP)

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun TestScope.createProcessor() = DefaultStickerAnimationProcessor(
        context = RuntimeEnvironment.getApplication(),
        animatedImageDecoder = decoder,
        dispatchers = testCoroutineDispatchers(),
    )
}

private class FakeAnimatedImageDecoder(
    var image: DecodedAnimatedImage? = null,
) : AnimatedImageDecoder {
    override fun decode(bytes: ByteArray): DecodedAnimatedImage? = image
}

private class FakeDecodedAnimatedImage(
    override val width: Int,
    override val height: Int,
    override val durationMs: Int,
) : DecodedAnimatedImage {
    override fun drawFrame(canvas: Canvas, timeMs: Int) = Unit
}
