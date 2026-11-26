/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.abs

/**
 * Groups consecutive images/videos sent by the same user into a [TimelineItem.MediaAlbum].
 *
 * This is a client-side only feature: the events are still regular `m.image` / `m.video` events, they are just rendered
 * as a single album bubble.
 *
 * The input list is ordered from the most recent item to the oldest one (the order used by the timeline).
 */
@SingleIn(RoomScope::class)
@Inject
class TimelineItemMediaAlbumGrouper {
    /**
     * Create a new list of [TimelineItem] where consecutive media events are replaced by a [TimelineItem.MediaAlbum].
     */
    fun group(from: List<TimelineItem>): List<TimelineItem> {
        val result = mutableListOf<TimelineItem>()
        // Events are accumulated from the most recent one to the oldest one.
        val albumEvents = mutableListOf<TimelineItem.Event>()

        fun flushAlbum() {
            if (albumEvents.isEmpty()) return
            if (albumEvents.size == 1) {
                result.add(albumEvents.first())
            } else {
                // Album events are displayed chronologically, from the oldest to the most recent one.
                val chronologicalEvents = albumEvents.asReversed().toImmutableList()
                result.add(
                    TimelineItem.MediaAlbum(
                        // Using the oldest event of the album makes the identifier stable when new media are added to it.
                        id = UniqueId("${chronologicalEvents.first().identifier()}_album"),
                        events = chronologicalEvents,
                    )
                )
            }
            albumEvents.clear()
        }

        from.forEach { timelineItem ->
            val event = timelineItem as? TimelineItem.Event
            if (event == null || !event.canBeInMediaAlbum()) {
                flushAlbum()
                result.add(timelineItem)
                return@forEach
            }
            val previousEvent = albumEvents.lastOrNull()
            when {
                previousEvent == null -> albumEvents.add(event)
                albumEvents.size >= MAX_ALBUM_SIZE -> {
                    flushAlbum()
                    albumEvents.add(event)
                }
                previousEvent.canBeFollowedBy(event) -> albumEvents.add(event)
                else -> {
                    flushAlbum()
                    albumEvents.add(event)
                }
            }
        }
        flushAlbum()
        return result
    }

    private fun TimelineItem.Event.canBeInMediaAlbum(): Boolean {
        if (inReplyTo != null) return false
        if (threadInfo != null) return false
        if (failedToSend) return false
        return when (content) {
            is TimelineItemImageContent,
            is TimelineItemVideoContent -> true
            else -> false
        }
    }

    /**
     * Return true if [olderEvent] can be added to the same album as this event (which is more recent).
     */
    private fun TimelineItem.Event.canBeFollowedBy(olderEvent: TimelineItem.Event): Boolean {
        if (senderId != olderEvent.senderId) return false
        if (abs(sentTimeMillis - olderEvent.sentTimeMillis) > MAX_TIME_GAP_MILLIS) return false
        // Only the most recent event of an album may have a caption.
        if (olderEvent.content.hasCaption()) return false
        return true
    }

    private fun TimelineItemEventContent.hasCaption(): Boolean {
        return when (this) {
            is TimelineItemImageContent -> caption != null
            is TimelineItemVideoContent -> caption != null
            else -> false
        }
    }

    companion object {
        const val MAX_ALBUM_SIZE = 30
        const val MAX_TIME_GAP_MILLIS = 60_000L
    }
}
