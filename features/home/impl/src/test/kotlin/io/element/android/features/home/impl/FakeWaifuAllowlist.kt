/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl

import io.element.android.features.waifu.api.WaifuAllowlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FakeWaifuAllowlist(
    initialUsers: Set<String> = emptySet(),
) : WaifuAllowlist {
    private val mutableUsers = MutableStateFlow(initialUsers)

    override val users: StateFlow<Set<String>> = mutableUsers

    override suspend fun refresh() = Unit
}
