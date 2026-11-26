/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.tray

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.element.android.features.stickers.api.SoundSticker
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class MyStickersState(
    val stickers: ImmutableList<SoundSticker>,
    val sendingStickerId: String?,
    val stickerPendingDelete: SoundSticker?,
    @StringRes val errorMessage: Int?,
    val eventSink: (MyStickersEvent) -> Unit,
)

sealed interface MyStickersEvent {
    /**
     * Sends the sticker directly to the room, then closes the tray.
     */
    data class Send(val stickerId: String) : MyStickersEvent
    data class RequestDelete(val stickerId: String) : MyStickersEvent
    data object ConfirmDelete : MyStickersEvent
    data object DismissDelete : MyStickersEvent
    data object DismissError : MyStickersEvent
    data object NavBack : MyStickersEvent
}
