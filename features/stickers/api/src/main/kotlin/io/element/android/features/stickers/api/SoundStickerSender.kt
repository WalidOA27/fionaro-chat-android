/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import java.io.File

/**
 * Sends a sticker: a spec-valid `m.sticker` event, with an optional extra
 * `io.fionaro.sticker_audio` content field when a sound is attached.
 *
 * Media is encrypted by the sender when the room is encrypted. The event itself is
 * transparently encrypted by the SDK when using [JoinedRoom.sendRawMessageLikeEvent].
 */
interface SoundStickerSender {
    /**
     * Uploads the media and sends the sticker.
     *
     * @param room the room to send the sticker to.
     * @param imageFile the sticker image. It must already fit in 512x512 px (see [StickerImageProcessor]).
     * @param imageMimeType the mime type of the image.
     * @param width the width of the image in pixels.
     * @param height the height of the image in pixels.
     * @param audioFile the sound to attach, at most 10s long, or `null` for a silent sticker.
     * @param audioMimeType the mime type of the sound (typically `audio/ogg`), required with `audioFile`.
     * @param durationMs the duration of the sound in milliseconds, at most 10 000, required with `audioFile`.
     * @param body the alt text of the sticker.
     * @param threadRootEventId when set, the sticker is sent in this thread instead of the main timeline.
     */
    suspend fun send(
        room: JoinedRoom,
        imageFile: File,
        imageMimeType: String,
        width: Int,
        height: Int,
        audioFile: File? = null,
        audioMimeType: String? = null,
        durationMs: Long? = null,
        body: String = "Sticker",
        threadRootEventId: EventId? = null,
    ): Result<Unit>
}
