/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.Manifest
import android.net.Uri
import app.cash.turbine.TurbineTestContext
import com.google.common.truth.Truth.assertThat
import io.element.android.features.stickers.impl.R
import io.element.android.features.stickers.test.FakeSoundStickerSender
import io.element.android.features.stickers.test.FakeStickerMediaFileReader
import io.element.android.features.stickers.test.FakeStickerSoundPreviewPlayer
import io.element.android.features.stickers.test.InMemorySoundStickerStore
import io.element.android.features.stickers.test.aStickerImage
import io.element.android.features.stickers.test.aStickerSoundFile
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.permissions.api.aPermissionsState
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.voicerecorder.test.FakeVoiceRecorder
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.lambda.lambdaRecorder
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SoundStickerCreatorPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val navigateUp = lambdaRecorder<Unit> {}
    private val sender = FakeSoundStickerSender()
    private val store = InMemorySoundStickerStore()
    private val reader = FakeStickerMediaFileReader()
    private val pickedUri: Uri = mockk()
    private val pickerProvider = FakePickerProvider().apply {
        givenResult(pickedUri)
    }
    private val previewPlayer = FakeStickerSoundPreviewPlayer()
    private var voiceRecorder = FakeVoiceRecorder(recordingDuration = 2.seconds)
    private val permissionsPresenter = FakePermissionsPresenter(
        aPermissionsState(showDialog = false, permission = Manifest.permission.RECORD_AUDIO, permissionGranted = true)
    )

    @Test
    fun `present - initial state`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val state = awaitItem()
            assertThat(state.image).isNull()
            assertThat(state.sound).isNull()
            assertThat(state.canSend).isFalse()
            assertThat(state.body).isEqualTo("Sticker")
            assertThat(state.saveToMyStickers).isTrue()
            assertThat(state.isRecording).isFalse()
            assertThat(state.isSending).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - picking an image updates the state`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickImage)
            advanceUntilIdle()

            assertThat(reader.imageCalls).containsExactly(pickedUri)
            val state = awaitState { it.image != null }
            assertThat(state.image).isEqualTo(aStickerImage())
            assertThat(state.canSend).isFalse()
            assertThat(reader.imageCalls).containsExactly(pickedUri)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - stopping the recording provides a sound`() = runTest {
        voiceRecorder = FakeVoiceRecorder(recordingDuration = 5.seconds)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.StartRecording)
            advanceUntilIdle()
            val recordingState = awaitState { it.isRecording }
            recordingState.eventSink(SoundStickerCreatorEvent.StopRecording)
            advanceUntilIdle()

            val state = awaitState { it.sound != null }
            assertThat(state.sound?.durationMs).isEqualTo(5000)
            voiceRecorder.assertCalls(started = 1, stopped = 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - recording auto-stops at 10 seconds`() = runTest {
        voiceRecorder = FakeVoiceRecorder(recordingDuration = 11.seconds)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.StartRecording)
            advanceUntilIdle()

            val state = awaitState { it.sound != null }
            assertThat(state.sound?.durationMs).isEqualTo(10_000)
            assertThat(state.isRecording).isFalse()
            voiceRecorder.assertCalls(started = 1, stopped = 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - an audio file longer than 10 seconds shows an error`() = runTest {
        reader.givenAudioResult { Result.success(aStickerSoundFile(durationMs = 12_000)) }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickAudio)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_audio_too_long)
            assertThat(state.sound).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a failing media read shows an error`() = runTest {
        reader.givenImageResult { Result.failure(IllegalStateException("Broken image")) }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickImage)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_image)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sending saves to my stickers and navigates up`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareImageAndSound(this)
            preparedState.eventSink(SoundStickerCreatorEvent.SetBody("My sticker"))
            advanceUntilIdle()
            preparedState.eventSink(SoundStickerCreatorEvent.Send)
            advanceUntilIdle()

            assertThat(sender.lastParameters?.body).isEqualTo("My sticker")
            assertThat(sender.lastParameters?.imageFile).isEqualTo(aStickerImage().file)
            assertThat(sender.lastParameters?.audioFile).isNotNull()
            assertThat(sender.lastParameters?.durationMs).isEqualTo(2000)
            assertThat(store.addCalls).hasSize(1)
            assertThat(store.addCalls.single().body).isEqualTo("My sticker")
            navigateUp.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sending with save disabled does not save to my stickers`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareImageAndSound(this)
            preparedState.eventSink(SoundStickerCreatorEvent.SetSaveToMyStickers(false))
            advanceUntilIdle()
            preparedState.eventSink(SoundStickerCreatorEvent.Send)
            advanceUntilIdle()

            assertThat(sender.lastParameters).isNotNull()
            assertThat(store.addCalls).isEmpty()
            navigateUp.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a send failure keeps the screen open`() = runTest {
        sender.givenResult(Result.failure(IllegalStateException("Network error")))
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareImageAndSound(this)
            preparedState.eventSink(SoundStickerCreatorEvent.Send)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_send)
            assertThat(state.isSending).isFalse()
            navigateUp.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - recording requests the microphone permission first`() = runTest {
        permissionsPresenter.setPermissionDenied()
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.StartRecording)
            advanceUntilIdle()

            val state = awaitState { it.permissionsState.showDialog }
            assertThat(state.isRecording).isFalse()
            voiceRecorder.assertCalls(started = 0)

            permissionsPresenter.setPermissionGranted()
            advanceUntilIdle()
            voiceRecorder.assertCalls(started = 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun TurbineTestContext<SoundStickerCreatorState>.awaitState(
        predicate: (SoundStickerCreatorState) -> Boolean,
    ): SoundStickerCreatorState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }

    private suspend fun TestScope.prepareImageAndSound(context: TurbineTestContext<SoundStickerCreatorState>): SoundStickerCreatorState {
        var state = context.awaitItem()
        assertThat(state.image).isNull()
        state.eventSink(SoundStickerCreatorEvent.PickImage)
        advanceUntilIdle()
        state = context.awaitState { it.image != null }
        state.eventSink(SoundStickerCreatorEvent.StartRecording)
        advanceUntilIdle()
        context.awaitState { it.isRecording }
        state.eventSink(SoundStickerCreatorEvent.StopRecording)
        advanceUntilIdle()
        return context.awaitState { it.canSend }
    }

    private fun TestScope.createPresenter() = SoundStickerCreatorPresenter(
        room = FakeJoinedRoom(),
        soundStickerSender = sender,
        soundStickerStore = store,
        mediaFileReader = reader,
        voiceRecorder = voiceRecorder,
        previewPlayer = previewPlayer,
        mediaPickerProvider = pickerProvider,
        dispatchers = testCoroutineDispatchers(),
        sessionCoroutineScope = this,
        permissionsPresenterFactory = FakePermissionsPresenterFactory(permissionsPresenter),
        navigateUp = { navigateUp() },
    )
}
