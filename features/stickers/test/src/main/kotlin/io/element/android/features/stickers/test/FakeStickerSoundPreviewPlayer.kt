/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.impl.creation.StickerSoundPreviewPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class FakeStickerSoundPreviewPlayer(
    private var playResult: Result<Unit> = Result.success(Unit),
    initialIsPlaying: Boolean = false,
) : StickerSoundPreviewPlayer {
    private val _isPlaying = MutableStateFlow(initialIsPlaying)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    var playCalls: MutableList<Pair<File, String>> = mutableListOf()
        private set
    var stopCount: Int = 0
        private set

    fun givenPlayResult(value: Result<Unit>) {
        playResult = value
    }

    fun setPlaying(value: Boolean) {
        _isPlaying.value = value
    }

    override suspend fun play(file: File, mimeType: String): Result<Unit> {
        playCalls.add(file to mimeType)
        _isPlaying.value = true
        return playResult
    }

    override fun stop() {
        stopCount++
        _isPlaying.value = false
    }
}
