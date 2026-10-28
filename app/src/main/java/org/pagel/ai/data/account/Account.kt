package org.pagel.ai.data.account

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import org.pagel.ai.BuildConfig
import org.pagel.ai.crash.CrashLog
import org.pagel.ai.crash.CrashRecorder
import org.pagel.ai.crash.CrashReport
import org.pagel.ai.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/** The paired phone: which server, which device, and its token. Never logged, never shown. */
@Serializable
data class Account(val serverUrl: String, val deviceId: String, val deviceName: String, val token: String)

/** Where the account lives. The real one is an encrypted file (Keystore key); tests use [InMemoryAccountStore]. */
interface AccountStore {
    /** null until [load] has run; then the account, or [SignedOut]. */
    val state: StateFlow<AccountState?>
    suspend fun load(): AccountState
    suspend fun save(account: Account)
    suspend fun clear()
}

sealed interface AccountState {
    data object SignedOut : AccountState
    data class Paired(val account: Account) : AccountState
}

class InMemoryAccountStore(initial: Account? = null) : AccountStore {
    private val flow = MutableStateFlow<AccountState?>(initial?.let { AccountState.Paired(it) } ?: AccountState.SignedOut)
    override val state: StateFlow<AccountState?> = flow.asStateFlow()
    override suspend fun load(): AccountState = flow.value!!
    override suspend fun save(account: Account) { flow.value = AccountState.Paired(account) }
    override suspend fun clear() { flow.value = AccountState.SignedOut }
}

/**
 * Seals and opens one file. The real one ([KeystoreFileSeal]) is androidx.security's [EncryptedFile], which binds the
 * FILE NAME as the cipher's associated data: a sealed file opens only under the name it was written with.
 */
interface FileSeal {
    fun write(target: File, bytes: ByteArray)
    fun read(source: File): ByteArray
}

class KeystoreFileSeal(private val ctx: Context) : FileSeal {
    override fun write(target: File, bytes: ByteArray) = encrypted(target).openFileOutput().use { it.write(bytes) }
    override fun read(source: File): ByteArray = encrypted(source).openFileInput().use { it.readBytes() }

    private fun encrypted(target: File): EncryptedFile = EncryptedFile.Builder(
        ctx, target,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()
}

/**
 * The account in a sealed file under a Keystore key, read and written on [io] only: the Keystore is slow on the A05s
 * and must never run on Main (RULES.md, main thread).
 *
 * Saved under its own name: it is sealed in `files/account-staging/pagel-account`, then renamed into place. Up to
 * app-v0.8.1 it was sealed as `cache/pagel-account.new` and renamed, so the name bound into the cipher no longer
 * matched and the file could never be opened again: every cold start (an update, the A05s closing PAGEL) asked for a
 * new pairing code (Boss 2026-10-28: "keeps asking to pair every time I update"). [load] rescues such a file by
 * opening a copy under its old name and saving it again the right way. A file that still cannot be opened is reported
 * ([onUnreadable]: the error's class only, never its contents) before the phone counts as signed out.
 */
@Singleton
class EncryptedAccountStore internal constructor(
    private val ctx: Context,
    private val io: CoroutineDispatcher,
    private val seal: FileSeal,
    private val onUnreadable: (Throwable) -> Unit,
) : AccountStore {
    @Inject constructor(@ApplicationContext ctx: Context, @IoDispatcher io: CoroutineDispatcher) :
        this(ctx, io, KeystoreFileSeal(ctx), { e -> reportUnreadable(ctx, e) })

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val flow = MutableStateFlow<AccountState?>(null)
    override val state: StateFlow<AccountState?> = flow.asStateFlow()
    private val file get() = File(ctx.filesDir, FILE)

    override suspend fun load(): AccountState = lock.withLock {
        flow.value?.let { return it }
        val read = withContext(io) { if (file.exists()) readOrRescue() else null }
        val s = read?.let { AccountState.Paired(it) } ?: AccountState.SignedOut
        flow.value = s
        s
    }

    private fun decode(f: File): Account = json.decodeFromString(Account.serializer(), seal.read(f).decodeToString())

    private fun readOrRescue(): Account? {
        val first = runCatching { decode(file) }
        first.getOrNull()?.let { return it }
        // Written by app-v0.8.1 or older: open a copy under the name it was sealed with, then save it right.
        val rescue = File(File(ctx.cacheDir, "account-rescue").apply { mkdirs() }, LEGACY_NAME)
        val rescued = runCatching {
            file.copyTo(rescue, overwrite = true)
            decode(rescue).also(::write)
        }
        rescue.delete()
        rescued.getOrNull()?.let { return it }
        onUnreadable(rescued.exceptionOrNull() ?: first.exceptionOrNull() ?: IllegalStateException())
        return null
    }

    private fun write(account: Account) {
        val staging = File(File(ctx.filesDir, STAGING).apply { mkdirs() }, FILE).apply { delete() }
        seal.write(staging, json.encodeToString(Account.serializer(), account).encodeToByteArray())
        file.delete()
        check(staging.renameTo(file)) { "could not save the account" }
        File(ctx.cacheDir, LEGACY_NAME).delete()
    }

    override suspend fun save(account: Account) = lock.withLock {
        withContext(io) { write(account) }
        flow.value = AccountState.Paired(account)
    }

    override suspend fun clear() = lock.withLock {
        withContext(io) { file.delete() }
        flow.value = AccountState.SignedOut
    }

    internal companion object {
        const val FILE = "pagel-account"
        const val STAGING = "account-staging"
        /** The name app-v0.8.1 and older sealed the account under (in the cache folder) before renaming it. */
        const val LEGACY_NAME = "$FILE.new"

        /** A saved account the phone could not open: reported with the next crash upload, the error's class only. */
        fun reportUnreadable(ctx: Context, e: Throwable) {
            runCatching {
                CrashLog.write(CrashRecorder.dir(ctx), CrashReport(
                    at = System.currentTimeMillis(), versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
                    thread = "account", type = "AccountUnreadable: ${e.javaClass.name}", message = "", stack = "",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}", sdk = Build.VERSION.SDK_INT,
                ))
            }
        }
    }
}
