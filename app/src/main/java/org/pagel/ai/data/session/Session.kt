package org.pagel.ai.data.session

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.pagel.ai.crash.CrashRecorder
import org.pagel.ai.crash.CrashUploader
import org.pagel.ai.data.DataError
import org.pagel.ai.data.Outcome
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.AccountStore
import org.pagel.ai.data.api.PagelApi
import org.pagel.ai.data.db.PagelDao
import org.pagel.ai.data.sync.LiveStatus
import org.pagel.ai.data.sync.SyncEngine
import org.pagel.ai.di.AppScope
import org.pagel.ai.di.IoDispatcher
import org.pagel.ai.di.PagelHttp
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's link to pagel.uz: pair with the 6-digit code from pagel.uz → Phones, then keep Room in sync while the
 * app runs. The repository boundary: callers get [Outcome] and [DataError], never an exception.
 */
@Singleton
class Session @Inject constructor(
    private val api: PagelApi,
    private val accounts: AccountStore,
    private val sync: SyncEngine,
    private val dao: PagelDao,
    @PagelHttp private val http: OkHttpClient,
    @ApplicationContext private val ctx: Context,
    @AppScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    val account: StateFlow<AccountState?> = accounts.state
    val live: StateFlow<LiveStatus> = sync.status

    fun projects() = dao.projects()

    /**
     * PAGEL is on screen: read the saved account off Main and, if paired, sync and listen live. The process lives
     * all day (the anchor service), so the live stream runs only while the app is in front ([pause] stops it).
     */
    fun resume() {
        // A new build is found when Boss opens PAGEL, not up to 30 minutes later (the periodic check).
        org.pagel.ai.update.UpdateWorker.checkNow(ctx)
        scope.launch {
            val s = accounts.load()
            if (s is AccountState.Paired) {
                startSync(s.account)
                // Crashes saved since the last start go to pagel.uz → events (kind app_crash).
                withContext(io) { runCatching { CrashUploader(http).upload(CrashRecorder.dir(ctx), s.account.serverUrl, s.account.token) } }
            }
        }
    }

    fun pause() = sync.stop()

    /** Forget this phone's token here (the web's Revoke does the server side). */
    suspend fun disconnect() {
        sync.stop()
        accounts.clear()
    }

    suspend fun pair(serverUrl: String, code: String): Outcome<Account> {
        val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Phone" }
        return when (val r = api.pair(serverUrl, code.filter(Char::isDigit), name)) {
            is Outcome.Failed -> r
            is Outcome.Ok -> {
                val a = Account(serverUrl.trimEnd('/'), r.value.id, r.value.name, r.value.token)
                accounts.save(a)
                startSync(a)
                Outcome.Ok(a)
            }
        }
    }

    private fun startSync(a: Account) = sync.start(scope, a) {
        // A revoked phone forgets its token and asks for a new code.
        accounts.clear()
    }

    companion object {
        const val DEFAULT_SERVER = "https://pagel.uz"
    }
}

/** Boss's words for each failure, on the pairing screen. */
fun DataError.message(): String = when (this) {
    DataError.Network -> "Can't reach pagel.uz. Check the internet and try again."
    DataError.Unauthorized -> "This phone was disconnected. Make a new code on pagel.uz → Phones."
    is DataError.Refused -> message
    is DataError.Server -> "pagel.uz had a problem ($status). Try again in a minute."
}
