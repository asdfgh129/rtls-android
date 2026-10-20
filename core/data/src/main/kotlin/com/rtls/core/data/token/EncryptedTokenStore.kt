package com.rtls.core.data.token

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Token storage backed by EncryptedSharedPreferences (AES-256-GCM). Tokens are
 * never written to logs or plain preferences (protocol §14).
 */
class EncryptedTokenStore(context: Context) : TokenStore {

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): TokenSet? {
        val access = preferences.getString(KEY_ACCESS, null) ?: return null
        val refresh = preferences.getString(KEY_REFRESH, null) ?: return null
        return TokenSet(
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAtMs = preferences.getLong(KEY_ACCESS_EXPIRES, 0L),
            refreshExpiresAtMs = preferences.getLong(KEY_REFRESH_EXPIRES, 0L),
        )
    }

    override fun save(tokens: TokenSet) {
        preferences.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putString(KEY_REFRESH, tokens.refreshToken)
            .putLong(KEY_ACCESS_EXPIRES, tokens.accessExpiresAtMs)
            .putLong(KEY_REFRESH_EXPIRES, tokens.refreshExpiresAtMs)
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val FILE_NAME = "rtls_tokens"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCESS_EXPIRES = "access_expires_at"
        const val KEY_REFRESH_EXPIRES = "refresh_expires_at"
    }
}
