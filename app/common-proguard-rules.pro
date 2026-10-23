# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Increase optimizations passes to 3
-optimizationpasses 3

# JNA
-dontwarn java.awt.*
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# TagSoup, coming from the RTE library
-keep class org.ccil.cowan.tagsoup.** { *; }

# kotlinx.serialization

# Kotlin serialization looks up the generated serializer classes through a function on companion
# objects. The companions are looked up reflectively so we need to explicitly keep these functions.
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
# If a companion has the serializer function, keep the companion field on the original type so that
# the reflective lookup succeeds.
-if class **.*$Companion {
  kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class <1>.<2> {
  <1>.<2>$Companion Companion;
}

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
# Taken from https://raw.githubusercontent.com/square/okhttp/master/okhttp/src/jvmMain/resources/META-INF/proguard/okhttp3.pro
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Needed for Posthog
-keepclassmembers class android.view.JavaViewSpy {
    static int windowAttachCount(android.view.View);
}

# Keep LogSessionId class and related classes (https://github.com/androidx/media/issues/2535)
-keep class android.media.metrics.LogSessionId { *; }
-keep class android.media.metrics.** { *; }

# Keep Media3 classes that use reflection (https://github.com/androidx/media/issues/2535)
-keep class androidx.media3.** { *; }
-dontwarn android.media.metrics.**

# New rules after AGP 8.13.1 upgrade
-dontwarn androidx.window.extensions.WindowExtensions
-dontwarn androidx.window.extensions.WindowExtensionsProvider
-dontwarn androidx.window.extensions.area.ExtensionWindowAreaPresentation
-dontwarn androidx.window.extensions.layout.DisplayFeature
-dontwarn androidx.window.extensions.layout.FoldingFeature
-dontwarn androidx.window.extensions.layout.WindowLayoutComponent
-dontwarn androidx.window.extensions.layout.WindowLayoutInfo
-dontwarn androidx.window.sidecar.SidecarDeviceState
-dontwarn androidx.window.sidecar.SidecarDisplayFeature
-dontwarn androidx.window.sidecar.SidecarInterface$SidecarCallback
-dontwarn androidx.window.sidecar.SidecarInterface
-dontwarn androidx.window.sidecar.SidecarProvider
-dontwarn androidx.window.sidecar.SidecarWindowLayoutInfo

# Also needed after AGP 8.13.1 upgrade, it seems like proguard is now more aggressive on removing unused code
-keep,allowoptimization,allowshrinking class org.matrix.rustcomponents.sdk.** { *;}
-keep,allowoptimization,allowshrinking class uniffi.** { *;}
-keep,allowoptimization,allowshrinking class io.element.android.x.di.** { *; }

# Compose is R8-safe: the Compose compiler emits static calls and the Compose
# runtime ships its own consumer rules, so no project-wide Compose keep rule is
# needed here (a broad -keep for androidx.compose.** would only bloat the APK).

# Keep Metro classes
-keep,allowoptimization,allowshrinking class dev.zacsweers.metro.** { *; }

# Rustls Platform Verifier
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }

# WebView JavaScript bridges
# JavaScript inside the Element Call widget (and the account creation web view)
# invokes these methods by name through objects registered with
# WebView.addJavascriptInterface(). JavaScript cannot see renamed members, so
# the classes and their JavascriptInterface methods must survive R8. FOSS
# builds additionally apply -dontobfuscate, but enterprise builds do not, so
# keep the rules here where both build types pick them up.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Call widget: androidNativeBridge.setAudioDevice()/onTrackReady()
-keep,allowoptimization class io.element.android.features.call.impl.utils.AndroidWebViewAudioBridge { *; }

# Call widget: backHandler.onBackPressed()
-keep,allowoptimization class io.element.android.features.call.impl.ui.JavascriptBackHandlerBridge { *; }

# Call widget: postMessage() message channel (anonymous JavascriptInterface)
-keepclassmembers class io.element.android.features.call.impl.utils.WebViewWidgetMessageInterceptor$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Login (account creation): postMessage() message channel fallback
-keepclassmembers class io.element.android.features.login.impl.screens.createaccount.WebViewMessageInterceptor$* {
    @android.webkit.JavascriptInterface <methods>;
}
