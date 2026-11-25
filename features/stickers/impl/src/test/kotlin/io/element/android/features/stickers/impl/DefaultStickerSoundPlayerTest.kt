/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.item.event.StickerAudio
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.AN_EVENT_ID_2
import io.element.android.libraries.matrix.test.media.FakeMatrixMediaLoader
import io.element.android.libraries.mediaplayer.api.MediaPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultStickerSoundPlayerTest {
    private val mediaPlayer = ControllableMediaPlayer()
    private val mediaLoader = FakeMatrixMediaLoader().apply { path = "/tmp/sticker_sound.ogg" }

    @Test
    fun `play - plays the sound three times then stops`() = runTest {
        val sut = createPlayer()

        val result = sut.play(AN_EVENT_ID, aStickerAudio())
        advanceUntilIdle()

        assertThat(result.isSuccess).isTrue()
        assertThat(mediaPlayer.playCount).isEqualTo(1)
        assertThat(sut.playingEventId.value).isEqualTo(AN_EVENT_ID)

        mediaPlayer.finishTrack()
        advanceUntilIdle()
        assertThat(mediaPlayer.playCount).isEqualTo(2)
        assertThat(sut.playingEventId.value).isEqualTo(AN_EVENT_ID)

        mediaPlayer.finishTrack()
        advanceUntilIdle()
        assertThat(mediaPlayer.playCount).isEqualTo(3)

        mediaPlayer.finishTrack()
        advanceUntilIdle()
        assertThat(mediaPlayer.playCount).isEqualTo(3)
        assertThat(sut.playingEventId.value).isNull()
        assertThat(mediaPlayer.state.value.isPlaying).isFalse()
    }

    @Test
    fun `play - starting a new sound stops the previous one`() = runTest {
        val sut = createPlayer()

        sut.play(AN_EVENT_ID, aStickerAudio())
        advanceUntilIdle()
        sut.play(AN_EVENT_ID_2, aStickerAudio())
        advanceUntilIdle()

        assertThat(sut.playingEventId.value).isEqualTo(AN_EVENT_ID_2)
        // Only the new sound can complete loops.
        mediaPlayer.finishTrack()
        advanceUntilIdle()
        assertThat(mediaPlayer.playCount).isEqualTo(3)
        assertThat(sut.playingEventId.value).isEqualTo(AN_EVENT_ID_2)

        sut.stop()
    }

    @Test
    fun `stop - stops the current sound`() = runTest {
        val sut = createPlayer()
        sut.play(AN_EVENT_ID, aStickerAudio())
        advanceUntilIdle()

        sut.stop()

        assertThat(sut.playingEventId.value).isNull()
        assertThat(mediaPlayer.state.value.isPlaying).isFalse()
    }

    @Test
    fun `play - a download failure is returned and clears the state`() = runTest {
        mediaLoader.shouldFail = true
        val sut = createPlayer()

        val result = sut.play(AN_EVENT_ID, aStickerAudio())
        advanceUntilIdle()

        assertThat(result.isFailure).isTrue()
        assertThat(sut.playingEventId.value).isNull()
        assertThat(mediaPlayer.playCount).isEqualTo(0)
    }

    @Test
    fun `play - uses the audio mime type and downloaded path`() = runTest {
        val sut = createPlayer()

        sut.play(AN_EVENT_ID, aStickerAudio(mimeType = "audio/mp4"))
        advanceUntilIdle()

        assertThat(mediaPlayer.lastUri).isEqualTo("/tmp/sticker_sound.ogg")
        assertThat(mediaPlayer.lastMimeType).isEqualTo("audio/mp4")

        sut.stop()
    }

    private fun TestScope.createPlayer() = DefaultStickerSoundPlayer(
        mediaPlayer = mediaPlayer,
        mediaLoader = mediaLoader,
        roomCoroutineScope = this,
    )

    private fun aStickerAudio(mimeType: String = "audio/ogg") = StickerAudio(
        mediaSource = MediaSource("mxc://matrix.org/audio"),
        mimetype = mimeType,
        size = 1234,
        durationMs = 3000,
    )
}

private class ControllableMediaPlayer : MediaPlayer {
    private val _state = MutableStateFlow(MediaPlayer.State(
        isReady = false,
        isPlaying = false,
        isEnded = false,
        mediaId = null,
        currentPosition = 0,
        duration = null,
    ))
    override val state: StateFlow<MediaPlayer.State> = _state.asStateFlow()

    var playCount = 0
        private set
    var lastUri: String? = null
        private set
    var lastMimeType: String? = null
        private set

    override suspend fun setMedia(uri: String, mediaId: String, mimeType: String, startPositionMs: Long): MediaPlayer.State {
        lastUri = uri
        lastMimeType = mimeType
        _state.value = MediaPlayer.State(
            isReady = true,
            isPlaying = false,
            isEnded = false,
            mediaId = mediaId,
            currentPosition = startPositionMs,
            duration = 3000,
        )
        return _state.value
    }

    override fun play() {
        playCount++
        _state.update { it.copy(isPlaying = true, isEnded = false) }
    }

    override fun pause() {
        _state.update { it.copy(isPlaying = false) }
    }

    override fun seekTo(positionMs: Long) {
        _state.update { it.copy(currentPosition = positionMs) }
    }

    override fun setPlaybackSpeed(speed: Float) = Unit

    override fun close() = Unit

    fun finishTrack() {
        _state.update {
            it.copy(isPlaying = false, isReady = false, isEnded = true, currentPosition = it.duration ?: 0)
        }
    }
}
