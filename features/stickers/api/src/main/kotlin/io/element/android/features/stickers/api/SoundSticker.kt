/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

/**
 * A sticker saved locally in "My stickers". The sound is optional.
 */
data class SoundSticker(
    val id: String,
    val imagePath: String,
    val imageMimeType: String,
    val width: Int,
    val height: Int,
    val audioPath: String?,
    val audioMimeType: String?,
    val durationMs: Long?,
    val body: String,
    val createdAt: Long,
    /**
     * The mxc URL the sticker was received from, when it was saved from the timeline.
     * Used to avoid saving the same sticker twice. `null` for locally created stickers
     * and for stickers saved by older versions of the app.
     */
    val sourceUrl: String? = null,
)
