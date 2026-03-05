package com.fivesided.socialnotesposter

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Credentials

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
        // Strip spaces and ensure no trailing/leading whitespace
        val cleanPass = appPass.trim().replace(" ", "")
        val cleanUser = username.trim()
        val cleanUrl = url.trim().removeSuffix("/")

        sharedPreferences.edit()
            .putString("blog_url", cleanUrl)
            .putString("username", cleanUser)
            .putString("app_pass", cleanPass)
            .commit()
    }

    fun getBlogUrl(): String? = sharedPreferences.getString("blog_url", null)
    fun getUsername(): String? = sharedPreferences.getString("username", null)
    fun getAppPassword(): String? = sharedPreferences.getString("app_pass", null)

    fun getCredentials(): Triple<String?, String?, String?> {
        return Triple(getBlogUrl(), getUsername(), getAppPassword())
    }

    fun hasCredentials(): Boolean {
        val (url, username, appPass) = getCredentials()
        return !url.isNullOrBlank() && !username.isNullOrBlank() && !appPass.isNullOrBlank()
    }

    fun getAuthHeader(): String {
        val username = getUsername()
        val appPass = getAppPassword()
        if (username == null || appPass == null) return ""
        return Credentials.basic(username, appPass)
    }

    fun clearCredentials() {
        sharedPreferences.edit().clear().commit()
    }
}
