/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.store

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import io.element.android.features.stickers.api.SoundStickerStore
import io.element.android.libraries.preferences.test.FakePreferenceDataStoreFactory
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.robolectric.RuntimeEnvironment
import java.io.File

class DefaultSoundStickerStoreTest : RobolectricTest() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val preferenceDataStoreFactory = FakePreferenceDataStoreFactory()

    @Test
    fun `add - copies the media and indexes the sticker`() = runTest {
        val sut = createStore()

        val result = sut.add(
            imageFile = temporaryFolder.writeFile("image.png", "image".toByteArray()),
            imageMimeType = "image/png",
            width = 512,
            height = 480,
            audioFile = temporaryFolder.writeFile("audio.ogg", "audio".toByteArray()),
            audioMimeType = "audio/ogg",
            durationMs = 3200,
            body = "A sticker",
        )

        val sticker = result.getOrThrow()
        assertThat(File(sticker.imagePath).readBytes()).isEqualTo("image".toByteArray())
        assertThat(File(sticker.audioPath).readBytes()).isEqualTo("audio".toByteArray())
        assertThat(sticker.imageMimeType).isEqualTo("image/png")
        assertThat(sticker.audioMimeType).isEqualTo("audio/ogg")
        assertThat(sticker.durationMs).isEqualTo(3200)
        assertThat(sticker.body).isEqualTo("A sticker")
        assertThat(sticker.width).isEqualTo(512)
        assertThat(sticker.height).isEqualTo(480)
        assertThat(sticker.id).isNotEmpty()
        assertThat(sticker.createdAt).isGreaterThan(0)
        assertThat(sut.stickers.first()).containsExactly(sticker)
    }

    @Test
    fun `stickers - are sorted newest first`() = runTest {
        val sut = createStore()
        val first = sut.addSticker("first")
        val second = sut.addSticker("second")

        assertThat(sut.stickers.first().map { it.id }).containsExactly(second.id, first.id).inOrder()
    }

    @Test
    fun `delete - removes the sticker and its files`() = runTest {
        val sut = createStore()
        val sticker = sut.addSticker("to delete")

        val result = sut.delete(sticker.id)

        assertThat(result.isSuccess).isTrue()
        assertThat(sut.stickers.first()).isEmpty()
        assertThat(File(sticker.imagePath).exists()).isFalse()
        assertThat(File(sticker.audioPath!!).exists()).isFalse()
    }

    @Test
    fun `add - keeps at most the 100 newest stickers`() = runTest {
        val sut = createStore()
        val stickers = (0 until 101).map { index ->
            sut.addSticker("sticker $index")
        }

        val stored = sut.stickers.first()
        assertThat(stored).hasSize(SoundStickerStore.MAX_STICKERS)
        // The oldest one has been evicted, files included.
        assertThat(stored.map { it.id }).doesNotContain(stickers.first().id)
        assertThat(File(stickers.first().imagePath).exists()).isFalse()
    }

    @Test
    fun `add - saves a sticker without sound`() = runTest {
        val sut = createStore()

        val sticker = sut.add(
            imageFile = temporaryFolder.writeFile("silent.png", "image".toByteArray()),
            imageMimeType = "image/png",
            width = 512,
            height = 512,
            audioFile = null,
            audioMimeType = null,
            durationMs = null,
            body = "Silent",
        ).getOrThrow()

        assertThat(sticker.audioPath).isNull()
        assertThat(sticker.audioMimeType).isNull()
        assertThat(sticker.durationMs).isNull()
        assertThat(File(sticker.imagePath).exists()).isTrue()
        assertThat(File(sticker.imagePath).parentFile?.listFiles()?.toList()).hasSize(1)
        assertThat(sut.stickers.first()).containsExactly(sticker)
    }

    @Test
    fun `stickers - invalid JSON is ignored`() = runTest {
        val store = preferenceDataStoreFactory.create("fionaro_sound_stickers")
        store.edit { preferences ->
            preferences[stringPreferencesKey("sound_stickers")] = "not json"
        }
        val sut = createStore()

        assertThat(sut.stickers.first()).isEmpty()
    }

    private fun TestScope.createStore() = DefaultSoundStickerStore(
        context = context,
        preferenceDataStoreFactory = preferenceDataStoreFactory,
        dispatchers = testCoroutineDispatchers(),
    )

    private suspend fun DefaultSoundStickerStore.addSticker(body: String) = add(
        imageFile = temporaryFolder.writeFile("image_$body.png", "image".toByteArray()),
        imageMimeType = "image/png",
        width = 512,
        height = 512,
        audioFile = temporaryFolder.writeFile("audio_$body.ogg", "audio".toByteArray()),
        audioMimeType = "audio/ogg",
        durationMs = 1000,
        body = body,
    ).getOrThrow()

    private fun TemporaryFolder.writeFile(name: String, bytes: ByteArray): File {
        return newFile(name.replace(" ", "_")).apply { writeBytes(bytes) }
    }
}
