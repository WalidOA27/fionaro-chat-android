/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

import java.io.File

/**
 * Prepares a picked image so that it can be sent as a sticker.
 */
interface StickerImageProcessor {
    /**
     * Scales [inputFile] to fit in 512x512 px and encodes it as PNG.
     *
     * GIF and WebP images are passed through unchanged when they already fit in 512x512 px
     * and are at most 1 MiB. A GIF which does not fit is decoded frame by frame and re-encoded
     * as an animated sticker sized GIF. Returns a failure when an animated WebP does not fit,
     * when a GIF cannot be re-encoded, or when an image cannot be decoded.
     */
    suspend fun process(inputFile: File, mimeType: String): Result<StickerImage>
}

/**
 * A sticker image ready to be uploaded.
 */
data class StickerImage(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int,
)
