/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorPresenter
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.firstInstanceOf
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.rememberMarkdownTextEditorState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@AssistedInject
class AttachmentsPreviewPresenter(
    @Assisted private val attachments: ImmutableList<Attachment>,
    @Assisted private val onDoneListener: OnDoneListener,
    @Assisted private val timelineMode: Timeline.Mode,
    @Assisted private val inReplyToEventId: EventId?,
    mediaSenderFactory: MediaSenderFactory,
    private val permalinkBuilder: PermalinkBuilder,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val attachmentImageEditor: AttachmentImageEditor,
    private val mediaOptimizationSelectorPresenterFactory: MediaOptimizationSelectorPresenter.Factory,
    private val videoCompressionPresetSelector: VideoCompressionPresetSelector,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
) : Presenter<AttachmentsPreviewState> {
    @AssistedFactory
    interface Factory {
        fun create(
            attachments: ImmutableList<Attachment>,
            timelineMode: Timeline.Mode,
            onDoneListener: OnDoneListener,
            inReplyToEventId: EventId?,
        ): AttachmentsPreviewPresenter
    }

    /**
     * An attachment being previewed, along with the original picked media and the image edits applied to it.
     */
    private data class PreviewItem(
        val originalMedia: LocalMedia,
        val attachment: Attachment.Media,
        val appliedImageEdits: AttachmentImageEdits = AttachmentImageEdits(),
        val editedTempFile: File? = null,
    )

    private val mediaSender: MediaSender = mediaSenderFactory.create(timelineMode)

    @Composable
    override fun present(): AttachmentsPreviewState {
        val coroutineScope = rememberCoroutineScope()

        val items = remember {
            mutableStateListOf<PreviewItem>().apply {
                attachments.forEach { attachment ->
                    val media = attachment as Attachment.Media
                    add(PreviewItem(originalMedia = media.localMedia, attachment = media))
                }
            }
        }
        var selectedIndex by remember { mutableIntStateOf(0) }

        val sendActionState = remember {
            mutableStateOf<SendActionState>(SendActionState.Idle)
        }
        val currentItem = items.getOrNull(selectedIndex) ?: items.firstOrNull()
        val mediaAttachment = currentItem?.attachment ?: (attachments.first() as Attachment.Media)

        var canEditImage by remember { mutableStateOf(currentItem?.originalMedia?.info?.canEditImage() == true) }
        var imageEditorState by remember { mutableStateOf<AttachmentImageEditorState?>(null) }
        var isApplyingImageEdits by remember { mutableStateOf(false) }
        var displayImageEditError by remember { mutableStateOf(false) }

        val markdownTextEditorState = rememberMarkdownTextEditorState(initialText = null, initialFocus = false)
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        val ongoingSendAttachmentJob = remember { mutableStateOf<Job?>(null) }

        var preprocessMediaJob by remember { mutableStateOf<Job?>(null) }

        val mediaOptimizationSelectorPresenter = remember(mediaAttachment.localMedia.uri, mediaAttachment.sendAsFile) {
            mediaOptimizationSelectorPresenterFactory.create(
                localMedia = mediaAttachment.localMedia,
                sendAsFile = mediaAttachment.sendAsFile,
            )
        }
        val mediaOptimizationSelectorState by rememberUpdatedState(mediaOptimizationSelectorPresenter.present())

        val observableSendState = snapshotFlow { sendActionState.value }

        var displayFileTooLargeError by remember { mutableStateOf(false) }

        LaunchedEffect(
            mediaOptimizationSelectorState.displayMediaSelectorViews,
            mediaOptimizationSelectorState.videoSizeEstimations,
            selectedIndex,
            mediaAttachment,
            imageEditorState,
            isApplyingImageEdits,
        ) {
            // Multi-selection is pre-processed item by item when sending, to keep memory usage low.
            if (items.size > 1) return@LaunchedEffect
            // If the media optimization selector is not displayed, we can pre-process the media
            // to prepare it for sending. This is done to avoid blocking the UI thread when the
            // user clicks on the send button.
            @Suppress("ComplexCondition")
            if (mediaOptimizationSelectorState.displayMediaSelectorViews == false &&
                preprocessMediaJob == null &&
                imageEditorState == null &&
                !isApplyingImageEdits
            ) {
                if (mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo() && mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull() == null) {
                    Timber.d("Waiting for video size estimations to be able to select the best video compression preset before pre-processing the media")
                    return@LaunchedEffect
                }
                val config = getAutoPreprocessMediaOptimizationConfig(
                    mediaAttachment = mediaAttachment,
                    mediaOptimizationSelectorState = mediaOptimizationSelectorState,
                ) ?: return@LaunchedEffect
                preprocessMediaJob = coroutineScope.preProcessAttachment(
                    attachment = mediaAttachment,
                    mediaOptimizationConfig = config,
                    displayProgress = false,
                    sendActionState = sendActionState,
                )
            }
        }

        LaunchedEffect(currentItem?.originalMedia) {
            val originalMedia = currentItem?.originalMedia ?: return@LaunchedEffect
            canEditImage = originalMedia.info.canEditImage() || attachmentImageEditor.canEdit(originalMedia)
        }

        val maxUploadSize = mediaOptimizationSelectorState.maxUploadSize.dataOrNull()
        LaunchedEffect(maxUploadSize, selectedIndex, mediaAttachment) {
            displayFileTooLargeError = false
            // Check file upload size if the media won't be processed for upload
            val isImageFile = mediaAttachment.localMedia.info.isImageAttachment()
            val isVideoFile = mediaAttachment.localMedia.info.mimeType.isMimeTypeVideo()
            if (maxUploadSize != null && !(isImageFile || isVideoFile)) {
                // If file size is not known, we're permissive and allow sending. The SDK will cancel the upload if needed.
                val fileSize = mediaAttachment.localMedia.info.fileSize ?: 0L
                if (maxUploadSize < fileSize) {
                    displayFileTooLargeError = true
                }
            }
        }

        val videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()
        LaunchedEffect(videoSizeEstimations, selectedIndex) {
            if (videoSizeEstimations != null) {
                // Check if the video size estimations are too large for the max upload size
                displayFileTooLargeError = videoSizeEstimations.none { it.canUpload }
            }
        }

        fun handleEvent(event: AttachmentsPreviewEvent) {
            when (event) {
                is AttachmentsPreviewEvent.SendAttachment -> {
                    ongoingSendAttachmentJob.value = coroutineScope.launch {
                        val item = items.getOrNull(selectedIndex) ?: return@launch
                        val selectorState = mediaOptimizationSelectorState
                        // If the media optimization selector is displayed, we need to wait for the user to select the options
                        // before we can pre-process the media.
                        val selectedConfig = if (selectorState.displayMediaSelectorViews == true) {
                            MediaOptimizationConfig(
                                compressImages = selectorState.isImageOptimizationEnabled == true,
                                videoCompressionPreset = selectorState.selectedVideoPreset ?: VideoCompressionPreset.STANDARD,
                            )
                        } else {
                            null
                        }

                        if (selectedConfig != null) {
                            preprocessMediaJob = preProcessAttachment(
                                attachment = item.attachment,
                                mediaOptimizationConfig = selectedConfig,
                                displayProgress = true,
                                sendActionState = sendActionState,
                            )
                        }

                        // If the processing was hidden before, make it visible now
                        if (sendActionState.value is SendActionState.Sending.Processing) {
                            sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                        }

                        // Pre-processing is done, send the attachment
                        val caption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder)
                            .takeIf { it.isNotEmpty() }

                        // Wait until the media is ready to be uploaded
                        val mediaUploadInfo = observableSendState
                            .firstInstanceOf<SendActionState.Sending.ReadyToUpload>().mediaInfo

                        val editedTempFileToDelete = items.getOrNull(selectedIndex)?.editedTempFile

                        // If we're supposed to send the media as a background job, we can dismiss this screen already
                        if (coroutineContext.isActive) {
                            onDoneListener()
                        }

                        // Send the media using the session coroutine scope so it doesn't matter if this screen or the chat one are closed
                        sessionCoroutineScope.launch(dispatchers.io) {
                            try {
                                sendPreProcessedMedia(
                                    mediaUploadInfo = mediaUploadInfo,
                                    caption = caption,
                                    sendActionState = sendActionState,
                                    dismissAfterSend = false,
                                    inReplyToEventId = inReplyToEventId,
                                )
                            } finally {
                                editedTempFileToDelete?.safeDelete()
                                // Clean up the pre-processed media after it's been sent
                                mediaSender.cleanUp()
                            }
                        }
                    }
                }
                AttachmentsPreviewEvent.CancelAndDismiss -> {
                    displayFileTooLargeError = false
                    displayImageEditError = false
                    isApplyingImageEdits = false

                    // Cancel media preprocessing and sending
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    // If we couldn't send the pre-processed media, remove it
                    mediaSender.cleanUp()
                    ongoingSendAttachmentJob.value?.cancel()

                    // Dismiss the screen
                    dismiss(sendActionState, items.toList())
                }
                AttachmentsPreviewEvent.CancelAndClearSendState -> {
                    // Cancel media sending
                    ongoingSendAttachmentJob.value?.let {
                        it.cancel()
                        ongoingSendAttachmentJob.value = null
                    }

                    if (items.size > 1) {
                        // Retry will send the attachments which have not been sent yet.
                        mediaSender.cleanUp()
                        sendActionState.value = SendActionState.Idle
                    } else {
                        val mediaUploadInfo = sendActionState.value.mediaUploadInfo()
                        sendActionState.value = if (mediaUploadInfo != null) {
                            SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
                        } else {
                            SendActionState.Idle
                        }
                    }
                }
                is AttachmentsPreviewEvent.SelectAttachment -> {
                    if (sendActionState.value is SendActionState.Sending.Uploading) return
                    val newIndex = event.index.coerceIn(items.indices)
                    if (newIndex == selectedIndex) return
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    resetPreparedMedia(sendActionState)
                    selectedIndex = newIndex
                    imageEditorState = null
                    displayFileTooLargeError = false
                    displayImageEditError = false
                }
                is AttachmentsPreviewEvent.RemoveAttachment -> {
                    if (sendActionState.value is SendActionState.Sending.Uploading) return
                    if (event.index !in items.indices) return
                    if (items.size == 1) {
                        // Removing the last attachment closes the screen.
                        dismiss(sendActionState, items.toList())
                        return
                    }
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    resetPreparedMedia(sendActionState)
                    val removed = items.removeAt(event.index)
                    removed.editedTempFile?.safeDelete()
                    imageEditorState = null
                    selectedIndex = when {
                        event.index < selectedIndex -> selectedIndex - 1
                        selectedIndex > items.lastIndex -> items.lastIndex
                        else -> selectedIndex
                    }
                }
                AttachmentsPreviewEvent.OpenImageEditor -> {
                    val item = items.getOrNull(selectedIndex) ?: return
                    val resolvedCanEditImage = canEditImage || item.originalMedia.info.canEditImage()
                    if (resolvedCanEditImage) {
                        preprocessMediaJob?.cancel()
                        preprocessMediaJob = null
                        resetPreparedMedia(sendActionState)
                        imageEditorState = AttachmentImageEditorState(
                            localMedia = item.originalMedia,
                            edits = item.appliedImageEdits,
                            previewDebug = false,
                        )
                    }
                }
                AttachmentsPreviewEvent.CloseImageEditor -> {
                    imageEditorState = null
                }
                is AttachmentsPreviewEvent.UpdateImageCropRect -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.copy(cropRect = event.cropRect)
                    )
                }
                AttachmentsPreviewEvent.RotateImageToTheLeft -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.rotateAntiClockwise()
                    )
                }
                AttachmentsPreviewEvent.FlipImageHorizontally -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipHorizontally()
                    )
                }
                AttachmentsPreviewEvent.FlipImageVertically -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipVertically()
                    )
                }
                AttachmentsPreviewEvent.ResetImageEdits -> {
                    imageEditorState = imageEditorState?.copy(
                        edits = AttachmentImageEdits()
                    )
                }
                AttachmentsPreviewEvent.ApplyImageEdits -> {
                    val pendingState = imageEditorState ?: return
                    val index = selectedIndex
                    val item = items.getOrNull(index) ?: return
                    if (!pendingState.edits.hasChanges) {
                        item.editedTempFile?.safeDelete()
                        items[index] = item.copy(
                            attachment = Attachment.Media(item.originalMedia, sendAsFile = item.attachment.sendAsFile),
                            appliedImageEdits = pendingState.edits,
                            editedTempFile = null,
                        )
                        imageEditorState = null
                        resetPreparedMedia(sendActionState)
                        return
                    }
                    isApplyingImageEdits = true
                    displayImageEditError = false
                    coroutineScope.launch {
                        val result = withContext(dispatchers.io) {
                            attachmentImageEditor.exportEdits(
                                localMedia = item.originalMedia,
                                edits = pendingState.edits,
                            )
                        }
                        result.fold(
                            onSuccess = { editedMedia ->
                                item.editedTempFile?.safeDelete()
                                items[index] = item.copy(
                                    attachment = Attachment.Media(
                                        localMedia = editedMedia.localMedia,
                                        sendAsFile = item.attachment.sendAsFile,
                                    ),
                                    appliedImageEdits = pendingState.edits,
                                    editedTempFile = editedMedia.file,
                                )
                                imageEditorState = null
                                resetPreparedMedia(sendActionState)
                            },
                            onFailure = {
                                Timber.e(it, "Failed to apply image edits")
                                displayImageEditError = true
                            }
                        )
                        isApplyingImageEdits = false
                    }
                }
                AttachmentsPreviewEvent.ClearImageEditError -> {
                    displayImageEditError = false
                }
            }
        }

        return AttachmentsPreviewState(
            attachments = items.map { it.attachment }.toImmutableList(),
            selectedIndex = selectedIndex.coerceIn(items.indices),
            imageEditorState = imageEditorState,
            canEditImage = canEditImage,
            isApplyingImageEdits = isApplyingImageEdits,
            displayImageEditError = displayImageEditError,
            sendActionState = sendActionState.value,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorState,
            displayFileTooLargeError = displayFileTooLargeError,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun getAutoPreprocessMediaOptimizationConfig(
        mediaAttachment: Attachment.Media,
        mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    ): MediaOptimizationConfig? {
        return if (mediaAttachment.sendAsFile) {
            // If we're sending the media as a file, we can skip image compression and we should select the highest video compression preset that still fits
            // the upload limit (if the estimations are available)
            val videoCompressionPreset = videoCompressionPresetSelector.selectBestVideoPreset(
                expectedVideoPreset = VideoCompressionPreset.HIGH,
                videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations,
            ).dataOrNull() ?: VideoCompressionPreset.HIGH

            MediaOptimizationConfig(
                compressImages = false,
                videoCompressionPreset = videoCompressionPreset,
            )
        } else {
            // Otherwise, we just rely on the user preferences for media optimization
            mediaOptimizationConfigProvider.get()
        }
    }

    private fun CoroutineScope.preProcessAttachment(
        attachment: Attachment,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) = launch(dispatchers.io) {
        when (attachment) {
            is Attachment.Media -> {
                preProcessMedia(
                    mediaAttachment = attachment,
                    mediaOptimizationConfig = mediaOptimizationConfig,
                    displayProgress = displayProgress,
                    sendActionState = sendActionState,
                )
            }
        }
    }

    private suspend fun preProcessMedia(
        mediaAttachment: Attachment.Media,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) {
        preProcessMediaForSending(
            mediaAttachment = mediaAttachment,
            mediaOptimizationConfig = mediaOptimizationConfig,
            displayProgress = displayProgress,
            sendActionState = sendActionState,
        )
    }

    /**
     * Pre-process the given media, updating the [sendActionState] accordingly. Returns the processed media, or `null` if
     * the processing failed (in which case the state is set to [SendActionState.Failure]).
     */
    private suspend fun preProcessMediaForSending(
        mediaAttachment: Attachment.Media,
        mediaOptimizationConfig: MediaOptimizationConfig,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ): MediaUploadInfo? {
        sendActionState.value = SendActionState.Sending.Processing(displayProgress = displayProgress)
        return mediaSender.preProcessMedia(
            uri = mediaAttachment.localMedia.uri,
            mimeType = mediaAttachment.localMedia.info.mimeType,
            mediaOptimizationConfig = mediaOptimizationConfig,
        ).fold(
            onSuccess = { mediaUploadInfo ->
                Timber.d("Media ${mediaUploadInfo.file.path.orEmpty().hash()} finished processing, it's now ready to upload")
                sendActionState.value = SendActionState.Sending.ReadyToUpload(mediaUploadInfo)
                mediaUploadInfo
            },
            onFailure = {
                Timber.e(it, "Failed to pre-process media")
                if (it is CancellationException) {
                    throw it
                } else {
                    sendActionState.value = SendActionState.Failure(it, null)
                    null
                }
            }
        )
    }

    private fun dismiss(
        sendActionState: MutableState<SendActionState>,
        items: List<PreviewItem>,
    ) {
        // Delete the temporary files
        items.forEach { item ->
            temporaryUriDeleter.delete(item.attachment.localMedia.uri)
            item.editedTempFile?.safeDelete()
        }
        sendActionState.value.mediaUploadInfo()?.let { data ->
            cleanUp(data)
        }
        // Reset the sendActionState to ensure that dialog is closed before the screen
        sendActionState.value = SendActionState.Done
        onDoneListener()
    }

    private fun cleanUp(
        mediaUploadInfo: MediaUploadInfo,
    ) {
        mediaUploadInfo.allFiles().forEach { file ->
            file.safeDelete()
        }
    }

    private fun resetPreparedMedia(sendActionState: MutableState<SendActionState>) {
        sendActionState.value.mediaUploadInfo()?.let(::cleanUp)
        mediaSender.cleanUp()
        sendActionState.value = SendActionState.Idle
    }

    private suspend fun sendPreProcessedMedia(
        mediaUploadInfo: MediaUploadInfo,
        caption: String?,
        sendActionState: MutableState<SendActionState>,
        dismissAfterSend: Boolean,
        inReplyToEventId: EventId?,
        sentCount: Int = 1,
        totalCount: Int = 1,
        updateDoneState: Boolean = true,
    ): Boolean = runCatchingExceptions {
        sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfo, sentCount, totalCount)
        mediaSender.sendPreProcessedMedia(
            mediaUploadInfo = mediaUploadInfo,
            caption = caption,
            formattedCaption = null,
            inReplyToEventId = inReplyToEventId,
        ).getOrThrow()
    }.fold(
        onSuccess = {
            cleanUp(mediaUploadInfo)
            if (updateDoneState) {
                // Reset the sendActionState to ensure that dialog is closed before the screen
                sendActionState.value = SendActionState.Done
            }

            if (dismissAfterSend) {
                onDoneListener()
            }
            true
        },
        onFailure = { error ->
            Timber.e(error, "Failed to send attachment")
            if (error is CancellationException) {
                throw error
            } else {
                sendActionState.value = SendActionState.Failure(error, mediaUploadInfo)
                false
            }
        }
    )
}
