package org.pagel.ai.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.account.Account
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.data.account.EncryptedAccountStore
import org.pagel.ai.data.account.FileSeal
import org.robolectric.annotation.Config
import java.io.File
import java.security.GeneralSecurityException

/**
 * The pairing survives a cold start (Boss 2026-10-28: "keeps asking to pair every time I update"). Robolectric has no
 * Android Keystore, so the seal is a double that binds the file name the way EncryptedFile does (its associated data):
 * a file opens only under the name it was sealed with. The real Keystore is proven by the emulator smoke's cold start.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccountStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val account = Account("https://pagel.uz", "d_1", "A05s", "tok")
    private val unreadable = mutableListOf<Throwable>()

    /** "name\u0000payload": opening it under another name fails, as AES-GCM with the name as associated data does. */
    private val seal = object : FileSeal {
        override fun write(target: File, bytes: ByteArray) = target.writeBytes(target.name.encodeToByteArray() + byteArrayOf(0) + bytes)
        override fun read(source: File): ByteArray {
            val all = source.readBytes()
            val cut = all.indexOf(0.toByte())
            if (cut < 0 || all.copyOfRange(0, cut).decodeToString() != source.name) throw GeneralSecurityException("decryption failed")
            return all.copyOfRange(cut + 1, all.size)
        }
    }

    private fun store() = EncryptedAccountStore(ctx, Dispatchers.IO, seal) { unreadable += it }
    private val saved get() = File(ctx.filesDir, "pagel-account")

    @Before fun clean() {
        saved.delete()
        File(ctx.filesDir, "account-staging").deleteRecursively()
        File(ctx.cacheDir, "account-rescue").deleteRecursively()
    }

    @Test fun aSavedAccountIsStillPairedAfterAColdStart() = runBlocking {
        store().save(account)
        // A new process: a new store with nothing in memory.
        assertEquals(AccountState.Paired(account), store().load())
        assertTrue(unreadable.isEmpty())
    }

    @Test fun anAccountSavedByTheOldAppIsRescuedAndSavedAgainRight() = runBlocking {
        // app-v0.8.1: sealed as cache/pagel-account.new, then renamed to files/pagel-account (its name no longer matches).
        val old = File(ctx.cacheDir, "pagel-account.new")
        seal.write(old, """{"serverUrl":"https://pagel.uz","deviceId":"d_1","deviceName":"A05s","token":"tok"}""".encodeToByteArray())
        assertTrue(old.renameTo(saved))
        assertTrue("the bug: it cannot be opened under its new name", runCatching { seal.read(saved) }.isFailure)

        assertEquals(AccountState.Paired(account), store().load())
        // Saved again under its own name: the next cold start opens it directly.
        assertTrue(runCatching { seal.read(saved) }.isSuccess)
        assertEquals(AccountState.Paired(account), store().load())
        assertTrue(unreadable.isEmpty())
    }

    @Test fun aFileNothingCanOpenIsReportedThenSignedOut() = runBlocking {
        saved.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(AccountState.SignedOut, store().load())
        assertEquals(1, unreadable.size)
        assertTrue(unreadable.single() is GeneralSecurityException)
    }
}
