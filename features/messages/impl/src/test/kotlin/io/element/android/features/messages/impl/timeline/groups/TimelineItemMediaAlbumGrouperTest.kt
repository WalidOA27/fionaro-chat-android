/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemTextContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemVideoContent
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test

class TimelineItemMediaAlbumGrouperTest {
    private val sut = TimelineItemMediaAlbumGrouper()

    private fun anImageEvent(
        id: String,
        senderId: UserId = A_USER_ID,
        sentTimeMillis: Long = 0L,
        caption: String? = null,
        inReplyTo: InReplyToDetails? = null,
        threadInfo: TimelineItemThreadInfo? = null,
    ): TimelineItem.Event = aTimelineItemEvent(
        senderId = senderId,
        sentTimeMillis = sentTimeMillis,
        content = aTimelineItemImageContent(caption = caption),
        inReplyTo = inReplyTo,
        threadInfo = threadInfo,
    ).copy(id = UniqueId(id))

    private fun aVideoEvent(
        id: String,
        senderId: UserId = A_USER_ID,
        sentTimeMillis: Long = 0L,
        caption: String? = null,
    ): TimelineItem.Event = aTimelineItemEvent(
        senderId = senderId,
        sentTimeMillis = sentTimeMillis,
        content = aTimelineItemVideoContent(caption = caption),
    ).copy(id = UniqueId(id))

    private fun anEvent(
        id: String,
        content: TimelineItemEventContent = aTimelineItemTextContent(),
        sentTimeMillis: Long = 0L,
    ): TimelineItem.Event = aTimelineItemEvent(
        sentTimeMillis = sentTimeMillis,
        content = content,
    ).copy(id = UniqueId(id))

    private fun aMediaAlbum(vararg events: TimelineItem.Event): TimelineItem.MediaAlbum = TimelineItem.MediaAlbum(
        id = UniqueId("${events.first().identifier().value}_album"),
        events = events.toList().toImmutableList(),
    )

    @Test
    fun `empty list returns empty list`() {
        assertThat(sut.group(emptyList())).isEmpty()
    }

    @Test
    fun `a single media is not grouped`() {
        val event = anImageEvent("1")
        assertThat(sut.group(listOf(event))).containsExactly(event)
    }

    @Test
    fun `two consecutive images are grouped and ordered chronologically`() {
        val newest = anImageEvent("2", sentTimeMillis = 2_000L)
        val oldest = anImageEvent("1", sentTimeMillis = 1_000L)
        val result = sut.group(listOf(newest, oldest))
        assertThat(result).containsExactly(aMediaAlbum(oldest, newest))
    }

    @Test
    fun `consecutive images and videos are grouped together`() {
        val newest = aVideoEvent("3", sentTimeMillis = 3_000L)
        val middle = anImageEvent("2", sentTimeMillis = 2_000L)
        val oldest = aVideoEvent("1", sentTimeMillis = 1_000L)
        val result = sut.group(listOf(newest, middle, oldest))
        assertThat(result).containsExactly(aMediaAlbum(oldest, middle, newest))
    }

    @Test
    fun `media from different senders are not grouped`() {
        val newest = anImageEvent("2", senderId = UserId("@other:domain"), sentTimeMillis = 2_000L)
        val oldest = anImageEvent("1", sentTimeMillis = 1_000L)
        assertThat(sut.group(listOf(newest, oldest))).containsExactly(newest, oldest)
    }

    @Test
    fun `media within 60 seconds are grouped`() {
        val newest = anImageEvent("2", sentTimeMillis = 60_000L)
        val oldest = anImageEvent("1", sentTimeMillis = 0L)
        assertThat(sut.group(listOf(newest, oldest))).containsExactly(aMediaAlbum(oldest, newest))
    }

