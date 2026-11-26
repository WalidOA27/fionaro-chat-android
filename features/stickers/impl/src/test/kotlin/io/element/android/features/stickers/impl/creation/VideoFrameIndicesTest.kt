/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoFrameIndicesTest {
    @Test
    fun `sourceIndices - maps a 10 fps sample onto a 30 fps video`() {
        val indices = VideoFrameIndices.sourceIndices(
            sourceFrameCount = 180,
            videoDurationMs = 6_000,
            startMs = 0,
            durationMs = 6_000,
            framesPerSecond = 10,
        )

        assertThat(indices.toList()).hasSize(60)
        assertThat(indices.toList().take(5)).containsExactly(0, 3, 6, 9, 12).inOrder()
        assertThat(indices.last()).isEqualTo(177)
    }

    @Test
    fun `sourceIndices - offsets the window and clamps to the last frame`() {
        val indices = VideoFrameIndices.sourceIndices(
            sourceFrameCount = 180,
            videoDurationMs = 6_000,
            startMs = 3_000,
            durationMs = 1_000,
            framesPerSecond = 10,
        )

        assertThat(indices.toList().take(3)).containsExactly(90, 93, 96).inOrder()

        val clamped = VideoFrameIndices.sourceIndices(
            sourceFrameCount = 10,
            videoDurationMs = 1_000,
            startMs = 950,
            durationMs = 1_000,
            framesPerSecond = 10,
        )
        assertThat(clamped.last()).isEqualTo(9)
    }

    @Test
    fun `sourceIndices - is empty when the video frame rate is unknown`() {
        assertThat(VideoFrameIndices.sourceIndices(0, 6_000, 0, 6_000, 10)).isEmpty()
        assertThat(VideoFrameIndices.sourceIndices(180, 0, 0, 6_000, 10)).isEmpty()
        assertThat(VideoFrameIndices.sourceIndices(-1, 6_000, 0, 6_000, 10)).isEmpty()
    }
}
