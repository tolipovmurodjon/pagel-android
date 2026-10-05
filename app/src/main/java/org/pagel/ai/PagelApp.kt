package org.pagel.ai

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.pagel.ai.update.UpdateWorker

/**
 * Application root and Hilt entry point.
 *
 * It used to also supply a WorkManager [androidx.work.Configuration] for the @HiltWorker
 * harvest/rollup/sync jobs; those jobs were removed with the on-device usage feature, so there is
 * nothing left to schedule. Chat's offline send queue is the next thing likely to want WorkManager
 * back — add it then, with the workers that need it, not before.
 */
@HiltAndroidApp
class PagelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        UpdateWorker.schedule(this)
    }
}
