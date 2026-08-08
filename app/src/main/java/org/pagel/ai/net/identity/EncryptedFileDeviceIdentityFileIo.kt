package org.pagel.ai.net.identity

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
        return encryptedFile().openFileInput().use { it.readBytes() }
    }

    override fun writeBytes(bytes: ByteArray) {
        // EncryptedFile refuses to write over a file that already exists.
        if (file.exists()) file.delete()
        encryptedFile().openFileOutput().use { it.write(bytes) }
    }

    private fun masterKey(): MasterKey =
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private fun encryptedFile(): EncryptedFile =
        EncryptedFile.Builder(ctx, file, masterKey(), EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()

    private companion object {
        const val FILE_NAME = "pagel-device-identity.json"
    }
}
