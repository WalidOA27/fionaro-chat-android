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
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Switch

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
            Text(text = "SFW", style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = state.isNsfw,
                onCheckedChange = { state.eventSink(WaifuEvent.ToggleNsfw(it)) },
            )
            Text(text = "NSFW", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.weight(1f))
            Button(
                text = "Actualizar",
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
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { state.eventSink(WaifuEvent.ToggleTag(tag.slug)) }
                        .border(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
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
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(text = state.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        text = "Reintentar",
                        onClick = { state.eventSink(WaifuEvent.Retry) },
                    )
                }
            }

            state.images.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "Sin resultados", style = MaterialTheme.typography.bodyMedium)
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
                                    text = "Has visto todas las imágenes",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Button(
                                    text = "Actualizar",
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
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = image.url,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio),
        )
    }
}
