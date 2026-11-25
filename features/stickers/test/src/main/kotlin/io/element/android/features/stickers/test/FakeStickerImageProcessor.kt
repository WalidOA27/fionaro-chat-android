/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.test

import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.api.StickerImageProcessor
import io.element.android.tests.testutils.simulateLongTask
import java.io.File

class FakeStickerImageProcessor(
    private var result: (File, String) -> Result<StickerImage> = { file, mimeType ->
        Result.success(StickerImage(file = file, mimeType = mimeType, width = 512, height = 512))
    },
) : StickerImageProcessor {
    fun givenResult(value: (File, String) -> Result<StickerImage>) {
        result = value
    }

    override suspend fun process(inputFile: File, mimeType: String): Result<StickerImage> = simulateLongTask {
        result(inputFile, mimeType)
    }
}
