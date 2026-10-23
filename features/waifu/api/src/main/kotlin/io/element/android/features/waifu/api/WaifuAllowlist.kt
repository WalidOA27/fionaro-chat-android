/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Dynamic allowlist of users that can see the Waifu gallery.
 * Populated from the remote config.json (chat.fionaro.pw), editable without a release.
 */
interface WaifuAllowlist {
    val users: StateFlow<Set<String>>

    suspend fun refresh()
}
