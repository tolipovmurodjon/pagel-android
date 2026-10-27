package org.pagel.ai.net

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [GatewayAuthTokenFileIo] backed by [EncryptedFile], encrypted at rest at
 * `filesDir/pagel-gateway-auth-token`. Same write-then-rename pattern as
 * [org.pagel.ai.net.identity.EncryptedFileDeviceIdentityFileIo] — see that class's doc for why: a
 * process kill can never leave a half-written token behind, and the staging copy must keep the
 * *same* [File.getName] as the destination, because [EncryptedFile] passes the filename to Tink as
 * the streaming AEAD's associated data — a staging file named anything else would encrypt under
 * different AAD and fail to decrypt after the rename.
 */
@Singleton
class EncryptedFileGatewayAuthTokenFileIo @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : GatewayAuthTokenFileIo {

    private val file = File(ctx.filesDir, FILE_NAME)

    override fun readBytes(): ByteArray? {
        if (!file.exists()) return null
        return encryptedFile(file).openFileInput().use { it.readBytes() }
    }

    override fun writeBytes(bytes: ByteArray) {
        val staging = stagingFile()
        staging.delete() // a leftover from an earlier crash; EncryptedFile won't write over it
        encryptedFile(staging).openFileOutput().use { it.write(bytes) }
        if (staging.renameTo(file)) return
        // Filesystems where rename can't replace an existing destination: fall back to the old
        // non-atomic sequence rather than leaving Boss's token unreadable.
        file.delete()
        if (!staging.renameTo(file)) {
            staging.delete()
            throw IOException("could not commit $FILE_NAME into ${file.parent}")
        }
    }

    override fun delete() {
        file.delete()
        stagingFile().delete()
    }

    private fun stagingFile(): File {
        val dir = File(ctx.filesDir, STAGING_DIR_NAME)
        dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun masterKey(): MasterKey =
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encryptedFile(target: File): EncryptedFile =
        EncryptedFile.Builder(ctx, target, masterKey(), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()

    private companion object {
        const val FILE_NAME = "pagel-gateway-auth-token"
        const val STAGING_DIR_NAME = "pagel-gateway-auth-token-staging"
    }
}
