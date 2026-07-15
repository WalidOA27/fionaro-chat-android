/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.call.impl.utils

import android.util.Log
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClientProvider
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.createroom.CreateRoomParameters
import io.element.android.libraries.matrix.api.createroom.RoomPreset
import io.element.android.libraries.matrix.api.roomdirectory.RoomVisibility
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

        val callRoom = if (isDm) {
            originalRoom
        } else {
            val params = CreateRoomParameters(
                name = null,
                isEncrypted = false,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.PRIVATE_CHAT,
            )
            val newRoomId = matrixClient.createRoom(params).getOrThrow()
            Log.e("FionaroCall", "Pre-created call room: ${newRoomId.value}")

            matrixClient.getJoinedRoom(newRoomId)
                ?: error("Pre-created room ${newRoomId.value} not found after creation")
        }

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
        ).getOrThrow().replace("appassets.androidplatform.net", "call.fionaro.pw")

        val finalUrl = if (!isDm) {
            callUrl.replace("call.fionaro.pw/#?", "call.fionaro.pw/room/#?") + "&skipLobby=true"
        } else {
            callUrl
        }

        val driver = callRoom.getWidgetDriver(widgetSettings).getOrThrow()

        CallWidgetProvider.GetWidgetResult(
            driver = driver,
            url = finalUrl,
            preCreatedRoomId = if (isDm) null else callRoom.roomId.value,
        )
    }
}
