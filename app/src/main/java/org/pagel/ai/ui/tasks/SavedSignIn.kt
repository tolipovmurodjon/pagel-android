package org.pagel.ai.ui.tasks

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pagel.uz password, typed once by Boss and kept encrypted with a key in the Android Keystore.
 * pagel.uz asks for it on every fresh start (nginx basic auth); the app answers for him, so he never sees the prompt again.
 */
class SavedSignIn private constructor(private val prefs: SharedPreferences) {

    fun get(): Pair<String, String>? {
        val user = prefs.getString("user", null) ?: return null
        val pass = prefs.getString("pass", null) ?: return null
        return user to pass
    }

    fun save(user: String, pass: String) = prefs.edit().putString("user", user).putString("pass", pass).apply()

    fun forget() = prefs.edit().clear().apply()

    companion object {
        private const val FILE = "pagel_signin"

        /**
         * Opens the store on IO: the Keystore is slow on the A05s and can throw (a broken key after a restore). It used to
         * open in the first frame on Main, which froze or closed the app (2026-10-27). A broken store is wiped and
         * rebuilt once; if that fails too, null, and the app simply asks for the password.
         */
        suspend fun open(context: Context): SavedSignIn? = withContext(Dispatchers.IO) {
            runCatching { SavedSignIn(create(context)) }
                .recoverCatching { context.deleteSharedPreferences(FILE); SavedSignIn(create(context)) }
                .getOrNull()
        }

        private fun create(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
