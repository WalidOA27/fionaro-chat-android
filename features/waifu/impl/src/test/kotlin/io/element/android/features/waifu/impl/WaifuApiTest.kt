/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class WaifuApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WaifuApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = WaifuApi(
            okHttpClient = OkHttpClient.Builder().build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `getTags - returns tags from response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":12,"name":"Waifu","slug":"waifu","imageCount":4276}]}""")
        )

        val tags = api.getTags()
        assertThat(tags).hasSize(1)
        assertThat(tags.first().name).isEqualTo("Waifu")
        assertThat(tags.first().imageCount).isEqualTo(4276)

        val request = server.takeRequest()
        assertThat(request.path).startsWith("/tags")
    }

    @Test
    fun `getImages - builds query params and returns response`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"items":[{"id":1997,"extension":".jpeg","url":"https://cdn.waifu.im/1997.jpeg","width":1203,"height":2047,"byteSize":186575,"isNsfw":false,"isAnimated":false}],"pageNumber":2,"totalPages":10,"hasNextPage":true}"""
                )
        )

        val response = api.getImages(tags = listOf("Waifu"), isNsfw = false, pageNumber = 2)
        assertThat(response.items).hasSize(1)
        assertThat(response.items.first().id).isEqualTo(1997)
        assertThat(response.pageNumber).isEqualTo(2)
        assertThat(response.hasNextPage).isTrue()

        val request = server.takeRequest()
        assertThat(request.path).startsWith("/images")
        assertThat(request.path).contains("includedTags=Waifu")
        assertThat(request.path).contains("isNsfw=false")
        assertThat(request.path).contains("pageNumber=2")
        assertThat(request.path).contains("pageSize=12")
        assertThat(request.path).doesNotContain("order=random")
        assertThat(request.path).contains("full=true")
    }

    @Test
    fun `getImages - envia order=random cuando random es true`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"items":[{"id":1997,"extension":".jpeg","url":"https://cdn.waifu.im/1997.jpeg","width":1203,"height":2047,"byteSize":186575,"isNsfw":false,"isAnimated":false}],"pageNumber":1,"totalPages":10,"hasNextPage":true}"""
                )
        )

        api.getImages(tags = emptyList(), isNsfw = false, pageNumber = 1, random = true)
        val request = server.takeRequest()
        assertThat(request.path).contains("order=random")
    }

    @Test
    fun `getImages - throws with status 403`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"code":40301,"error":"forbidden"}""")
        )

        val exception = runCatching { api.getImages(tags = emptyList(), isNsfw = false, pageNumber = 1) }.exceptionOrNull()
        assertThat(exception).isInstanceOf(WaifuApiException::class.java)
        assertThat((exception as WaifuApiException).code).isEqualTo(403)
    }
}
