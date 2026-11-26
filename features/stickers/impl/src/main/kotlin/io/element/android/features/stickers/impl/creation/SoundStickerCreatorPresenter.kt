/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.stickers.api.SoundStickerSender
import io.element.android.features.stickers.api.SoundStickerStore
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.R
import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class SoundStickerCreatorPresenter(
    private val room: JoinedRoom,
    private val soundStickerSender: SoundStickerSender,
    private val soundStickerStore: SoundStickerStore,
    private val mediaFileReader: StickerMediaFileReader,
    private val animationProcessor: StickerAnimationProcessor,
    private val videoAudioExtractor: StickerVideoAudioExtractor,
    private val voiceRecorder: VoiceRecorder,
    private val previewPlayer: StickerSoundPreviewPlayer,
    private val mediaPickerProvider: PickerProvider,
    private val dispatchers: CoroutineDispatchers,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    permissionsPresenterFactory: PermissionsPresenter.Factory,
    @Assisted private val navigateUp: () -> Unit,
) : Presenter<SoundStickerCreatorState> {
    @AssistedFactory
    fun interface Factory {
        fun create(navigateUp: () -> Unit): SoundStickerCreatorPresenter
    }

    private val permissionsPresenter = permissionsPresenterFactory.create(Manifest.permission.RECORD_AUDIO)

    @Composable
    override fun present(): SoundStickerCreatorState {
        val localCoroutineScope = rememberCoroutineScope()
        val permissionsState = permissionsPresenter.present()
        val recorderState by voiceRecorder.state.collectAsState(initial = VoiceRecorderState.Idle)
        val isPlayingSound by previewPlayer.isPlaying.collectAsState()
        var image by remember { mutableStateOf<StickerImage?>(null) }
        var isPreparingAnimation by remember { mutableStateOf(false) }
        var preparationProgress by remember { mutableFloatStateOf(0f) }
        var video by remember { mutableStateOf<StickerVideo?>(null) }
        var sound by remember { mutableStateOf<StickerSoundFile?>(null) }
        var isUsingVideoSound by remember { mutableStateOf(false) }
        var body by rememberSaveable { mutableStateOf(DEFAULT_BODY) }
        var saveToMyStickers by rememberSaveable { mutableStateOf(true) }
        var isSending by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<Int?>(null) }
        var pendingStartRecording by remember { mutableStateOf(false) }
        var processingJob by remember { mutableStateOf<Job?>(null) }
        val latestRecorderState by rememberUpdatedState(recorderState)

        /**
         * Runs [block] as the only media processing job: any previous one is cancelled and
         * joined first, so that a stale encoding can never overwrite a newer result.
         */
        fun startProcessing(block: suspend () -> Unit) {
            val previousJob = processingJob
            val job = localCoroutineScope.launch {
                previousJob?.cancelAndJoin()
                isPreparingAnimation = true
                preparationProgress = 0f
                try {
                    block()
                } finally {
                    isPreparingAnimation = false
                }
            }
            processingJob = job
        }

        fun resetMedia() {
            previewPlayer.stop()
            image = null
            video = null
            sound = null
            isUsingVideoSound = false
        }

        suspend fun readImage(uri: Uri) {
            resetMedia()
            mediaFileReader.readImage(uri)
                .onSuccess { image = it }
                .onFailure {
                    Timber.w(it, "Unable to read the picked sticker image")
                    errorMessage = R.string.screen_sound_sticker_creator_error_image
                }
        }

        suspend fun prepareAnimation(source: StickerVideoSource, startMs: Long) {
            val (_, durationMs) = StickerGifEncodingPlan.videoWindow(startMs, source.durationMs)
            preparationProgress = 0f
            animationProcessor.processVideo(source.file, startMs, durationMs) { progress ->
                localCoroutineScope.launch { preparationProgress = progress }
            }.onSuccess {
                image = it
            }.onFailure {
                Timber.w(it, "Unable to encode the sticker animation")
                image = null
                errorMessage = when (it) {
                    is StickerAnimationException.TooComplex -> R.string.screen_sound_sticker_creator_error_video_too_complex
                    else -> R.string.screen_sound_sticker_creator_error_media_unreadable
                }
            }
        }

        suspend fun readVideo(uri: Uri) {
            resetMedia()
            mediaFileReader.readVideo(uri)
                .onSuccess { source ->
                    val startMs = 0L
                    video = StickerVideo(source = source, startMs = startMs, thumbnailFile = null)
                    // Preselect the video sound, the user can still record or pick another one.
                    isUsingVideoSound = source.hasAacAudioTrack
                    val thumbnail = mediaFileReader.readVideoThumbnail(source, startMs).getOrNull()
                    video = video?.copy(thumbnailFile = thumbnail)
                    prepareAnimation(source, startMs)
                }
                .onFailure {
                    Timber.w(it, "Unable to read the picked sticker video")
                    errorMessage = R.string.screen_sound_sticker_creator_error_video
                }
        }

        suspend fun prepareVideoWindow(current: StickerVideo, startMs: Long) {
            val clampedStart = startMs.coerceIn(0, current.maxStartMs)
            val thumbnail = mediaFileReader.readVideoThumbnail(current.source, clampedStart).getOrNull()
            video = current.copy(startMs = clampedStart, thumbnailFile = thumbnail ?: current.thumbnailFile)
            prepareAnimation(current.source, clampedStart)
        }

        suspend fun readAudio(uri: Uri) {
            mediaFileReader.readAudio(uri)
                .onSuccess { pickedSound ->
                    if (pickedSound.durationMs > MAX_DURATION_MS) {
                        errorMessage = R.string.screen_sound_sticker_creator_error_audio_too_long
                    } else {
                        previewPlayer.stop()
                        isUsingVideoSound = false
                        sound = pickedSound
                    }
                }
                .onFailure {
                    Timber.w(it, "Unable to read the picked sticker sound")
                    errorMessage = R.string.screen_sound_sticker_creator_error_audio
                }
        }

        suspend fun startRecording() {
            sound = null
            isUsingVideoSound = false
            // Recording is only reachable once RECORD_AUDIO is granted (gated by PermissionsPresenter), and a
            // SecurityException is still caught below.
            @SuppressLint("MissingPermission")
            val result = runCatchingExceptions { voiceRecorder.startRecord() }
            result
                .onFailure {
                    Timber.w(it, "Unable to start the sticker recording")
                    errorMessage = R.string.screen_sound_sticker_creator_error_record
                }
        }

        /**
         * The sound of the sticker, or `null` for a silent sticker. The video sound is only
         * extracted when the sticker is sent, because it depends on the selected window.
         */
        suspend fun resolveSound(): StickerSoundFile? {
            if (!isUsingVideoSound) return sound
            val currentVideo = video ?: return null
            val (_, durationMs) = StickerGifEncodingPlan.audioWindow(currentVideo.startMs, currentVideo.source.durationMs)
            return videoAudioExtractor.extractAudio(
                videoFile = currentVideo.source.file,
                startMs = currentVideo.startMs,
                durationMs = durationMs,
            ).getOrThrow()
        }

        suspend fun sendSticker(currentImage: StickerImage, currentBody: String, save: Boolean) {
            isSending = true
            val audio = try {
                resolveSound()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Timber.w(exception, "Unable to prepare the sticker sound")
                isSending = false
                errorMessage = R.string.screen_sound_sticker_creator_error_audio
                return
            }
            soundStickerSender.send(
                room = room,
                imageFile = currentImage.file,
                imageMimeType = currentImage.mimeType,
                width = currentImage.width,
                height = currentImage.height,
                audioFile = audio?.file,
                audioMimeType = audio?.mimeType,
                durationMs = audio?.durationMs,
                body = currentBody,
            ).onSuccess {
                isSending = false
                if (save) {
                    withContext(dispatchers.io) {
                        soundStickerStore.add(
                            imageFile = currentImage.file,
                            imageMimeType = currentImage.mimeType,
                            width = currentImage.width,
                            height = currentImage.height,
                            audioFile = audio?.file,
                            audioMimeType = audio?.mimeType,
                            durationMs = audio?.durationMs,
                            body = currentBody,
                        ).onFailure { Timber.w(it, "Unable to save the sticker to My stickers") }
                    }
                }
                navigateUp()
            }.onFailure {
                Timber.w(it, "Unable to send the sticker")
                isSending = false
                errorMessage = R.string.screen_sound_sticker_creator_error_send
            }
        }

        LaunchedEffect(recorderState) {
            when (val state = recorderState) {
                is VoiceRecorderState.Recording -> {
                    if (state.elapsedTime >= MAX_RECORDING_DURATION) {
                        voiceRecorder.stopRecord()
                    }
                }
                is VoiceRecorderState.Finished -> {
                    previewPlayer.stop()
                    sound = StickerSoundFile(
                        file = state.file,
                        mimeType = state.mimeType,
                        // The recorder may overshoot the cap by a few milliseconds.
                        durationMs = state.duration.inWholeMilliseconds.coerceIn(1, MAX_DURATION_MS),
                    )
                }
                VoiceRecorderState.Idle -> Unit
            }
        }

        LaunchedEffect(permissionsState.permissionGranted) {
            if (permissionsState.permissionGranted && pendingStartRecording) {
                pendingStartRecording = false
                localCoroutineScope.launch { startRecording() }
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                previewPlayer.stop()
                processingJob?.cancel()
                if (latestRecorderState is VoiceRecorderState.Recording) {
                    sessionCoroutineScope.launch {
                        runCatchingExceptions { voiceRecorder.stopRecord(cancelled = true) }
                    }
                }
            }
        }

        val mediaPicker = mediaPickerProvider.registerGalleryPicker { uri, mimeType ->
            if (uri != null) {
                startProcessing {
                    if (mimeType?.startsWith("video/") == true) {
                        readVideo(uri)
                    } else {
                        readImage(uri)
                    }
                }
            }
        }
        val audioPicker = mediaPickerProvider.registerFilePicker(MimeTypes.Audio) { uri, _ ->
            if (uri != null) {
                localCoroutineScope.launch { readAudio(uri) }
            }
        }

        fun handleEvent(event: SoundStickerCreatorEvent) {
            when (event) {
                SoundStickerCreatorEvent.PickMedia -> mediaPicker.launch()
                is SoundStickerCreatorEvent.SetVideoStartPosition -> {
                    val currentVideo = video ?: return
                    video = currentVideo.copy(startMs = event.positionMs.coerceIn(0, currentVideo.maxStartMs))
                }
                SoundStickerCreatorEvent.ConfirmVideoStartPosition -> {
                    val currentVideo = video ?: return
                    startProcessing { prepareVideoWindow(currentVideo, currentVideo.startMs) }
                }
                SoundStickerCreatorEvent.StartRecording -> {
                    if (permissionsState.permissionGranted) {
                        localCoroutineScope.launch { startRecording() }
                    } else {
                        pendingStartRecording = true
                        permissionsState.eventSink(PermissionsEvent.RequestPermissions)
                    }
                }
                SoundStickerCreatorEvent.StopRecording -> localCoroutineScope.launch {
                    runCatchingExceptions { voiceRecorder.stopRecord() }
                }
                SoundStickerCreatorEvent.PickAudio -> audioPicker.launch()
                SoundStickerCreatorEvent.ToggleSoundPreview -> {
                    val currentSound = sound ?: return
                    if (isPlayingSound) {
                        previewPlayer.stop()
                    } else {
                        localCoroutineScope.launch { previewPlayer.play(currentSound.file, currentSound.mimeType) }
                    }
                }
                is SoundStickerCreatorEvent.SetUseVideoSound -> {
                    if (event.enabled) {
                        previewPlayer.stop()
                        sound = null
                        isUsingVideoSound = true
                    } else {
                        isUsingVideoSound = false
                    }
                }
                SoundStickerCreatorEvent.RemoveSound -> {
                    previewPlayer.stop()
                    sound = null
                    isUsingVideoSound = false
                }
                is SoundStickerCreatorEvent.SetBody -> body = event.body
                is SoundStickerCreatorEvent.SetSaveToMyStickers -> saveToMyStickers = event.enabled
                SoundStickerCreatorEvent.Send -> {
                    val currentImage = image ?: return
                    if (!isSending && !isPreparingAnimation) {
                        localCoroutineScope.launch {
                            sendSticker(currentImage, body.ifBlank { DEFAULT_BODY }, saveToMyStickers)
                        }
                    }
                }
                SoundStickerCreatorEvent.DismissError -> errorMessage = null
                SoundStickerCreatorEvent.NavBack -> navigateUp()
            }
        }

        val recordingElapsed = (recorderState as? VoiceRecorderState.Recording)?.elapsedTime ?: Duration.ZERO
        return SoundStickerCreatorState(
            image = image,
            isPreparingAnimation = isPreparingAnimation,
            preparationProgress = preparationProgress,
            video = video,
            sound = sound,
            isUsingVideoSound = isUsingVideoSound,
            isRecording = recorderState is VoiceRecorderState.Recording,
            recordingElapsed = recordingElapsed,
            isPlayingSound = isPlayingSound,
            body = body,
            saveToMyStickers = saveToMyStickers,
            isSending = isSending,
            errorMessage = errorMessage,
            permissionsState = permissionsState,
            eventSink = ::handleEvent,
        )
    }

    private companion object {
        const val DEFAULT_BODY = "Sticker"
        const val MAX_DURATION_MS = 10_000L
        val MAX_RECORDING_DURATION = 10.seconds
    }
}
