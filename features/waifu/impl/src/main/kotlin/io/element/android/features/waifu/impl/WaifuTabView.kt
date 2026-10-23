/*
 * Copyright (c) 2026 Fionaro Chat
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.waifu.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed

@Composable
fun WaifuTabView(
    state: WaifuState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // NSFW toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_waifu_toggle_sfw),
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textPrimary,
            )
            Switch(
                checked = state.isNsfw,
                onCheckedChange = { state.eventSink(WaifuEvent.ToggleNsfw(it)) },
            )
            Text(
                text = stringResource(R.string.screen_waifu_toggle_nsfw),
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textPrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                text = stringResource(R.string.screen_waifu_action_refresh),
                onClick = { state.eventSink(WaifuEvent.Retry) },
                showProgress = state.isLoading,
            )
        }

        // Tag chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            lazyItems(state.displayTags, key = { it.id }) { tag ->
                val selected = tag.slug in state.selectedTags
                Text(
                    text = tag.name,
                    style = ElementTheme.typography.fontBodySmMedium,
                    color = if (selected) {
                        ElementTheme.colors.textOnSolidPrimary
                    } else {
                        ElementTheme.colors.textPrimary
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) {
                                ElementTheme.colors.bgAccentRest
                            } else {
                                ElementTheme.colors.bgSubtleSecondary
                            }
                        )
                        .clickable { state.eventSink(WaifuEvent.ToggleTag(tag.slug)) }
                        .border(
                            width = 1.dp,
                            color = if (selected) {
                                ElementTheme.colors.bgAccentRest
                            } else {
                                ElementTheme.colors.borderInteractiveSecondary
                            },
                            shape = RoundedCornerShape(16.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                val errorText = when (state.error) {
                    WaifuError.NsfwForbidden -> stringResource(R.string.screen_waifu_error_nsfw_forbidden)
                    WaifuError.Generic -> stringResource(R.string.screen_waifu_error_generic)
                }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = errorText,
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        text = stringResource(CommonStrings.action_retry),
                        onClick = { state.eventSink(WaifuEvent.Retry) },
                    )
                }
            }

            state.images.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(CommonStrings.common_no_results),
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textSecondary,
                    )
                }
            }

            else -> {
                // Scroll infinito: al acercarse al final (5 items), cargar la siguiente pagina.
                val gridState = rememberLazyStaggeredGridState()
                val shouldLoadMore by remember {
                    derivedStateOf {
                        val info = gridState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= info.totalItemsCount - 5
                    }
                }
                LaunchedEffect(shouldLoadMore) {
                    if (
                        shouldLoadMore &&
                        state.hasNextPage &&
                        !state.isLoadingMore &&
                        !state.isLoading &&
                        state.images.size < MAX_WAIFU_SESSION_IMAGES
                    ) {
                        state.eventSink(WaifuEvent.LoadMore)
                    }
                }

                // Masonry grid (Pinterest style): cada imagen con su altura real.
                // El grid virtualiza: las imagenes lejanas se descomponen y Coil libera su memoria.
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    staggeredItemsIndexed(state.images, key = { _, image -> image.id }) { index, image ->
                        WaifuCard(
                            image = image,
                            onClick = { state.eventSink(WaifuEvent.OpenImage(index)) },
                        )
                    }
                    if (state.isLoadingMore) {
                        item(key = "waifu-loading", span = StaggeredGridItemSpan.FullLine) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    if (state.sessionLimitReached) {
                        item(key = "waifu-session-limit", span = StaggeredGridItemSpan.FullLine) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.screen_waifu_session_limit),
                                    style = ElementTheme.typography.fontBodyMdRegular,
                                    color = ElementTheme.colors.textPrimary,
                                )
                                Button(
                                    text = stringResource(R.string.screen_waifu_action_refresh),
                                    onClick = { state.eventSink(WaifuEvent.Retry) },
                                    showProgress = state.isLoading,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.lightboxIndex != null) {
            WaifuLightbox(
                state = state,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WaifuCard(
    image: WaifuImage,
    onClick: () -> Unit,
) {
    val ratio = if (image.width > 0 && image.height > 0) {
        image.width.toFloat() / image.height.toFloat()
    } else {
        3f / 4f
    }
    val placeholderColor = ElementTheme.colors.bgSubtleSecondary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(placeholderColor)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = image.url,
            contentDescription = stringResource(R.string.screen_waifu_a11y_image),
            contentScale = ContentScale.FillWidth,
            placeholder = ColorPainter(placeholderColor),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun WaifuTabViewPreview(@PreviewParameter(WaifuStateProvider::class) state: WaifuState) = ElementPreview {
    WaifuTabView(
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}
