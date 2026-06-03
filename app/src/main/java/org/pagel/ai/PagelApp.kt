package org.pagel.ai

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application root. Hilt entry point, and the WorkManager [Configuration.Provider] so our
 * @HiltWorker harvest/rollup jobs can be constructed with injected dependencies.
 */
@HiltAndroidApp
class PagelApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
