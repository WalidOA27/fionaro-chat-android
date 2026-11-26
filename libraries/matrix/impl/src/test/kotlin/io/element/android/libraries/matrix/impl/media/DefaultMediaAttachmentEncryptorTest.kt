/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.media

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class DefaultMediaAttachmentEncryptorTest {
    private val sut = DefaultMediaAttachmentEncryptor()
    private val base64 = Base64.withPadding(Base64.PaddingOption.ABSENT)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    @Test
    fun `encrypt then decrypt returns the original data`() {
        val data = "a sticker sound".toByteArray()
        val result = sut.encrypt(data)
        val json = Json.parseToJsonElement(result.encryptedFileJson).jsonObject
        val key = base64Url.decode(json.getValue("key").jsonObject.getValue("k").jsonPrimitive.content)
        val iv = base64.decode(json.getValue("iv").jsonPrimitive.content)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(result.ciphertext)

        assertThat(decrypted).isEqualTo(data)
    }

    @Test
    fun `the IV is 16 bytes with the last 8 bytes set to zero`() {
        val json = Json.parseToJsonElement(sut.encrypt(ByteArray(8)).encryptedFileJson).jsonObject
        val iv = base64.decode(json.getValue("iv").jsonPrimitive.content)

        assertThat(iv.size).isEqualTo(16)
        assertThat(iv.copyOfRange(8, 16)).isEqualTo(ByteArray(8))
    }

    @Test
    fun `the sha256 hash matches the ciphertext`() {
        val result = sut.encrypt("some content".toByteArray())
        val json = Json.parseToJsonElement(result.encryptedFileJson).jsonObject
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(result.ciphertext)
        val encodedHash = json.getValue("hashes").jsonObject.getValue("sha256").jsonPrimitive.content

        assertThat(base64.decode(encodedHash)).isEqualTo(expectedHash)
        assertThat(encodedHash).doesNotContain("=")
    }

    @Test
    fun `the key is a 32 bytes url-safe unpadded base64 string`() {
        val json = Json.parseToJsonElement(sut.encrypt(ByteArray(8)).encryptedFileJson).jsonObject
        val keyObject = json.getValue("key").jsonObject
        val encodedKey = keyObject.getValue("k").jsonPrimitive.content

        assertThat(keyObject.getValue("kty").jsonPrimitive.content).isEqualTo("oct")
        assertThat(keyObject.getValue("alg").jsonPrimitive.content).isEqualTo("A256CTR")
        assertThat(keyObject.getValue("ext").jsonPrimitive.content).isEqualTo("true")
        assertThat(keyObject.getValue("key_ops").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("encrypt", "decrypt")
            .inOrder()
        assertThat(encodedKey).doesNotContain("=")
        assertThat(encodedKey).hasLength(43)
        assertThat(base64Url.decode(encodedKey)).hasLength(32)
    }

    @Test
    fun `the produced JSON is a v2 EncryptedFile without url`() {
        val json = Json.parseToJsonElement(sut.encrypt(ByteArray(8)).encryptedFileJson).jsonObject

        assertThat(json.getValue("v").jsonPrimitive.content).isEqualTo("v2")
        assertThat(json).doesNotContainKey("url")
    }

    @Test
    fun `two encryptions of the same data use different keys and IVs`() {
        val first = Json.parseToJsonElement(sut.encrypt(ByteArray(8)).encryptedFileJson).jsonObject
        val second = Json.parseToJsonElement(sut.encrypt(ByteArray(8)).encryptedFileJson).jsonObject

        assertThat(first.getValue("key").jsonObject.getValue("k")).isNotEqualTo(second.getValue("key").jsonObject.getValue("k"))
        assertThat(first.getValue("iv")).isNotEqualTo(second.getValue("iv"))
    }
}
