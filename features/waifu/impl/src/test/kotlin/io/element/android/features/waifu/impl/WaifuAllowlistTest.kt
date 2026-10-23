/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WaifuAllowlistTest {
    @Test
    fun `parseAllowlist - returns users from config`() {
        val body = """{"fionaroWaifu":{"users":["@walid:fionaro.pw","@tmoat057:fionaro.pw"]}}"""
        assertThat(parseAllowlist(body)).containsExactly("@walid:fionaro.pw", "@tmoat057:fionaro.pw")
    }

    @Test
    fun `parseAllowlist - returns null when section missing`() {
        assertThat(parseAllowlist("""{"brand":"Fionaro Chat"}""")).isNull()
    }

    @Test
    fun `parseAllowlist - returns empty set when no users`() {
        val body = """{"fionaroWaifu":{}}"""
        assertThat(parseAllowlist(body)).isEmpty()
    }

    @Test
    fun `parseAllowlist - ignores unknown keys`() {
        val body = """{"brand":"X","features":{"a":true},"fionaroWaifu":{"users":["@a:domain"],"extra":1}}"""
        assertThat(parseAllowlist(body)).containsExactly("@a:domain")
    }

    @Test
    fun `parseAllowlist - malformed JSON returns null instead of throwing`() {
        assertThat(parseAllowlist("""{"fionaroWaifu":{"users":["@a:domain"]""")).isNull()
        assertThat(parseAllowlist("not json at all")).isNull()
        assertThat(parseAllowlist("")).isNull()
    }

    @Test
    fun `parseAllowlist - malformed JSON leaves no users, so the tab stays hidden`() {
        assertThat(parseAllowlist("""{"fionaroWaifu":{"users":["@a:domain"]""").orEmpty()).isEmpty()
        assertThat(parseAllowlist("not json at all").orEmpty()).isEmpty()
    }

    @Test
    fun `parseAllowlist - wrong users type returns null instead of throwing`() {
        assertThat(parseAllowlist("""{"fionaroWaifu":{"users":"@a:domain"}}""")).isNull()
        assertThat(parseAllowlist("""{"fionaroWaifu":{"users":[1,2,3]}}""")).isNull()
        assertThat(parseAllowlist("""{"fionaroWaifu":"nope"}""")).isNull()
        assertThat(parseAllowlist("""[]""")).isNull()
    }

    @Test
    fun `parseAllowlist - filters invalid user ids and normalizes entries`() {
        val body = """
            {"fionaroWaifu":{"users":[
              " @a:domain ",
              "@a:domain",
              "@b:domain",
              "a:domain",
              "@no-colon",
              "",
              "   ",
              "@with space:domain"
            ]}}
        """.trimIndent()
        assertThat(parseAllowlist(body)).containsExactly("@a:domain", "@b:domain")
    }
}
