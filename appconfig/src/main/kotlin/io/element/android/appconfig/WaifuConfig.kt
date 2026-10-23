/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.appconfig

object WaifuConfig {
    /**
     * Base URL of the waifu.im proxy (Caddy on the VPS injects the API key).
     */
    const val API_BASE_URL = "https://waifu-api.fionaro.pw"

    /**
     * URL of the webapp config.json that holds the dynamic allowlist (fionaroWaifu.users).
     */
    const val ALLOWLIST_CONFIG_URL = "https://chat.fionaro.pw/config.json"
}
