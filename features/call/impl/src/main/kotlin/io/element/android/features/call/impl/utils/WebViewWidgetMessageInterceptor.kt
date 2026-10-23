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
import androidx.core.net.toUri
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.element.android.features.call.impl.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

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
                Timber.d("onPageStarted fired, webView hash=%d", System.identityHashCode(view))

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
                if (urlStr.contains("config.json")) {
                    Timber.d("Intercepting a config.json request")
                }
                if (urlStr.contains("/_matrix/") && request.method == "OPTIONS") {
                    Timber.d("CORS preflight intercepted")
                    val corsHeaders = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, HEAD, POST, PUT, DELETE, OPTIONS",
                        "Access-Control-Allow-Headers" to "X-Requested-With, Content-Type, Authorization, Date"
                    )
                    return WebResourceResponse("text/plain", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(ByteArray(0)))
                }
                if (urlStr.contains("/_matrix/client/v3/createRoom")) {
                    val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")
                    val sharedRoomId = preCreatedRoomId
                    return if (sharedRoomId != null) {
                        // Never create a room here. The call already runs in the room where the
                        // user started it, and the widget driver below is bound to that room.
                        // The previous fallback called client.createRoom(), which left one orphan
                        // room behind every time Element Call issued this request.
                        Timber.d("createRoom intercepted, returning the shared call room")
                        val json = "{\"room_id\":\"$sharedRoomId\"}"
                        WebResourceResponse("application/json", "UTF-8", 200, "OK", corsHeaders, ByteArrayInputStream(json.toByteArray()))
                    } else {
                        // The interceptor is configured before the URL is loaded, so this should
                        // not happen. Refuse instead of creating an unrelated room.
                        Timber.w("createRoom intercepted before the call room was known, refusing")
                        val errorJson = "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"Call rooms are not created by the widget\"}"
                        WebResourceResponse("application/json", "UTF-8", 403, "Forbidden", corsHeaders, ByteArrayInputStream(errorJson.toByteArray()))
                    }
                }
                if (!mainDocumentIntercepted && request.isForMainFrame && request.method == "GET" &&
                    urlStr.matches(Regex("^https?://call\\.fionaro\\.pw(/|\$|\\?).*"))) {
                    mainDocumentIntercepted = true
                    Timber.d("Main document intercepted, injecting permission primer")
                    try {
                        val conn = URL(urlStr).openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.instanceFollowRedirects = true
                        val bytes = conn.inputStream.readBytes()
                        val contentType = conn.contentType ?: "text/html; charset=UTF-8"
                        val charset = contentType.substringAfter("charset=", "UTF-8").split(";")[0].trim()
                        val html = String(bytes, Charset.forName(charset))
                        val primingScript = "<script>(async function(){try{" +
                            "const s=await navigator.mediaDevices.getUserMedia({audio:true,video:true});" +
                            "s.getTracks().forEach(function(t){t.stop()});" +
                            "}catch(e){}})();</script>"
                        val modifiedHtml = if ("</head>" in html) {
                            html.replace("</head>", "$primingScript</head>")
                        } else if ("<body" in html) {
                            html.replace("<body", "$primingScript<body")
                        } else {
                            "$primingScript$html"
                        }
                        return WebResourceResponse(
                            "text/html",
                            charset,
                            200,
                            "OK",
                            emptyMap(),
                            ByteArrayInputStream(modifiedHtml.toByteArray(Charset.forName(charset)))
                        )
                    } catch (e: Exception) {
                        Timber.e("Main document intercept failed (%s)", e.javaClass.simpleName)
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
        Timber.v("Sending a widget message to the WebView (%d chars)", message.length)
        webView.evaluateJavascript("postMessage($message, '*')", null)
    }

    private fun onMessageReceived(json: String?) {
        // Here is where we would handle the messages from the WebView, passing them to the Rust SDK
        json?.let { interceptedMessages.tryEmit(it) }
    }
}
