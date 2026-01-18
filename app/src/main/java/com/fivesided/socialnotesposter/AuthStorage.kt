package com.fivesided.socialnotesposter

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(url: String, username: String, appPass: String) {
        sharedPreferences.edit()
            .putString("blog_url", url)
            .putString("username", username)
            .putString("app_pass", appPass)
            .apply()
    }

    fun getCredentials(): Triple<String?, String?, String?> {
        val url = sharedPreferences.getString("blog_url", null)
        val username = sharedPreferences.getString("username", null)
        val appPass = sharedPreferences.getString("app_pass", null)
        return Triple(url, username, appPass)
    }

    fun hasCredentials(): Boolean {
        val (url, username, appPass) = getCredentials()
        return !url.isNullOrBlank() && !username.isNullOrBlank() && !appPass.isNullOrBlank()
    }

    fun getAuthHeader(): String {
        val (_, username, appPass) = getCredentials()
        val credentials = "$username:$appPass"
        return "Basic " + Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)
    }

    fun clearCredentials() {
        sharedPreferences.edit().clear().apply()
    }
}
