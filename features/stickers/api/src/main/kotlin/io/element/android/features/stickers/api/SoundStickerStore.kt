/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Local, per-app-install storage of stickers and their optional sound.
 */
interface SoundStickerStore {
    /**
     * The saved stickers, newest first.
     */
    val stickers: Flow<List<SoundSticker>>

    /**
     * Copies the media into the store directory and indexes the sticker.
     *
     * At most [MAX_STICKERS] stickers are kept, the oldest ones being removed.
     */
    suspend fun add(
        imageFile: File,
        imageMimeType: String,
        width: Int,
        height: Int,
        audioFile: File? = null,
        audioMimeType: String? = null,
        durationMs: Long? = null,
        body: String,
    ): Result<SoundSticker>

    /**
     * Removes the sticker from the index and deletes its media.
     */
    suspend fun delete(stickerId: String): Result<Unit>

    companion object {
        const val MAX_STICKERS = 100
    }
}
