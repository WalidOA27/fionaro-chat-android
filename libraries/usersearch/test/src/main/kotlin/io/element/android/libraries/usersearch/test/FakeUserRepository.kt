/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.test

import io.element.android.libraries.usersearch.api.UserRepository
import io.element.android.libraries.usersearch.api.UserSearchResult
import io.element.android.libraries.usersearch.api.UserSearchResultState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow

class FakeUserRepository : UserRepository {
    var providedQuery: String? = null
        private set

    private var searchFailure: Throwable? = null
    private val flow = MutableSharedFlow<UserSearchResultState>()

    override fun search(query: String): Flow<UserSearchResultState> {
        providedQuery = query
        return searchFailure?.let { failure -> flow { throw failure } } ?: flow
    }

    suspend fun emitState(state: UserSearchResultState) {
        flow.emit(state)
    }

    suspend fun emitState(
        results: List<UserSearchResult>,
        isSearching: Boolean,
        query: String = providedQuery.orEmpty(),
    ) {
        emitState(UserSearchResultState(query = query, results = results, isSearching = isSearching))
    }

    fun givenSearchFailure(failure: Throwable) {
        searchFailure = failure
    }
}
