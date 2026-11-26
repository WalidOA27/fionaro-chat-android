/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Copies a window of the audio track of a video into a local `.m4a` file.
 */
interface StickerVideoAudioExtractor {
    /**
     * Extracts [startMs, startMs + durationMs] of the AAC audio track of [videoFile].
     *
     * Fails when the video has no AAC audio track.
     */
    suspend fun extractAudio(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
    ): Result<StickerSoundFile>
}

@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultStickerVideoAudioExtractor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : StickerVideoAudioExtractor {
    override suspend fun extractAudio(
        videoFile: File,
        startMs: Long,
        durationMs: Long,
    ): Result<StickerSoundFile> = withContext(dispatchers.io) {
        runCatchingExceptions {
            extract(videoFile = videoFile, startMs = startMs, durationMs = durationMs)
        }
    }

    private fun extract(videoFile: File, startMs: Long, durationMs: Long): StickerSoundFile {
        require(durationMs in 1..MAX_AUDIO_DURATION_MS) {
            "The sound must last between 1 and $MAX_AUDIO_DURATION_MS ms, was $durationMs"
        }
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) == AUDIO_MIME_TYPE
            } ?: error("The video has no AAC audio track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val output = newOutputFile()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(MIN_BUFFER_SIZE)
            } else {
                MIN_BUFFER_SIZE
            }
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            val startUs = startMs * 1000
            val endUs = (startMs + durationMs) * 1000
            var firstSampleTimeUs = UNKNOWN_TIME
            var samplesWritten = 0

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs >= endUs) break
                if (sampleTimeUs >= startUs) {
                    if (firstSampleTimeUs == UNKNOWN_TIME) firstSampleTimeUs = sampleTimeUs
                    info.set(0, size, sampleTimeUs - firstSampleTimeUs, extractor.sampleFlags)
                    muxer.writeSampleData(muxerTrack, buffer, info)
                    samplesWritten += 1
                }
                if (!extractor.advance()) break
            }
            check(samplesWritten > 0) { "The video has no audio in the selected window" }
            return StickerSoundFile(
                file = output,
                mimeType = MimeTypes.Mp4Audio,
                durationMs = durationMs,
            )
        } finally {
            extractor.release()
            muxer?.let { mediaMuxer ->
                if (muxerStarted) {
                    runCatching { mediaMuxer.stop() }
                }
                mediaMuxer.release()
            }
        }
    }

    private fun newOutputFile(): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        return File(directory, "${UUID.randomUUID()}.m4a")
    }

    private companion object {
        const val CACHE_DIRECTORY = "sticker_video_audio"
        const val AUDIO_MIME_TYPE = "audio/mp4a-latm"
        const val MIN_BUFFER_SIZE = 256 * 1024
        const val MAX_AUDIO_DURATION_MS = 10_000L
        const val UNKNOWN_TIME = -1L
    }
}
