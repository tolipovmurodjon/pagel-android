package org.pagel.ai.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.pagel.ai.BuildConfig
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Self-update. The release pipeline publishes `latest.json` + the signed APK on a private host that is reachable
 * only over the tailnet. This checks it, downloads the APK, verifies its SHA-256, and hands it to Android's
 * PackageInstaller. Android itself refuses any APK that is not signed with the SAME key as the installed app,
 * so a wrong or tampered file cannot replace PAGEL even if the hash check were bypassed.
 */
class Updater(private val ctx: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun check(): UpdateState {
        return try {
            val req = Request.Builder().url(BASE + "latest.json").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return UpdateState.Failed("update host answered ${r.code}")
                val m = UpdateLogic.parse(r.body?.string().orEmpty()) ?: return UpdateState.Failed("latest.json is not valid")
                if (!UpdateLogic.safeApkName(m.apk)) return UpdateState.Failed("unsafe apk name in latest.json")
                if (UpdateLogic.isNewer(BuildConfig.VERSION_CODE, m)) UpdateState.Available(m) else UpdateState.UpToDate
            }
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Download, verify, install. Returns null on success of hand-off, or a human reason for failure. */
    fun downloadAndInstall(m: UpdateManifest): String? {
        if (!canInstall()) return "Install unknown apps is off for PAGEL"
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, m.apk)
        try {
            http.newCall(Request.Builder().url(BASE + m.apk).build()).execute().use { r ->
                if (!r.isSuccessful) return "download answered ${r.code}"
                r.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        } catch (e: Exception) {
            file.delete()
            return "download failed: ${e.message}"
        }
        val actual = UpdateLogic.sha256Hex(file)
        if (!UpdateLogic.sameHash(actual, m.sha256)) {
            file.delete()
            return "checksum mismatch, file discarded"
        }
        return try {
            install(file)
            null
        } catch (e: Exception) {
            "installer refused: ${e.message}"
        }
    }

    private fun install(file: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12+: an app that installed itself before may update without a prompt, when the platform allows.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("pagel.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
            val pending = PendingIntent.getBroadcast(
                ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    companion object {
        /** Tailnet-only host; cleartext to this exact IP is already allowed in network_security_config. */
        const val BASE = "http://100.64.0.1:8788/"
        const val ACTION_RESULT = "org.pagel.ai.INSTALL_RESULT"
    }
}

/** Gets the installer's verdict. When Android wants a human tap, it becomes a notification (a background app is
 *  not allowed to pop the confirm screen open by itself). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "PAGEL updates", NotificationManager.IMPORTANCE_DEFAULT))
                val tap = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val n: Notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("PAGEL update ready")
                    .setContentText("Tap to install")
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build()
                nm.notify(7, n)
            }
            PackageInstaller.STATUS_SUCCESS -> Timber.i("self-update installed")
            else -> Timber.w("self-update failed: status=%d %s", status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    private companion object {
        const val CHANNEL = "pagel_updates"
    }
}
