/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaifuMediaTest {
    @Test
    fun `resolveWaifuMediaType - uses the response Content-Type`() {
        assertThat(resolveWaifuMediaType("image/png", ".jpeg"))
            .isEqualTo(WaifuMediaType("image/png", ".png"))
        assertThat(resolveWaifuMediaType("image/gif", ".jpeg"))
            .isEqualTo(WaifuMediaType("image/gif", ".gif"))
        assertThat(resolveWaifuMediaType("image/jpeg", ".png"))
            .isEqualTo(WaifuMediaType("image/jpeg", ".jpeg"))
        assertThat(resolveWaifuMediaType("image/webp", ".jpeg"))
            .isEqualTo(WaifuMediaType("image/webp", ".webp"))
    }

    @Test
    fun `resolveWaifuMediaType - ignores parameters and casing in Content-Type`() {
        assertThat(resolveWaifuMediaType("IMAGE/PNG; charset=binary", null))
            .isEqualTo(WaifuMediaType("image/png", ".png"))
    }

    @Test
    fun `resolveWaifuMediaType - normalizes the legacy image jpg MIME type`() {
        assertThat(resolveWaifuMediaType("image/jpg", null))
            .isEqualTo(WaifuMediaType("image/jpeg", ".jpeg"))
    }

    @Test
    fun `resolveWaifuMediaType - falls back to the API extension`() {
        assertThat(resolveWaifuMediaType(null, ".webp"))
            .isEqualTo(WaifuMediaType("image/webp", ".webp"))
        assertThat(resolveWaifuMediaType("application/octet-stream", "png"))
            .isEqualTo(WaifuMediaType("image/png", ".png"))
    }

    @Test
    fun `resolveWaifuMediaType - defaults to jpeg when nothing is known`() {
        assertThat(resolveWaifuMediaType(null, null))
            .isEqualTo(WaifuMediaType("image/jpeg", ".jpeg"))
        assertThat(resolveWaifuMediaType("application/octet-stream", ".bmp"))
            .isEqualTo(WaifuMediaType("image/jpeg", ".jpeg"))
    }

    @Test
    fun `displayNameFor - uses the resolved extension`() {
        val image = aWaifuImage(id = 42, extension = ".jpeg")
        assertThat(image.displayNameFor("image/png")).isEqualTo("waifu-42.png")
        assertThat(image.displayNameFor("image/gif")).isEqualTo("waifu-42.gif")
        assertThat(image.displayNameFor(null)).isEqualTo("waifu-42.jpeg")
    }
}
