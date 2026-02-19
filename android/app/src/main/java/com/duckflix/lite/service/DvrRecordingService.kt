package com.duckflix.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.duckflix.lite.R
import com.duckflix.lite.data.dvr.HlsRecorder
import com.duckflix.lite.data.local.dao.RecordingDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class DvrRecordingService : Service() {

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
        const val ACTION_STOP = "com.duckflix.lite.DVR_STOP"
        private const val CHANNEL_ID = "dvr_recording"
        private const val NOTIFICATION_ID = 9001
        private const val TAG = "DvrRecordingService"

        fun startRecording(context: Context, recordingId: Int) {
            val intent = Intent(context, DvrRecordingService::class.java).apply {
                putExtra(EXTRA_RECORDING_ID, recordingId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Inject lateinit var recordingDao: RecordingDao
    @Inject lateinit var okHttpClient: OkHttpClient

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRecorders = mutableMapOf<Int, HlsRecorder>()
    private var recordingJobs = mutableMapOf<Int, Job>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val recordingId = intent.getIntExtra(EXTRA_RECORDING_ID, -1)
            if (recordingId != -1) {
                stopRecording(recordingId)
            } else {
                stopAllAndShutdown()
            }
            return START_NOT_STICKY
        }

        val recordingId = intent?.getIntExtra(EXTRA_RECORDING_ID, -1) ?: -1
        if (recordingId == -1) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting recording..."))
        startRecordingJob(recordingId)
        return START_NOT_STICKY
    }

    private fun startRecordingJob(recordingId: Int) {
        if (recordingJobs.containsKey(recordingId)) return

        recordingJobs[recordingId] = serviceScope.launch {
            val recording = recordingDao.getRecordingById(recordingId) ?: run {
                println("[$TAG] Recording $recordingId not found")
                checkAndStopIfIdle()
                return@launch
            }

            updateNotification("Recording: ${recording.channelName} - ${recording.programTitle}")

            val recorder = HlsRecorder(okHttpClient, recordingDao)
            activeRecorders[recordingId] = recorder

            try {
                recorder.record(recording)
            } catch (e: Exception) {
                println("[$TAG] Recording $recordingId error: ${e.message}")
            } finally {
                activeRecorders.remove(recordingId)
                recordingJobs.remove(recordingId)
                checkAndStopIfIdle()
            }
        }
    }

    private fun stopRecording(recordingId: Int) {
        activeRecorders[recordingId]?.stop()
    }

    private fun stopAllAndShutdown() {
        activeRecorders.values.forEach { it.stop() }
    }

    private fun checkAndStopIfIdle() {
        if (activeRecorders.isEmpty()) {
            println("[$TAG] No active recordings, stopping service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            // Update notification with remaining recording info
            val first = activeRecorders.keys.firstOrNull()
            if (first != null) {
                serviceScope.launch {
                    val rec = recordingDao.getRecordingById(first)
                    if (rec != null) {
                        updateNotification("Recording: ${rec.channelName} - ${rec.programTitle}")
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DVR Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when a live TV recording is in progress"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, DvrRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DuckFlix DVR")
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, "Stop All", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
