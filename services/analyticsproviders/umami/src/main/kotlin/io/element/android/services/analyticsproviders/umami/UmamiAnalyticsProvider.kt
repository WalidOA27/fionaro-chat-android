/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.services.analyticsproviders.umami

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import im.vector.app.features.analytics.itf.VectorAnalyticsEvent
import im.vector.app.features.analytics.itf.VectorAnalyticsScreen
import im.vector.app.features.analytics.plan.SuperProperties
import im.vector.app.features.analytics.plan.UserProperties
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.services.analyticsproviders.api.AnalyticsProvider
import io.element.android.services.analyticsproviders.api.AnalyticsTransaction
import timber.log.Timber

/**
 * Analytics provider forwarding events to the self-hosted Umami instance.
 *
 * It is only active while the user has opted in to analytics: [init] is called when consent is
 * granted and [stop] when it is revoked, and [UmamiTracker] also refuses to send anything while
 * it is disabled, including already queued events.
 */
@ContributesIntoSet(AppScope::class)
class UmamiAnalyticsProvider(
    @ApplicationContext private val context: Context,
) : AnalyticsProvider {
    override val name = "Umami"

    override fun init() {
        Timber.tag("Umami").d("Analytics enabled, initializing Umami")
        UmamiTracker.init(context)
        UmamiTracker.setEnabled(true)
    }

    override fun stop() {
        Timber.tag("Umami").d("Analytics disabled, stopping Umami")
        UmamiTracker.setEnabled(false)
    }

    override fun capture(event: VectorAnalyticsEvent) {
        UmamiTracker.capture(event)
    }

    override fun screen(screen: VectorAnalyticsScreen) {
        UmamiTracker.screen(screen)
    }

    override fun updateUserProperties(userProperties: UserProperties) = Unit

    override fun updateSuperProperties(updatedProperties: SuperProperties) = Unit

    override fun trackError(throwable: Throwable) {
        UmamiTracker.trackError(throwable)
    }

    override fun startTransaction(name: String, operation: String?, description: String?): AnalyticsTransaction? = null
}
