/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.creation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.stickers.api.StickerImage
import io.element.android.features.stickers.impl.R
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.AlertDialog
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.permissions.api.PermissionsView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundStickerCreatorView(
    state: SoundStickerCreatorState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.screen_sound_sticker_creator_title),
                navigationIcon = {
                    BackButton(onClick = { state.eventSink(SoundStickerCreatorEvent.NavBack) })
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StickerImagePicker(image = state.image, onClick = { state.eventSink(SoundStickerCreatorEvent.PickImage) })
            StickerSoundSection(state = state)
            TextField(
                value = state.body,
                onValueChange = { state.eventSink(SoundStickerCreatorEvent.SetBody(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.screen_sound_sticker_creator_body_label),
                placeholder = stringResource(R.string.screen_sound_sticker_creator_body_placeholder),
                singleLine = true,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { state.eventSink(SoundStickerCreatorEvent.SetSaveToMyStickers(!state.saveToMyStickers)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.saveToMyStickers,
                    onCheckedChange = { state.eventSink(SoundStickerCreatorEvent.SetSaveToMyStickers(it)) },
                )
                Text(
                    text = stringResource(R.string.screen_sound_sticker_creator_save_to_my_stickers),
                    style = ElementTheme.typography.fontBodyMdRegular,
                )
            }
            Button(
                text = stringResource(R.string.screen_sound_sticker_creator_send),
                onClick = { state.eventSink(SoundStickerCreatorEvent.Send) },
                enabled = state.canSend,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.isSending) {
            ProgressDialog(text = stringResource(R.string.screen_sound_sticker_creator_sending))
        }
        state.errorMessage?.let { errorMessage ->
            AlertDialog(
                content = stringResource(errorMessage),
                onDismiss = { state.eventSink(SoundStickerCreatorEvent.DismissError) },
            )
        }
        PermissionsView(state = state.permissionsState)
    }
}

@Composable
private fun StickerImagePicker(
    image: StickerImage?,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.screen_sound_sticker_creator_image_description)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .clickable(onClickLabel = stringResource(R.string.screen_sound_sticker_creator_change_image), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = CompoundIcons.Image(), contentDescription = null, tint = ElementTheme.colors.iconSecondary)
                Text(
                    text = stringResource(R.string.screen_sound_sticker_creator_choose_image),
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
        } else {
            AsyncImage(
                model = File(image.file.absolutePath),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun StickerSoundSection(state: SoundStickerCreatorState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.screen_sound_sticker_creator_sound_section),
            style = ElementTheme.typography.fontHeadingSmMedium,
        )
        when {
            state.isRecording -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.screen_sound_sticker_creator_recording_elapsed,
                            state.recordingElapsed.inWholeSeconds.toInt(),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.screen_sound_sticker_creator_stop),
                        onClick = { state.eventSink(SoundStickerCreatorEvent.StopRecording) },
                    )
                }
            }
            state.sound != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.screen_sound_sticker_creator_sound_ready,
                            (state.sound.durationMs / 1000).toInt(),
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(
                            if (state.isPlayingSound) {
                                R.string.screen_sound_sticker_creator_stop_preview
                            } else {
                                R.string.screen_sound_sticker_creator_play_preview
                            }
                        ),
                        onClick = { state.eventSink(SoundStickerCreatorEvent.ToggleSoundPreview) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = stringResource(R.string.screen_sound_sticker_creator_record),
                        onClick = { state.eventSink(SoundStickerCreatorEvent.StartRecording) },
                    )
                    TextButton(
                        text = stringResource(R.string.screen_sound_sticker_creator_pick_audio),
                        onClick = { state.eventSink(SoundStickerCreatorEvent.PickAudio) },
                    )
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        text = stringResource(R.string.screen_sound_sticker_creator_record),
                        onClick = { state.eventSink(SoundStickerCreatorEvent.StartRecording) },
                    )
                    TextButton(
                        text = stringResource(R.string.screen_sound_sticker_creator_pick_audio),
                        onClick = { state.eventSink(SoundStickerCreatorEvent.PickAudio) },
                    )
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun SoundStickerCreatorViewPreview(
    @PreviewParameter(SoundStickerCreatorStateProvider::class) state: SoundStickerCreatorState,
) = ElementPreview {
    SoundStickerCreatorView(state = state)
}
