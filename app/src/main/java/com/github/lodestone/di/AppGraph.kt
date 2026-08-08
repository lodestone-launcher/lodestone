package com.github.lodestone.di

import android.app.Application
import android.content.Context
import com.github.lodestone.data.local.settings.SettingsStore
import com.github.lodestone.data.remote.microsoft.MicrosoftAuthApi
import com.github.lodestone.data.repository.AccountRepository
import com.github.lodestone.data.repository.RuntimeInstaller
import com.github.lodestone.data.repository.VersionInstaller
import com.github.lodestone.domain.usecase.BuildLaunchSpecUseCase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The application-wide object graph.
 *
 * Metro resolves this at compile time, so a missing or ambiguous binding is a compiler error rather
 * than something that surfaces the first time a screen is opened.
 */
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }

    @Provides
    fun provideContext(application: Application): Context = application

    /** Exposed so the UI layer can build view models without depending on the data layer's wiring. */
    val versionInstaller: VersionInstaller

    val runtimeInstaller: RuntimeInstaller

    val buildLaunchSpec: BuildLaunchSpecUseCase

    val accounts: AccountRepository

    val microsoftAuth: MicrosoftAuthApi

    val settings: SettingsStore
}
