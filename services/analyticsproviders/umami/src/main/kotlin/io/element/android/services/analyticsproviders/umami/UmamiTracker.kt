package io.element.android.services.analyticsproviders.umami

import im.vector.app.features.analytics.itf.VectorAnalyticsEvent
import im.vector.app.features.analytics.itf.VectorAnalyticsScreen
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

object UmamiTracker {
    private const val UMAMI_API_URL = "https://analytics.nexusmsp.fionaro.pw/api/send"
    private const val UMAMI_WEBSITE_ID = "d7cd6851-cebf-4fb7-ac44-1d187a750fd6"
    private const val HOSTNAME = "fionaro-chat-android"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "UmamiAnalytics").apply { isDaemon = true }
    }

    fun init() {}

    fun capture(event: VectorAnalyticsEvent) {
        val dataMap = mutableMapOf<String, Any>()
        event.getProperties()?.forEach { (key, value) ->
            if (value != null) dataMap[key] = value
        }
        sendUmami(name = event.getName(), data = dataMap)
    }

    fun screen(screen: VectorAnalyticsScreen) {
        sendUmami(url = "/screen/${screen.getName()}", title = screen.getName())
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

    private fun sendUmami(url: String = "", title: String = "", name: String = "event", data: Map<String, Any> = emptyMap()) {
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
                    if (data.isNotEmpty()) put("data", JSONObject(data))
                }
                val body = JSONObject().apply {
                    put("payload", payload)
                    put("type", "event")
                }
                val connection = URL(UMAMI_API_URL).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; FionaroChat)")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
                connection.responseCode
                connection.disconnect()
            } catch (e: Exception) {
                android.util.Log.e("Umami", "Failed to send", e)
            }
        }
    }
}
