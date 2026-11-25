/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

/**
 * Fixture used by previews and tests.
 */
fun aStickerSoundState(
    isPlaying: Boolean = false,
    eventSink: (StickerSoundEvent) -> Unit = {},
): StickerSoundState = StickerSoundState(
    isPlaying = isPlaying,
    eventSink = eventSink,
)
