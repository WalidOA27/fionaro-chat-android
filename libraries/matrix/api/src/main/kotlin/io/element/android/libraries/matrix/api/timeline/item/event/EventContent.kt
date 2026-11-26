/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.timeline.item.event

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.matrix.api.poll.PollAnswer
import io.element.android.libraries.matrix.api.poll.PollKind
import io.element.android.libraries.matrix.api.room.location.AssetType
import io.element.android.libraries.matrix.api.room.location.LiveLocationInfo
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap

@Immutable
sealed interface EventContent

data class MessageContent(
    val body: String,
    val inReplyTo: InReplyTo?,
    val isEdited: Boolean,
    val threadInfo: EventThreadInfo?,
    val type: MessageType,
) : EventContent

data object RedactedContent : EventContent

data class StickerContent(
    val filename: String,
    val body: String?,
    val info: ImageInfo,
    val source: MediaSource,
    val threadInfo: EventThreadInfo?,
    /**
     * Optional sound attached to the sticker by Fionaro clients. `null` for plain stickers.
     */
    val audio: StickerAudio? = null,
) : EventContent {
    val bestDescription: String
        get() = body ?: filename
}

/**
 * Key of the custom content field carrying a sticker sound.
 */
const val STICKER_AUDIO_CONTENT_KEY = "io.fionaro.sticker_audio"

/**
 * Maximum duration of a sticker sound, in milliseconds.
 */
const val MAX_STICKER_AUDIO_DURATION_MS = 10_000L

/**
 * Maximum width or height of a sticker image, in pixels.
 */
const val MAX_STICKER_IMAGE_SIZE = 512

/**
 * Sound attached to a sticker (Fionaro extension stored in `io.fionaro.sticker_audio`).
 */
@Immutable
data class StickerAudio(
    /**
     * The media source of the audio, plain (`url`) or encrypted (`file`).
     */
    val mediaSource: MediaSource,
    val mimetype: String,
    val size: Long?,
    /**
     * Duration of the audio clip in milliseconds. Senders are supposed to cap it at 10s, but
     * receivers must tolerate longer values.
     */
    val durationMs: Long,
)

data class PollContent(
    val question: String,
    val kind: PollKind,
    val maxSelections: ULong,
    val answers: ImmutableList<PollAnswer>,
    val votes: ImmutableMap<String, ImmutableList<UserId>>,
    val endTime: ULong?,
    val isEdited: Boolean,
    val threadInfo: EventThreadInfo?,
) : EventContent

data class UnableToDecryptContent(
    val data: Data,
    val threadInfo: EventThreadInfo?,
) : EventContent {
    @Immutable
    sealed interface Data {
        data class OlmV1Curve25519AesSha2(
            val senderKey: String
        ) : Data

        data class MegolmV1AesSha2(
            val sessionId: String,
            val utdCause: UtdCause
        ) : Data

        data object Unknown : Data
    }
}

data class RoomMembershipContent(
    val userId: UserId,
    val userDisplayName: String?,
    val change: MembershipChange?,
    val reason: String?,
) : EventContent

data class ProfileChangeContent(
    val displayName: String?,
    val prevDisplayName: String?,
    val avatarUrl: String?,
    val prevAvatarUrl: String?
) : EventContent

data class StateContent(
    val stateKey: String,
    val content: OtherState
) : EventContent

data class FailedToParseMessageLikeContent(
    val eventType: String,
    val error: String
) : EventContent

data class FailedToParseStateContent(
    val eventType: String,
    val stateKey: String,
    val error: String
) : EventContent

data class LiveLocationContent(
    val isLive: Boolean,
    val description: String?,
    val startTimestamp: Long,
    val timeout: Long,
    val assetType: AssetType?,
    val locations: List<LiveLocationInfo>,
) : EventContent {
    val endTimestamp = startTimestamp + timeout
}

data object LegacyCallInviteContent : EventContent

data class CallNotifyContent(
    val callIntent: CallIntent,
    val declinedBy: List<UserId>
) : EventContent

data object UnknownContent : EventContent
