/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline.item.event

import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.item.event.STICKER_AUDIO_CONTENT_KEY
import io.element.android.libraries.matrix.api.timeline.item.event.StickerAudio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Parses the Fionaro sticker sound from the raw JSON of an `m.sticker` event.
 *
 * Any invalid or missing value makes the whole sound ignored, so that the sticker is still
 * displayed as a plain (silent) sticker.
 */
internal fun parseStickerAudio(rawEventJson: String?): StickerAudio? {
    if (rawEventJson.isNullOrBlank()) return null
    val content = try {
        Json.parseToJsonElement(rawEventJson).jsonObject["content"]?.asJsonObjectOrNull()
    } catch (_: Exception) {
        null
    } ?: return null
    val audio = content[STICKER_AUDIO_CONTENT_KEY]?.asJsonObjectOrNull() ?: return null
    val mimetype = audio["mimetype"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val durationMs = audio["duration"].asLongOrNull()?.takeIf { it > 0 } ?: return null
    val mediaSource = audio.toMediaSourceOrNull() ?: return null
    return StickerAudio(
        mediaSource = mediaSource,
        mimetype = mimetype,
        size = audio["size"].asLongOrNull()?.takeIf { it >= 0 },
        durationMs = durationMs,
    )
}

private fun JsonObject.toMediaSourceOrNull(): MediaSource? {
    // Encrypted media: "file": { EncryptedFile }
    val encryptedFile = get("file").asJsonObjectOrNull()
    if (encryptedFile != null) {
        val url = encryptedFile["url"].asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // Minimal validation of the EncryptedFile object, so that a malformed one is treated
        // as a plain sticker instead of a broken one.
        val hasKey = encryptedFile["key"].asJsonObjectOrNull() != null
        val hasIv = encryptedFile["iv"].asStringOrNull() != null
        if (!hasKey || !hasIv) return null
        return MediaSource(url = url, json = encryptedFile.toString())
    }
    // Plain media: "url": "mxc://…"
    val url = get("url").asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return MediaSource(url = url, json = null)
}

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asStringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString.not()) return null
    return primitive.contentOrNull
}

private fun JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.longOrNull
}
