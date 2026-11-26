/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.media

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.media.EncryptedAttachment
import io.element.android.libraries.matrix.api.media.MediaAttachmentEncryptor
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Implements the Matrix attachment encryption described in the spec (`EncryptedFile`).
 *
 * - AES-256-CTR with a random 256-bit key.
 * - The IV is 16 bytes: 8 random bytes followed by 8 zero bytes, as required by the spec.
 * - The SHA-256 hash is computed over the ciphertext.
 * - `k` is base64url-unpadded, `iv` and `hashes.sha256` are standard base64-unpadded.
 */
@OptIn(ExperimentalEncodingApi::class)
@ContributesBinding(SessionScope::class)
class DefaultMediaAttachmentEncryptor : MediaAttachmentEncryptor {
    private val secureRandom = SecureRandom()
    private val base64 = Base64.withPadding(Base64.PaddingOption.ABSENT)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    override fun encrypt(data: ByteArray): EncryptedAttachment {
        val key = ByteArray(AES_KEY_SIZE_IN_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(INITIALIZATION_VECTOR_SIZE_IN_BYTES)
        // Only the first 8 bytes are random, the last 8 bytes must be zero.
        val randomIvPart = ByteArray(INITIALIZATION_VECTOR_SIZE_IN_BYTES / 2)
        secureRandom.nextBytes(randomIvPart)
        randomIvPart.copyInto(iv)

        val cipher = Cipher.getInstance(AES_CTR_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(data)
        val hash = MessageDigest.getInstance(SHA_256_ALGORITHM).digest(ciphertext)

        val encryptedFileJson = buildJsonObject {
            put("v", "v2")
            putJsonObject("key") {
                put("kty", "oct")
                putJsonArray("key_ops") {
                    add("encrypt")
                    add("decrypt")
                }
                put("alg", "A256CTR")
                put("k", base64Url.encode(key))
                put("ext", true)
            }
            put("iv", base64.encode(iv))
            putJsonObject("hashes") {
                put("sha256", base64.encode(hash))
            }
        }.toString()

        return EncryptedAttachment(
            ciphertext = ciphertext,
            encryptedFileJson = encryptedFileJson,
        )
    }

    private companion object {
        const val AES_ALGORITHM = "AES"
        const val AES_CTR_TRANSFORMATION = "AES/CTR/NoPadding"
        const val AES_KEY_SIZE_IN_BYTES = 32
        const val INITIALIZATION_VECTOR_SIZE_IN_BYTES = 16
        const val SHA_256_ALGORITHM = "SHA-256"
    }
}
