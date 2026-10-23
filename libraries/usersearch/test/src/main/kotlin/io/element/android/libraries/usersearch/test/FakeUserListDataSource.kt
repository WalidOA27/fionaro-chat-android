/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.test

import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.usersearch.api.UserListDataSource

class FakeUserListDataSource : UserListDataSource {
    private var searchResult: List<MatrixUser> = emptyList()
    private var profile: MatrixUser? = null
    private var searchFailure: Throwable? = null
    private var profileFailure: Throwable? = null

    var lastSearchQuery: String? = null
        private set
    var lastProfileUserId: UserId? = null
        private set
    var searchCallCount: Int = 0
        private set

    override suspend fun search(query: String, count: Long): List<MatrixUser> {
        lastSearchQuery = query
        searchCallCount++
        searchFailure?.let { throw it }
        return searchResult.take(count.toInt())
    }

    override suspend fun getProfile(userId: UserId): MatrixUser? {
        lastProfileUserId = userId
        profileFailure?.let { throw it }
        return profile
    }

    fun givenSearchResult(users: List<MatrixUser>) {
        this.searchResult = users
    }

    fun givenUserProfile(matrixUser: MatrixUser?) {
        this.profile = matrixUser
    }

    fun givenSearchFailure(failure: Throwable) {
        searchFailure = failure
    }

    fun givenProfileFailure(failure: Throwable) {
        profileFailure = failure
    }
}
