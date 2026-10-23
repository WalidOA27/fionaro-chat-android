/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.usersearch.impl

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_NAME
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.ui.components.aMatrixUserList
import io.element.android.libraries.usersearch.api.UserSearchResult
import io.element.android.libraries.usersearch.test.FakeUserListDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val SESSION_ID = SessionId("@current-user:example.com")

internal class MatrixUserRepositoryTest {
    @Test
    fun `search - emits nothing if the search query is too short`() = runTest {
        val dataSource = FakeUserListDataSource()
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("x")

        result.test {
            awaitComplete()
        }
    }

    @Test
    fun `search - emits nothing if the search query is blank`() = runTest {
        val dataSource = FakeUserListDataSource()
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("   ")

        result.test {
            awaitComplete()
        }
        assertThat(dataSource.searchCallCount).isEqualTo(0)
    }

    @Test
    fun `search - trims the query before searching`() = runTest {
        val userProfile = MatrixUser(userId = A_USER_ID, displayName = A_USER_NAME)
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(emptyList())
        dataSource.givenUserProfile(userProfile)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)
        val query = "  ${A_USER_ID.value}  "

        val result = repository.search(query)

        result.test {
            skipItems(1)
            val state = awaitItem()
            assertThat(state.query).isEqualTo(query)
            assertThat(state.results).isEqualTo(listOf(UserSearchResult(userProfile)))
            awaitComplete()
        }
        assertThat(dataSource.lastSearchQuery).isEqualTo(A_USER_ID.value)
        assertThat(dataSource.lastProfileUserId).isEqualTo(A_USER_ID)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `search - debounces directory requests`() = runTest {
        val dataSource = FakeUserListDataSource()
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val job = launch { repository.search("some query").collect() }
        runCurrent()
        assertThat(dataSource.searchCallCount).isEqualTo(0)

        advanceTimeBy(249)
        runCurrent()
        assertThat(dataSource.searchCallCount).isEqualTo(0)

        advanceTimeBy(2)
        runCurrent()
        assertThat(dataSource.searchCallCount).isEqualTo(1)

        job.cancel()
    }

