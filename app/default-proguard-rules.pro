# Don't obfuscate anything for non-enterprise builds
-dontobfuscate

# Keep Umami analytics (self-hosted)
-keep class io.element.android.services.analyticsproviders.umami.** { *; }
-keep class io.element.android.services.analyticsproviders.umami.UmamiTracker { *; }
