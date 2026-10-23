/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.detektrules

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportDirective

/**
 * Forbid direct use of the platform logger. The project standard is Timber.
 *
 * The call feature and the widget bridge still contain direct platform logger calls and are
 * intentionally excluded for now; they are tracked separately.
 */
class NoAndroidUtilLogRule(config: Config) : Rule(config) {
    override val issue: Issue = Issue(
        id = "AndroidUtilLogNotAllowed",
        severity = Severity.Style,
        description = "Use Timber instead of the platform logger.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitImportDirective(importDirective: KtImportDirective) {
        super.visitImportDirective(importDirective)
        if (importDirective.importedFqName?.asString() == forbiddenImport && !importDirective.isExcluded()) {
            report(importDirective)
        }
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        super.visitDotQualifiedExpression(expression)
        if (expression.text.startsWith("$forbiddenImport.") && !expression.isExcluded()) {
            report(expression)
        }
    }

    private fun KtElement.isExcluded(): Boolean {
        val ktFile = containingKtFile
        val path = ktFile.virtualFile?.path?.replace('\\', '/')
        if (path != null) {
            if (path.contains("/features/call/")) return true
            if (path.endsWith("/RustWidgetDriver.kt") || path.endsWith("/JoinedRustRoom.kt")) return true
        }
        val packageName = ktFile.packageFqName.asString()
        return packageName == CALL_FEATURE_PACKAGE ||
            packageName.startsWith("$CALL_FEATURE_PACKAGE.")
    }

    private fun report(element: KtElement) {
        report(
            CodeSmell(
                issue = issue,
                entity = Entity.from(element),
                message = "Use Timber instead of the platform logger.",
            )
        )
    }

    private companion object {
        private const val ANDROID_UTIL_PACKAGE = "android.util"
        private const val LOG_CLASS = "Log"
        private val forbiddenImport = "$ANDROID_UTIL_PACKAGE.$LOG_CLASS"
        private const val CALL_FEATURE_PACKAGE = "io.element.android.features.call"
    }
}
