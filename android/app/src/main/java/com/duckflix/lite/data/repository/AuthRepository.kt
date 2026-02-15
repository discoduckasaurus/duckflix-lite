package com.duckflix.lite.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.duckflix.lite.data.local.dao.UserDao
import com.duckflix.lite.data.local.entity.UserEntity
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.LoginRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: DuckFlixApi,
    private val userDao: UserDao
) {

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

    suspend fun login(username: String, password: String): Result<Unit> {
        return try {
            val response = api.login(LoginRequest(username, password))

            // Save token securely (use commit() for synchronous save)
            encryptedPrefs.edit()
                .putString(KEY_TOKEN, response.token)
                .putInt(KEY_USER_ID, response.user.id)
                .commit()

            // Save user to local database
            userDao.insertUser(
                UserEntity(
                    id = response.user.id,
                    username = response.user.username,
                    isAdmin = response.user.isAdmin,
                    rdExpiryDate = response.user.rdExpiryDate
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<Unit> {
        return try {
            val token = getAuthToken()

            // Call backend logout (graceful degradation if fails)
            if (!token.isNullOrEmpty()) {
                try {
                    api.logout("Bearer $token")
                } catch (e: Exception) {
                    println("[WARN] Backend logout failed: ${e.message}")
                }
            }

            // Clear local data (use commit for synchronous)
            encryptedPrefs.edit().clear().commit()
            userDao.deleteAll()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isLoggedIn(): Flow<Boolean> = flow {
        val token = encryptedPrefs.getString(KEY_TOKEN, null)
        emit(!token.isNullOrEmpty())
    }

    fun getAuthToken(): String? {
        return encryptedPrefs.getString(KEY_TOKEN, null)
    }

    suspend fun getCurrentUser(): UserEntity? {
        val userId = encryptedPrefs.getInt(KEY_USER_ID, -1)
        if (userId == -1) return null
        return userDao.getUserById(userId)
    }

    companion object {
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USER_ID = "user_id"
    }
}
