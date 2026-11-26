/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GifQuantizerTest {
    @Test
    fun `quantize - a reused quantizer gives the same palette as a fresh one`() {
        val quantizer = GifQuantizer()

        quantizer.quantize(gradientFrame(offset = 17))
        val reused = quantizer.quantize(gradientFrame(offset = 130))
        val fresh = GifQuantizer().quantize(gradientFrame(offset = 130))

        assertThat(reused.colors.toList()).isEqualTo(fresh.colors.toList())
    }

    @Test
    fun `quantize - quantizing the same frame twice gives the same palette`() {
        val quantizer = GifQuantizer()
        val frame = gradientFrame(offset = 64)

        val first = quantizer.quantize(frame)
        val second = quantizer.quantize(frame)

        assertThat(second.colors.toList()).isEqualTo(first.colors.toList())
    }

    private fun gradientFrame(offset: Int): IntArray = IntArray(32 * 32) { index ->
        val red = index % 32 * 8 + offset and 0xFF
        val green = index / 32 * 8 + offset and 0xFF
        val blue = (index % 32 + index / 32) * 4 + offset and 0xFF
        0xFF shl 24 or (red shl 16) or (green shl 8) or blue
    }
}
