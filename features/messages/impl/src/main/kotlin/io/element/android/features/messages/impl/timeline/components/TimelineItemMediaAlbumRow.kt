/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import android.text.SpannedString
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.TimelineRoomInfo
import io.element.android.features.messages.impl.timeline.aMediaAlbum
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayout
import io.element.android.features.messages.impl.timeline.components.layout.ContentAvoidingLayoutData
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.protection.ProtectedView
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionEvent
import io.element.android.features.messages.impl.timeline.protection.TimelineProtectionState
import io.element.android.libraries.designsystem.components.blurhash.blurHashBackground
import io.element.android.libraries.designsystem.modifiers.onKeyboardContextMenuAction
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.media.MAX_THUMBNAIL_HEIGHT
import io.element.android.libraries.matrix.ui.media.MAX_THUMBNAIL_WIDTH
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.textcomposer.ElementRichTextEditorStyle
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.a11y.isTalkbackActive
import io.element.android.wysiwyg.compose.EditorStyledText
import io.element.android.wysiwyg.link.Link

private const val MAX_DISPLAYED_TILES = 4
private val TILE_SPACING = 2.dp

/**
 * Renders a [TimelineItem.MediaAlbum] as a single bubble, reusing all the event row decorations (sender, timestamp,
 * send state, reactions and read receipts) of the most recent event of the album.
 */
@Composable
fun TimelineItemMediaAlbumRow(
    timelineItem: TimelineItem.MediaAlbum,
    timelineMode: Timeline.Mode,
    timelineRoomInfo: TimelineRoomInfo,
    timelineProtectionState: TimelineProtectionState,
    isLastOutgoingMessage: Boolean,
    displayThreadSummaries: Boolean,
    onContentClick: (TimelineItem.Event) -> Unit,
    onLongClick: (TimelineItem.Event) -> Unit,
    inReplyToClick: (EventId) -> Unit,
    onUserDataClick: (MatrixUser) -> Unit,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onReactionClick: (key: String, TimelineItem.Event) -> Unit,
    onReactionLongClick: (key: String, TimelineItem.Event) -> Unit,
    onMoreReactionsClick: (TimelineItem.Event) -> Unit,
    onReadReceiptClick: (TimelineItem.Event) -> Unit,
    onSwipeToReply: (TimelineItem.Event) -> Unit,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastEvent = timelineItem.lastEvent
    TimelineItemEventRow(
        event = lastEvent,
        timelineMode = timelineMode,
        timelineRoomInfo = timelineRoomInfo,
        timelineProtectionState = timelineProtectionState,
        isLastOutgoingMessage = isLastOutgoingMessage,
        displayThreadSummaries = displayThreadSummaries,
        onEventClick = { onContentClick(lastEvent) },
        onLongClick = { onLongClick(lastEvent) },
        onLinkClick = onLinkClick,
        onLinkLongClick = onLinkLongClick,
        onUserDataClick = onUserDataClick,
        inReplyToClick = inReplyToClick,
        onReactionClick = onReactionClick,
        onReactionLongClick = onReactionLongClick,
        onMoreReactionsClick = onMoreReactionsClick,
        onReadReceiptClick = onReadReceiptClick,
        onSwipeToReply = { onSwipeToReply(lastEvent) },
        eventSink = eventSink,
        modifier = modifier,
        eventContentView = { contentModifier, onContentLayoutChange ->
            MediaAlbumContentView(
                album = timelineItem,
                timelineProtectionState = timelineProtectionState,
                onTileClick = onContentClick,
                onTileLongClick = onLongClick,
                onShowContentClick = { eventId ->
                    timelineProtectionState.eventSink(TimelineProtectionEvent.ShowContent(eventId))
                },
                onLinkClick = onLinkClick,
                onLinkLongClick = onLinkLongClick,
                onContentLayoutChange = onContentLayoutChange,
                modifier = contentModifier,
            )
        },
    )
}

@Composable
private fun MediaAlbumContentView(
    album: TimelineItem.MediaAlbum,
    timelineProtectionState: TimelineProtectionState,
    onTileClick: (TimelineItem.Event) -> Unit,
    onTileLongClick: (TimelineItem.Event) -> Unit,
    onShowContentClick: (EventId?) -> Unit,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tiles = album.events.take(MAX_DISPLAYED_TILES)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp)),
        ) {
            when (tiles.size) {
                2 -> Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                    tiles.forEach { event ->
                        AlbumTile(
                            event = event,
                            timelineProtectionState = timelineProtectionState,
                            onTileClick = onTileClick,
                            onTileLongClick = onTileLongClick,
                            onShowContentClick = onShowContentClick,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                        )
                    }
                }
                3 -> BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val albumHeight = maxWidth * 0.66f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(albumHeight),
                        horizontalArrangement = Arrangement.spacedBy(TILE_SPACING),
                    ) {
                        AlbumTile(
                            event = tiles[0],
                            timelineProtectionState = timelineProtectionState,
                            onTileClick = onTileClick,
                            onTileLongClick = onTileLongClick,
                            onShowContentClick = onShowContentClick,
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight(),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(TILE_SPACING),
                        ) {
                            tiles.drop(1).forEach { event ->
                                AlbumTile(
                                    event = event,
                                    timelineProtectionState = timelineProtectionState,
                                    onTileClick = onTileClick,
                                    onTileLongClick = onTileLongClick,
                                    onShowContentClick = onShowContentClick,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                        listOfNotNull(tiles.getOrNull(0), tiles.getOrNull(1)).forEach { event ->
                            AlbumTile(
                                event = event,
                                timelineProtectionState = timelineProtectionState,
                                onTileClick = onTileClick,
                                onTileLongClick = onTileLongClick,
                                onShowContentClick = onShowContentClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                        listOfNotNull(tiles.getOrNull(2), tiles.getOrNull(3)).forEachIndexed { index, event ->
                            val remainingCount = album.events.size - MAX_DISPLAYED_TILES
                            AlbumTile(
                                event = event,
                                timelineProtectionState = timelineProtectionState,
                                onTileClick = onTileClick,
                                onTileLongClick = onTileLongClick,
                                onShowContentClick = onShowContentClick,
                                overlayText = if (index == 1 && remainingCount > 0) "+$remainingCount" else null,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                            )
                        }
                    }
                }
            }
        }
        AlbumCaption(
            album = album,
            onLinkClick = onLinkClick,
            onLinkLongClick = onLinkLongClick,
            onContentLayoutChange = onContentLayoutChange,
        )
    }
}

