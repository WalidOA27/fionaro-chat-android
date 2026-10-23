/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import io.element.android.appconfig.WaifuConfig
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

const val WAIFU_PAGE_SIZE = 12
const val MAX_WAIFU_SESSION_IMAGES = 30

@Serializable
data class WaifuTag(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
    val imageCount: Long = 0,
)

@Serializable
data class WaifuImageTag(
    val id: Long = 0,
    val name: String = "",
    val slug: String = "",
)

@Serializable
data class WaifuImage(
    val id: Long = 0,
    val extension: String = "",
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val byteSize: Long = 0,
    val isNsfw: Boolean = false,
    val isAnimated: Boolean = false,
    val tags: List<WaifuImageTag> = emptyList(),
)

@Serializable
data class WaifuImagesResponse(
    val items: List<WaifuImage> = emptyList(),
    val pageNumber: Int = 1,
    val totalPages: Int = 0,
    val hasNextPage: Boolean = false,
)

@Serializable
private data class WaifuTagsResponse(
    val items: List<WaifuTag> = emptyList(),
)

class WaifuApiException(val code: Int) : Exception("Waifu API error $code")

class WaifuApi @Inject constructor(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val baseUrl: String = WaifuConfig.API_BASE_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getTags(): List<WaifuTag> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/tags".toHttpUrl().newBuilder()
            .addQueryParameter("pageSize", "100")
            .build()
        val request = Request.Builder().url(url).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WaifuApiException(response.code)
            val body = response.body?.string() ?: throw WaifuApiException(-1)
            json.decodeFromString<WaifuTagsResponse>(body).items
                .sortedByDescending { it.imageCount }
        }
    }

    suspend fun getImages(
        tags: List<String>,
        isNsfw: Boolean,
        pageNumber: Int,
        pageSize: Int = WAIFU_PAGE_SIZE,
        random: Boolean = false,
    ): WaifuImagesResponse = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/images".toHttpUrl().newBuilder()
            .apply {
                tags.forEach { addQueryParameter("includedTags", it) }
                addQueryParameter("isNsfw", isNsfw.toString())
                addQueryParameter("pageSize", pageSize.toString())
                addQueryParameter("pageNumber", pageNumber.toString())
                if (random) addQueryParameter("order", "random")
                addQueryParameter("full", "true")
            }
        val request = Request.Builder().url(urlBuilder.build()).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WaifuApiException(response.code)
            val body = response.body?.string() ?: throw WaifuApiException(-1)
            json.decodeFromString<WaifuImagesResponse>(body)
        }
    }
}

internal fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
