package com.duckflix.lite.data.remote

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(context: Context) : Interceptor {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Only add auth token for our own API requests, not external URLs (like Real-Debrid)
        val isOurApi = original.url.host.contains("duckflix") || original.url.host.contains("192.168.4")

        // Get auth token from encrypted preferences
        val token = encryptedPrefs.getString("auth_token", null)

        // If we have a token and this is our API, add it to the Authorization header
        val request = if (!token.isNullOrEmpty() && isOurApi) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }

        return chain.proceed(request)
    }
}