@Composable
private fun AlbumCaption(
    album: TimelineItem.MediaAlbum,
    onLinkClick: (Link) -> Unit,
    onLinkLongClick: (Link) -> Unit,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit,
) {
    val content = album.lastEvent.content
    val rawCaption: String? = when (content) {
        is TimelineItemImageContent -> content.caption
        is TimelineItemVideoContent -> content.caption
        else -> null
    }
    if (rawCaption == null) return
    val formattedCaption: CharSequence? = when (content) {
        is TimelineItemImageContent -> content.formattedCaption
        is TimelineItemVideoContent -> content.formattedCaption
        else -> null
    }
    val caption = if (LocalInspectionMode.current) {
        SpannedString(rawCaption)
    } else {
        formattedCaption ?: SpannedString(rawCaption)
    }
    Spacer(modifier = Modifier.height(8.dp))
    CompositionLocalProvider(
        LocalContentColor provides ElementTheme.colors.textPrimary,
        LocalTextStyle provides ElementTheme.typography.fontBodyLgRegular,
    ) {
        EditorStyledText(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp), // This is (12.dp - 8.dp) contentPadding from CommonLayout
            text = caption,
            style = ElementRichTextEditorStyle.textStyle(),
            onLinkClickedListener = onLinkClick,
            onLinkLongClickedListener = onLinkLongClick,
            releaseOnDetach = false,
            onTextLayout = ContentAvoidingLayout.measureLegacyLastTextLine(onContentLayoutChange = onContentLayoutChange),
        )
    }
}

@Composable
private fun AlbumTile(
    event: TimelineItem.Event,
    timelineProtectionState: TimelineProtectionState,
    onTileClick: (TimelineItem.Event) -> Unit,
    onTileLongClick: (TimelineItem.Event) -> Unit,
    onShowContentClick: (EventId?) -> Unit,
    modifier: Modifier = Modifier,
    overlayText: String? = null,
) {
    val hideMediaContent = timelineProtectionState.hideMediaContent(event.eventId, event.isMine)
    val isVideo = event.content is TimelineItemVideoContent
    val contentDescription = stringResource(
        if (isVideo) CommonStrings.common_video else CommonStrings.common_image
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .background(ElementTheme.colors.bgSubtleSecondary),
        contentAlignment = Alignment.Center,
    ) {
        ProtectedView(
            hideContent = hideMediaContent,
            onShowClick = { onShowContentClick(event.eventId) },
            modifier = Modifier.fillMaxSize(),
        ) {
            AlbumThumbnail(
                event = event,
                contentDescription = contentDescription,
                onContentClick = onTileClick,
                onLongClick = onTileLongClick,
            )
        }
        if (isVideo) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = CompoundIcons.PlaySolid(),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        overlayText?.let { text ->
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    style = ElementTheme.typography.fontBodyLgMedium,
                )
            }
        }
    }
}

@Composable
private fun AlbumThumbnail(
    event: TimelineItem.Event,
    contentDescription: String,
    onContentClick: (TimelineItem.Event) -> Unit,
    onLongClick: (TimelineItem.Event) -> Unit,
) {
    val clickableModifier = if (isTalkbackActive()) {
        Modifier
    } else {
        Modifier
            .combinedClickable(
                onClick = { onContentClick(event) },
                onLongClick = { onLongClick(event) },
            )
            .onKeyboardContextMenuAction { onLongClick(event) }
    }
    when (val content = event.content) {
        is TimelineItemImageContent -> AsyncImage(
            model = content.thumbnailMediaRequestData,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blurHashBackground(content.blurhash, alpha = 0.9f)
                .then(clickableModifier),
        )
        is TimelineItemVideoContent -> AsyncImage(
            model = MediaRequestData(
                source = content.thumbnailSource,
                kind = MediaRequestData.Kind.Thumbnail(
                    width = content.thumbnailWidth?.toLong() ?: MAX_THUMBNAIL_WIDTH,
                    height = content.thumbnailHeight?.toLong() ?: MAX_THUMBNAIL_HEIGHT,
                ),
            ),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blurHashBackground(content.blurHash, alpha = 0.9f)
                .then(clickableModifier),
        )
        else -> Unit
    }
}

@PreviewsDayNight
@Composable
internal fun TimelineItemMediaAlbumRowPreview() = ElementPreview {
    Column {
        ATimelineItemMediaAlbumRow(album = aMediaAlbum(count = 2))
        ATimelineItemMediaAlbumRow(album = aMediaAlbum(count = 3, withVideo = true))
        ATimelineItemMediaAlbumRow(album = aMediaAlbum(count = 4, withCaption = true))
        ATimelineItemMediaAlbumRow(album = aMediaAlbum(count = 7, isMine = true))
    }
}
