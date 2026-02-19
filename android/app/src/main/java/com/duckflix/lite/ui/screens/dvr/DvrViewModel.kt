package com.duckflix.lite.ui.screens.dvr

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.duckflix.lite.data.dvr.DvrStorageManager
import com.duckflix.lite.data.local.dao.RecordingDao
import com.duckflix.lite.data.local.entity.RecordingEntity
import com.duckflix.lite.service.DvrRecordingService
import com.duckflix.lite.service.DvrSchedulerWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DvrUiState(
    val activeRecordings: List<RecordingEntity> = emptyList(),
    val scheduledRecordings: List<RecordingEntity> = emptyList(),
    val completedRecordings: List<RecordingEntity> = emptyList(),
    val failedRecordings: List<RecordingEntity> = emptyList(),
    val availableSpace: Long = 0,
    val storageType: String = "internal",
    val selectedTab: Int = 0
)

@HiltViewModel
class DvrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingDao: RecordingDao,
    private val storageManager: DvrStorageManager
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)

    val uiState: StateFlow<DvrUiState> = combine(
        recordingDao.getAllRecordings(),
        _selectedTab
    ) { recordings, tab ->
        DvrUiState(
            activeRecordings = recordings.filter { it.status == "recording" },
            scheduledRecordings = recordings.filter { it.status == "scheduled" },
            completedRecordings = recordings.filter { it.status == "completed" },
            failedRecordings = recordings.filter { it.status == "failed" },
            availableSpace = storageManager.getAvailableSpace(),
            storageType = storageManager.getStorageType(),
            selectedTab = tab
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DvrUiState())

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun cancelRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            if (recording.status == "recording") {
                // Stop the active recording via service
                val stopIntent = Intent(context, DvrRecordingService::class.java).apply {
                    action = DvrRecordingService.ACTION_STOP
                    putExtra(DvrRecordingService.EXTRA_RECORDING_ID, recording.id)
                }
                context.startService(stopIntent)
            }
            recordingDao.updateRecording(recording.copy(status = "cancelled"))
        }
    }

    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            recording.filePath?.let { storageManager.deleteFile(it) }
            recordingDao.deleteById(recording.id)
        }
    }

    fun startRecordingNow(channelId: String, channelName: String, programTitle: String, programDescription: String?, endTimeMs: Long) {
        viewModelScope.launch {
            val filePath = storageManager.generateFilePath(channelName, programTitle)
            val recording = RecordingEntity(
                channelId = channelId,
                channelName = channelName,
                programTitle = programTitle,
                programDescription = programDescription,
                scheduledStart = System.currentTimeMillis(),
                scheduledEnd = endTimeMs,
                filePath = filePath,
                storageType = storageManager.getStorageType()
            )
            val id = recordingDao.insertRecording(recording).toInt()

            // Start immediately via one-shot WorkManager
            val workRequest = OneTimeWorkRequestBuilder<DvrSchedulerWorker>()
                .setInputData(workDataOf(DvrSchedulerWorker.KEY_RECORDING_ID to id))
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    fun scheduleRecording(channelId: String, channelName: String, programTitle: String, programDescription: String?, startTimeMs: Long, endTimeMs: Long) {
        viewModelScope.launch {
            val filePath = storageManager.generateFilePath(channelName, programTitle)
            val recording = RecordingEntity(
                channelId = channelId,
                channelName = channelName,
                programTitle = programTitle,
                programDescription = programDescription,
                scheduledStart = startTimeMs,
                scheduledEnd = endTimeMs,
                filePath = filePath,
                storageType = storageManager.getStorageType()
            )
            recordingDao.insertRecording(recording)
            ensurePeriodicScheduler()
        }
    }

    private fun ensurePeriodicScheduler() {
        val workRequest = androidx.work.PeriodicWorkRequestBuilder<DvrSchedulerWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DvrSchedulerWorker.WORK_NAME_PERIODIC,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
