/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.utils

import com.google.common.truth.Truth.assertThat
import io.element.android.features.call.impl.utils.CallWidgetUrlBuilder
import org.junit.Test

class CallWidgetUrlBuilderTest {
    @Test
    fun `build - uses the room path so Element Call never renders its home page`() {
        val url = "https://call.fionaro.pw/#?roomId=%21room%3Afionaro.pw&widgetId=widget-id&userId=%40alice%3Afionaro.pw"

        val result = CallWidgetUrlBuilder.build(url)

        assertThat(result).isEqualTo(
            "https://call.fionaro.pw/room/#?roomId=%21room%3Afionaro.pw&widgetId=widget-id&userId=%40alice%3Afionaro.pw&skipLobby=true"
        )
    }

    @Test
    fun `build - does not duplicate the call path for an already fixed url`() {
        val url = "https://call.fionaro.pw/room/#?roomId=%21room%3Afionaro.pw"

        val result = CallWidgetUrlBuilder.build(url)

        assertThat(result).isEqualTo("https://call.fionaro.pw/room/#?roomId=%21room%3Afionaro.pw&skipLobby=true")
    }

    @Test
    fun `build - forces skipLobby to true instead of appending a second value`() {
        val url = "https://call.fionaro.pw/room/#?roomId=%21room%3Afionaro.pw&skipLobby=false"

        val result = CallWidgetUrlBuilder.build(url)

        assertThat(result).isEqualTo("https://call.fionaro.pw/room/#?roomId=%21room%3Afionaro.pw&skipLobby=true")
    }

    @Test
    fun `build - adds a query separator when the url has no query string`() {
        val url = "https://call.fionaro.pw/room/"

        val result = CallWidgetUrlBuilder.build(url)

        assertThat(result).isEqualTo("https://call.fionaro.pw/room/?skipLobby=true")
    }

    @Test
    fun `build - keeps a custom Element Call base url untouched except for skipLobby`() {
        val url = "https://custom.element.io/#?roomId=%21room%3Afionaro.pw"

        val result = CallWidgetUrlBuilder.build(url)

        assertThat(result).isEqualTo("https://custom.element.io/#?roomId=%21room%3Afionaro.pw&skipLobby=true")
    }

    @Test
    fun `build - replaces the legacy Android asset host with the Element Call host`() {
        val url = "https://appassets.androidplatform.net/#?roomId=%21room%3Afionaro.pw"

        val result = CallWidgetUrlBuilder.build(url)

        assertThat(result).isEqualTo("https://call.fionaro.pw/room/#?roomId=%21room%3Afionaro.pw&skipLobby=true")
    }
}
