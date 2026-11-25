/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.api.StickerSoundPlayer
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.item.event.StickerAudio
import io.element.android.tests.testutils.simulateLongTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeStickerSoundPlayer(
    private var playResult: Result<Unit> = Result.success(Unit),
) : StickerSoundPlayer {
    private val _playingEventId = MutableStateFlow<EventId?>(null)
    override val playingEventId: StateFlow<EventId?> = _playingEventId.asStateFlow()

    var playCalls: MutableList<Pair<EventId, StickerAudio>> = mutableListOf()
        private set
    var stopCount: Int = 0
        private set

    fun givenPlayResult(result: Result<Unit>) {
        playResult = result
    }

    fun givenPlayingEventId(eventId: EventId?) {
        _playingEventId.value = eventId
    }

    override suspend fun play(eventId: EventId, audio: StickerAudio): Result<Unit> = simulateLongTask {
        playCalls.add(eventId to audio)
        _playingEventId.value = eventId
        playResult
    }

    override fun stop() {
        stopCount++
        _playingEventId.value = null
    }
}