    @Test
    fun `search - returns the profile result when the directory is empty for an mxid`() = runTest {
        val userProfile = MatrixUser(userId = A_USER_ID, displayName = A_USER_NAME)
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(emptyList())
        dataSource.givenUserProfile(userProfile)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            assertThat(awaitItem().results).isEqualTo(listOf(placeholderResult()))
            assertThat(awaitItem().results).isEqualTo(listOf(UserSearchResult(userProfile)))
            awaitComplete()
        }
    }

    @Test
    fun `search - emits an unresolved profile result when the directory is empty for an mxid`() = runTest {
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(emptyList())
        dataSource.givenUserProfile(null)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(listOf(placeholderResult(isUnresolved = true)))
            awaitComplete()
        }
    }

    @Test
    fun `search - emits a terminal state if the directory request fails`() = runTest {
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchFailure(RuntimeException("Directory unavailable"))
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("some query")

        result.test {
            assertThat(awaitItem().isSearching).isTrue()
            val terminalState = awaitItem()
            assertThat(terminalState.isSearching).isFalse()
            assertThat(terminalState.results).isEmpty()
            awaitComplete()
        }
    }

    @Test
    fun `search - falls back to the profile if the directory request fails for an mxid`() = runTest {
        val userProfile = MatrixUser(userId = A_USER_ID, displayName = A_USER_NAME)
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchFailure(RuntimeException("Directory unavailable"))
        dataSource.givenUserProfile(userProfile)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(listOf(UserSearchResult(userProfile)))
            awaitComplete()
        }
    }

    @Test
    fun `search - uses the unresolved result if the profile request fails`() = runTest {
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(emptyList())
        dataSource.givenProfileFailure(RuntimeException("Profile unavailable"))
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(listOf(placeholderResult(isUnresolved = true)))
            awaitComplete()
        }
    }

    @Test
    fun `search - removes duplicate results`() = runTest {
        val user = aMatrixUserList().first()
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(listOf(user, user))
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("some query")

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(listOf(UserSearchResult(user)))
            awaitComplete()
        }
    }

    @Test
    fun `search - returns empty list if no results are found`() = runTest {
        val dataSource = FakeUserListDataSource()
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("some query")

        result.test {
            awaitItem().also {
                assertThat(it.isSearching).isTrue()
                assertThat(it.results).isEmpty()
            }
            awaitItem().also {
                assertThat(it.isSearching).isFalse()
                assertThat(it.results).isEmpty()
            }
            awaitComplete()
        }
    }

    @Test
    fun `search - returns users if results are found`() = runTest {
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(aMatrixUserList())
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("some query")

        result.test {
            awaitItem().also {
                assertThat(it.isSearching).isTrue()
                assertThat(it.results).isEmpty()
            }
            awaitItem().also {
                assertThat(it.isSearching).isFalse()
                assertThat(it.results).isEqualTo(aMatrixUserList().toUserSearchResults())
            }
            awaitComplete()
        }
    }

    @Test
    fun `search - immediately returns placeholder if search is mxid`() = runTest {
        val dataSource = FakeUserListDataSource()
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            awaitItem().also {
                assertThat(it.isSearching).isTrue()
                assertThat(it.results).isEqualTo(listOf(placeholderResult()))
            }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `search - doesn't return placeholder if search is the local user's mxid`() = runTest {
        val dataSource = FakeUserListDataSource()
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(SESSION_ID.value)

        result.test {
            awaitItem().also {
                assertThat(it.isSearching).isTrue()
                assertThat(it.results).isEmpty()
            }
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `search - filters out results with the local user's mxid`() = runTest {
        val searchResults = aMatrixUserList() + MatrixUser(userId = SESSION_ID, displayName = A_USER_NAME)
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(searchResults)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search("some text")

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(aMatrixUserList().toUserSearchResults())
            awaitComplete()
        }
    }

    @Test
    fun `search - does not change results if they contain searched mxid`() = runTest {
        val searchResults = aMatrixUserListWithoutUserId(A_USER_ID) + MatrixUser(userId = A_USER_ID, displayName = A_USER_NAME)
        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(searchResults)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(searchResults.toUserSearchResults())
            awaitComplete()
        }
    }

    @Test
    fun `search - gets profile results if searched mxid not in results`() = runTest {
        val userProfile = MatrixUser(userId = A_USER_ID, displayName = A_USER_NAME)
        val searchResults = aMatrixUserListWithoutUserId(A_USER_ID)

        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(searchResults)
        dataSource.givenUserProfile(userProfile)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo((listOf(userProfile) + searchResults).toUserSearchResults())
            awaitComplete()
        }
    }

    @Test
    fun `search - doesn't add profile results if searched mxid is local user and not in results`() = runTest {
        val userProfile = MatrixUser(userId = A_USER_ID, displayName = A_USER_NAME)
        val searchResults = aMatrixUserListWithoutUserId(SESSION_ID)

        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(searchResults)
        dataSource.givenUserProfile(userProfile)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(SESSION_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(searchResults.toUserSearchResults())
            awaitComplete()
        }
    }

    @Test
    fun `search - returns unresolved user if profile can't be loaded`() = runTest {
        val searchResults = aMatrixUserListWithoutUserId(A_USER_ID)

        val dataSource = FakeUserListDataSource()
        dataSource.givenSearchResult(searchResults)
        dataSource.givenUserProfile(null)
        val repository = MatrixUserRepository(FakeMatrixClient(SESSION_ID), dataSource)

        val result = repository.search(A_USER_ID.value)

        result.test {
            skipItems(1)
            assertThat(awaitItem().results).isEqualTo(listOf(placeholderResult(isUnresolved = true)) + searchResults.toUserSearchResults())
            awaitComplete()
        }
    }

    private fun aMatrixUserListWithoutUserId(userId: UserId) = aMatrixUserList().filterNot { it.userId == userId }

    private fun List<MatrixUser>.toUserSearchResults() = map { UserSearchResult(it) }

    private fun placeholderResult(id: UserId = A_USER_ID, isUnresolved: Boolean = false) = UserSearchResult(MatrixUser(id), isUnresolved = isUnresolved)
}
