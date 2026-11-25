/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.stickers.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import io.element.android.features.messages.impl.timeline.di.TimelineItemEventContentKey
import io.element.android.features.messages.impl.timeline.di.TimelineItemPresenterFactory
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStickerContent
import io.element.android.features.stickers.api.StickerSoundEvent
import io.element.android.features.stickers.api.StickerSoundPlayer
import io.element.android.features.stickers.api.StickerSoundState
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.di.RoomScope
import kotlinx.coroutines.launch

@BindingContainer
@ContributesTo(RoomScope::class)
interface StickerSoundPresenterModule {
    @Binds
    @IntoMap
    @TimelineItemEventContentKey(TimelineItemStickerContent::class)
    fun bindStickerSoundPresenterFactory(factory: StickerSoundPresenter.Factory): TimelineItemPresenterFactory<*, *>
}

@AssistedInject
class StickerSoundPresenter(
    private val stickerSoundPlayer: StickerSoundPlayer,
    @Assisted private val content: TimelineItemStickerContent,
) : Presenter<StickerSoundState> {
    @AssistedFactory
    fun interface Factory : TimelineItemPresenterFactory<TimelineItemStickerContent, StickerSoundState> {
        override fun create(content: TimelineItemStickerContent): StickerSoundPresenter
    }

    @Composable
    override fun present(): StickerSoundState {
        val playingEventId by stickerSoundPlayer.playingEventId.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        val isPlaying = content.audio != null && content.eventId != null && playingEventId == content.eventId
        return StickerSoundState(
            isPlaying = isPlaying,
            eventSink = { event ->
                when (event) {
                    StickerSoundEvent.TogglePlay -> {
                        val audio = content.audio
                        val eventId = content.eventId
                        if (isPlaying) {
                            stickerSoundPlayer.stop()
                        } else if (audio != null && eventId != null) {
                            coroutineScope.launch {
                                stickerSoundPlayer.play(eventId, audio)
                            }
                        }
                    }
                }
            },
        )
    }
}
