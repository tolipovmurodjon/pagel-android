package org.pagel.ai.ui.tasks

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The pagel.uz password, typed once by Boss and kept encrypted with a key in the Android Keystore.
 * pagel.uz asks for it on every fresh start (nginx basic auth); the app answers for him, so he never sees the prompt again.
 */
class SavedSignIn(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "pagel_signin",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): Pair<String, String>? {
        val user = prefs.getString("user", null) ?: return null
        val pass = prefs.getString("pass", null) ?: return null
        return user to pass
    }

    fun save(user: String, pass: String) = prefs.edit().putString("user", user).putString("pass", pass).apply()

    fun forget() = prefs.edit().clear().apply()
}
