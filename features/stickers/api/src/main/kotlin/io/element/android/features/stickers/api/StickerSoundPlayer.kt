/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.StickerAudio
import kotlinx.coroutines.flow.StateFlow

/**
 * Room-scoped player for sticker sounds.
 *
 * Only one sound can play at a time: starting a new one stops the previous one.
 * A sound is played 3 times in a row before stopping.
 */
interface StickerSoundPlayer {
    /**
     * The event id of the sticker whose sound is currently being played, or `null`.
     */
    val playingEventId: StateFlow<EventId?>

    /**
     * Downloads (and decrypts, if needed) the sound and plays it 3 times in a row.
     *
     * Any sound currently playing is stopped first.
     */
    suspend fun play(eventId: EventId, audio: StickerAudio): Result<Unit>

    /**
     * Stops the sound currently playing, if any.
     */
    fun stop()
}
