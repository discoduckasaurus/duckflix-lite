package com.duckflix.lite.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.duckflix.lite.data.local.dao.RecordingDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic WorkManager worker that checks for due recordings and starts the foreground service.
 * Runs every 15 minutes (WorkManager minimum). Also used as one-shot for "Record Now".
 */
@HiltWorker
class DvrSchedulerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recordingDao: RecordingDao
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME_PERIODIC = "dvr_scheduler_periodic"
        const val WORK_NAME_ONE_SHOT = "dvr_record_now"
        const val KEY_RECORDING_ID = "recording_id"
    }

    override suspend fun doWork(): Result {
        // Check if this is a one-shot "Record Now" request with a specific recording ID
        val specificId = inputData.getInt(KEY_RECORDING_ID, -1)
        if (specificId != -1) {
            DvrRecordingService.startRecording(applicationContext, specificId)
            return Result.success()
        }

        // Periodic check: find recordings due within 1 minute
        val now = System.currentTimeMillis()
        val dueRecordings = recordingDao.getDueRecordings(now + 60_000)

        for (recording in dueRecordings) {
            println("[DvrScheduler] Starting due recording: ${recording.id} - ${recording.programTitle}")
            DvrRecordingService.startRecording(applicationContext, recording.id)
        }

        return Result.success()
    }
}
