/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.RoomCoroutineScope
import io.element.android.libraries.mediaplayer.api.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Plays a local sound three times, for the creator preview.
 *
 * Uses the room media player, so it is mutually exclusive with the timeline sticker sounds and
 * voice messages.
 */
interface StickerSoundPreviewPlayer {
    val isPlaying: StateFlow<Boolean>
    suspend fun play(file: File, mimeType: String): Result<Unit>
    fun stop()
}

@SingleIn(RoomScope::class)
@ContributesBinding(RoomScope::class)
class DefaultStickerSoundPreviewPlayer(
    private val mediaPlayer: MediaPlayer,
    @RoomCoroutineScope private val roomCoroutineScope: CoroutineScope,
) : StickerSoundPreviewPlayer {
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var playbackJob: Job? = null

    override suspend fun play(file: File, mimeType: String): Result<Unit> = runCatchingExceptions {
        stop()
        val mediaId = "$MEDIA_ID_PREFIX${file.absolutePath}"
        mediaPlayer.setMedia(
            uri = file.absolutePath,
            mediaId = mediaId,
            mimeType = mimeType,
            startPositionMs = 0,
        )
        _isPlaying.value = true
        playbackJob = roomCoroutineScope.launch {
            playThreeTimes(mediaId = mediaId, file = file, mimeType = mimeType)
        }
        mediaPlayer.play()
    }.onFailure {
        playbackJob?.cancel()
        playbackJob = null
        _isPlaying.value = false
        Timber.w(it, "Unable to preview sticker sound")
    }

    override fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        if (_isPlaying.value) {
            _isPlaying.value = false
            mediaPlayer.pause()
        }
    }

    private suspend fun playThreeTimes(mediaId: String, file: File, mimeType: String) {
        var completedLoops = 0
        mediaPlayer.state.collect { state ->
            if (state.mediaId != mediaId) {
                stop()
                return@collect
            }
            if (state.isEnded) {
                completedLoops++
                if (completedLoops >= NUMBER_OF_LOOPS) {
                    stop()
                } else {
                    runCatchingExceptions {
                        mediaPlayer.setMedia(
                            uri = file.absolutePath,
                            mediaId = mediaId,
                            mimeType = mimeType,
                            startPositionMs = 0,
                        )
                        mediaPlayer.play()
                    }.onFailure {
                        Timber.w(it, "Unable to loop sticker sound preview")
                        stop()
                    }
                }
            }
        }
    }

    private companion object {
        const val NUMBER_OF_LOOPS = 3
        const val MEDIA_ID_PREFIX = "sticker_preview:"
    }
}
