/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

/**
 * UI state of the sound badge of a sticker.
 */
data class StickerSoundState(
    val isPlaying: Boolean,
    val eventSink: (StickerSoundEvent) -> Unit,
)

sealed interface StickerSoundEvent {
    /**
     * Starts the sound if it is not playing, stops it otherwise.
     */
    data object TogglePlay : StickerSoundEvent
}
