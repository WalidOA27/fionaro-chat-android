import config.BuildTimeConfig
import extension.buildConfigFieldStr

/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
plugins {
    id("io.element.android-library")
}

android {
    namespace = "io.element.android.appconfig"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigFieldStr(
            name = "URL_POLICY",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.URL_POLICY ?: ""
            } else {
                "https://fionaro.pw/cookies"
            },
        )
        buildConfigFieldStr(
            name = "BUG_REPORT_URL",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.BUG_REPORT_URL ?: ""
            } else {
                ""
            },
        )
        buildConfigFieldStr(
            name = "BUG_REPORT_APP_NAME",
            value = if (isEnterpriseBuild) {
                BuildTimeConfig.BUG_REPORT_APP_NAME ?: ""
            } else {
                ""
            },
        )
    }
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.androidx.annotationjvm)
    implementation(libs.androidx.corektx)
    implementation(projects.libraries.matrix.api)
}
