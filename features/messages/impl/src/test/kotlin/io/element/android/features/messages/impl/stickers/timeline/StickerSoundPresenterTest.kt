/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.stickers.timeline

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.model.event.aStickerAudio
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemStickerContent
import io.element.android.features.stickers.api.StickerSoundEvent
import io.element.android.features.stickers.test.FakeStickerSoundPlayer
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class StickerSoundPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val stickerSoundPlayer = FakeStickerSoundPlayer()

    @Test
    fun `present - initial state is not playing`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val state = awaitItem()
            assertThat(state.isPlaying).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - toggling starts playing the sound`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(StickerSoundEvent.TogglePlay)

            val state = awaitItem()
            assertThat(state.isPlaying).isTrue()
            assertThat(stickerSoundPlayer.playCalls).containsExactly(AN_EVENT_ID to aStickerAudio())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - toggling while playing stops the sound`() = runTest {
        stickerSoundPlayer.givenPlayingEventId(AN_EVENT_ID)
        val presenter = createPresenter()

        presenter.test {
            val playingState = awaitItem()
            assertThat(playingState.isPlaying).isTrue()

            playingState.eventSink(StickerSoundEvent.TogglePlay)

            val stoppedState = awaitItem()
            assertThat(stoppedState.isPlaying).isFalse()
            assertThat(stickerSoundPlayer.stopCount).isEqualTo(1)
            assertThat(stickerSoundPlayer.playCalls).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a sticker without sound ignores the event`() = runTest {
        val presenter = StickerSoundPresenter(
            stickerSoundPlayer = stickerSoundPlayer,
            content = aTimelineItemStickerContent(eventId = AN_EVENT_ID, audio = null),
        )

        presenter.test {
            val state = awaitItem()
            assertThat(state.isPlaying).isFalse()
            state.eventSink(StickerSoundEvent.TogglePlay)
            assertThat(stickerSoundPlayer.playCalls).isEmpty()
            assertThat(stickerSoundPlayer.stopCount).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createPresenter() = StickerSoundPresenter(
        stickerSoundPlayer = stickerSoundPlayer,
        content = aTimelineItemStickerContent(eventId = AN_EVENT_ID, audio = aStickerAudio()),
    )
}
