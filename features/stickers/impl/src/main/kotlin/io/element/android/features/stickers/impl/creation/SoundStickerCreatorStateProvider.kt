/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.R
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.permissions.api.aPermissionsState
import java.io.File
import kotlin.time.Duration.Companion.seconds

open class SoundStickerCreatorStateProvider : PreviewParameterProvider<SoundStickerCreatorState> {
    override val values: Sequence<SoundStickerCreatorState>
        get() = sequenceOf(
            // Nothing picked yet.
            aSoundStickerCreatorState(),
            // A still image with a recorded sound.
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                body = "A sticker",
            ),
            // A still image without sound, sendable.
            aSoundStickerCreatorState(image = aStickerImage()),
            // Recording a sound.
            aSoundStickerCreatorState(
                image = aStickerImage(),
                isRecording = true,
                recordingElapsed = 4.seconds,
            ),
            // Previewing a recorded sound.
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                isPlayingSound = true,
            ),
            // A video longer than the animation, with a start slider and its sound.
            aSoundStickerCreatorState(
                image = aStickerImage(mimeType = MimeTypes.Gif),
                video = aStickerVideo(startMs = 3_000, durationMs = 20_000),
                isUsingVideoSound = true,
            ),
            // A short video without sound, being encoded.
            aSoundStickerCreatorState(
                video = aStickerVideo(durationMs = 4_000, hasAacAudioTrack = false),
                isPreparingAnimation = true,
                preparationProgress = 0.42f,
            ),
            // Sending.
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                isSending = true,
            ),
            // An error.
            aSoundStickerCreatorState(errorMessage = R.string.screen_sound_sticker_creator_error_video_too_complex),
        )
}

fun aStickerImage(mimeType: String = MimeTypes.Png) = StickerImage(
    file = File(if (mimeType == MimeTypes.Gif) "/tmp/element-sticker.gif" else "/tmp/element-sticker.png"),
    mimeType = mimeType,
    width = 512,
    height = 512,
)

fun aStickerSoundFile() = StickerSoundFile(
    file = File("/tmp/element-sticker.ogg"),
    mimeType = "audio/ogg",
    durationMs = 3200,
)

fun aStickerVideo(
    startMs: Long = 0,
    durationMs: Long = 12_000,
    hasAacAudioTrack: Boolean = true,
) = StickerVideo(
    source = StickerVideoSource(
        file = File("/tmp/element-sticker-video.mp4"),
        mimeType = MimeTypes.Mp4,
        durationMs = durationMs,
        hasAacAudioTrack = hasAacAudioTrack,
    ),
    startMs = startMs,
    thumbnailFile = File("/tmp/element-sticker-thumbnail.png"),
)

fun aSoundStickerCreatorState(
    image: StickerImage? = null,
    isPreparingAnimation: Boolean = false,
    preparationProgress: Float = 0f,
    video: StickerVideo? = null,
    sound: StickerSoundFile? = null,
    isUsingVideoSound: Boolean = false,
    isRecording: Boolean = false,
    recordingElapsed: kotlin.time.Duration = kotlin.time.Duration.ZERO,
    isPlayingSound: Boolean = false,
    body: String = "Sticker",
    saveToMyStickers: Boolean = true,
    isSending: Boolean = false,
    errorMessage: Int? = null,
    eventSink: (SoundStickerCreatorEvent) -> Unit = {},
) = SoundStickerCreatorState(
    image = image,
    isPreparingAnimation = isPreparingAnimation,
    preparationProgress = preparationProgress,
    video = video,
    sound = sound,
    isUsingVideoSound = isUsingVideoSound,
    isRecording = isRecording,
    recordingElapsed = recordingElapsed,
    isPlayingSound = isPlayingSound,
    body = body,
    saveToMyStickers = saveToMyStickers,
    isSending = isSending,
    errorMessage = errorMessage,
    permissionsState = aPermissionsState(showDialog = false),
    eventSink = eventSink,
)
