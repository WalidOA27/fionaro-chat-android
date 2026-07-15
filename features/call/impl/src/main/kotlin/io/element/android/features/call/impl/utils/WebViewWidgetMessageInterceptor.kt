/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.element.android.features.call.impl.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber

class WebViewWidgetMessageInterceptor(
    private val webView: WebView,
    private val onUrlLoaded: (String) -> Unit,
    private val onError: (String?) -> Unit,
) : WidgetMessageInterceptor {
    companion object {
        // We call both the WebMessageListener and the JavascriptInterface objects in JS with this
        // 'listenerName' so they can both receive the data from the WebView when
        // `${LISTENER_NAME}.postMessage(...)` is called
        const val LISTENER_NAME = "elementX"
    }

    internal var matrixClientProvider: MatrixClientProvider? = null
    internal var callSessionId: String = ""
    internal var preCreatedRoomId: String? = null
    private var mainDocumentIntercepted = false

    // It's important to have extra capacity here to make sure we don't drop any messages
    override val interceptedMessages = MutableSharedFlow<String>(extraBufferCapacity = 10)

    init {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(webView.context))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                android.util.Log.e("FionaroCall", "onPageStarted fired, webView hash=" + System.identityHashCode(view) + ", url=$url")

                // Due to https://github.com/element-hq/element-x-android/issues/4097
                // we need to supply a logging implementation that correctly includes
                // objects in log lines.
                view.evaluateJavascript(
                    """
                        function logFn(consoleLogFn, ...args) {
                            consoleLogFn(
                                args.map(
                                    a => typeof a === "string" ? a : JSON.stringify(a)
                                ).join(' ')
                            );
                        };
                        globalThis.console.debug = logFn.bind(null, console.debug);
                        globalThis.console.log = logFn.bind(null, console.log);
                        globalThis.console.info = logFn.bind(null, console.info);
                        globalThis.console.warn = logFn.bind(null, console.warn);
                        globalThis.console.error = logFn.bind(null, console.error);
                    """.trimIndent(),
                    null
                )

                // We inject this JS code when the page starts loading to attach a message listener to the window.
                // This listener will receive both messages:
                // - EC widget API -> Element X (message.data.api == "fromWidget")
                // - Element X -> EC widget API (message.data.api == "toWidget"), we should ignore these
                view.evaluateJavascript(
                    """
                        window.addEventListener('message', function(event) {
                            let message = {data: event.data, origin: event.origin}
                            if (message.data.response && message.data.api == "toWidget"
                                || !message.data.response && message.data.api == "fromWidget") {
                                let json = JSON.stringify(event.data) 
                                ${"console.log('message sent: ' + json);".takeIf { BuildConfig.DEBUG }}
                                $LISTENER_NAME.postMessage(json);
                            } else {
                                ${"console.log('message received (ignored): ' + JSON.stringify(event.data));".takeIf { BuildConfig.DEBUG }}
                            }
                        });
                    """.trimIndent(),
                    null
                )

                // Instrumented permission priming + enumerateDevices diagnostics
                // Logs real call timing to determine if origin permission persists between navigations
                view.evaluateJavascript(
                    """
                        (function() {
                            // Proxy enumerateDevices to log timing and results
                            var origEnumerate = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
                            var callCount = 0;
                            navigator.mediaDevices.enumerateDevices = function() {
                                callCount++;
                                var callId = callCount;
                                console.time('enumerate-' + callId);
                                return origEnumerate().then(function(devices) {
                                    console.timeEnd('enumerate-' + callId);
                                    console.log('[FionaroDiag] enumerateDevices #' + callId + ': ' + devices.length + ' devices, kinds: ' + JSON.stringify(devices.map(function(d) { return 'kind=' + d.kind + ' label=' + d.label; })));
                                    return devices;
                                });
                            };
                        })();
                        console.time('getUserMedia-prime');
                        navigator.mediaDevices.getUserMedia({audio:true,video:true})
                            .then(function(s) {
                                console.timeEnd('getUserMedia-prime');
                                console.log('[FionaroDiag] getUserMedia SUCCESS');
                                s.getTracks().forEach(function(t){t.stop()});
                            })
                            .catch(function(e){
                                console.timeEnd('getUserMedia-prime');
                                console.log('[FionaroDiag] getUserMedia FAILED: ' + e.message);
                            });
                    """.trimIndent(),
                    null
                )

                // Wiretap postMessage to log all update_state messages (synthetic and real)
                // This lets us distinguish if EC receives a real update_state after the synthetic one
                // by checking room_id, event_ids, and event types.
                view.evaluateJavascript(
                    """
                    (function() {
                        var origPost = window.postMessage.bind(window);
                        window.postMessage = function(msg, targetOrigin, transfer) {
                            if (msg && typeof msg === 'object' && msg.api === 'toWidget' && msg.action === 'update_state') {
                                try {
                                    var firstRoomId = (msg.data && msg.data.state && msg.data.state[0]) ? msg.data.state[0].room_id : 'unknown';
                                    var eventTypes = (msg.data && msg.data.state) ? msg.data.state.map(function(s) { return s.type; }).join(',') : '';
                                    var eventIds = (msg.data && msg.data.state) ? msg.data.state.map(function(s) { return s.event_id; }).join(',') : '';
                                    console.log('[FionaroDiag] update_state intercepted via postMessage patch: room_id=' + firstRoomId + ' types=[' + eventTypes + '] event_ids=[' + eventIds + ']');
                                } catch(e) {
                                    console.log('[FionaroDiag] update_state intercepted (log error: ' + e.message + ')');
                                }
                            }
                            return origPost(msg, targetOrigin, transfer);
                        };
                    })();
                    """.trimIndent(),
                    null
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                onUrlLoaded(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // No network for instance, transmit the error
                Timber.e("onReceivedError error: ${error?.errorCode} ${error?.description}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(error?.description.toString())
                }

                super.onReceivedError(view, request, error)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                Timber.e("onReceivedHttpError error: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == request?.url.toString()) {
                    onError(errorResponse?.statusCode.toString())
                }

                super.onReceivedHttpError(view, request, errorResponse)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Timber.e("onReceivedSslError error: ${error?.primaryError}")

                // Only propagate the error if it happens while loading the current page
                if (view?.url == error?.url.toString()) {
                    onError(error?.toString())
                }

                super.onReceivedSslError(view, handler, error)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
            val urlStr = request.url.toString()
            if (urlStr.contains("config.json")) android.util.Log.e("FionaroCallJS", "Intercept: " + request.url)
            if (urlStr.contains("/_matrix/") && request.method == "OPTIONS") {
                android.util.Log.e("FionaroCall", "CORS preflight intercepted: " + urlStr)
                val corsHeaders = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, HEAD, POST, PUT, DELETE, OPTIONS",
                    "Access-Control-Allow-Headers" to "X-Requested-With, Content-Type, Authorization, Date"
                )
                return WebResourceResponse("text/plain", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(ByteArray(0)))
            }
            if (urlStr.contains("/_matrix/client/v3/createRoom") && matrixClientProvider != null && callSessionId.isNotEmpty()) {
                android.util.Log.e("FionaroCall", "createRoom intercepted, webView.url=${webView.url}")
                if (preCreatedRoomId != null) {
                    val json = "{\"room_id\":\"" + preCreatedRoomId + "\"}"
                    android.util.Log.e("FionaroCall", "createRoom intercepted, returning pre-created room: " + json)
                    val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                    return WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(json.toByteArray()))
                }
                android.util.Log.e("FionaroCall", "createRoom intercepted, proxying with session token")
                try {
                    return runBlocking(Dispatchers.IO) {
                        withTimeout(5000L) {
                            val client = matrixClientProvider!!.getOrRestore(SessionId(callSessionId)).getOrNull()
                            if (client != null) {
                                val params = CreateRoomParameters(
                                    name = null,
                                    isEncrypted = false,
                                    visibility = RoomVisibility.Private,
                                    preset = RoomPreset.PRIVATE_CHAT,
                                )
                                val result = client.createRoom(params)
                                result.fold(
                                    onSuccess = { roomId ->
                                        val json = "{\"room_id\":\"" + roomId.value + "\"}"
                                        android.util.Log.e("FionaroCall", "createRoom SUCCESS: " + json)
                                        injectUpdateState(roomId.value)
                                        val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                                        WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(json.toByteArray()))
                                    },
                                    onFailure = { error ->
                                        android.util.Log.e("FionaroCall", "createRoom FAILED: " + error.message)
                                        val errorJson = "{\"errcode\":\"M_UNKNOWN\",\"error\":\"" + (error.message ?: "unknown") + "\"}"
                                        WebResourceResponse("application/json", "UTF-8", 500, "OK", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(errorJson.toByteArray()))
                                    }
                                )
                            } else {
                                android.util.Log.e("FionaroCall", "createRoom: no MatrixClient")
                                null
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FionaroCall", "createRoom exception: " + e.message)
                    return null
                }
            }
            if (!mainDocumentIntercepted && request.isForMainFrame && request.method == "GET" &&
                urlStr.matches(Regex("^https?://call\\.fionaro\\.pw(/|\$|\\?).*"))) {
                mainDocumentIntercepted = true
                android.util.Log.e("FionaroCall", "Main document intercepted, injecting permission primer")
                try {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.instanceFollowRedirects = true
                    val bytes = conn.inputStream.readBytes()
                    val contentType = conn.contentType ?: "text/html; charset=UTF-8"
                    val charset = contentType.substringAfter("charset=", "UTF-8").split(";")[0].trim()
                    val html = String(bytes, Charset.forName(charset))
                    val primingScript = "<script>(async function(){try{const s=await navigator.mediaDevices.getUserMedia({audio:true,video:true});s.getTracks().forEach(function(t){t.stop()});}catch(e){}})();</script>"
                    val modifiedHtml = if ("</head>" in html) {
                        html.replace("</head>", "$primingScript</head>")
                    } else if ("<body" in html) {
                        html.replace("<body", "$primingScript<body")
                    } else {
                        "$primingScript$html"
                    }
                    return WebResourceResponse("text/html", charset, 200, "OK", emptyMap(), ByteArrayInputStream(modifiedHtml.toByteArray(Charset.forName(charset))))
                } catch (e: Exception) {
                    android.util.Log.e("FionaroCall", "Main document intercept failed: " + e.message)
                }
            }
                return assetLoader.shouldInterceptRequest(request.url)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView?, url: String): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(url.toUri())
            }
        }

        // Always register JavascriptInterface as the baseline message channel.
        // This works on all WebView implementations including Huawei.
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun postMessage(json: String?) {
                onMessageReceived(json)
            }
        }, LISTENER_NAME)

        // Additionally register WebMessageListener on WebViews that reliably support it.
        // Huawei WebView (Chromium < 119) reports WEB_MESSAGE_LISTENER as supported
        // but silently drops messages, so we only trust it on Chromium 119+.
        // See: https://github.com/element-hq/element-x-android/issues/6632
        val webViewVersionName = WebViewCompat.getCurrentWebViewPackage(webView.context)?.versionName.orEmpty()
        Timber.d("Using WebView version: $webViewVersionName")
        val webViewVersionCode = webViewVersionName.split(".").firstOrNull()?.toIntOrNull() ?: 0

        if (webViewVersionCode >= 119 &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                LISTENER_NAME,
                setOf("*"),
                WebViewCompat.WebMessageListener { _, message, _, _, _ ->
                    onMessageReceived(message.data)
                }
            )
        }
    }

    override fun sendMessage(message: String) {
        if (message.contains("\"update_state\"")) {
            android.util.Log.e("FionaroCall", "sendMessage (native->JS): " + message.take(1000))
        }
        webView.evaluateJavascript("postMessage($message, '*')", null)
    }

    private fun onMessageReceived(json: String?) {
        // Here is where we would handle the messages from the WebView, passing them to the Rust SDK
        json?.let { interceptedMessages.tryEmit(it) }
    }

    private fun injectUpdateState(roomId: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val webUrl = webView.url ?: return@post
                val fragment = webUrl.substringAfter("#", "").trimStart('?')
                val params = fragment.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
                    else parts[0] to ""
                }

                // DIAG: log fragment and parsed params to diagnose URL parsing
                android.util.Log.e("FionaroCall", "injectUpdateState: webView.url=$webUrl")
                android.util.Log.e("FionaroCall", "injectUpdateState: fragment=$fragment")
                android.util.Log.e("FionaroCall", "injectUpdateState: params=$params")
                android.util.Log.e("FionaroCall", "injectUpdateState: params[userId]=${params["userId"]} params[widgetId]=${params["widgetId"]}")

                val widgetId = params["widgetId"] ?: run {
                    android.util.Log.e("FionaroCall", "injectUpdateState: MISSING widgetId — returning early")
                    return@post
                }
                val userId = params["userId"] ?: run {
                    android.util.Log.e("FionaroCall", "injectUpdateState: MISSING userId — returning early")
                    return@post
                }
                val displayName = params["displayName"] ?: userId.substringBefore(":")

                val ts = System.currentTimeMillis()
                val tsPlus1 = ts + 1
                val dollar = "$"
                val eventIdCreate = dollar + "fionaro" + ts.toString(36) + "0"
                val eventIdMember = dollar + "fionaro" + (tsPlus1).toString(36) + "1"

                val js = buildString {
                    append("window.postMessage({")
                    append("api:\"toWidget\",widgetId:\"").append(widgetId).append("\",")
                    append("requestId:\"fionaro-synthetic-update-state\",action:\"update_state\",")
                    append("data:{state:[{")
                    append("type:\"m.room.create\",state_key:\"\",room_id:\"").append(roomId).append("\",")
                    append("content:{creator:\"").append(userId).append("\",room_version:\"10\"},")
                    append("event_id:\"").append(eventIdCreate).append("\",origin_server_ts:").append(ts).append(",")
                    append("sender:\"").append(userId).append("\"")
                    append("},{")
                    append("type:\"m.room.member\",state_key:\"").append(userId).append("\",")
                    append("room_id:\"").append(roomId).append("\",")
                    append("content:{displayname:\"").append(displayName).append("\",membership:\"join\"},")
                    append("event_id:\"").append(eventIdMember).append("\",origin_server_ts:").append(tsPlus1).append(",")
                    append("sender:\"").append(userId).append("\",user_id:\"").append(userId).append("\"")
                    append("}]},response:{}},'*')")
                    append(";console.log('[FionaroDiag] Synthetic update_state injected for room ").append(roomId).append("');")
                }

                try {
                    webView.evaluateJavascript(js, null)
                    android.util.Log.e("FionaroCall", "injectUpdateState: evaluateJavascript succeeded for room: $roomId")
                } catch (e: Exception) {
                    android.util.Log.e("FionaroCall", "injectUpdateState: evaluateJavascript failed: " + e.message)
                }
            } catch (e: Exception) {
                android.util.Log.e("FionaroCall", "injectUpdateState exception: " + e.message)
            }
        }
    }
}
