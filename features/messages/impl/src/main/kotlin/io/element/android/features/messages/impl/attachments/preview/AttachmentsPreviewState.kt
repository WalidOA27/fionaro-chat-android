/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Immutable
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.textcomposer.model.TextEditorState
import kotlinx.collections.immutable.ImmutableList

data class AttachmentsPreviewState(
    val attachments: ImmutableList<Attachment>,
    val selectedIndex: Int,
    /**
     * Indices of the attachments which have already been sent. They can no longer be removed or edited, so that
     * retrying a partially failed multi-send never sends the same media twice.
     */
    val sentAttachmentIndices: ImmutableList<Int>,
    val imageEditorState: AttachmentImageEditorState?,
    val canEditImage: Boolean,
    val isApplyingImageEdits: Boolean,
    val displayImageEditError: Boolean,
    val sendActionState: SendActionState,
    val textEditorState: TextEditorState,
    val mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    val displayFileTooLargeError: Boolean,
    val eventSink: (AttachmentsPreviewEvent) -> Unit,
) {
    /** The attachment currently displayed in the preview. */
    val currentAttachment: Attachment
        get() = attachments[selectedIndex.coerceIn(attachments.indices)]
}

@Immutable
sealed interface SendActionState {
    data object Idle : SendActionState

    @Immutable
    sealed interface Sending : SendActionState {
        data class Processing(val displayProgress: Boolean) : Sending
        data class ReadyToUpload(val mediaInfo: MediaUploadInfo) : Sending
        data class Uploading(
            val mediaUploadInfo: MediaUploadInfo,
            val sentCount: Int = 1,
            val totalCount: Int = 1,
        ) : Sending
    }

    data class Failure(val error: Throwable, val mediaUploadInfo: MediaUploadInfo?) : SendActionState
    data object Done : SendActionState

    fun mediaUploadInfo(): MediaUploadInfo? = when (this) {
        is Sending.ReadyToUpload -> mediaInfo
        is Sending.Uploading -> mediaUploadInfo
        is Failure -> mediaUploadInfo
        else -> null
    }
}
