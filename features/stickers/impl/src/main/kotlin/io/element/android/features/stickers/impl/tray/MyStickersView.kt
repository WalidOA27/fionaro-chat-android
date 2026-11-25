/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.tray

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.stickers.api.SoundSticker
import io.element.android.features.stickers.impl.R
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.ui.strings.CommonStrings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyStickersView(
    state: MyStickersState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_my_stickers_title),
                navigationIcon = {
                    BackButton(onClick = { state.eventSink(MyStickersEvent.NavBack) })
                },
            )
        },
    ) { paddingValues ->
        if (state.stickers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.screen_my_stickers_empty),
                    style = ElementTheme.typography.fontBodyLgRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = state.stickers, key = SoundSticker::id) { sticker ->
                    MyStickerCell(
                        sticker = sticker,
                        onClick = { state.eventSink(MyStickersEvent.Send(sticker.id)) },
                        onLongClick = { state.eventSink(MyStickersEvent.RequestDelete(sticker.id)) },
                    )
                }
            }
        }

        state.sendingStickerId?.let {
            ProgressDialog(text = stringResource(R.string.screen_sound_sticker_creator_sending))
        }
        state.stickerPendingDelete?.let {
            ConfirmationDialog(
                title = stringResource(R.string.screen_my_stickers_delete_title),
                content = stringResource(R.string.screen_my_stickers_delete_message),
                cancelText = stringResource(CommonStrings.action_cancel),
                submitText = stringResource(CommonStrings.action_delete),
                onSubmitClick = { state.eventSink(MyStickersEvent.ConfirmDelete) },
                onDismiss = { state.eventSink(MyStickersEvent.DismissDelete) },
            )
        }
        state.errorMessage?.let { errorMessage ->
            AlertDialog(
                content = stringResource(errorMessage),
                onDismiss = { state.eventSink(MyStickersEvent.DismissError) },
            )
        }
    }
}

@Composable
private fun MyStickerCell(
    sticker: SoundSticker,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(CommonStrings.action_delete),
            ),
    ) {
        AsyncImage(
            model = File(sticker.imagePath),
            contentDescription = sticker.body,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = CompoundIcons.VolumeOffSolid(),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@PreviewsDayNight
@Composable
internal fun MyStickersViewPreview(
    @PreviewParameter(MyStickersStateProvider::class) state: MyStickersState,
) = ElementPreview {
    MyStickersView(state = state)
}
