/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.stickers.api.SoundSticker
import io.element.android.features.stickers.api.SoundStickerSender
import io.element.android.features.stickers.api.SoundStickerStore
import io.element.android.features.stickers.impl.R
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarMessage
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

@AssistedInject
class MyStickersPresenter(
    private val room: JoinedRoom,
    private val soundStickerStore: SoundStickerStore,
    private val soundStickerSender: SoundStickerSender,
    private val snackbarDispatcher: SnackbarDispatcher,
    @Assisted private val navigateUp: () -> Unit,
) : Presenter<MyStickersState> {
    @AssistedFactory
    fun interface Factory {
        fun create(navigateUp: () -> Unit): MyStickersPresenter
    }

    @Composable
    override fun present(): MyStickersState {
        val localCoroutineScope = rememberCoroutineScope()
        val stickers by soundStickerStore.stickers.collectAsState(initial = emptyList())
        var sendingStickerId by remember { mutableStateOf<String?>(null) }
        var pendingDelete by remember { mutableStateOf<SoundSticker?>(null) }
        var errorMessage by remember { mutableStateOf<Int?>(null) }

        suspend fun sendSticker(sticker: SoundSticker) {
            sendingStickerId = sticker.id
            soundStickerSender.send(
                room = room,
                imageFile = File(sticker.imagePath),
                imageMimeType = sticker.imageMimeType,
                width = sticker.width,
                height = sticker.height,
                audioFile = sticker.audioPath?.let(::File),
                audioMimeType = sticker.audioMimeType,
                durationMs = sticker.durationMs,
                body = sticker.body,
            ).onSuccess {
                sendingStickerId = null
                snackbarDispatcher.post(SnackbarMessage(R.string.screen_my_stickers_sent))
                navigateUp()
            }.onFailure {
                Timber.w(it, "Unable to send the saved sound sticker")
                sendingStickerId = null
                errorMessage = R.string.screen_my_stickers_error_send
            }
        }

        fun handleEvent(event: MyStickersEvent) {
            when (event) {
                is MyStickersEvent.Send -> {
                    val sticker = stickers.firstOrNull { it.id == event.stickerId } ?: return
                    if (sendingStickerId == null) {
                        localCoroutineScope.launch { sendSticker(sticker) }
                    }
                }
                is MyStickersEvent.RequestDelete -> {
                    pendingDelete = stickers.firstOrNull { it.id == event.stickerId }
                }
                MyStickersEvent.ConfirmDelete -> {
                    val sticker = pendingDelete ?: return
                    pendingDelete = null
                    localCoroutineScope.launch {
                        soundStickerStore.delete(sticker.id).onFailure {
                            Timber.w(it, "Unable to delete the saved sticker")
                            errorMessage = R.string.screen_my_stickers_error_delete
                        }
                    }
                }
                MyStickersEvent.DismissDelete -> pendingDelete = null
                MyStickersEvent.DismissError -> errorMessage = null
                MyStickersEvent.NavBack -> navigateUp()
            }
        }

        return MyStickersState(
            stickers = stickers.toImmutableList(),
            sendingStickerId = sendingStickerId,
            stickerPendingDelete = pendingDelete,
            errorMessage = errorMessage,
            eventSink = ::handleEvent,
        )
    }
}
