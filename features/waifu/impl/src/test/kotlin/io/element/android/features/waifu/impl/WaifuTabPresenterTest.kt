/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import kotlin.time.Duration.Companion.seconds

class WaifuTabPresenterTest : RobolectricTest() {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun TestScope.createPresenter() = WaifuTabPresenter(
        waifuApi = WaifuApi(
            okHttpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        ),
        context = RuntimeEnvironment.getApplication(),
        dispatchers = testCoroutineDispatchers(useUnconfinedTestDispatcher = true),
    )

    private fun enqueueTags() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items":[{"id":12,"name":"Waifu","slug":"waifu","imageCount":4276}]}""")
        )
    }

    private fun enqueueImages(
        pageNumber: Int = 1,
        hasNextPage: Boolean = false,
        ids: List<Long> = listOf(1997),
    ) {
        val items = ids.joinToString(",") { id ->
            """{"id":$id,"extension":".jpeg","url":"https://cdn.waifu.im/$id.jpeg","width":1203,"height":2047,"byteSize":186575,"isNsfw":false,"isAnimated":false}"""
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"items":[$items],"pageNumber":$pageNumber,"totalPages":10,"hasNextPage":$hasNextPage}"""
                )
        )
    }

    @Test
    fun `present - loads tags and initial SFW random page`() = runTest {
        enqueueTags()
        enqueueImages(ids = listOf(1997, 1998, 1999))
        val presenter = createPresenter()

        presenter.test {
            val state = consumeItemsUntilPredicate(10.seconds) { !it.isLoading && it.images.isNotEmpty() }.last()
            assertThat(state.isNsfw).isFalse()
            assertThat(state.error).isNull()
            assertThat(state.images.map { it.id }).containsExactly(1997L, 1998L, 1999L).inOrder()
            assertThat(state.tags.map { it.slug }).containsExactly("waifu")

            val tagsRequest = server.takeRequest()
            val imagesRequest = server.takeRequest()
            assertThat(tagsRequest.path).startsWith("/tags")
            assertThat(imagesRequest.path).contains("order=random")
            assertThat(imagesRequest.path).contains("isNsfw=false")
            assertThat(imagesRequest.path).contains("pageNumber=1")
        }
    }

    @Test
    fun `present - 403 exposes NSFW error without crashing`() = runTest {
        enqueueTags()
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"code":40301,"error":"forbidden"}""")
        )
        val presenter = createPresenter()

        presenter.test {
            val state = consumeItemsUntilPredicate(10.seconds) { !it.isLoading }.last()
            assertThat(state.error).isEqualTo(WaifuError.NsfwForbidden)
            assertThat(state.images).isEmpty()
            assertThat(state.isNsfw).isFalse()
        }
    }

    @Test
    fun `present - toggles NSFW and tags without enabling NSFW by default`() = runTest {
        enqueueTags()
        enqueueImages()
        val presenter = createPresenter()

        presenter.test {
            val loaded = consumeItemsUntilPredicate(10.seconds) { !it.isLoading && it.images.isNotEmpty() }.last()
            loaded.eventSink(WaifuEvent.ToggleNsfw(true))
            val nsfwState = consumeItemsUntilPredicate { it.isNsfw }.last()
            assertThat(nsfwState.isNsfw).isTrue()
            assertThat(loaded.isNsfw).isFalse()

            nsfwState.eventSink(WaifuEvent.ToggleTag("maid"))
            val selectedState = consumeItemsUntilPredicate { "maid" in it.selectedTags }.last()
            assertThat(selectedState.selectedTags).containsExactly("maid")

            selectedState.eventSink(WaifuEvent.ToggleTag("maid"))
            val unselectedState = consumeItemsUntilPredicate { "maid" !in it.selectedTags }.last()
            assertThat(unselectedState.selectedTags).isEmpty()
        }
    }

    @Test
    fun `present - appends the next page on LoadMore`() = runTest {
        enqueueTags()
        enqueueImages(pageNumber = 1, hasNextPage = true, ids = listOf(1))
        val presenter = createPresenter()

        presenter.test {
            val loaded = consumeItemsUntilPredicate(10.seconds) { !it.isLoading && it.images.size == 1 }.last()
            enqueueImages(pageNumber = 2, hasNextPage = false, ids = listOf(2))
            loaded.eventSink(WaifuEvent.LoadMore)
            val state = consumeItemsUntilPredicate(10.seconds) { !it.isLoadingMore && it.images.size == 2 }.last()
            assertThat(state.images.map { it.id }).containsExactly(1L, 2L).inOrder()
            assertThat(state.hasNextPage).isFalse()
        }
    }

    @Test
    fun `present - lightbox navigation is bounded`() = runTest {
        enqueueTags()
        enqueueImages(ids = listOf(1, 2))
        val presenter = createPresenter()

        presenter.test {
            val loaded = consumeItemsUntilPredicate(10.seconds) { !it.isLoading && it.images.size == 2 }.last()

            loaded.eventSink(WaifuEvent.OpenImage(0))
            val opened = consumeItemsUntilPredicate { it.lightboxIndex == 0 }.last()
            assertThat(opened.lightboxIndex).isEqualTo(0)

            opened.eventSink(WaifuEvent.NextImage)
            val next = consumeItemsUntilPredicate { it.lightboxIndex == 1 }.last()
            assertThat(next.lightboxIndex).isEqualTo(1)

            next.eventSink(WaifuEvent.PrevImage)
            val previous = consumeItemsUntilPredicate { it.lightboxIndex == 0 }.last()
            assertThat(previous.lightboxIndex).isEqualTo(0)

            previous.eventSink(WaifuEvent.CloseLightbox)
            assertThat(consumeItemsUntilPredicate { it.lightboxIndex == null }.last().lightboxIndex).isNull()
        }
    }
}
