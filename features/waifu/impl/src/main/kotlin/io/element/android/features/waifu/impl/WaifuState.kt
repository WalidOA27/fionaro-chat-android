/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

sealed interface WaifuEvent {
    data object Retry : WaifuEvent
    data object LoadMore : WaifuEvent
    data object ClearTags : WaifuEvent
    data object CloseLightbox : WaifuEvent
    data object PrevImage : WaifuEvent
    data object NextImage : WaifuEvent
    data class ToggleNsfw(val enabled: Boolean) : WaifuEvent
    data class ToggleTag(val slug: String) : WaifuEvent
    data class OpenImage(val index: Int) : WaifuEvent
    data class Download(val image: WaifuImage) : WaifuEvent
}

data class WaifuState(
    val tags: List<WaifuTag> = emptyList(),
    val selectedTags: List<String> = emptyList(),
    val isNsfw: Boolean = false,
    val images: List<WaifuImage> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val error: String? = null,
    val lightboxIndex: Int? = null,
    val eventSink: (WaifuEvent) -> Unit = {},
) {
    val displayTags: List<WaifuTag>
        get() = tags.sortedByDescending { it.imageCount }.take(40)

    val sessionLimitReached: Boolean
        get() = images.size >= MAX_WAIFU_SESSION_IMAGES && hasNextPage
}
