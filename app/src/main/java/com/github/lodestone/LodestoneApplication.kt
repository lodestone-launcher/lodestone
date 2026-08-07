package com.github.lodestone

import android.app.Application
import com.github.lodestone.di.AppGraph
import dev.zacsweers.metro.createGraphFactory
import timber.log.Timber

class LodestoneApplication : Application() {

    /** The application graph, created eagerly so the first screen never waits on DI setup. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        graph = createGraphFactory<AppGraph.Factory>().create(this)
    }
}
