package com.duckflix.lite.data.dvr

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DvrStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val RECORDINGS_DIR = "DuckFlix/Recordings"
    }

    /**
     * Get the preferred storage directory for recordings.
     * Prefers external/USB storage (index > 0), falls back to internal.
     */
    fun getRecordingsDir(): File {
        val externalDirs = context.getExternalFilesDirs(null)

        // Index > 0 = removable/USB storage
        val usbDir = externalDirs.drop(1).firstOrNull { it != null && it.canWrite() }
        val baseDir = usbDir ?: externalDirs.firstOrNull() ?: context.filesDir

        val recordingsDir = File(baseDir, RECORDINGS_DIR)
        recordingsDir.mkdirs()
        return recordingsDir
    }

    /**
     * Whether USB/external storage is available.
     */
    fun isExternalStorageAvailable(): Boolean {
        val externalDirs = context.getExternalFilesDirs(null)
        return externalDirs.drop(1).any { it != null && it.canWrite() }
    }

    /**
     * Get the storage type string for the current preferred storage.
     */
    fun getStorageType(): String {
        return if (isExternalStorageAvailable()) "external" else "internal"
    }

    /**
     * Generate a file path for a new recording.
     */
    fun generateFilePath(channelName: String, programTitle: String): String {
        val dir = getRecordingsDir()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = "${sanitize(channelName)}_${sanitize(programTitle)}_$timestamp.ts"
        return File(dir, safeName).absolutePath
    }

    /**
     * Available space in bytes on the recordings storage.
     */
    fun getAvailableSpace(): Long {
        return try {
            val stat = StatFs(getRecordingsDir().absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Delete a recording file.
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            false
        }
    }

    private fun sanitize(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
    }
}
