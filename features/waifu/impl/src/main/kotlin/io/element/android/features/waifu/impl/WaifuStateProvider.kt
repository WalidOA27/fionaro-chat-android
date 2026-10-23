/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

open class WaifuStateProvider : PreviewParameterProvider<WaifuState> {
    override val values: Sequence<WaifuState>
        get() = sequenceOf(
            aWaifuState(isLoading = true),
            aWaifuState(
                tags = aWaifuTagList(),
                images = aWaifuImageList(),
                hasNextPage = true,
            ),
            aWaifuState(error = WaifuError.Generic),
            aWaifuState(
                error = WaifuError.NsfwForbidden,
                isNsfw = true,
            ),
            aWaifuState(isLoading = false),
            aWaifuState(
                tags = aWaifuTagList(),
                images = aWaifuImageList(),
                hasNextPage = true,
                isLoadingMore = true,
            ),
        )
}

open class WaifuLightboxStateProvider : PreviewParameterProvider<WaifuState> {
    override val values: Sequence<WaifuState>
        get() = sequenceOf(
            aWaifuState(
                tags = aWaifuTagList(),
                selectedTags = listOf("waifu"),
                images = aWaifuImageList(),
                lightboxIndex = 0,
            ),
        )
}

internal fun aWaifuState(
    tags: List<WaifuTag> = emptyList(),
    selectedTags: List<String> = emptyList(),
    isNsfw: Boolean = false,
    images: List<WaifuImage> = emptyList(),
    isLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasNextPage: Boolean = false,
    error: WaifuError? = null,
    lightboxIndex: Int? = null,
    eventSink: (WaifuEvent) -> Unit = {},
) = WaifuState(
    tags = tags,
    selectedTags = selectedTags,
    isNsfw = isNsfw,
    images = images,
    isLoading = isLoading,
    isLoadingMore = isLoadingMore,
    hasNextPage = hasNextPage,
    error = error,
    lightboxIndex = lightboxIndex,
    eventSink = eventSink,
)

internal fun aWaifuTag(
    id: Long = 1,
    name: String = "Waifu",
    slug: String = "waifu",
    imageCount: Long = 100,
) = WaifuTag(
    id = id,
    name = name,
    slug = slug,
    imageCount = imageCount,
)

internal fun aWaifuTagList() = listOf(
    aWaifuTag(id = 1, name = "Waifu", slug = "waifu", imageCount = 4276),
    aWaifuTag(id = 2, name = "Maid", slug = "maid", imageCount = 1200),
    aWaifuTag(id = 3, name = "Uniform", slug = "uniform", imageCount = 800),
)

internal fun aWaifuImage(
    id: Long = 1997,
    extension: String = ".jpeg",
    width: Int = 1203,
    height: Int = 2047,
    isNsfw: Boolean = false,
    isAnimated: Boolean = false,
) = WaifuImage(
    id = id,
    extension = extension,
    url = "https://cdn.waifu.im/$id$extension",
    width = width,
    height = height,
    byteSize = 186575,
    isNsfw = isNsfw,
    isAnimated = isAnimated,
    tags = listOf(WaifuImageTag(id = 1, name = "Waifu", slug = "waifu")),
)

internal fun aWaifuImageList() = listOf(
    aWaifuImage(id = 1997),
    aWaifuImage(id = 1998, width = 2047, height = 1203),
    aWaifuImage(id = 1999, extension = ".png", width = 1000, height = 1500),
)
