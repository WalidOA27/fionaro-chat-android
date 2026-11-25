/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.api.SoundSticker
import io.element.android.features.stickers.api.SoundStickerStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

class InMemorySoundStickerStore(
    initialStickers: List<SoundSticker> = emptyList(),
    private var addResult: (List<SoundSticker>) -> Result<SoundSticker> = { Result.success(aSoundSticker(id = "added")) },
) : SoundStickerStore {
    private val _stickers = MutableStateFlow(initialStickers)
    override val stickers: Flow<List<SoundSticker>> = _stickers.asStateFlow()

    var addCalls: MutableList<AddParameters> = mutableListOf()
        private set
    var deleteCalls: MutableList<String> = mutableListOf()
        private set

    fun givenAddResult(value: (List<SoundSticker>) -> Result<SoundSticker>) {
        addResult = value
    }

    override suspend fun add(
        imageFile: File,
        imageMimeType: String,
        width: Int,
        height: Int,
        audioFile: File,
        audioMimeType: String,
        durationMs: Long,
        body: String,
    ): Result<SoundSticker> {
        addCalls.add(
            AddParameters(imageFile, imageMimeType, width, height, audioFile, audioMimeType, durationMs, body)
        )
        val result = addResult(_stickers.value)
        result.onSuccess { sticker ->
            _stickers.update { current -> listOf(sticker) + current }
        }
        return result
    }

    override suspend fun delete(stickerId: String): Result<Unit> {
        deleteCalls.add(stickerId)
        _stickers.update { current -> current.filterNot { it.id == stickerId } }
        return Result.success(Unit)
    }

    data class AddParameters(
        val imageFile: File,
        val imageMimeType: String,
        val width: Int,
        val height: Int,
        val audioFile: File,
        val audioMimeType: String,
        val durationMs: Long,
        val body: String,
    )
}

fun aSoundSticker(
    id: String = "sticker-id",
    imagePath: String = "/tmp/sticker.png",
    audioPath: String = "/tmp/sticker.ogg",
    body: String = "Sticker",
) = SoundSticker(
    id = id,
    imagePath = imagePath,
    imageMimeType = "image/png",
    width = 512,
    height = 512,
    audioPath = audioPath,
    audioMimeType = "audio/ogg",
    durationMs = 3200,
    body = body,
    createdAt = 0,
)
