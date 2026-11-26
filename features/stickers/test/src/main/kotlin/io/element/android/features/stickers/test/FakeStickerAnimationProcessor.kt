/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.creation.StickerAnimationProcessor
import io.element.android.libraries.core.mimetype.MimeTypes
import kotlinx.coroutines.CompletableDeferred
import java.io.File

class FakeStickerAnimationProcessor(
    private var videoResult: (File, Long, Long) -> Result<StickerImage> = { _, _, _ ->
        Result.success(aStickerImage(mimeType = MimeTypes.Gif))
    },
    private var imageResult: (File, String) -> Result<StickerImage> = { _, _ ->
        Result.success(aStickerImage(mimeType = MimeTypes.Gif))
    },
) : StickerAnimationProcessor {
    var videoCalls: MutableList<VideoCall> = mutableListOf()
        private set
    var imageCalls: MutableList<ImageCall> = mutableListOf()
        private set
    var progressValues: MutableList<Float> = mutableListOf()
        private set

    /**
     * When set, the processing suspends until it completes, so that tests can observe the
     * in-progress state.
     */
    var gate: CompletableDeferred<Unit>? = null

    fun givenVideoResult(value: (File, Long, Long) -> Result<StickerImage>) {
        videoResult = value
    }

    fun givenImageResult(value: (File, String) -> Result<StickerImage>) {
        imageResult = value
    }

    override suspend fun processVideo(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
        onProgress: (Float) -> Unit,
    ): Result<StickerImage> {
        videoCalls.add(VideoCall(videoFile, startMs, durationMs))
        onProgress(0.42f)
        gate?.await()
        onProgress(1f)
        return videoResult(videoFile, startMs, durationMs)
    }

    override suspend fun processAnimatedImage(
        imageFile: File,
        mimeType: String,
        onProgress: (Float) -> Unit,
    ): Result<StickerImage> {
        imageCalls.add(ImageCall(imageFile, mimeType))
        onProgress(1f)
        return imageResult(imageFile, mimeType)
    }

    data class VideoCall(
        val videoFile: File,
        val startMs: Long,
        val durationMs: Long,
    )

    data class ImageCall(
        val imageFile: File,
        val mimeType: String,
    )
}
