/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.EncryptedAttachment
import io.element.android.libraries.matrix.api.media.MediaAttachmentEncryptor
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomInfo
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultSoundStickerSenderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val imageBytes = byteArrayOf(1, 2, 3, 4)
    private val audioBytes = byteArrayOf(5, 6, 7, 8)
    private val fakeEncryptor = FakeMediaAttachmentEncryptor()
    private val matrixClient = FakeMatrixClient()
    private val sentEvents = mutableListOf<Pair<String, String>>()
    private val joinedRoom = FakeJoinedRoom(
        sendRawMessageLikeEventResult = { eventType, contentJson ->
            sentEvents.add(eventType to contentJson)
            Result.success(Unit)
        },
    )

    @Test
    fun `send - plain room sends a spec compliant sticker with a plain audio url`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        matrixClient.uploadMediaResultLambda = { _, _ ->
            Result.success("mxc://matrix.org/${matrixClient.uploadMediaCalls.size}")
        }
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 480,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 3200,
            body = "A sticker",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(matrixClient.uploadMediaCalls.map { it.first }).containsExactly(MimeTypes.Png, MimeTypes.Ogg).inOrder()
        assertThat(matrixClient.uploadMediaCalls.map { it.second.toList() })
            .containsExactly(imageBytes.toList(), audioBytes.toList())
            .inOrder()
        assertThat(sentEvents.map { it.first }).containsExactly("m.sticker")
    }

    @Test
    fun `send - plain room builds the expected content`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        matrixClient.uploadMediaResultLambda = { mimeType, _ ->
            Result.success("mxc://matrix.org/${mimeType.replace('/', '_')}")
        }
        val sut = createSender()

        sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 480,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 3200,
            body = "A sticker",
        )

        val content = Json.parseToJsonElement(sendRawContent()).jsonObject
        assertThat(content.getValue("body").jsonPrimitive.content).isEqualTo("A sticker")
        assertThat(content.getValue("url").jsonPrimitive.content).isEqualTo("mxc://matrix.org/image_png")
        assertThat(content.getValue("info").jsonObject.getValue("mimetype").jsonPrimitive.content).isEqualTo(MimeTypes.Png)
        assertThat(content.getValue("info").jsonObject.getValue("size").jsonPrimitive.content).isEqualTo(imageBytes.size.toString())
        assertThat(content.getValue("info").jsonObject.getValue("w").jsonPrimitive.content).isEqualTo("512")
        assertThat(content.getValue("info").jsonObject.getValue("h").jsonPrimitive.content).isEqualTo("480")
        val audio = content.getValue("io.fionaro.sticker_audio").jsonObject
        assertThat(audio.getValue("url").jsonPrimitive.content).isEqualTo("mxc://matrix.org/audio_ogg")
        assertThat(audio.getValue("mimetype").jsonPrimitive.content).isEqualTo(MimeTypes.Ogg)
        assertThat(audio.getValue("size").jsonPrimitive.content).isEqualTo(audioBytes.size.toString())
        assertThat(audio.getValue("duration").jsonPrimitive.long).isEqualTo(3200)
    }

    @Test
    fun `send - encrypted room uploads ciphertext and embeds EncryptedFile objects`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = true))
        matrixClient.uploadMediaResultLambda = { mimeType, _ ->
            Result.success("mxc://matrix.org/${mimeType.replace('/', '_')}")
        }
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 512,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 3200,
            body = "A sticker",
        )

        assertThat(result.isSuccess).isTrue()
        // Encrypted payloads must be uploaded as opaque bytes.
        assertThat(matrixClient.uploadMediaCalls.map { it.first })
            .containsExactly(MimeTypes.OctetStream, MimeTypes.OctetStream)
        assertThat(matrixClient.uploadMediaCalls.map { it.second.toList() })
            .containsExactly(imageBytes.reversedArray().toList(), audioBytes.reversedArray().toList())
            .inOrder()

        val content = Json.parseToJsonElement(sendRawContent()).jsonObject
        assertThat(content).doesNotContainKey("url")
        assertThat(content).hasSize(4)
        val imageFile = content.getValue("file").jsonObject
        assertThat(imageFile.getValue("url").jsonPrimitive.content).isEqualTo("mxc://matrix.org/application_octet-stream")
        assertThat(imageFile.getValue("v").jsonPrimitive.content).isEqualTo("v2")
        // The info keeps the plaintext mime type, Element Web needs it to render the sticker.
        assertThat(content.getValue("info").jsonObject.getValue("mimetype").jsonPrimitive.content).isEqualTo(MimeTypes.Png)

        val audio = content.getValue("io.fionaro.sticker_audio").jsonObject
        assertThat(audio).doesNotContainKey("url")
        assertThat(audio.getValue("file").jsonObject.getValue("v").jsonPrimitive.content).isEqualTo("v2")
        assertThat(audio.getValue("mimetype").jsonPrimitive.content).isEqualTo(MimeTypes.Ogg)
    }

    @Test
    fun `send - without sound sends a sticker without the audio field`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        matrixClient.uploadMediaResultLambda = { _, _ -> Result.success("mxc://matrix.org/image") }
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 256,
            height = 256,
            body = "A sticker",
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(matrixClient.uploadMediaCalls).hasSize(1)
        val content = Json.parseToJsonElement(sendRawContent()).jsonObject
        assertThat(content).doesNotContainKey("io.fionaro.sticker_audio")
        assertThat(content.getValue("body").jsonPrimitive.content).isEqualTo("A sticker")
        assertThat(content.getValue("info").jsonObject.getValue("mimetype").jsonPrimitive.content).isEqualTo(MimeTypes.Png)
        assertThat(content.getValue("url").jsonPrimitive.content).isEqualTo("mxc://matrix.org/image")
    }

    @Test
    fun `send - without sound encrypts the only media of an encrypted room`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = true))
        matrixClient.uploadMediaResultLambda = { _, _ -> Result.success("mxc://matrix.org/image") }
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 512,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(matrixClient.uploadMediaCalls.map { it.first }).containsExactly(MimeTypes.OctetStream)
        val content = Json.parseToJsonElement(sendRawContent()).jsonObject
        assertThat(content).doesNotContainKey("url")
        assertThat(content.getValue("file").jsonObject.getValue("v").jsonPrimitive.content).isEqualTo("v2")
        assertThat(content).doesNotContainKey("io.fionaro.sticker_audio")
    }

    @Test
    fun `send - rejects an audio file without its mime type`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 512,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(matrixClient.uploadMediaCalls).isEmpty()
        assertThat(sentEvents).isEmpty()
    }

    @Test
    fun `send - rejects an audio longer than 10 seconds`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 512,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 10_001,
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(matrixClient.uploadMediaCalls).isEmpty()
        assertThat(sentEvents).isEmpty()
    }

    @Test
    fun `send - rejects an image larger than 512 px`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 513,
            height = 512,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 3200,
        )

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(matrixClient.uploadMediaCalls).isEmpty()
        assertThat(sentEvents).isEmpty()
    }

    @Test
    fun `send - encrypts when the room encryption state is unknown`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = null))
        matrixClient.uploadMediaResultLambda = { _, _ -> Result.success("mxc://matrix.org/media") }
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 512,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 3200,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(matrixClient.uploadMediaCalls.map { it.first })
            .containsExactly(MimeTypes.OctetStream, MimeTypes.OctetStream)
        val content = Json.parseToJsonElement(sendRawContent()).jsonObject
        assertThat(content).doesNotContainKey("url")
        assertThat(content.getValue("file").jsonObject.getValue("v").jsonPrimitive.content).isEqualTo("v2")
        assertThat(content.getValue("io.fionaro.sticker_audio").jsonObject)
            .containsKey("file")
    }

    @Test
    fun `send - propagates an upload failure`() = runTest {
        joinedRoom.givenRoomInfo(aRoomInfo(isEncrypted = false))
        matrixClient.givenUploadMediaResult(Result.failure(IllegalStateException("Upload failed")))
        val sut = createSender()

        val result = sut.send(
            room = joinedRoom,
            imageFile = temporaryFolder.writeFile("sticker.png", imageBytes),
            imageMimeType = MimeTypes.Png,
            width = 512,
            height = 512,
            audioFile = temporaryFolder.writeFile("sound.ogg", audioBytes),
            audioMimeType = MimeTypes.Ogg,
            durationMs = 3200,
        )

        assertThat(result.exceptionOrNull()).hasMessageThat().isEqualTo("Upload failed")
        assertThat(sentEvents).isEmpty()
    }

    private fun TestScope.createSender() = DefaultSoundStickerSender(
        matrixClient = matrixClient,
        mediaAttachmentEncryptor = fakeEncryptor,
        dispatchers = testCoroutineDispatchers(),
    )

    private fun sendRawContent(): String = sentEvents.single().second

    private fun TemporaryFolder.writeFile(name: String, bytes: ByteArray): File {
        return newFile(name).apply { writeBytes(bytes) }
    }
}

private class FakeMediaAttachmentEncryptor : MediaAttachmentEncryptor {
    override fun encrypt(data: ByteArray): EncryptedAttachment {
        return EncryptedAttachment(
            ciphertext = data.reversedArray(),
            encryptedFileJson = """
                {
                  "v": "v2",
                  "key": {
                    "kty": "oct",
                    "key_ops": ["encrypt", "decrypt"],
                    "alg": "A256CTR",
                    "k": "fake-key",
                    "ext": true
                  },
                  "iv": "fake-iv",
                  "hashes": {"sha256": "fake-hash"}
                }
            """.trimIndent(),
        )
    }
}
