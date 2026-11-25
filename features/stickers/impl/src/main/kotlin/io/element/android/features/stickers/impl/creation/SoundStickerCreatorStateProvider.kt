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
import io.element.android.libraries.permissions.api.aPermissionsState
import java.io.File
import kotlin.time.Duration.Companion.seconds

open class SoundStickerCreatorStateProvider : PreviewParameterProvider<SoundStickerCreatorState> {
    override val values: Sequence<SoundStickerCreatorState>
        get() = sequenceOf(
            aSoundStickerCreatorState(),
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                body = "A sticker",
            ),
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                isRecording = true,
                recordingElapsed = 4.seconds,
            ),
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                isPlayingSound = true,
            ),
            aSoundStickerCreatorState(
                image = aStickerImage(),
                sound = aStickerSoundFile(),
                isSending = true,
            ),
            aSoundStickerCreatorState(errorMessage = R.string.screen_sound_sticker_creator_error_audio_too_long),
        )
}

fun aStickerImage() = StickerImage(
    file = File("/tmp/element-sticker.png"),
    mimeType = "image/png",
    width = 512,
    height = 512,
)

fun aStickerSoundFile() = StickerSoundFile(
    file = File("/tmp/element-sticker.ogg"),
    mimeType = "audio/ogg",
    durationMs = 3200,
)

fun aSoundStickerCreatorState(
    image: StickerImage? = null,
    sound: StickerSoundFile? = null,
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
    sound = sound,
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
