/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.impl.creation.StickerSoundFile
import io.element.android.features.stickers.impl.creation.StickerVideoAudioExtractor
import io.element.android.libraries.core.mimetype.MimeTypes
import java.io.File

class FakeStickerVideoAudioExtractor(
    private var result: (File, Long, Long) -> Result<StickerSoundFile> = { _, _, durationMs ->
        Result.success(
            aStickerSoundFile(
                durationMs = durationMs,
                file = File("/tmp/sticker-video-sound.m4a"),
                mimeType = MimeTypes.Mp4Audio,
            )
        )
    },
) : StickerVideoAudioExtractor {
    var calls: MutableList<Call> = mutableListOf()
        private set

    fun givenResult(value: (File, Long, Long) -> Result<StickerSoundFile>) {
        result = value
    }

    override suspend fun extractAudio(videoFile: File, startMs: Long, durationMs: Long): Result<StickerSoundFile> {
        calls.add(Call(videoFile, startMs, durationMs))
        return result(videoFile, startMs, durationMs)
    }

    data class Call(
        val videoFile: File,
        val startMs: Long,
        val durationMs: Long,
    )
}
