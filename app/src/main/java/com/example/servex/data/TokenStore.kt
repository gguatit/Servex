package com.example.servex.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(private val context: Context) : TokenStoreContract {
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "servex_tokens", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(id: String): String? = prefs.getString(id, null)
    override fun put(id: String, token: String) { prefs.edit().putString(id, token).apply() }
    override fun remove(id: String) { prefs.edit().remove(id).apply() }
}
