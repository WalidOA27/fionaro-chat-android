/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.stickers.api.SoundStickerSender
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.media.MediaAttachmentEncryptor
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.item.event.MAX_STICKER_AUDIO_DURATION_MS
import io.element.android.libraries.matrix.api.timeline.item.event.MAX_STICKER_IMAGE_SIZE
import io.element.android.libraries.matrix.api.timeline.item.event.STICKER_AUDIO_CONTENT_KEY
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

@ContributesBinding(SessionScope::class)
class DefaultSoundStickerSender(
    private val matrixClient: MatrixClient,
    private val mediaAttachmentEncryptor: MediaAttachmentEncryptor,
    private val dispatchers: CoroutineDispatchers,
) : SoundStickerSender {
    override suspend fun send(
        room: JoinedRoom,
        imageFile: File,
        imageMimeType: String,
        width: Int,
        height: Int,
        audioFile: File,
        audioMimeType: String,
        durationMs: Long,
        body: String,
    ): Result<Unit> = withContext(dispatchers.io) {
        runCatchingExceptions {
            validate(width = width, height = height, durationMs = durationMs, body = body)

            val imageBytes = imageFile.readBytes()
            val audioBytes = audioFile.readBytes()
            val isEncrypted = room.info().isEncrypted == true

            val uploadedImage = upload(
                bytes = imageBytes,
                mimeType = imageMimeType,
                encrypt = isEncrypted,
            )
            val uploadedAudio = upload(
                bytes = audioBytes,
                mimeType = audioMimeType,
                encrypt = isEncrypted,
            )

            val contentJson = buildStickerContentJson(
                body = body.ifBlank { DEFAULT_STICKER_BODY },
                image = uploadedImage,
                imageWidth = width,
                imageHeight = height,
                audio = uploadedAudio,
                audioDurationMs = durationMs,
            )

            room.sendRawMessageLikeEvent(eventType = EVENT_TYPE_STICKER, contentJson = contentJson).getOrThrow()
        }
    }

    private suspend fun upload(
        bytes: ByteArray,
        mimeType: String,
        encrypt: Boolean,
    ): UploadedStickerMedia {
        val (data, encryptedFileJson) = if (encrypt) {
            val encryptedAttachment = mediaAttachmentEncryptor.encrypt(bytes)
            encryptedAttachment.ciphertext to encryptedAttachment.encryptedFileJson
        } else {
            bytes to null
        }
        // Encrypted payloads are opaque, do not let clients interpret them as the original mime type.
        val uploadMimeType = if (encrypt) MimeTypes.OctetStream else mimeType
        val mxcUrl = matrixClient.uploadMedia(mimeType = uploadMimeType, data = data).getOrThrow()
        return UploadedStickerMedia(
            mxcUrl = mxcUrl,
            mimeType = mimeType,
            size = bytes.size.toLong(),
            encryptedFileJson = encryptedFileJson,
        )
    }

    private fun validate(width: Int, height: Int, durationMs: Long, body: String) {
        require(width in 1..MAX_STICKER_IMAGE_SIZE) {
            "Sticker width must be between 1 and $MAX_STICKER_IMAGE_SIZE px, was $width"
        }
        require(height in 1..MAX_STICKER_IMAGE_SIZE) {
            "Sticker height must be between 1 and $MAX_STICKER_IMAGE_SIZE px, was $height"
        }
        require(durationMs in 1..MAX_STICKER_AUDIO_DURATION_MS) {
            "Sticker sound duration must be between 1 and $MAX_STICKER_AUDIO_DURATION_MS ms, was $durationMs"
        }
        require(body.length <= MAX_STICKER_BODY_LENGTH) {
            "Sticker body must be at most $MAX_STICKER_BODY_LENGTH characters"
        }
    }

    private companion object {
        const val EVENT_TYPE_STICKER = "m.sticker"
        const val DEFAULT_STICKER_BODY = "Sticker"
        const val MAX_STICKER_BODY_LENGTH = 1024
    }
}

internal data class UploadedStickerMedia(
    val mxcUrl: String,
    val mimeType: String,
    val size: Long,
    /**
     * `EncryptedFile` JSON (without `url`) when the media was encrypted, `null` otherwise.
     */
    val encryptedFileJson: String?,
)

/**
 * Builds the full `m.sticker` content.
 */
internal fun buildStickerContentJson(
    body: String,
    image: UploadedStickerMedia,
    imageWidth: Int,
    imageHeight: Int,
    audio: UploadedStickerMedia,
    audioDurationMs: Long,
): String {
    return buildJsonObject {
        put("body", body)
        putJsonObject("info") {
            put("mimetype", image.mimeType)
            put("size", image.size)
            put("w", imageWidth)
            put("h", imageHeight)
        }
        image.putMediaSource(this)
        putJsonObject(STICKER_AUDIO_CONTENT_KEY) {
            audio.putMediaSource(this)
            put("mimetype", audio.mimeType)
            put("size", audio.size)
            put("duration", audioDurationMs)
        }
    }.toString()
}

private fun UploadedStickerMedia.putMediaSource(json: JsonObjectBuilder) {
    if (encryptedFileJson == null) {
        json.put("url", mxcUrl)
    } else {
        val encryptedFile = Json.parseToJsonElement(encryptedFileJson).jsonObject + ("url" to JsonPrimitive(mxcUrl))
        json.put("file", JsonObject(encryptedFile))
    }
}
