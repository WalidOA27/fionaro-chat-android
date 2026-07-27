/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.services.analyticsproviders.umami

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import im.vector.app.features.analytics.itf.VectorAnalyticsEvent
import im.vector.app.features.analytics.itf.VectorAnalyticsScreen
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Always-on analytics tracker that sends events to our self-hosted Umami instance.
 * Unlike PostHog/Sentry, this bypasses the user consent flow because
 * Umami is our own infrastructure with no third-party data sharing.
 */
@SingleIn(AppScope::class)
class UmamiAnalyticsProvider() {

    private companion object {
        const val UMAMI_API_URL = "https://analytics.nexusmsp.fionaro.pw/api/send"
        const val UMAMI_WEBSITE_ID = "d7cd6851-cebf-4fb7-ac44-1d187a750fd6"
        const val HOSTNAME = "fionaro-chat-android"
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UmamiAnalytics").apply { isDaemon = true }
    }

    fun capture(event: VectorAnalyticsEvent) {
        val dataMap = mutableMapOf<String, Any>()
        event.getProperties()?.forEach { (key, value) ->
            if (value != null) dataMap[key] = value
        }
        sendUmami(name = event.getName(), data = dataMap)
    }

    fun screen(screen: VectorAnalyticsScreen) {
        sendUmami(
            url = "/screen/${screen.getName()}",
            title = screen.getName()
        )
    }

    fun trackError(throwable: Throwable) {
        sendUmami(
            name = "error",
            data = mapOf(
                "message" to (throwable.message ?: "unknown"),
                "type" to (throwable::class.simpleName ?: "Throwable")
            )
        )
    }

    private fun sendUmami(
        url: String = "",
        title: String = "",
        name: String = "event",
        data: Map<String, Any> = emptyMap(),
    ) {
        executor.submit {
            try {
                val payload = JSONObject().apply {
                    put("hostname", HOSTNAME)
                    put("language", Locale.getDefault().toString())
                    put("screen", "N/A")
                    put("url", url)
                    put("title", title)
                    put("website", UMAMI_WEBSITE_ID)
                    put("name", name)
                    if (data.isNotEmpty()) {
                        put("data", JSONObject(data))
                    }
                }
                val body = JSONObject().apply {
                    put("payload", payload)
                    put("type", "event")
                }

                val connection = URL(UMAMI_API_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "FionaroChat-Android/1.0")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(body.toString())
                }
                connection.responseCode
                connection.disconnect()
            } catch (e: Exception) {
                Timber.tag("Umami").w(e, "Failed to send analytics event")
            }
        }
    }
}
