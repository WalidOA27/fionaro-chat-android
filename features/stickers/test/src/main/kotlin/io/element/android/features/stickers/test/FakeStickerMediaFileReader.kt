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
import io.element.android.features.stickers.impl.creation.StickerVideoSource
import io.element.android.libraries.core.mimetype.MimeTypes
import java.io.File

class FakeStickerMediaFileReader(
    private var imageResult: (Uri) -> Result<StickerImage> = { Result.success(aStickerImage()) },
    private var audioResult: (Uri) -> Result<StickerSoundFile> = { Result.success(aStickerSoundFile()) },
    private var videoResult: (Uri) -> Result<StickerVideoSource> = { Result.success(aStickerVideoSource()) },
    private var thumbnailResult: (StickerVideoSource, Long) -> Result<File> = { _, _ ->
        Result.success(File("/tmp/sticker-thumbnail.png"))
    },
) : StickerMediaFileReader {
    var imageCalls: MutableList<Uri> = mutableListOf()
        private set
    var audioCalls: MutableList<Uri> = mutableListOf()
        private set
    var videoCalls: MutableList<Uri> = mutableListOf()
        private set
    var thumbnailCalls: MutableList<ThumbnailCall> = mutableListOf()
        private set

    fun givenImageResult(value: (Uri) -> Result<StickerImage>) {
        imageResult = value
    }

    fun givenAudioResult(value: (Uri) -> Result<StickerSoundFile>) {
        audioResult = value
    }

    fun givenVideoResult(value: (Uri) -> Result<StickerVideoSource>) {
        videoResult = value
    }

    fun givenThumbnailResult(value: (StickerVideoSource, Long) -> Result<File>) {
        thumbnailResult = value
    }

    override suspend fun readImage(uri: Uri): Result<StickerImage> {
        imageCalls.add(uri)
        return imageResult(uri)
    }

    override suspend fun readAudio(uri: Uri): Result<StickerSoundFile> {
        audioCalls.add(uri)
        return audioResult(uri)
    }

    override suspend fun readVideo(uri: Uri): Result<StickerVideoSource> {
        videoCalls.add(uri)
        return videoResult(uri)
    }

    override suspend fun readVideoThumbnail(video: StickerVideoSource, positionMs: Long): Result<File> {
        thumbnailCalls.add(ThumbnailCall(video, positionMs))
        return thumbnailResult(video, positionMs)
    }

    data class ThumbnailCall(
        val video: StickerVideoSource,
        val positionMs: Long,
    )
}

fun aStickerImage(
    file: File = File("/tmp/sticker.png"),
    mimeType: String = MimeTypes.Png,
    width: Int = 512,
    height: Int = 512,
) = StickerImage(
    file = file,
    mimeType = mimeType,
    width = width,
    height = height,
)

fun aStickerSoundFile(
    durationMs: Long = 3200,
    file: File = File("/tmp/sticker.ogg"),
    mimeType: String = MimeTypes.Ogg,
) = StickerSoundFile(
    file = file,
    mimeType = mimeType,
    durationMs = durationMs,
)

fun aStickerVideoSource(
    file: File = File("/tmp/sticker-video.mp4"),
    mimeType: String = MimeTypes.Mp4,
    durationMs: Long = 12_000,
    hasAacAudioTrack: Boolean = true,
) = StickerVideoSource(
    file = file,
    mimeType = mimeType,
    durationMs = durationMs,
    hasAacAudioTrack = hasAacAudioTrack,
)
