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
import io.element.android.features.stickers.test.FakeStickerAnimationProcessor
import io.element.android.features.stickers.test.FakeStickerMediaFileReader
import io.element.android.features.stickers.test.FakeStickerSoundPreviewPlayer
import io.element.android.features.stickers.test.FakeStickerVideoAudioExtractor
import io.element.android.features.stickers.test.InMemorySoundStickerStore
import io.element.android.features.stickers.test.aStickerImage
import io.element.android.features.stickers.test.aStickerSoundFile
import io.element.android.features.stickers.test.aStickerVideoSource
import io.element.android.libraries.core.mimetype.MimeTypes
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SoundStickerCreatorPresenterTest {
    @get:Rule
    val warmUpRule = WarmUpRule()

    private val navigateUp = lambdaRecorder<Unit> {}
    private val sender = FakeSoundStickerSender()
    private val store = InMemorySoundStickerStore()
    private val reader = FakeStickerMediaFileReader()
    private val animationProcessor = FakeStickerAnimationProcessor()
    private val videoAudioExtractor = FakeStickerVideoAudioExtractor()
    private val pickedUri: Uri = mockk()
    private val pickerProvider = FakePickerProvider().apply {
        givenResult(pickedUri)
        givenMimeType(MimeTypes.Png)
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
            assertThat(state.video).isNull()
            assertThat(state.sound).isNull()
            assertThat(state.isPreparingAnimation).isFalse()
            assertThat(state.isUsingVideoSound).isFalse()
            assertThat(state.showVideoSoundOption).isFalse()
            assertThat(state.canChooseVideoStart).isFalse()
            assertThat(state.canSend).isFalse()
            assertThat(state.body).isEqualTo("Sticker")
            assertThat(state.saveToMyStickers).isTrue()
            assertThat(state.isRecording).isFalse()
            assertThat(state.isSending).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - picking a still image makes the sticker sendable without sound`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.image != null }
            assertThat(reader.imageCalls).containsExactly(pickedUri)
            assertThat(state.image).isEqualTo(aStickerImage())
            assertThat(state.video).isNull()
            assertThat(state.sound).isNull()
            assertThat(state.canSend).isTrue()
            assertThat(state.hasSound).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - picking an animated image keeps it animated`() = runTest {
        reader.givenImageResult { Result.success(aStickerImage(mimeType = MimeTypes.Gif)) }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.image != null }
            assertThat(state.image?.mimeType).isEqualTo(MimeTypes.Gif)
            assertThat(state.isAnimated).isTrue()
            assertThat(state.canSend).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - picking a video prepares a looping sticker with the video sound`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.image != null }
            assertThat(reader.videoCalls).containsExactly(pickedUri)
            assertThat(animationProcessor.videoCalls).hasSize(1)
            assertThat(animationProcessor.videoCalls.single().startMs).isEqualTo(0)
            assertThat(animationProcessor.videoCalls.single().durationMs).isEqualTo(6_000)
            assertThat(state.video?.source).isEqualTo(aStickerVideoSource())
            assertThat(state.video?.startMs).isEqualTo(0)
            assertThat(state.video?.thumbnailFile).isEqualTo(File("/tmp/sticker-thumbnail.png"))
            assertThat(state.image?.mimeType).isEqualTo(MimeTypes.Gif)
            assertThat(state.showVideoSoundOption).isTrue()
            assertThat(state.isUsingVideoSound).isTrue()
            assertThat(state.canSend).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - the video sound option is hidden without an AAC track`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        reader.givenVideoResult { Result.success(aStickerVideoSource(hasAacAudioTrack = false)) }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.image != null }
            assertThat(state.showVideoSoundOption).isFalse()
            assertThat(state.isUsingVideoSound).isFalse()
            assertThat(state.hasSound).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a short video does not show the start slider`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        reader.givenVideoResult { Result.success(aStickerVideoSource(durationMs = 4_000)) }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.image != null }
            assertThat(state.canChooseVideoStart).isFalse()
            assertThat(state.video?.maxStartMs).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - moving the video start slider re-encodes the window on release`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()
            val pickedState = awaitState { it.image != null }
            assertThat(pickedState.canChooseVideoStart).isTrue()

            pickedState.eventSink(SoundStickerCreatorEvent.SetVideoStartPosition(3_000))
            advanceUntilIdle()
            val movedState = awaitState { it.video?.startMs == 3_000L }
            assertThat(animationProcessor.videoCalls).hasSize(1)

            movedState.eventSink(SoundStickerCreatorEvent.ConfirmVideoStartPosition)
            advanceUntilIdle()
            awaitState { !it.isPreparingAnimation && it.image != null }
            assertThat(animationProcessor.videoCalls).hasSize(2)
            assertThat(animationProcessor.videoCalls.last().startMs).isEqualTo(3_000)
            assertThat(reader.thumbnailCalls.map { it.positionMs }).containsExactly(0L, 3_000L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - the video start position is clamped to the last full window`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()
            val pickedState = awaitState { it.image != null }

            pickedState.eventSink(SoundStickerCreatorEvent.SetVideoStartPosition(20_000))
            advanceUntilIdle()

            val state = awaitState { it.video?.startMs == 6_000L }
            assertThat(state.video?.maxStartMs).isEqualTo(6_000)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - preparing an animation shows its progress`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        animationProcessor.gate = CompletableDeferred()
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val preparingState = awaitState { it.isPreparingAnimation && it.preparationProgress > 0f }
            assertThat(preparingState.preparationProgress).isEqualTo(0.42f)
            assertThat(preparingState.image).isNull()
            assertThat(preparingState.canSend).isFalse()

            animationProcessor.gate?.complete(Unit)
            advanceUntilIdle()

            val readyState = awaitState { !it.isPreparingAnimation && it.image != null }
            assertThat(readyState.preparationProgress).isEqualTo(1f)
            assertThat(readyState.canSend).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a video which is too complex shows an error`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        animationProcessor.givenVideoResult { _, _, _ ->
            Result.failure(IllegalStateException("This video is too complex for a sticker"))
        }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_video_too_complex)
            assertThat(state.image).isNull()
            assertThat(state.isPreparingAnimation).isFalse()
            assertThat(state.canSend).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a failing video read shows an error`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        reader.givenVideoResult { Result.failure(IllegalStateException("Broken video")) }
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_video)
            assertThat(state.isPreparingAnimation).isFalse()
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
            assertThat(state.hasSound).isTrue()
            voiceRecorder.assertCalls(started = 1, stopped = 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - recording a sound replaces the video sound`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()
            val pickedState = awaitState { it.isUsingVideoSound }

            pickedState.eventSink(SoundStickerCreatorEvent.StartRecording)
            advanceUntilIdle()
            val recordingState = awaitState { it.isRecording }
            assertThat(recordingState.isUsingVideoSound).isFalse()
            recordingState.eventSink(SoundStickerCreatorEvent.StopRecording)
            advanceUntilIdle()

            val state = awaitState { it.sound != null }
            assertThat(state.isUsingVideoSound).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - the video sound can be removed`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        val presenter = createPresenter()

        presenter.test {
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()
            val pickedState = awaitState { it.isUsingVideoSound }

            pickedState.eventSink(SoundStickerCreatorEvent.SetUseVideoSound(false))
            advanceUntilIdle()

            val state = awaitState { !it.isUsingVideoSound }
            assertThat(state.hasSound).isFalse()
            assertThat(state.canSend).isTrue()
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
            awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_image)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sending a sticker without sound navigates up and saves the image only`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareImage(this)
            preparedState.eventSink(SoundStickerCreatorEvent.SetBody("My sticker"))
            advanceUntilIdle()
            preparedState.eventSink(SoundStickerCreatorEvent.Send)
            advanceUntilIdle()

            assertThat(sender.lastParameters?.body).isEqualTo("My sticker")
            assertThat(sender.lastParameters?.imageFile).isEqualTo(aStickerImage().file)
            assertThat(sender.lastParameters?.audioFile).isNull()
            assertThat(sender.lastParameters?.durationMs).isNull()
            assertThat(store.addCalls).hasSize(1)
            assertThat(store.addCalls.single().body).isEqualTo("My sticker")
            assertThat(store.addCalls.single().audioFile).isNull()
            navigateUp.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sending with the video sound extracts it first`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareVideo(this)
            preparedState.eventSink(SoundStickerCreatorEvent.Send)
            advanceUntilIdle()

            assertThat(videoAudioExtractor.calls).hasSize(1)
            assertThat(videoAudioExtractor.calls.single().startMs).isEqualTo(0)
            assertThat(videoAudioExtractor.calls.single().durationMs).isEqualTo(10_000)
            assertThat(sender.lastParameters?.audioMimeType).isEqualTo(MimeTypes.Mp4Audio)
            assertThat(sender.lastParameters?.durationMs).isEqualTo(10_000)
            assertThat(sender.lastParameters?.audioFile).isEqualTo(File("/tmp/sticker-video-sound.m4a"))
            navigateUp.assertions().isCalledOnce()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - a failing video sound extraction keeps the screen open`() = runTest {
        pickerProvider.givenMimeType(MimeTypes.Mp4)
        videoAudioExtractor.givenResult { _, _, _ -> Result.failure(IllegalStateException("No audio")) }
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareVideo(this)
            preparedState.eventSink(SoundStickerCreatorEvent.Send)
            advanceUntilIdle()

            val state = awaitState { it.errorMessage != null }
            assertThat(state.errorMessage).isEqualTo(R.string.screen_sound_sticker_creator_error_audio)
            assertThat(sender.lastParameters).isNull()
            navigateUp.assertions().isNeverCalled()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sending with save disabled does not save to my stickers`() = runTest {
        val presenter = createPresenter()

        presenter.test {
            val preparedState = prepareImage(this)
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
            val preparedState = prepareImage(this)
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

    private suspend fun TestScope.prepareImage(context: TurbineTestContext<SoundStickerCreatorState>): SoundStickerCreatorState {
        val state = context.awaitItem()
        assertThat(state.image).isNull()
        state.eventSink(SoundStickerCreatorEvent.PickMedia)
        advanceUntilIdle()
        val pickedState = context.awaitState { it.image != null }
        assertThat(pickedState.canSend).isTrue()
        return pickedState
    }

    private suspend fun TestScope.prepareVideo(context: TurbineTestContext<SoundStickerCreatorState>): SoundStickerCreatorState {
        context.awaitItem().eventSink(SoundStickerCreatorEvent.PickMedia)
        advanceUntilIdle()
        return context.awaitState { it.image != null && it.canSend && it.isUsingVideoSound }
    }

    private fun TestScope.createPresenter() = SoundStickerCreatorPresenter(
        room = FakeJoinedRoom(),
        soundStickerSender = sender,
        soundStickerStore = store,
        mediaFileReader = reader,
        animationProcessor = animationProcessor,
        videoAudioExtractor = videoAudioExtractor,
        voiceRecorder = voiceRecorder,
        previewPlayer = previewPlayer,
        mediaPickerProvider = pickerProvider,
        dispatchers = testCoroutineDispatchers(),
        sessionCoroutineScope = this,
        permissionsPresenterFactory = FakePermissionsPresenterFactory(permissionsPresenter),
        navigateUp = { navigateUp() },
    )
}
