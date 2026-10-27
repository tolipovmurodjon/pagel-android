package org.pagel.ai.net.identity

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** [DeviceIdentityFileIo] backed by [EncryptedFile], encrypted at rest at `filesDir/pagel-device-identity.json`. */
@Singleton
class EncryptedFileDeviceIdentityFileIo @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : DeviceIdentityFileIo {

    private val file = File(ctx.filesDir, FILE_NAME)

    override fun readBytes(): ByteArray? {
        if (!file.exists()) return null
        return encryptedFile(file).openFileInput().use { it.readBytes() }
    }

    /**
     * Write-then-rename, so a process kill can never leave a half-written identity behind. The old
     * delete-then-write was a real corruption window: [EncryptedFile] refuses to write over an
     * existing file, so the previous identity had to be deleted first, and dying in between left a
     * truncated file that `DefaultDeviceIdentityStore.read()` then choked on.
     *
     * The staging copy lives in a *subdirectory* under the same [File.getName]. That's not
     * cosmetic: `EncryptedFile.openFileOutput()` passes `file.getName()` to Tink as the streaming
     * AEAD's associated data, so a staging file named `...json.tmp` would encrypt under different
     * AAD and fail to decrypt after the rename. Same name, different directory, same filesystem —
     * `rename(2)` is atomic and replaces the destination in one step.
     */
    override fun writeBytes(bytes: ByteArray) {
        val staging = stagingFile()
        staging.delete() // a leftover from an earlier crash; EncryptedFile won't write over it
        encryptedFile(staging).openFileOutput().use { it.write(bytes) }
        if (staging.renameTo(file)) return
        // Filesystems where rename can't replace an existing destination: fall back to the old
        // non-atomic sequence rather than leaving the device with no identity at all.
        file.delete()
        if (!staging.renameTo(file)) {
            staging.delete()
            throw IOException("could not commit $FILE_NAME into ${file.parent}")
        }
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
        const val FILE_NAME = "pagel-device-identity.json"
        const val STAGING_DIR_NAME = "pagel-device-identity-staging"
    }
}
