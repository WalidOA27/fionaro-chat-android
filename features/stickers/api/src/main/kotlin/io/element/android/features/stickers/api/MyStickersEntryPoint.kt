/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.api

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import io.element.android.libraries.architecture.FeatureEntryPoint
import io.element.android.libraries.matrix.api.core.EventId

/**
 * Entry point for the "My stickers" tray.
 */
interface MyStickersEntryPoint : FeatureEntryPoint {
    data class Params(
        /**
         * When set, the sticker picked from the tray is sent in this thread.
         */
        val threadRootEventId: EventId? = null,
    )

    fun createNode(
        parentNode: Node,
        buildContext: BuildContext,
        params: Params = Params(),
    ): Node
}
