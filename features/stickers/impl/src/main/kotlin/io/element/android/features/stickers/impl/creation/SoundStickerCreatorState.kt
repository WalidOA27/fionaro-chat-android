/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import androidx.annotation.StringRes
import io.element.android.features.stickers.api.StickerImage
import io.element.android.libraries.permissions.api.PermissionsState
import kotlin.time.Duration

data class SoundStickerCreatorState(
    val image: StickerImage?,
    val sound: StickerSoundFile?,
    val isRecording: Boolean,
    val recordingElapsed: Duration,
    val isPlayingSound: Boolean,
    val body: String,
    val saveToMyStickers: Boolean,
    val isSending: Boolean,
    @StringRes val errorMessage: Int?,
    val permissionsState: PermissionsState,
    val eventSink: (SoundStickerCreatorEvent) -> Unit,
) {
    val canSend: Boolean = image != null && sound != null && !isSending
}

sealed interface SoundStickerCreatorEvent {
    data object PickImage : SoundStickerCreatorEvent
    data object StartRecording : SoundStickerCreatorEvent
    data object StopRecording : SoundStickerCreatorEvent
    data object PickAudio : SoundStickerCreatorEvent
    data object ToggleSoundPreview : SoundStickerCreatorEvent
    data class SetBody(val body: String) : SoundStickerCreatorEvent
    data class SetSaveToMyStickers(val enabled: Boolean) : SoundStickerCreatorEvent
    data object Send : SoundStickerCreatorEvent
    data object DismissError : SoundStickerCreatorEvent
    data object NavBack : SoundStickerCreatorEvent
}
