package io.element.android.services.analyticsproviders.umami.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.element.android.services.analyticsproviders.umami.UmamiAnalyticsProvider

@BindingContainer
@ContributesTo(AppScope::class)
object UmamiAnalyticsModule {
    @Provides
    fun provideUmamiAnalyticsProvider(): UmamiAnalyticsProvider = UmamiAnalyticsProvider()
}
