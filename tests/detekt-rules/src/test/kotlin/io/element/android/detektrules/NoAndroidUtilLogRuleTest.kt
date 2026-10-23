/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.detektrules

import com.google.common.truth.Truth.assertThat
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.compileAndLint
import io.gitlab.arturbosch.detekt.test.lint
import org.junit.Test
import java.nio.file.Files

class NoAndroidUtilLogRuleTest {
    private val subject = NoAndroidUtilLogRule(Config.empty)

    @Test
    fun `reports direct platform logger import and usage`() {
        val findings = subject.compileAndLint(forbiddenLoggerCode)
        assertThat(findings).hasSize(2)
    }

    @Test
    fun `allows Timber`() {
        val findings = subject.compileAndLint(
            """
            package foo

            import timber.log.Timber

            object Foo {
                fun bar() {
                    Timber.d("message")
                }
            }
            """.trimIndent()
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report the call feature`() {
        val findings = subject.compileAndLint(
            """
            package io.element.android.features.call.impl

            import $FORBIDDEN_LOGGER

            object Foo {
                fun bar() {
                    Log.d("tag", "message")
                }
            }
            """.trimIndent()
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `does not report the widget bridge driver`() {
        val directory = Files.createTempDirectory("detekt-rule")
        val file = directory.resolve("RustWidgetDriver.kt")
        Files.writeString(
            file,
            """
            package io.element.android.libraries.matrix.impl.widget

            object Foo {
                fun bar() {
                    $FORBIDDEN_LOGGER.e("tag", "message")
                }
            }
            """.trimIndent()
        )
        val findings = subject.lint(file)
        assertThat(findings).isEmpty()
    }

    private val forbiddenLoggerCode = """
        package foo

        import $FORBIDDEN_LOGGER

        object Foo {
            fun bar() {
                $FORBIDDEN_LOGGER.d("tag", "message")
            }
        }
    """.trimIndent()

    private companion object {
        private const val FORBIDDEN_LOGGER = "android.util" + "." + "Log"
    }
}
