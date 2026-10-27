package org.pagel.ai.crash

import android.content.Context
import android.os.Build
import org.pagel.ai.BuildConfig
import java.io.File

/**
 * Catches every uncaught crash, writes its stack to `files/crashes/`, then lets Android close the app as before.
 * The next launch sends the file to pagel.uz ([CrashUploader]), so real crashes from Boss's phone reach the server
 * instead of vanishing (2026-10-27: "it closes at once", and no one could see why).
 */
object CrashRecorder {
    fun dir(ctx: Context) = File(ctx.filesDir, "crashes")

    fun install(ctx: Context) {
        val dir = dir(ctx)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                CrashLog.write(
                    dir,
                    CrashLog.report(thread, e, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, System.currentTimeMillis()),
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
