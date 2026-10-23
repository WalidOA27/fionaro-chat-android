/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.widget.CallWidgetSettingsProvider
import io.element.android.libraries.preferences.api.store.AppPreferencesStore
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.flow.firstOrNull

private const val EMBEDDED_CALL_WIDGET_BASE_URL = "https://call.fionaro.pw"

@ContributesBinding(AppScope::class)
class DefaultCallWidgetProvider(
    private val matrixClientsProvider: MatrixClientProvider,
    private val appPreferencesStore: AppPreferencesStore,
    private val callWidgetSettingsProvider: CallWidgetSettingsProvider,
    private val activeRoomsHolder: ActiveRoomsHolder,
) : CallWidgetProvider {
    override suspend fun getWidget(
        sessionId: SessionId,
        roomId: RoomId,
        isAudioCall: Boolean,
        clientId: String,
        languageTag: String?,
        theme: String?,
    ): Result<CallWidgetProvider.GetWidgetResult> = runCatchingExceptions {
        val matrixClient = matrixClientsProvider.getOrRestore(sessionId).getOrThrow()
        val originalRoom = activeRoomsHolder.getActiveRoomMatching(sessionId, roomId)
            ?: matrixClient.getJoinedRoom(roomId)
            ?: error("Room not found")

        val isDm = originalRoom.isDm()

        // The call always runs in the original room. The widget driver created below is bound to
        // this room, so call.member and the other widget state events are written to the room all
        // participants already share. No room is ever created for a call.
        val callRoom = originalRoom

        val customBaseUrl = appPreferencesStore.getCustomElementCallBaseUrlFlow().firstOrNull()
        val baseUrl = customBaseUrl ?: EMBEDDED_CALL_WIDGET_BASE_URL

        val callRoomInfo = callRoom.info()
        val widgetSettings = callWidgetSettingsProvider.provide(
            baseUrl = baseUrl,
            encrypted = false,
            direct = isDm,
            isAudioCall = isAudioCall,
            hasActiveCall = callRoomInfo.hasRoomCall,
        )
        val callUrl = callRoom.generateWidgetWebViewUrl(
            widgetSettings = widgetSettings,
            clientId = clientId,
            languageTag = languageTag,
            theme = theme,
        ).getOrThrow()

        // /room/ bypasses React Router's "/" (home page) route and reaches the call view, which
        // never calls createRoom. skipLobby starts the call immediately.
        val finalUrl = CallWidgetUrlBuilder.build(callUrl)

        val driver = callRoom.getWidgetDriver(widgetSettings).getOrThrow()

        CallWidgetProvider.GetWidgetResult(
            driver = driver,
            url = finalUrl,
            // Element Call may still POST /_matrix/client/v3/createRoom (for example if it ever
            // renders its home page). The interceptor must answer with the room the call is
            // already using; it must never create a new room, which is what used to leave one
            // orphan room behind per call.
            preCreatedRoomId = originalRoom.roomId.value,
        )
    }
}
