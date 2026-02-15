package com.duckflix.lite

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duckflix.lite.data.remote.DuckFlixApi
import com.duckflix.lite.data.remote.dto.AppVersionResponse
import com.duckflix.lite.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val api: DuckFlixApi,
    private val application: Application
) : ViewModel() {

    val isLoggedIn = authRepository.isLoggedIn()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Update state
    private val _updateInfo = MutableStateFlow<AppVersionResponse?>(null)
    val updateInfo: StateFlow<AppVersionResponse?> = _updateInfo.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val prefs = application.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val response = api.getAppVersion()
                if (response.versionCode > BuildConfig.VERSION_CODE && !isDismissedToday(response.versionCode)) {
                    _updateInfo.value = response
                }
            } catch (e: Exception) {
                // Silently fail — update check is non-critical
            }
        }
    }

    fun dismissUpdate() {
        val info = _updateInfo.value ?: return
        prefs.edit()
            .putInt("dismissed_version", info.versionCode)
            .putLong("dismissed_at", System.currentTimeMillis())
            .apply()
        _updateInfo.value = null
    }

    fun downloadAndInstall() {
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            try {
                val responseBody = withContext(Dispatchers.IO) { api.downloadLatestApk() }
                val totalBytes = responseBody.contentLength()
                val apkDir = File(application.cacheDir, "apk")
                if (!apkDir.exists()) apkDir.mkdirs()
                val apkFile = File(apkDir, "update.apk")

                withContext(Dispatchers.IO) {
                    responseBody.byteStream().use { input ->
                        apkFile.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Long = 0
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read
                                if (totalBytes > 0) {
                                    _downloadProgress.value = bytesRead.toFloat() / totalBytes.toFloat()
                                }
                            }
                        }
                    }
                }

                _downloadProgress.value = 1f
                installApk(apkFile)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isDownloading.value = false
            }
        }
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            application,
            "com.duckflix.lite.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        application.startActivity(intent)
    }

    private fun isDismissedToday(versionCode: Int): Boolean {
        val dismissedVersion = prefs.getInt("dismissed_version", -1)
        val dismissedAt = prefs.getLong("dismissed_at", 0L)
        if (dismissedVersion != versionCode) return false
        val oneDayMs = 24 * 60 * 60 * 1000L
        return (System.currentTimeMillis() - dismissedAt) < oneDayMs
    }
}
