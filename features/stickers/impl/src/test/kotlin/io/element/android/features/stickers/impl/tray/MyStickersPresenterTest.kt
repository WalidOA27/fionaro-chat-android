/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.tray

import app.cash.turbine.TurbineTestContext
import com.google.common.truth.Truth.assertThat
import io.element.android.features.stickers.impl.R
import io.element.android.features.stickers.test.FakeSoundStickerSender
import io.element.android.features.stickers.test.InMemorySoundStickerStore
import io.element.android.features.stickers.test.aSoundSticker
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MyStickersPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val navigateUp = lambdaRecorder<Unit> {}
    private val sender = FakeSoundStickerSender()
    private val sticker = aSoundSticker(id = "saved-sticker")
    private val store = InMemorySoundStickerStore(initialStickers = listOf(sticker))

    @Test
    fun `present - exposes the saved stickers`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val state = awaitState { it.stickers.isNotEmpty() }
            assertThat(state.stickers).containsExactly(sticker)
            assertThat(state.sendingStickerId).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - tapping a sticker sends it and closes the tray`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(MyStickersEvent.Send(sticker.id))
            advanceUntilIdle()

            val parameters = sender.lastParameters
            assertThat(parameters?.imageFile).isEqualTo(File(sticker.imagePath))
            assertThat(parameters?.imageMimeType).isEqualTo(sticker.imageMimeType)
            assertThat(parameters?.width).isEqualTo(sticker.width)
            assertThat(parameters?.height).isEqualTo(sticker.height)
            assertThat(parameters?.audioFile).isEqualTo(File(sticker.audioPath))
            assertThat(parameters?.audioMimeType).isEqualTo(sticker.audioMimeType)
            assertThat(parameters?.durationMs).isEqualTo(sticker.durationMs)
            assertThat(parameters?.body).isEqualTo(sticker.body)
            navigateUp.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a send failure shows an error and keeps the tray open`() = runTest {
        sender.givenResult(Result.failure(IllegalStateException("Network error")))
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(MyStickersEvent.Send(sticker.id))
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_my_stickers_error_send)
            assertThat(state.sendingStickerId).isNull()
            navigateUp.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - deleting a sticker asks for confirmation then removes it`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(MyStickersEvent.RequestDelete(sticker.id))
            val confirmationState = awaitState { it.stickerPendingDelete != null }
            assertThat(confirmationState.stickerPendingDelete).isEqualTo(sticker)

            confirmationState.eventSink(MyStickersEvent.ConfirmDelete)
            advanceUntilIdle()

            val state = awaitState { it.stickers.isEmpty() }
            assertThat(state.stickerPendingDelete).isNull()
            assertThat(store.deleteCalls).containsExactly(sticker.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - dismissing the delete confirmation keeps the sticker`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(MyStickersEvent.RequestDelete(sticker.id))
            val confirmationState = awaitState { it.stickerPendingDelete != null }
            confirmationState.eventSink(MyStickersEvent.DismissDelete)

            val state = awaitState { it.stickerPendingDelete == null }
            assertThat(state.stickers).containsExactly(sticker)
            assertThat(store.deleteCalls).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun TurbineTestContext<MyStickersState>.awaitState(
        predicate: (MyStickersState) -> Boolean,
    ): MyStickersState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }

    private fun TestScope.createPresenter() = MyStickersPresenter(
        room = FakeJoinedRoom(),
        soundStickerStore = store,
        soundStickerSender = sender,
        snackbarDispatcher = SnackbarDispatcher(),
        navigateUp = { navigateUp() },
    )
}
