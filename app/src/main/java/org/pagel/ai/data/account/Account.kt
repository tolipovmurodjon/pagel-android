package org.pagel.ai.data.account

import android.content.Context
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
 * The account in an [EncryptedFile] under a Keystore key, read and written on [io] only: the Keystore is slow on the
 * A05s and must never run on Main (RULES.md, main thread). A file the Keystore can no longer open (a restore, a
 * reset key) counts as signed out, so the app asks for a new pairing code instead of crashing.
 */
@Singleton
class EncryptedAccountStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AccountStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val flow = MutableStateFlow<AccountState?>(null)
    override val state: StateFlow<AccountState?> = flow.asStateFlow()
    private val file get() = File(ctx.filesDir, FILE)

    override suspend fun load(): AccountState = lock.withLock {
        flow.value?.let { return it }
        val read = withContext(io) {
            runCatching {
                if (!file.exists()) null
                else json.decodeFromString(Account.serializer(), encrypted(file).openFileInput().use { it.readBytes() }.decodeToString())
            }.getOrNull()
        }
        val s = read?.let { AccountState.Paired(it) } ?: AccountState.SignedOut
        flow.value = s
        s
    }

    override suspend fun save(account: Account) = lock.withLock {
        withContext(io) {
            val staging = File(ctx.cacheDir, "$FILE.new").apply { delete() }
            encrypted(staging).openFileOutput().use { it.write(json.encodeToString(Account.serializer(), account).encodeToByteArray()) }
            file.delete()
            check(staging.renameTo(file)) { "could not save the account" }
        }
        flow.value = AccountState.Paired(account)
    }

    override suspend fun clear() = lock.withLock {
        withContext(io) { file.delete() }
        flow.value = AccountState.SignedOut
    }

    private fun encrypted(target: File): EncryptedFile = EncryptedFile.Builder(
        ctx, target,
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private companion object {
        const val FILE = "pagel-account"
    }
}
