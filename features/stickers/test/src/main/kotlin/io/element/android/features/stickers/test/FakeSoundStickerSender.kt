/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.api.SoundStickerSender
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.tests.testutils.simulateLongTask
import java.io.File

class FakeSoundStickerSender(
    private var result: Result<Unit> = Result.success(Unit),
) : SoundStickerSender {
    var lastParameters: Parameters? = null
        private set

    fun givenResult(value: Result<Unit>) {
        result = value
    }

    override suspend fun send(
        room: JoinedRoom,
        imageFile: File,
        imageMimeType: String,
        width: Int,
        height: Int,
        audioFile: File,
        audioMimeType: String,
        durationMs: Long,
        body: String,
    ): Result<Unit> = simulateLongTask {
        lastParameters = Parameters(room, imageFile, imageMimeType, width, height, audioFile, audioMimeType, durationMs, body)
        result
    }

    data class Parameters(
        val room: JoinedRoom,
        val imageFile: File,
        val imageMimeType: String,
        val width: Int,
        val height: Int,
        val audioFile: File,
        val audioMimeType: String,
        val durationMs: Long,
        val body: String,
    )
}
