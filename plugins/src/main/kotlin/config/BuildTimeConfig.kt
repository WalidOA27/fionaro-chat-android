/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package config

object BuildTimeConfig {
    const val APPLICATION_ID = "pw.fionaro.chat"
    const val APPLICATION_NAME = "Fionaro Chat"
    const val GOOGLE_APP_ID_RELEASE = ""
    const val GOOGLE_APP_ID_DEBUG = ""
    const val GOOGLE_APP_ID_NIGHTLY = ""

    val METADATA_HOST_REVERSED: String? = "pw.fionaro.chat"
    val URL_WEBSITE: String? = "https://fionaro.pw"
    val URL_LOGO: String? = "https://fionaro.pw/logo.png"
    val URL_COPYRIGHT: String? = "https://fionaro.pw/copyright"
    val URL_ACCEPTABLE_USE: String? = "https://fionaro.pw/terms"
    val URL_PRIVACY: String? = "https://fionaro.pw/privacy"
    val URL_POLICY: String? = "https://fionaro.pw/cookies"
    val SERVICES_MAPTILER_BASE_URL: String? = null
    val SERVICES_MAPTILER_APIKEY: String? = null
    val SERVICES_MAPTILER_LIGHT_MAPID: String? = null
    val SERVICES_MAPTILER_DARK_MAPID: String? = null
    val SERVICES_POSTHOG_HOST: String? = null
    val SERVICES_POSTHOG_APIKEY: String? = null
    val SERVICES_SENTRY_DSN: String? = null
    val SERVICES_SENTRY_DSN_RUST: String? = null
    val BUG_REPORT_URL: String? = null
    val BUG_REPORT_APP_NAME: String? = null

    const val PUSH_CONFIG_INCLUDE_FIREBASE = false
    const val PUSH_CONFIG_INCLUDE_UNIFIED_PUSH = true
}
