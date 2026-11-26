/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.gif.StickerGifEncodingPlan
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.permissions.api.PermissionsState
import java.io.File
import kotlin.time.Duration

/**
 * The video being turned into an animated sticker, and the position of the 6 second window
 * which is kept.
 */
@Immutable
data class StickerVideo(
    val source: StickerVideoSource,
    val startMs: Long,
    val thumbnailFile: File?,
) {
    /**
     * A start position can only be chosen when the video is longer than the sticker animation.
     */
    val canChooseStart: Boolean get() = source.durationMs > StickerGifEncodingPlan.MAX_ANIMATION_DURATION_MS

    /**
     * The last position at which a full animation window still fits in the video.
     */
    val maxStartMs: Long get() = StickerGifEncodingPlan.maxStartMs(source.durationMs)
}

@Immutable
data class SoundStickerCreatorState(
    val image: StickerImage?,
    val isPreparingAnimation: Boolean,
    val preparationProgress: Float,
    val video: StickerVideo?,
    val sound: StickerSoundFile?,
    val isUsingVideoSound: Boolean,
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
    /**
     * Only the sticker image is required, the sound is optional.
     */
    val canSend: Boolean = image != null && !isPreparingAnimation && !isSending

    val isAnimated: Boolean = image?.mimeType == MimeTypes.Gif

    /**
     * "Use the video's sound" is only offered when the video has an AAC audio track.
     */
    val showVideoSoundOption: Boolean = video?.source?.hasAacAudioTrack == true

    val canChooseVideoStart: Boolean = video?.canChooseStart == true

    val hasSound: Boolean = isUsingVideoSound || sound != null
}

sealed interface SoundStickerCreatorEvent {
    data object PickMedia : SoundStickerCreatorEvent
    data class SetVideoStartPosition(val positionMs: Long) : SoundStickerCreatorEvent
    data object ConfirmVideoStartPosition : SoundStickerCreatorEvent
    data object StartRecording : SoundStickerCreatorEvent
    data object StopRecording : SoundStickerCreatorEvent
    data object PickAudio : SoundStickerCreatorEvent
    data object ToggleSoundPreview : SoundStickerCreatorEvent
    data class SetUseVideoSound(val enabled: Boolean) : SoundStickerCreatorEvent
    data object RemoveSound : SoundStickerCreatorEvent
    data class SetBody(val body: String) : SoundStickerCreatorEvent
    data class SetSaveToMyStickers(val enabled: Boolean) : SoundStickerCreatorEvent
    data object Send : SoundStickerCreatorEvent
    data object DismissError : SoundStickerCreatorEvent
    data object NavBack : SoundStickerCreatorEvent
}
