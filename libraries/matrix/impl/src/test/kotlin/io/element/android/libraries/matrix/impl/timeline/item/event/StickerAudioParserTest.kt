/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.timeline.item.event

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.item.event.StickerAudio
import org.junit.Test

class StickerAudioParserTest {
    @Test
    fun `parseStickerAudio - valid plain audio`() {
        val result = parseStickerAudio(
            """
            {
              "type": "m.sticker",
              "content": {
                "body": "Sticker",
                "url": "mxc://matrix.org/image",
                "info": {"mimetype": "image/png", "w": 512, "h": 512},
                "io.fionaro.sticker_audio": {
                  "url": "mxc://matrix.org/audio",
                  "mimetype": "audio/ogg",
                  "size": 4567,
                  "duration": 3200
                }
              }
            }
            """.trimIndent()
        )

        assertThat(result).isEqualTo(
            StickerAudio(
                mediaSource = MediaSource(url = "mxc://matrix.org/audio", json = null),
                mimetype = "audio/ogg",
                size = 4567,
                durationMs = 3200,
            )
        )
    }

    @Test
    fun `parseStickerAudio - valid encrypted audio`() {
        val encryptedFile = """
            {
              "url": "mxc://matrix.org/encrypted_audio",
              "key": {"kty": "oct", "key_ops": ["encrypt", "decrypt"], "alg": "A256CTR", "k": "abc", "ext": true},
              "iv": "abcdefghijklmnop",
              "hashes": {"sha256": "hash"},
              "v": "v2"
            }
        """.trimIndent()
        val result = parseStickerAudio(
            """
            {
              "type": "m.sticker",
              "content": {
                "body": "Sticker",
                "url": "mxc://matrix.org/image",
                "io.fionaro.sticker_audio": {
                  "file": $encryptedFile,
                  "mimetype": "audio/ogg",
                  "size": 4567,
                  "duration": 3200
                }
              }
            }
            """.trimIndent()
        )

        assertThat(result?.mediaSource?.safeUrl).isEqualTo("mxc://matrix.org/encrypted_audio")
        assertThat(result?.mediaSource?.json).isNotNull()
        assertThat(result?.mimetype).isEqualTo("audio/ogg")
        assertThat(result?.durationMs).isEqualTo(3200)
    }

    @Test
    fun `parseStickerAudio - missing field returns null`() {
        val result = parseStickerAudio(
            """
            {
              "type": "m.sticker",
              "content": {
                "body": "Sticker",
                "url": "mxc://matrix.org/image",
                "info": {"mimetype": "image/png", "w": 512, "h": 512}
              }
            }
            """.trimIndent()
        )

        assertThat(result).isNull()
    }

    @Test
    fun `parseStickerAudio - malformed field returns null`() {
        val malformedValues = listOf(
            """"not an object"""",
            "42",
            """{"mimetype": "audio/ogg"}""",
            """{"url": "mxc://matrix.org/audio", "duration": 3200}""",
            """{"url": "mxc://matrix.org/audio", "mimetype": "audio/ogg"}""",
            """{"url": "mxc://matrix.org/audio", "mimetype": "audio/ogg", "duration": "not a number"}""",
        )

        malformedValues.forEach { value ->
            val result = parseStickerAudio(
                """
                {
                  "type": "m.sticker",
                  "content": {
                    "body": "Sticker",
                    "io.fionaro.sticker_audio": $value
                  }
                }
                """.trimIndent()
            )
            assertThat(result).isNull()
        }
    }

    @Test
    fun `parseStickerAudio - malformed JSON returns null`() {
        assertThat(parseStickerAudio("not json")).isNull()
        assertThat(parseStickerAudio("")).isNull()
        assertThat(parseStickerAudio(null)).isNull()
    }

    @Test
    fun `parseStickerAudio - audio longer than 10 seconds is still accepted`() {
        val result = parseStickerAudio(
            """
            {
              "type": "m.sticker",
              "content": {
                "body": "Sticker",
                "io.fionaro.sticker_audio": {
                  "url": "mxc://matrix.org/audio",
                  "mimetype": "audio/ogg",
                  "size": 4567,
                  "duration": 12000
                }
              }
            }
            """.trimIndent()
        )

        assertThat(result?.durationMs).isEqualTo(12_000)
    }
}
