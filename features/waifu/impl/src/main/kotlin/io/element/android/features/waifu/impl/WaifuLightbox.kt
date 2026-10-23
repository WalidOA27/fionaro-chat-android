/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun WaifuLightbox(
    state: WaifuState,
    modifier: Modifier = Modifier,
) {
    val index = state.lightboxIndex ?: return
    val image = state.images.getOrNull(index) ?: return
    val lastIndex = state.images.lastIndex

    Dialog(
        onDismissRequest = { state.eventSink(WaifuEvent.CloseLightbox) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false),
    ) {
        BackHandler(onBack = { state.eventSink(WaifuEvent.CloseLightbox) })

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = image.url,
                contentDescription = stringResource(R.string.screen_waifu_a11y_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 64.dp, horizontal = 48.dp),
            )

            CompositionLocalProvider(LocalContentColor provides Color.White) {
                // Contador
                Text(
                    text = stringResource(R.string.screen_waifu_image_counter, index + 1, state.images.size),
                    style = ElementTheme.typography.fontBodySmMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )

                // Cerrar
                IconButton(
                    onClick = { state.eventSink(WaifuEvent.CloseLightbox) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = CompoundIcons.Close(),
                        contentDescription = stringResource(CommonStrings.action_close),
                    )
                }

                // Anterior / Siguiente
                IconButton(
                    onClick = { state.eventSink(WaifuEvent.PrevImage) },
                    enabled = index > 0,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp),
                ) {
                    Icon(
                        imageVector = CompoundIcons.ArrowLeft(),
                        contentDescription = stringResource(R.string.screen_waifu_a11y_previous_image),
                    )
                }
                IconButton(
                    onClick = { state.eventSink(WaifuEvent.NextImage) },
                    enabled = index < lastIndex,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp),
                ) {
                    Icon(
                        imageVector = CompoundIcons.ArrowRight(),
                        contentDescription = stringResource(R.string.screen_waifu_a11y_next_image),
                    )
                }

                // Descargar
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        text = stringResource(R.string.screen_waifu_action_download),
                        onClick = { state.eventSink(WaifuEvent.Download(image)) },
                        leadingIcon = IconSource.Vector(CompoundIcons.Download()),
                    )
                    Text(
                        text = image.tags.joinToString(", ") { it.name },
                        style = ElementTheme.typography.fontBodyXsRegular,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@PreviewsDayNight
@Composable
internal fun WaifuLightboxPreview(@PreviewParameter(WaifuLightboxStateProvider::class) state: WaifuState) = ElementPreview {
    WaifuLightbox(
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}
