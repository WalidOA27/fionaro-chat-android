/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import android.net.Uri
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.creation.StickerMediaFileReader
import io.element.android.features.stickers.impl.creation.StickerSoundFile

class FakeStickerMediaFileReader(
    private var imageResult: (Uri) -> Result<StickerImage> = { Result.success(aStickerImage()) },
    private var audioResult: (Uri) -> Result<StickerSoundFile> = { Result.success(aStickerSoundFile()) },
) : StickerMediaFileReader {
    var imageCalls: MutableList<Uri> = mutableListOf()
        private set
    var audioCalls: MutableList<Uri> = mutableListOf()
        private set

    fun givenImageResult(value: (Uri) -> Result<StickerImage>) {
        imageResult = value
    }

    fun givenAudioResult(value: (Uri) -> Result<StickerSoundFile>) {
        audioResult = value
    }

    override suspend fun readImage(uri: Uri): Result<StickerImage> {
        imageCalls.add(uri)
        return imageResult(uri)
    }

    override suspend fun readAudio(uri: Uri): Result<StickerSoundFile> {
        audioCalls.add(uri)
        return audioResult(uri)
    }
}

fun aStickerImage() = StickerImage(
    file = java.io.File("/tmp/sticker.png"),
    mimeType = "image/png",
    width = 512,
    height = 512,
)

fun aStickerSoundFile(durationMs: Long = 3200) = StickerSoundFile(
    file = java.io.File("/tmp/sticker.ogg"),
    mimeType = "audio/ogg",
    durationMs = durationMs,
)
