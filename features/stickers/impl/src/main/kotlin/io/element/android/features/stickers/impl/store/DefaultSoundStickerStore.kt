/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.stickers.api.SoundSticker
import io.element.android.features.stickers.api.SoundStickerStore
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID

@ContributesBinding(AppScope::class)
class DefaultSoundStickerStore(
    @ApplicationContext private val context: Context,
    preferenceDataStoreFactory: PreferenceDataStoreFactory,
    private val dispatchers: CoroutineDispatchers,
) : SoundStickerStore {
    private val store = preferenceDataStoreFactory.create(STORE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val stickersKey = stringPreferencesKey(STICKERS_KEY)

    override val stickers: Flow<List<SoundSticker>> = store.data.map { preferences ->
        decode(preferences[stickersKey]).map(StoredSoundSticker::toModel)
    }

    override suspend fun add(
        imageFile: File,
        imageMimeType: String,
        width: Int,
        height: Int,
        audioFile: File?,
        audioMimeType: String?,
        durationMs: Long?,
        body: String,
    ): Result<SoundSticker> = withContext(dispatchers.io) {
        runCatchingExceptions {
            val id = UUID.randomUUID().toString()
            val directory = File(stickersDirectory(), id).apply { mkdirs() }
            val imageTarget = File(directory, "image.${extensionOf(imageMimeType)}")
            imageFile.copyTo(target = imageTarget, overwrite = true)
            val audioTarget = if (audioFile != null && audioMimeType != null) {
                File(directory, "audio.${extensionOf(audioMimeType)}").also { target ->
                    audioFile.copyTo(target = target, overwrite = true)
                }
            } else {
                null
            }

            val sticker = SoundSticker(
                id = id,
                imagePath = imageTarget.absolutePath,
                imageMimeType = imageMimeType,
                width = width,
                height = height,
                audioPath = audioTarget?.absolutePath,
                audioMimeType = audioMimeType,
                durationMs = durationMs,
                body = body,
                createdAt = System.currentTimeMillis(),
            )

            val keptStickers = store.editAndGetStickers { current ->
                (listOf(sticker.toStored()) + current)
                    .sortedByDescending(StoredSoundSticker::createdAt)
                    .take(SoundStickerStore.MAX_STICKERS)
            }
            val latestStickers = decode(store.data.first()[stickersKey])
            deleteUnreferencedDirectories(latestStickers.mapTo(mutableSetOf(), StoredSoundSticker::id))
            check(keptStickers.any { it.id == id })
            sticker
        }
    }

    override suspend fun delete(stickerId: String): Result<Unit> = withContext(dispatchers.io) {
        runCatchingExceptions {
            store.editAndGetStickers { current ->
                current.filterNot { it.id == stickerId }
            }
            File(stickersDirectory(), stickerId).deleteRecursively()
            return@runCatchingExceptions
        }
    }

    private suspend fun DataStore<Preferences>.editAndGetStickers(
        transform: (List<StoredSoundSticker>) -> List<StoredSoundSticker>,
    ): List<StoredSoundSticker> {
        var updated: List<StoredSoundSticker> = emptyList()
        edit { preferences ->
            updated = transform(decode(preferences[stickersKey]))
            preferences[stickersKey] = json.encodeToString(updated)
        }
        return updated
    }

    private fun decode(value: String?): List<StoredSoundSticker> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<StoredSoundSticker>>(value)
        } catch (exception: Exception) {
            Timber.w(exception, "Unable to decode saved sound stickers")
            emptyList()
        }
    }

    private fun stickersDirectory(): File = File(context.filesDir, STICKERS_DIRECTORY)

    private fun deleteUnreferencedDirectories(referencedIds: Set<String>) {
        stickersDirectory()
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name !in referencedIds }
            .forEach { it.deleteRecursively() }
    }

    private fun extensionOf(mimeType: String): String {
        return when (mimeType.lowercase().substringBefore(';').trim()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            "audio/ogg" -> "ogg"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> mimeType.substringAfter('/', "bin").substringBefore(';').filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        }
    }

    private companion object {
        const val STORE_NAME = "fionaro_sound_stickers"
        const val STICKERS_KEY = "sound_stickers"
        const val STICKERS_DIRECTORY = "sound_stickers"
    }
}

@Serializable
private data class StoredSoundSticker(
    val id: String,
    val imagePath: String,
    val imageMimeType: String,
    val width: Int,
    val height: Int,
    val audioPath: String? = null,
    val audioMimeType: String? = null,
    val durationMs: Long? = null,
    val body: String,
    val createdAt: Long,
) {
    fun toModel() = SoundSticker(
        id = id,
        imagePath = imagePath,
        imageMimeType = imageMimeType,
        width = width,
        height = height,
        audioPath = audioPath,
        audioMimeType = audioMimeType,
        durationMs = durationMs,
        body = body,
        createdAt = createdAt,
    )
}

private fun SoundSticker.toStored() = StoredSoundSticker(
    id = id,
    imagePath = imagePath,
    imageMimeType = imageMimeType,
    width = width,
    height = height,
    audioPath = audioPath,
    audioMimeType = audioMimeType,
    durationMs = durationMs,
    body = body,
    createdAt = createdAt,
)