    @Test
    fun `media more than 60 seconds apart are not grouped`() {
        val newest = anImageEvent("2", sentTimeMillis = 60_001L)
        val oldest = anImageEvent("1", sentTimeMillis = 0L)
        assertThat(sut.group(listOf(newest, oldest))).containsExactly(newest, oldest)
    }

    @Test
    fun `a reply is not grouped`() {
        val newest = anImageEvent("2", sentTimeMillis = 2_000L)
        val reply = anImageEvent(
            id = "1",
            sentTimeMillis = 1_000L,
            inReplyTo = InReplyToDetails.Loading(AN_EVENT_ID),
        )
        assertThat(sut.group(listOf(newest, reply))).containsExactly(newest, reply)
    }

    @Test
    fun `an event in a thread is not grouped`() {
        val newest = anImageEvent("2", sentTimeMillis = 2_000L)
        val threadResponse = anImageEvent(
            id = "1",
            sentTimeMillis = 1_000L,
            threadInfo = TimelineItemThreadInfo.ThreadResponse(ThreadId("\$thread-root")),
        )
        assertThat(sut.group(listOf(newest, threadResponse))).containsExactly(newest, threadResponse)
    }

    @Test
    fun `a caption on the last event is allowed`() {
        val newest = anImageEvent("2", sentTimeMillis = 2_000L, caption = "A caption")
        val oldest = anImageEvent("1", sentTimeMillis = 1_000L)
        assertThat(sut.group(listOf(newest, oldest))).containsExactly(aMediaAlbum(oldest, newest))
    }

    @Test
    fun `a caption on a previous event splits the album`() {
        val newest = anImageEvent("3", sentTimeMillis = 3_000L)
        val middle = anImageEvent("2", sentTimeMillis = 2_000L, caption = "A caption")
        val oldest = anImageEvent("1", sentTimeMillis = 1_000L)
        assertThat(sut.group(listOf(newest, middle, oldest))).containsExactly(
            newest,
            aMediaAlbum(oldest, middle),
        )
    }

    @Test
    fun `a non media event splits the album`() {
        val newest = anImageEvent("3", sentTimeMillis = 3_000L)
        val text = anEvent("2", sentTimeMillis = 2_000L)
        val oldest = anImageEvent("1", sentTimeMillis = 1_000L)
        assertThat(sut.group(listOf(newest, text, oldest))).containsExactly(newest, text, oldest)
    }

    @Test
    fun `a redacted event is not grouped`() {
        val newest = anImageEvent("2", sentTimeMillis = 2_000L)
        val redacted = anEvent("1", content = TimelineItemRedactedContent, sentTimeMillis = 1_000L)
        assertThat(sut.group(listOf(newest, redacted))).containsExactly(newest, redacted)
    }

    @Test
    fun `more than 30 consecutive media start a new album`() {
        // Events are ordered from the most recent to the oldest one.
        val events = (1..31).map { index ->
            anImageEvent(id = index.toString(), sentTimeMillis = index * 1_000L)
        }.reversed()
        val result = sut.group(events)
        assertThat(result).hasSize(2)
        val firstAlbum = result[0] as TimelineItem.MediaAlbum
        val remainingEvent = result[1] as TimelineItem.Event
        assertThat(firstAlbum.events).hasSize(30)
        assertThat(firstAlbum.events.first().sentTimeMillis).isEqualTo(2_000L)
        assertThat(firstAlbum.events.last().sentTimeMillis).isEqualTo(31_000L)
        assertThat(remainingEvent.sentTimeMillis).isEqualTo(1_000L)
    }

    @Test
    fun `album keeps the position of its most recent event`() {
        val newest = anImageEvent("3", sentTimeMillis = 3_000L)
        val middle = anImageEvent("2", sentTimeMillis = 2_000L)
        val text = anEvent("1", sentTimeMillis = 1_000L)
        assertThat(sut.group(listOf(newest, middle, text))).containsExactly(
            aMediaAlbum(middle, newest),
            text,
        ).inOrder()
    }
}
