/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

/**
 * A sound sticker saved locally in "My stickers".
 */
data class SoundSticker(
    val id: String,
    val imagePath: String,
    val imageMimeType: String,
    val width: Int,
    val height: Int,
    val audioPath: String,
    val audioMimeType: String,
    val durationMs: Long,
    val body: String,
    val createdAt: Long,
)
