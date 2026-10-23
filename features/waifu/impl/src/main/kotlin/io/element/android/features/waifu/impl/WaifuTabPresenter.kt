/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.system.toast
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

@Inject
class WaifuTabPresenter(
    private val waifuApi: WaifuApi,
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
) : Presenter<WaifuState> {
    private val okHttpClient = defaultOkHttpClient()

    @Composable
    override fun present(): WaifuState {
        val coroutineScope = rememberCoroutineScope()
        var tags by remember { mutableStateOf<List<WaifuTag>>(emptyList()) }
        var selectedTags by remember { mutableStateOf<List<String>>(emptyList()) }
        // NSFW is always opt-in: it must never be enabled by default or by remote config.
        var isNsfw by remember { mutableStateOf(false) }
        var images by remember { mutableStateOf<List<WaifuImage>>(emptyList()) }
        var pageNumber by remember { mutableIntStateOf(1) }
        var hasNextPage by remember { mutableStateOf(false) }
        var isLoading by remember { mutableStateOf(true) }
        var isLoadingMore by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<WaifuError?>(null) }
        var lightboxIndex by remember { mutableStateOf<Int?>(null) }
        var loadJob by remember { mutableStateOf<Job?>(null) }

        fun loadPage(page: Int, append: Boolean, random: Boolean) {
            // Una sola peticion simultanea; se cancela la anterior si cambia el filtro/Actualizar.
            loadJob?.cancel()
            if (append) {
                isLoadingMore = true
            } else {
                isLoading = true
            }
            error = null
            loadJob = coroutineScope.launch {
                try {
                    val response = waifuApi.getImages(
                        tags = selectedTags,
                        isNsfw = isNsfw,
                        pageNumber = page,
                        random = random,
                    )
                    // Mezcla de filtros: el feed acumula; el limite de sesion corta el scroll.
                    images = if (append) images + response.items else response.items
                    if (!append) lightboxIndex = null
                    pageNumber = response.pageNumber
                    hasNextPage = response.hasNextPage
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (exception: Exception) {
                    error = if (exception is WaifuApiException && exception.code == 403) {
                        WaifuError.NsfwForbidden
                    } else {
                        WaifuError.Generic
                    }
                } finally {
                    isLoading = false
                    isLoadingMore = false
                }
            }
        }

        fun saveToDownloads(image: WaifuImage) {
            coroutineScope.launch {
                val saved = withContext(dispatchers.io) {
                    try {
                        val request = Request.Builder().url(image.url).get().build()
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@withContext false
                            val contentType = response.body.contentType()?.toString()
                            val bytes = response.body.bytes()
                            writeToMediaStore(image = image, bytes = bytes, contentType = contentType)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (exception: Exception) {
                        false
                    }
                }
                if (saved) {
                    context.toast(R.string.screen_waifu_download_success)
                } else {
                    context.toast(R.string.screen_waifu_download_failure)
                }
            }
        }

        LaunchedEffect(Unit) {
            try {
                tags = waifuApi.getTags()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                // Tags are optional: the gallery can still load without the filter chips.
            }
            // Sesion inicial: aleatoria, pagina 1.
            loadPage(1, append = false, random = true)
        }

        fun handleEvent(event: WaifuEvent) {
            when (event) {
                WaifuEvent.Retry -> loadPage(1, append = false, random = true)
                WaifuEvent.LoadMore -> loadPage(pageNumber + 1, append = true, random = false)
                WaifuEvent.ClearTags -> {
                    selectedTags = emptyList()
                    loadPage(1, append = false, random = true)
                }
                WaifuEvent.CloseLightbox -> lightboxIndex = null
                WaifuEvent.PrevImage -> {
                    lightboxIndex?.let { idx -> if (idx > 0) lightboxIndex = idx - 1 }
                }
                WaifuEvent.NextImage -> {
                    lightboxIndex?.let { idx -> if (idx < images.lastIndex) lightboxIndex = idx + 1 }
                }
                is WaifuEvent.OpenImage -> lightboxIndex = event.index
                is WaifuEvent.ToggleNsfw -> isNsfw = event.enabled
                is WaifuEvent.ToggleTag -> {
                    // Los toggles solo cambian el filtro activo; el scroll carga con el filtro actual.
                    selectedTags = if (event.slug in selectedTags) {
                        selectedTags - event.slug
                    } else {
                        selectedTags + event.slug
                    }
                }
                is WaifuEvent.Download -> saveToDownloads(event.image)
            }
        }

        return WaifuState(
            tags = tags,
            selectedTags = selectedTags,
            isNsfw = isNsfw,
            images = images,
            isLoading = isLoading,
            isLoadingMore = isLoadingMore,
            hasNextPage = hasNextPage,
            error = error,
            lightboxIndex = lightboxIndex,
            eventSink = ::handleEvent,
        )
    }

    private fun writeToMediaStore(image: WaifuImage, bytes: ByteArray, contentType: String?): Boolean {
        val mediaType = resolveWaifuMediaType(contentType = contentType, apiExtension = image.extension)
        val displayName = image.displayNameFor(contentType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mediaType.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return false
            return runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                } ?: return false
                true
            }.getOrDefault(false)
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return runCatching {
                File(dir, displayName).outputStream().use { output ->
                    output.write(bytes)
                }
                true
            }.getOrDefault(false)
        }
    }
}
