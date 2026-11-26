/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.media

/**
 * Encrypts attachments using the Matrix media encryption scheme (`EncryptedFile`, v1/v2).
 *
 * The resulting [EncryptedAttachment.ciphertext] must be uploaded as-is, and
 * [EncryptedAttachment.encryptedFileJson] must be embedded in the event content as the
 * `file` object of a media source (after adding its `url`).
 *
 * This is needed because the Rust SDK only exposes encrypted uploads as part of its own
 * `m.image`/`m.audio`/… send helpers, which cannot be used for custom event types such as
 * `m.sticker`.
 */
interface MediaAttachmentEncryptor {
    /**
     * Encrypts [data] with a random AES-256 key and a random IV.
     *
     * Never log the returned JSON, it contains the encryption key.
     */
    fun encrypt(data: ByteArray): EncryptedAttachment
}

/**
 * Result of [MediaAttachmentEncryptor.encrypt].
 */
data class EncryptedAttachment(
    /**
     * AES-256-CTR ciphertext, to be uploaded verbatim.
     */
    val ciphertext: ByteArray,
    /**
     * The Matrix spec `EncryptedFile` JSON object (containing `v`, `key`, `iv` and `hashes`),
     * **without** the `url` field, which can only be known after the upload.
     */
    val encryptedFileJson: String,
) {
    // ByteArray hashCode/equals are identity based, avoid surprising behaviour on a value type.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedAttachment) return false
        return ciphertext.contentEquals(other.ciphertext) && encryptedFileJson == other.encryptedFileJson
    }

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + encryptedFileJson.hashCode()
}
