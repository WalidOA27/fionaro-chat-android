/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

internal data class WaifuMediaType(
    val mimeType: String,
    val extension: String,
)

private val supportedMediaTypes = mapOf(
    "image/jpeg" to ".jpeg",
    "image/jpg" to ".jpeg",
    "image/png" to ".png",
    "image/gif" to ".gif",
    "image/webp" to ".webp",
)

private val extensionToMimeType = mapOf(
    ".jpeg" to "image/jpeg",
    ".jpg" to "image/jpeg",
    ".png" to "image/png",
    ".gif" to "image/gif",
    ".webp" to "image/webp",
)

/**
 * Resolve the media type to persist from the HTTP response Content-Type.
 * Falls back to the extension returned by the API when the header is missing or unsupported.
 */
internal fun resolveWaifuMediaType(contentType: String?, apiExtension: String?): WaifuMediaType {
    val headerMimeType = contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
    if (headerMimeType != null && headerMimeType in supportedMediaTypes) {
        return WaifuMediaType(
            mimeType = if (headerMimeType == "image/jpg") "image/jpeg" else headerMimeType,
            extension = supportedMediaTypes.getValue(headerMimeType),
        )
    }

    val normalizedExtension = apiExtension
        ?.trim()
        ?.lowercase()
        ?.let { if (it.startsWith(".")) it else ".$it" }
    if (normalizedExtension != null) {
        val fallbackMimeType = extensionToMimeType[normalizedExtension]
        if (fallbackMimeType != null) {
            return WaifuMediaType(
                mimeType = fallbackMimeType,
                extension = normalizedExtension,
            )
        }
    }

    return WaifuMediaType(mimeType = "image/jpeg", extension = ".jpeg")
}

internal fun WaifuImage.displayNameFor(contentType: String?): String {
    val mediaType = resolveWaifuMediaType(contentType = contentType, apiExtension = extension)
    return "waifu-$id${mediaType.extension}"
}
