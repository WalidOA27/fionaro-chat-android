/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.stickers.api.StickerSoundPlayer
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.RoomCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.timeline.item.event.StickerAudio
import io.element.android.libraries.mediaplayer.api.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plays sticker sounds 3 times in a row.
 *
 * Backed by the room-scoped [MediaPlayer], so at most one media is played at a time in the room.
 */
@SingleIn(RoomScope::class)
@ContributesBinding(RoomScope::class)
class DefaultStickerSoundPlayer(
    private val mediaPlayer: MediaPlayer,
    private val mediaLoader: MatrixMediaLoader,
    @RoomCoroutineScope private val roomCoroutineScope: CoroutineScope,
) : StickerSoundPlayer {
    private val _playingEventId = MutableStateFlow<EventId?>(null)
    override val playingEventId: StateFlow<EventId?> = _playingEventId.asStateFlow()

    private var playbackJob: Job? = null

    override suspend fun play(eventId: EventId, audio: StickerAudio): Result<Unit> = runCatchingExceptions {
        stop()

        val mediaFile = mediaLoader.downloadMediaFile(
            source = audio.mediaSource,
            mimeType = audio.mimetype,
            filename = null,
        ).getOrThrow()

        val mediaId = eventId.value
        mediaPlayer.setMedia(
            uri = mediaFile.path(),
            mediaId = mediaId,
            mimeType = audio.mimetype,
            startPositionMs = 0,
        )
        _playingEventId.value = eventId

        playbackJob = roomCoroutineScope.launch {
            playThreeTimes(
                mediaId = mediaId,
                uri = mediaFile.path(),
                mimeType = audio.mimetype,
            )
        }
        mediaPlayer.play()
    }.onFailure {
        playbackJob?.cancel()
        playbackJob = null
        _playingEventId.value = null
        Timber.w(it, "Unable to play sticker sound")
    }

    override fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        val currentEventId = _playingEventId.value ?: return
        val isPlayingOurMedia = mediaPlayer.state.value.mediaId == currentEventId.value
        _playingEventId.value = null
        if (isPlayingOurMedia) {
            mediaPlayer.pause()
        }
    }

    private suspend fun playThreeTimes(mediaId: String, uri: String, mimeType: String) {
        var completedLoops = 0
        mediaPlayer.state.collect { state ->
            if (state.mediaId != mediaId) {
                // Another media took over the room player, do not keep the "playing" state.
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
                            uri = uri,
                            mediaId = mediaId,
                            mimeType = mimeType,
                            startPositionMs = 0,
                        )
                        mediaPlayer.play()
                    }.onFailure {
                        Timber.w(it, "Unable to loop sticker sound")
                        stop()
                    }
                }
            }
        }
    }

    private companion object {
        const val NUMBER_OF_LOOPS = 3
    }
}
