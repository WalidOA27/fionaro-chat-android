/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

import config.AnalyticsConfig
import config.BuildTimeConfig
import config.PushProvidersConfig

object ModulesConfig {
    val pushProvidersConfig = PushProvidersConfig(
        includeFirebase = BuildTimeConfig.PUSH_CONFIG_INCLUDE_FIREBASE,
        includeUnifiedPush = BuildTimeConfig.PUSH_CONFIG_INCLUDE_UNIFIED_PUSH,
    )

    val analyticsConfig: AnalyticsConfig = if (isEnterpriseBuild) {
        // Is Posthog configuration available?
        val withPosthog = BuildTimeConfig.SERVICES_POSTHOG_APIKEY.isNullOrEmpty().not() &&
            BuildTimeConfig.SERVICES_POSTHOG_HOST.isNullOrEmpty().not()
        // Is Sentry configuration available?
        val withSentry = BuildTimeConfig.SERVICES_SENTRY_DSN.isNullOrEmpty().not()
        if (withPosthog || withSentry) {
            println("Analytics enabled with Posthog: $withPosthog, Sentry: $withSentry")
            AnalyticsConfig.Enabled(
                withPosthog = withPosthog,
                withSentry = withSentry,
            )
        } else {
            println("Analytics disabled")
            AnalyticsConfig.Disabled
        }
    } else if (BuildTimeConfig.APPLICATION_ID.startsWith("io.element.android.x")) {
        println("Analytics enabled (PostHog+Sentry placeholders, Umami active)")
        AnalyticsConfig.Enabled(
            withPosthog = true,
            withSentry = true,
        )
    } else if (BuildTimeConfig.APPLICATION_ID == "pw.fionaro.chat") {
        // Self-hosted build: only opt-in Umami analytics. PostHog and Sentry are not included at all.
        println("Analytics enabled (self-hosted Umami only, opt-in)")
        AnalyticsConfig.UmamiOnly
    } else {
        println("Analytics disabled (non-Element build)")
        AnalyticsConfig.Disabled
    }
}
