/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.appconfig.WaifuConfig
import io.element.android.features.waifu.api.WaifuAllowlist
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.preferences.api.store.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_ALLOWLIST_BODY_LENGTH = 64 * 1024
private const val MAX_USER_ID_LENGTH = 255

private val waifuUsersKey = stringPreferencesKey("waifuUsers")
private val allowlistJson = Json { ignoreUnknownKeys = true }

@ContributesBinding(AppScope::class)
class DefaultWaifuAllowlist @Inject constructor(
    private val dispatchers: CoroutineDispatchers,
    preferenceDataStoreFactory: PreferenceDataStoreFactory,
) : WaifuAllowlist {
    private val store = preferenceDataStoreFactory.create("fionaro_waifu")

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    // Cache local persistida: el arranque usa esto (0 red). La lista remota solo se aplica si es valida.
    private val cachedUsers = store.data.map { prefs ->
        prefs[waifuUsersKey]?.split("\n")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    }

    // null = todavia no hay respuesta remota; se usa la cache. Un set (aunque este vacio) es la verdad remota.
    private val remoteUsers = MutableStateFlow<Set<String>?>(null)

    override val users: StateFlow<Set<String>> = combine(cachedUsers, remoteUsers) { cached, remote ->
        remote ?: cached
    }.stateIn(
        scope = CoroutineScope(dispatchers.io + SupervisorJob()),
        started = SharingStarted.Eagerly,
        initialValue = emptySet(),
    )

    override suspend fun refresh() {
        withContext(dispatchers.io) {
            val body = try {
                fetchAllowlistBody() ?: return@withContext
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                // Sin red o timeout: mantenemos la cache local, la pestana no cambia.
                return@withContext
            }
            // JSON malformado o seccion ausente => lista vacia (fail closed: la pestana queda oculta).
            val parsedUsers = parseAllowlist(body).orEmpty()
            try {
                store.edit { prefs ->
                    if (parsedUsers.isEmpty()) {
                        prefs.remove(waifuUsersKey)
                    } else {
                        prefs[waifuUsersKey] = parsedUsers.joinToString("\n")
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                // La cache no se pudo persistir, pero la respuesta remota sigue siendo valida en memoria.
            }
            remoteUsers.value = parsedUsers
        }
    }

    private fun fetchAllowlistBody(): String? {
        val request = Request.Builder().url(WaifuConfig.ALLOWLIST_CONFIG_URL).get().build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            if (body.contentLength() > MAX_ALLOWLIST_BODY_LENGTH) return ""
            return body.string().takeIf { it.length <= MAX_ALLOWLIST_BODY_LENGTH } ?: ""
        }
    }

    @Serializable
    internal data class WebConfig(
        @SerialName("fionaroWaifu") val fionaroWaifu: WaifuConfigDto? = null,
    )

    @Serializable
    internal data class WaifuConfigDto(
        val users: List<String> = emptyList(),
    )
}

/**
 * Parse the remote allowlist defensively.
 * Returns null when the payload is not valid JSON or the `fionaroWaifu` section is missing.
 */
internal fun parseAllowlist(body: String): Set<String>? {
    if (body.length > MAX_ALLOWLIST_BODY_LENGTH) return null
    return runCatching {
        allowlistJson
            .decodeFromString<DefaultWaifuAllowlist.WebConfig>(body)
            .fionaroWaifu
            ?.users
            ?.asSequence()
            ?.map { it.trim() }
            ?.filter { it.isValidMatrixUserId() }
            ?.toSet()
    }.getOrNull()
}

private fun String.isValidMatrixUserId(): Boolean {
    return length in 3..MAX_USER_ID_LENGTH &&
        startsWith("@") &&
        contains(":") &&
        none { it.isWhitespace() }
}
