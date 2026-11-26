/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.tray

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.stickers.api.SoundSticker
import kotlinx.collections.immutable.toImmutableList

open class MyStickersStateProvider : PreviewParameterProvider<MyStickersState> {
    override val values: Sequence<MyStickersState>
        get() = sequenceOf(
            aMyStickersState(),
            aMyStickersState(
                stickers = listOf(
                    aSoundSticker("1"),
                    aSoundSticker("2", withSound = false),
                    aSoundSticker("3"),
                ),
            ),
            aMyStickersState(
                stickers = listOf(aSoundSticker("1")),
                sendingStickerId = "1",
            ),
            aMyStickersState(
                stickers = listOf(aSoundSticker("1")),
                stickerPendingDelete = aSoundSticker("1"),
            ),
        )
}

fun aSoundSticker(id: String, withSound: Boolean = true) = SoundSticker(
    id = id,
    imagePath = "/tmp/element-sticker.png",
    imageMimeType = "image/png",
    width = 512,
    height = 512,
    audioPath = if (withSound) "/tmp/element-sticker.ogg" else null,
    audioMimeType = if (withSound) "audio/ogg" else null,
    durationMs = if (withSound) 3200 else null,
    body = "Sticker $id",
    createdAt = id.toLongOrNull() ?: 0L,
)

fun aMyStickersState(
    stickers: List<SoundSticker> = emptyList(),
    sendingStickerId: String? = null,
    stickerPendingDelete: SoundSticker? = null,
    errorMessage: Int? = null,
    eventSink: (MyStickersEvent) -> Unit = {},
) = MyStickersState(
    stickers = stickers.toImmutableList(),
    sendingStickerId = sendingStickerId,
    stickerPendingDelete = stickerPendingDelete,
    errorMessage = errorMessage,
    eventSink = eventSink,
)
