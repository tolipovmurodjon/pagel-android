package org.pagel.ai.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** What `latest.json` on the update host says. Pure data, so the decision logic is testable on a plain JVM. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val sha256: String,
    val builtAt: String = "",
    val notes: String = "",
)

object UpdateLogic {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): UpdateManifest? = try {
        json.decodeFromString(UpdateManifest.serializer(), text)
    } catch (_: Exception) {
        null
    }

    /** Only ever move forward. Equal or older is never installed, so a stale host cannot downgrade the app. */
    fun isNewer(currentVersionCode: Int, m: UpdateManifest): Boolean = m.versionCode > currentVersionCode

    /** The apk name comes from a remote file: refuse anything that could climb out of the download folder. */
    fun safeApkName(name: String): Boolean =
        name.isNotEmpty() && name.endsWith(".apk") && !name.contains('/') && !name.contains('\\') && !name.contains("..")

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sameHash(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)
}
