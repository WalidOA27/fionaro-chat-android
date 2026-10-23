/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

interface UserRepository {
    fun search(query: String): Flow<UserSearchResultState>
}

/**
 * Collect the states of the search for [query], dropping any state that belongs to another query.
 *
 * This guarantees that a slow response for a previous query can never overwrite the results of the current one,
 * even if the underlying flow is shared between searches. An unexpected failure is reported as a terminal empty
 * state so that consumers can stop their loading indicator, while cancellation is propagated.
 */
suspend fun UserRepository.collectSearchStates(
    query: String,
    onState: suspend (UserSearchResultState) -> Unit,
) {
    try {
        search(query).collect { state ->
            if (state.query == query) {
                onState(state)
            }
        }
    } catch (cancellationException: CancellationException) {
        throw cancellationException
    } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
        onState(UserSearchResultState(query = query, results = emptyList(), isSearching = false))
    }
}
