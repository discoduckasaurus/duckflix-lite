# Android Background Recording Research

**Document Version:** 1.0
**Last Updated:** February 1, 2026
**Target Platform:** Android TV (Android 14+)

## Table of Contents

1. [Foreground Service Requirements](#1-foreground-service-requirements)
2. [Wake Locks and Power Management](#2-wake-locks-and-power-management)
3. [WorkManager for Scheduling](#3-workmanager-for-scheduling)
4. [Recording Implementation Options](#4-recording-implementation-options)
5. [Stream Monitoring and Failover](#5-stream-monitoring-and-failover)
6. [Multiple Simultaneous Recordings](#6-multiple-simultaneous-recordings)
7. [Android TV Specific Considerations](#7-android-tv-specific-considerations)
8. [Recommendations](#8-recommendations)

---

## 1. Foreground Service Requirements

### What is a Foreground Service?

A Foreground Service is a type of Android service that performs operations noticeable to the user and continues running even when the user isn't directly interacting with the app. The system guarantees that foreground services are kept running and not killed under low-resource conditions.

### Persistent Notification Requirements

**All foreground services must display a persistent notification** to inform users about the ongoing operation. This notification:
- Cannot be dismissed by the user while the service is running
- Must clearly describe what the service is doing
- Provides transparency and allows users to monitor background activities
- Must be shown within 5 seconds of calling `startForeground()`

**Important:** After the system creates a service, the app has only **5 seconds** to call the service's `startForeground()` method to show the notification.

### Foreground Service Types (Android 14+)

Starting with Android 14 (API level 34), **apps must specify appropriate foreground service types**. This is a mandatory requirement.

#### Available Service Types

For DVR/recording applications, the most relevant types are:

- **`mediaPlayback`** - For audio/video playback
- **`dataSync`** - For syncing data (has 6-hour runtime limit starting Android 15)
- **`specialUse`** - For valid use cases not covered by other types
- **`microphone`** - For background audio recording (requires `FOREGROUND_SERVICE_MICROPHONE` permission)

**For DuckFlix DVR functionality, we should use `dataSync` or `specialUse` type.**

#### Required Permissions

You must declare both:

1. **General foreground service permission:**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

2. **Type-specific permission:**
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<!-- OR -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

3. **Service declaration in manifest:**
```xml
<service
    android:name=".DvrRecordingService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

### Service Lifecycle Management

#### Starting a Foreground Service

```kotlin
class DvrRecordingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Create notification channel (Android 8.0+)
        createNotificationChannel()

        // Build notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording in Progress")
            .setContentText("Recording: ${intent?.getStringExtra("channel_name")}")
            .setSmallIcon(R.drawable.ic_record)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        // Start foreground service (must be called within 5 seconds)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Start recording logic
        startRecording(intent)

        return START_STICKY // Service will be restarted if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        stopRecording()
    }
}
```

#### Stopping the Service

```kotlin
// When recording is complete
stopSelf()
// Or from outside the service
stopService(Intent(context, DvrRecordingService::class.java))
```

### Best Practices for Long-Running Services

1. **Use START_STICKY** - Service will be restarted if killed by the system
2. **Release resources properly** - Always clean up in `onDestroy()`
3. **Update notification** - Keep users informed of recording progress
4. **Stop when done** - Call `stopSelf()` when recording completes to conserve resources
5. **Handle errors gracefully** - Implement proper error handling and user notifications
6. **Respect runtime limits** - Be aware that `dataSync` type has a 6-hour limit on Android 15+

### Play Console Declaration

Apps targeting Android 14+ must declare foreground service types in the Play Console's app content page with descriptions of the app functionality using each service type.

---

## 2. Wake Locks and Power Management

### WakeLock Types

Wake locks prevent the device from entering sleep mode. For background recording, we need **CPU-only** wake locks.

#### PARTIAL_WAKE_LOCK

**This is the type we need for DVR recording.**

- Keeps the CPU running
- Allows screen to turn off
- Allows device to sleep (but CPU stays active)
- Most battery-efficient option for background processing

```kotlin
class DvrRecordingService : Service() {
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DuckFlix::RecordingWakeLock"
        )
    }

    private fun startRecording(intent: Intent?) {
        // Acquire wake lock before starting recording
        wakeLock.acquire(10*60*60*1000L /*10 hours*/)

        // Start recording logic...
    }

    private fun stopRecording() {
        // Release wake lock when done
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }
}
```

**Required Permission:**
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### Battery Optimization Exemptions

#### Doze Mode and App Standby

Android's Doze mode puts the device into deep sleep when idle, which can affect background services. During Doze mode:

- The system **ignores wake locks** (with exceptions for partially exempt apps)
- Network access is suspended
- Background tasks are deferred

**Critical Exception:** Apps that are partially exempt can:
- Use the network during Doze mode
- Hold partial wake locks during Doze and App Standby
- Other restrictions still apply (deferred jobs, syncs)

#### Requesting Battery Optimization Exemption

**Permission:**
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

**Request exemption programmatically:**
```kotlin
fun requestBatteryOptimizationExemption(context: Context) {
    val packageName = context.packageName
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        val intent = Intent().apply {
            action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            data = Uri.parse("package:$packageName")
        }
        context.startActivity(intent)
    }
}
```

**Important:** Only use this for legitimate use cases (like DVR recording). Google Play may reject apps that abuse this permission.

### Keeping Recording During Sleep/Screen-Off

To ensure uninterrupted recording:

1. **Use Foreground Service** - Prevents service from being killed
2. **Acquire PARTIAL_WAKE_LOCK** - Keeps CPU running
3. **Request battery optimization exemption** - Prevents Doze mode interference
4. **Use appropriate service type** - Declare `dataSync` or `specialUse`

### Excessive Wake Lock Usage Warning (2026)

**CRITICAL UPDATE:** Starting **March 1, 2026**, apps with excessive wake lock usage may be excluded from prominent discovery surfaces in Google Play and may display a warning on the store listing.

**What constitutes "excessive":**
- Wake locks held for **at least 2 hours within a 24-hour period**
- While screen is off
- App is in background or running foreground service
- For non-exempted wake locks

**Best Practices:**
- Release wake locks as soon as recording completes
- Don't hold wake locks longer than necessary
- Monitor wake lock usage in Android vitals
- Use Firebase Cloud Messaging (FCM) when possible instead of persistent wake locks

---

## 3. WorkManager for Scheduling

### WorkManager vs AlarmManager

| Feature | WorkManager | AlarmManager |
|---------|-------------|--------------|
| **Timing Precision** | Approximate, "as soon as possible" | Exact, precise timing |
| **Best For** | Deferrable background tasks | Time-critical tasks (alarms, DVR) |
| **Battery Impact** | Battery-friendly, respects Doze | Can wake device from Doze |
| **Persistence** | Automatic across reboots | Manual with BOOT_COMPLETED |
| **Complexity** | Higher-level API | Lower-level, more control |

**For DVR recording with scheduled start times, AlarmManager is the better choice.**

### Why WorkManager Isn't Ideal for DVR

1. **No exact timing guarantees** - Tasks execute "as soon as possible" within constraints
2. **BOOT_COMPLETED conflicts** - WorkManager uses its own BOOT_COMPLETED receiver, which may conflict with custom scheduling
3. **Not designed for time-critical tasks** - Designed for deferrable work like syncing, backups

### AlarmManager for Exact Scheduling

#### Required Permissions (Android 12+/13+)

```xml
<!-- Android 12+ - User must grant permission -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<!-- Android 13+ - Granted automatically for valid use cases (alarm clocks, calendars, DVR) -->
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

**Important:** Starting with Android 14, apps targeting Android 13+ that declare `SCHEDULE_EXACT_ALARM` have it **denied by default**.

**Recommendation:** Use `USE_EXACT_ALARM` for DVR functionality, as it's granted automatically for time-sensitive use cases.

#### Scheduling a Recording

```kotlin
class DvrScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun scheduleRecording(
        recordingId: Int,
        startTimeMillis: Long,
        channelUrl: String,
        durationMinutes: Int
    ) {
        // Check if we can schedule exact alarms
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                requestExactAlarmPermission()
                return
            }
        }

        // Create intent for starting recording service
        val intent = Intent(context, DvrRecordingService::class.java).apply {
            putExtra("recording_id", recordingId)
            putExtra("channel_url", channelUrl)
            putExtra("duration_minutes", durationMinutes)
        }

        val pendingIntent = PendingIntent.getService(
            context,
            recordingId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule exact alarm
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            startTimeMillis,
            pendingIntent
        )
    }

    fun cancelRecording(recordingId: Int) {
        val intent = Intent(context, DvrRecordingService::class.java)
        val pendingIntent = PendingIntent.getService(
            context,
            recordingId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun requestExactAlarmPermission() {
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        context.startActivity(intent)
    }
}
```

### Handling Device Reboots

Scheduled alarms are **cleared when the device reboots**. You must reschedule them.

#### BOOT_COMPLETED Receiver

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<receiver
    android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

```kotlin
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Reschedule all pending recordings
            rescheduleAllRecordings(context)
        }
    }

    private fun rescheduleAllRecordings(context: Context) {
        // Query database for all scheduled recordings
        val scheduledRecordings = getScheduledRecordings() // Your DB query

        val scheduler = DvrScheduler(context)
        scheduledRecordings.forEach { recording ->
            if (recording.startTime > System.currentTimeMillis()) {
                scheduler.scheduleRecording(
                    recording.id,
                    recording.startTime,
                    recording.channelUrl,
                    recording.durationMinutes
                )
            }
        }
    }
}
```

### Triggering Foreground Service from AlarmManager

The alarm's PendingIntent starts the DvrRecordingService, which immediately calls `startForeground()`:

```kotlin
// AlarmManager triggers this at scheduled time
class DvrRecordingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MUST call startForeground() within 5 seconds
        startForeground(NOTIFICATION_ID, createNotification())

        // Start recording
        val channelUrl = intent?.getStringExtra("channel_url") ?: return START_NOT_STICKY
        val duration = intent.getIntExtra("duration_minutes", 60)

        startRecordingJob(channelUrl, duration)

        return START_STICKY
    }
}
```

---

## 4. Recording Implementation Options

We have two primary options for implementing stream recording on Android: **ExoPlayer + MediaMuxer** (native Kotlin) or **FFmpeg binary** (bundled).

### Option A: ExoPlayer + MediaMuxer (Native Kotlin)

#### Overview

- **ExoPlayer** downloads and demuxes HLS segments
- **MediaMuxer** muxes streams into MP4 container
- Pure Kotlin/Java implementation
- No external binaries

#### How It Works

1. **ExoPlayer downloads HLS segments:**
   - Parses M3U8 playlist
   - Downloads TS segments
   - Extracts audio/video tracks

2. **MediaMuxer creates output file:**
   - Muxes audio and video into MP4
   - Supports MP4, WebM, 3GP containers
   - **Does NOT support MPEG-TS output**

#### Implementation Approach

```kotlin
class ExoPlayerRecorder(
    private val context: Context,
    private val outputFile: File
) {
    private var player: ExoPlayer? = null
    private var mediaMuxer: MediaMuxer? = null

    fun startRecording(streamUrl: String) {
        // Initialize ExoPlayer
        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
        }

        // Initialize MediaMuxer for MP4 output
        mediaMuxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        // Extract and mux streams
        // Note: This is simplified - actual implementation is complex
        extractAndMuxStreams()
    }

    private fun extractAndMuxStreams() {
        // Complex implementation required:
        // 1. Use MediaCodec to decode/re-encode if needed
        // 2. Handle format changes
        // 3. Synchronize A/V timing
        // 4. Handle errors and retries
    }
}
```

#### Pros

- **Smaller APK size** - No external binary (saves ~35-40 MB)
- **Pure Kotlin** - Better IDE support, easier debugging
- **Native Android** - Better integration with Android APIs
- **More control** - Direct access to streams and formats
- **No licensing concerns** - Apache 2.0 license

#### Cons

- **Much more complex** - Requires deep understanding of media formats
- **Limited container support** - MediaMuxer only supports MP4, WebM, 3GP (no TS)
- **Format compatibility issues** - MediaMuxer is strict about audio formats for MP4
- **B-frame support** - Only from Android 7.1+ (Nougat MR1)
- **Error handling complexity** - Must handle stream errors, format changes, timing issues manually
- **Development time** - Significantly longer to implement and debug
- **Edge cases** - More potential for bugs with unusual stream formats

#### Code Complexity Estimation

**High complexity (3-4 weeks of development):**
- Stream extraction logic
- Format conversion if needed
- A/V synchronization
- Error handling and recovery
- Testing across different stream formats

### Option B: FFmpeg Binary (Bundled)

#### Overview

- Bundle pre-compiled FFmpeg binary in APK
- Execute FFmpeg commands via ProcessBuilder
- Battle-tested, handles all formats
- LGPL licensing

#### How It Works

```kotlin
class FFmpegRecorder(
    private val context: Context,
    private val ffmpegBinary: File
) {
    fun startRecording(
        streamUrl: String,
        outputFile: File,
        durationSeconds: Int
    ) {
        val command = arrayOf(
            ffmpegBinary.absolutePath,
            "-i", streamUrl,
            "-t", durationSeconds.toString(),
            "-c", "copy",  // Stream copy (no re-encoding)
            "-bsf:a", "aac_adtstoasc",  // Fix AAC for MP4
            outputFile.absolutePath
        )

        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        // Monitor output
        monitorProcess(process)
    }

    private fun monitorProcess(process: Process) {
        Thread {
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.d("FFmpeg", line)
                    // Parse progress, detect errors
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e("FFmpeg", "Recording failed with exit code: $exitCode")
            }
        }.start()
    }
}
```

#### FFmpeg Command Breakdown

```bash
ffmpeg -i <stream_url> -t <duration> -c copy <output_file>
```

- `-i <stream_url>` - Input HLS stream URL
- `-t <duration>` - Record for specified duration (seconds)
- `-c copy` - Copy streams without re-encoding (fast, no quality loss)
- `-bsf:a aac_adtstoasc` - Convert AAC format for MP4 container
- `<output_file>` - Output file path (.mp4, .ts, .mkv, etc.)

**Advanced options:**
```bash
# Record with automatic stream switching on errors
ffmpeg -i <primary_url> -i <backup_url> -t <duration> -c copy -map 0 <output.mp4>

# Record to MPEG-TS (more robust for streaming)
ffmpeg -i <stream_url> -t <duration> -c copy output.ts
```

#### Using FFmpegKit (Recommended)

FFmpegKit is the modern successor to MobileFFmpeg:

```gradle
dependencies {
    // LGPL build (essential codecs only)
    implementation 'com.arthenica:ffmpeg-kit-min:6.0-2'

    // Or full build (more codecs, larger size)
    implementation 'com.arthenica:ffmpeg-kit-full:6.0-2'
}
```

```kotlin
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode

class FFmpegKitRecorder {
    fun startRecording(streamUrl: String, outputFile: File, durationSeconds: Int) {
        val command = "-i $streamUrl -t $durationSeconds -c copy ${outputFile.absolutePath}"

        FFmpegKit.executeAsync(command) { session ->
            val returnCode = session.returnCode

            if (ReturnCode.isSuccess(returnCode)) {
                Log.d("FFmpeg", "Recording completed successfully")
            } else {
                Log.e("FFmpeg", "Recording failed: ${session.failStackTrace}")
            }
        }
    }
}
```

#### APK Size Impact

**LGPL Build (Min/Essentials):**
- **~15-20 MB** per architecture
- Total APK increase: **~35-40 MB** (release AAR size)
- Contains essential codecs (H.264, AAC, MP3, HLS, DASH)

**Full Build:**
- **~25-30 MB** per architecture
- Includes all codecs and formats
- Not necessary for DVR use case

**Optimization:**
- Use **Android App Bundles (AAB)** for Play Store
- Users only download their architecture
- Reduces download size by ~60-70%

#### Licensing Concerns

**LGPL v3.0:**
- FFmpegKit itself is LGPL
- Allows use in commercial/proprietary apps
- **Requirement:** Users must be able to replace the LGPL libraries
- **In practice:** Provide source code for FFmpeg portion or use dynamic linking

**GPL Flag:**
- Avoid `--enable-gpl` builds
- Avoid `-gpl` postfix binaries
- Use LGPL-only builds for commercial apps

#### Build Process

**Option 1: Use pre-built FFmpegKit (Recommended)**
```gradle
implementation 'com.arthenica:ffmpeg-kit-min:6.0-2'
```

**Option 2: Cross-compile yourself**
- Use Android NDK
- Follow build scripts from FFmpegKit repository
- Time-consuming, only if custom build needed

#### Pros

- **Battle-tested** - FFmpeg is industry standard
- **Handles all formats** - HLS, DASH, RTMP, any codec
- **Simple implementation** - Just execute command
- **Robust error handling** - FFmpeg handles edge cases
- **Fast development** - 1-2 days vs 3-4 weeks
- **Proven reliability** - Used by thousands of apps
- **Multiple output formats** - MP4, TS, MKV, etc.
- **Easy failover** - Can specify multiple input streams

#### Cons

- **Larger APK** - Adds 35-40 MB to APK size
- **LGPL licensing** - Must comply with LGPL requirements
- **External dependency** - Not pure Kotlin
- **Less control** - Black box for internal processing
- **Process overhead** - Running separate process

---

## 5. Stream Monitoring and Failover

### Detecting Stream Failures

#### FFmpeg Approach

Monitor FFmpeg process output for error indicators:

```kotlin
class StreamMonitor(private val process: Process) {

    private val errorPatterns = listOf(
        "Connection refused",
        "Connection timed out",
        "404 Not Found",
        "500 Internal Server Error",
        "Immediate exit requested",
        "Error opening input"
    )

    fun monitorStream(onError: (String) -> Unit) {
        Thread {
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    // Check for errors
                    errorPatterns.forEach { pattern ->
                        if (line.contains(pattern, ignoreCase = true)) {
                            onError(line)
                        }
                    }

                    // Parse progress to detect stalls
                    parseProgress(line)
                }
            }
        }.start()
    }

    private fun parseProgress(line: String) {
        // FFmpeg outputs: frame=X fps=X size=X time=XX:XX:XX.XX
        // Monitor for stalled time values
    }
}
```

#### ExoPlayer Approach

```kotlin
player.addListener(object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                // Network error - try backup stream
                switchToBackupStream()
            }
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                // Stream format error
                handleFormatError()
            }
        }
    }
})
```

### Switching to Backup Streams Mid-Recording

#### Strategy 1: FFmpeg Multiple Inputs

```bash
# Primary and backup streams
ffmpeg -i primary_url -i backup_url -t duration -c copy -map 0 output.mp4
```

If primary fails, FFmpeg automatically tries backup. However, this is limited.

#### Strategy 2: Process Restart with Append

```kotlin
class ResilientRecorder {
    private var currentProcess: Process? = null
    private var recordingStartTime = 0L

    fun startRecording(
        primaryUrl: String,
        backupUrls: List<String>,
        outputFile: File,
        totalDurationSeconds: Int
    ) {
        recordingStartTime = System.currentTimeMillis()
        startRecordingWithRetry(primaryUrl, backupUrls, outputFile, totalDurationSeconds)
    }

    private fun startRecordingWithRetry(
        currentUrl: String,
        remainingBackups: List<String>,
        outputFile: File,
        remainingDuration: Int
    ) {
        val command = buildFFmpegCommand(currentUrl, outputFile, remainingDuration)
        currentProcess = ProcessBuilder(*command).start()

        val monitor = StreamMonitor(currentProcess!!)
        monitor.monitorStream { error ->
            // Stream failed, try backup
            currentProcess?.destroy()

            if (remainingBackups.isNotEmpty()) {
                val nextUrl = remainingBackups.first()
                val newBackups = remainingBackups.drop(1)

                // Calculate remaining time
                val elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000
                val remaining = (remainingDuration - elapsed).toInt()

                if (remaining > 0) {
                    // Continue recording with backup stream
                    startRecordingWithRetry(
                        nextUrl,
                        newBackups,
                        outputFile,
                        remaining
                    )
                }
            } else {
                // No more backups, recording failed
                notifyRecordingFailed(error)
            }
        }
    }
}
```

#### Strategy 3: Segment-Based Recording

Record in segments and switch streams between segments:

```kotlin
class SegmentedRecorder {
    private val segmentDurationSeconds = 60 // 1 minute segments

    fun startSegmentedRecording(
        channelUrls: List<String>,
        outputDir: File,
        totalDurationMinutes: Int
    ) {
        val segments = mutableListOf<File>()
        var currentUrlIndex = 0

        repeat(totalDurationMinutes) { minute ->
            val segmentFile = File(outputDir, "segment_$minute.ts")

            val success = recordSegment(
                channelUrls[currentUrlIndex],
                segmentFile,
                segmentDurationSeconds
            )

            if (success) {
                segments.add(segmentFile)
            } else {
                // Try backup stream
                currentUrlIndex = (currentUrlIndex + 1) % channelUrls.size
                // Retry segment with backup
            }
        }

        // Concatenate segments
        concatenateSegments(segments, File(outputDir, "final_recording.mp4"))
    }
}
```

### Handling Gaps in Recordings

#### Option 1: Accept Gaps

Continue recording after gap, resulting in a file with missing content but no corruption.

#### Option 2: Pad with Black Frames

Insert black video/silent audio during gaps:

```bash
ffmpeg -f lavfi -i color=black:1920x1080:d=10 -f lavfi -i anullsrc -c:v libx264 -c:a aac gap.mp4
```

Then concatenate with main recording.

#### Option 3: Multiple Output Files

Create separate files for each stream segment, mark gaps in metadata:

```kotlin
data class RecordingSegment(
    val file: File,
    val startTime: Long,
    val endTime: Long,
    val streamUrl: String,
    val isGap: Boolean = false
)
```

### Error Logging and User Notifications

```kotlin
class RecordingNotificationManager(private val context: Context) {

    fun notifyRecordingStarted(channelName: String) {
        showNotification(
            "Recording Started",
            "Recording $channelName",
            R.drawable.ic_record
        )
    }

    fun notifyStreamSwitched(fromUrl: String, toUrl: String) {
        Log.w("DVR", "Switched stream from $fromUrl to $toUrl")
        // Optional: Show brief toast or update notification
    }

    fun notifyRecordingError(error: String) {
        showNotification(
            "Recording Failed",
            error,
            R.drawable.ic_error,
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    fun notifyRecordingCompleted(duration: String, fileSize: String) {
        showNotification(
            "Recording Complete",
            "Duration: $duration, Size: $fileSize",
            R.drawable.ic_check
        )
    }
}
```

---

## 6. Multiple Simultaneous Recordings

### Can a Single Foreground Service Handle Multiple Recordings?

**Yes**, a single foreground service can handle multiple recordings simultaneously. This is the recommended approach.

### Architecture Options

#### Option 1: Single Service with Queue (Recommended)

```kotlin
class DvrRecordingService : Service() {
    private val activeRecordings = mutableMapOf<Int, RecordingJob>()
    private val recordingExecutor = Executors.newFixedThreadPool(4) // Max 4 concurrent

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground) {
            startForeground(NOTIFICATION_ID, createNotification())
            isForeground = true
        }

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val recordingId = intent.getIntExtra("recording_id", -1)
                val streamUrl = intent.getStringExtra("stream_url") ?: return START_STICKY
                val duration = intent.getIntExtra("duration", 60)

                startRecordingJob(recordingId, streamUrl, duration)
            }
            ACTION_STOP_RECORDING -> {
                val recordingId = intent.getIntExtra("recording_id", -1)
                stopRecordingJob(recordingId)
            }
        }

        return START_STICKY
    }

    private fun startRecordingJob(id: Int, url: String, duration: Int) {
        val job = RecordingJob(id, url, duration)
        activeRecordings[id] = job

        recordingExecutor.submit {
            job.start()
            activeRecordings.remove(id)

            // Stop service if no more recordings
            if (activeRecordings.isEmpty()) {
                stopSelf()
            }
        }

        updateNotification()
    }

    private fun updateNotification() {
        val text = when (activeRecordings.size) {
            0 -> "No active recordings"
            1 -> "Recording 1 channel"
            else -> "Recording ${activeRecordings.size} channels"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DuckFlix DVR")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_record)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }
}
```

**Advantages:**
- Single foreground notification
- Shared resources (wake lock, network)
- Lower memory footprint
- Easier to manage

#### Option 2: Multiple Service Instances

```kotlin
// Create separate service instance for each recording
class DvrRecordingService : Service() {
    private lateinit var recordingJob: RecordingJob

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recordingId = intent?.getIntExtra("recording_id", -1) ?: -1

        // Use unique notification ID for each instance
        startForeground(NOTIFICATION_BASE_ID + recordingId, createNotification())

        recordingJob = RecordingJob(...)
        recordingJob.start()

        return START_STICKY
    }
}
```

**Disadvantages:**
- Multiple notifications (clutters notification shade)
- Higher memory usage
- More complex to coordinate
- Not recommended for Android TV

### Resource Constraints

#### CPU

- Modern Android devices can easily handle 2-4 simultaneous recordings
- If using FFmpeg with `-c copy` (no re-encoding), CPU usage is minimal
- With re-encoding: each stream uses 10-30% CPU

**Recommendation:** Limit to **3-4 simultaneous recordings** for stability.

#### Memory

Each recording process consumes:
- **FFmpeg process:** ~20-50 MB
- **ExoPlayer:** ~30-80 MB per instance
- **Buffering:** ~10-20 MB per stream

**Total per recording:** ~50-100 MB

**Recommendation:** Monitor memory usage and limit based on device capabilities.

```kotlin
fun canStartNewRecording(): Boolean {
    val runtime = Runtime.getRuntime()
    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
    val maxMemory = runtime.maxMemory()
    val availableMemory = maxMemory - usedMemory

    // Require at least 100 MB free
    return availableMemory > 100 * 1024 * 1024
}
```

#### Network Bandwidth

Each HD stream typically requires:
- **720p:** 2-5 Mbps
- **1080p:** 5-10 Mbps
- **4K:** 15-25 Mbps

**Example:** 3 simultaneous 1080p recordings = 15-30 Mbps total

**Recommendation:**
- Check available bandwidth before starting recording
- Prioritize recordings if bandwidth limited
- Monitor network errors and switch to lower quality backup streams

```kotlin
fun estimateRequiredBandwidth(recordings: List<RecordingRequest>): Int {
    return recordings.sumOf { request ->
        when (request.quality) {
            Quality.HD_720 -> 5_000_000  // 5 Mbps
            Quality.HD_1080 -> 8_000_000 // 8 Mbps
            Quality.UHD_4K -> 20_000_000 // 20 Mbps
        }
    }
}
```

### Dynamic Recording Limit

```kotlin
class ResourceManager {
    fun getMaxSimultaneousRecordings(): Int {
        val availableMemory = getAvailableMemory()
        val estimatedBandwidth = getEstimatedBandwidth()

        val memoryLimit = (availableMemory / (100 * 1024 * 1024)).toInt() // 100 MB per recording
        val bandwidthLimit = (estimatedBandwidth / 8_000_000).toInt() // 8 Mbps per recording

        return minOf(memoryLimit, bandwidthLimit, 4) // Cap at 4
    }
}
```

---

## 7. Android TV Specific Considerations

### Background Restrictions on Android TV

Android TV follows the same background execution limits as Android mobile (introduced in Android 8.0), but with some unique considerations:

#### Key Restrictions

1. **Foreground Service Requirements:** Same as mobile - must call `startForeground()` within 5 seconds
2. **BOOT_COMPLETED Limitations:** Android 15+ restricts launching media playback foreground services from BOOT_COMPLETED receivers
3. **Background Execution Limits:** Apps in the background face the same restrictions as mobile

**For DVR functionality:** Use `dataSync` or `specialUse` foreground service type, NOT `mediaPlayback`.

### User Experience - Notifications on TV

#### The Challenge

Android TV doesn't have a notification shade like mobile. Notifications appear as:
- Toast-like overlays
- Persistent icons in the corner
- Not easily accessible by users

#### Best Practices

1. **Minimize notification updates** - Don't spam the screen
2. **Use low priority** - `NotificationCompat.PRIORITY_LOW`
3. **Provide in-app status** - Main UI should show recording status
4. **Use persistent notification only** - No interactive actions

```kotlin
fun createTVNotification(): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("DuckFlix DVR")
        .setContentText("Recording in progress")
        .setSmallIcon(R.drawable.ic_record)
        .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority on TV
        .setOngoing(true)
        .setShowWhen(false) // Hide timestamp
        .setOnlyAlertOnce(true) // Don't keep alerting
        .build()
}
```

#### Enhanced Notifications with PiPup

For better notification UX on Android TV, consider using [PiPup](https://github.com/rogro82/PiPup), which provides enhanced notification support.

### Remote Control Interruptions

#### Problem

When users press remote buttons, it can:
- Bring app to foreground (interrupting playback)
- Trigger unwanted UI interactions

#### Solution

**Foreground service is unaffected by UI state.** Recording continues regardless of:
- User navigating to other apps
- Remote button presses
- App being backgrounded

**No special handling needed** - the foreground service model handles this automatically.

### Network Stability: Wi-Fi vs Ethernet

#### Wi-Fi Considerations

- **Signal strength fluctuations** - Can cause stream interruptions
- **Power saving** - Some TVs aggressively manage Wi-Fi power
- **Interference** - 2.4 GHz band congestion
- **Reconnection delays** - After sleep/wake

**Mitigation:**
```kotlin
// Request Wi-Fi lock to prevent power saving
val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
val wifiLock = wifiManager.createWifiLock(
    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
    "DuckFlix::RecordingWifiLock"
)
wifiLock.acquire()

// Release when recording stops
wifiLock.release()
```

**Permission:**
```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
```

#### Ethernet Recommendation

**Strongly recommend Ethernet for DVR functionality:**
- More reliable connection
- No power saving issues
- Consistent bandwidth
- No interference

**Check connection type:**
```kotlin
fun isEthernetConnected(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false

    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}

fun warnIfOnWifi() {
    if (!isEthernetConnected()) {
        // Show warning to user about potential reliability issues
        Toast.makeText(
            this,
            "For best results, connect via Ethernet",
            Toast.LENGTH_LONG
        ).show()
    }
}
```

### Android TV DVR Support

Android TV officially supports DVR functionality. According to the documentation, Android TV apps can implement Digital Video Recording (DVR) features using foreground services, but must use appropriate service types (`dataSync` or `specialUse`, not `mediaPlayback`).

### System Alert Window Permissions

On Android TV 8.0+, when sideloading apps, you may need to manually grant `SYSTEM_ALERT_WINDOW` permission using ADB, as there's no built-in UI for this on TV.

```bash
adb shell appops set com.duckflix.lite SYSTEM_ALERT_WINDOW allow
```

---

## 8. Recommendations

### Recording Implementation: MediaMuxer vs FFmpeg

**Recommendation: Use FFmpeg (FFmpegKit)**

#### Rationale

| Factor | MediaMuxer | FFmpeg | Winner |
|--------|------------|---------|---------|
| **Development Time** | 3-4 weeks | 1-2 days | FFmpeg |
| **Reliability** | Untested, many edge cases | Battle-tested | FFmpeg |
| **Format Support** | MP4, WebM, 3GP only | All formats | FFmpeg |
| **Container Options** | No MPEG-TS support | Full TS support | FFmpeg |
| **Error Handling** | Manual implementation | Built-in retry logic | FFmpeg |
| **Stream Failover** | Complex to implement | Native support | FFmpeg |
| **APK Size** | +0 MB | +35-40 MB | MediaMuxer |
| **Code Complexity** | Very high | Low | FFmpeg |
| **Format Compatibility** | Strict (audio format issues) | Handles everything | FFmpeg |
| **Maintenance** | High (custom code) | Low (stable library) | FFmpeg |

**Verdict:** FFmpeg is the clear winner for production use. The 35-40 MB APK size increase is a worthwhile trade-off for:
- **90% faster development**
- **Proven reliability**
- **Better format support**
- **Built-in failover and error handling**

**APK size mitigation:**
- Use Android App Bundles (AAB) - users only download their architecture
- Use min/essentials build (not full build)
- Effective size impact: ~10-15 MB per user with AAB

#### MediaMuxer Only Makes Sense If:

- APK size is absolutely critical (e.g., sub-10 MB requirement)
- You have 1-2 months for development and testing
- You only need MP4 output with standard H.264/AAC
- You have expertise in Android media APIs

**For DuckFlix DVR, use FFmpeg.**

### Best Architecture for DVR Foreground Service

```
┌─────────────────────────────────────────────────────────────┐
│                      AlarmManager                           │
│              (Schedules recordings at exact time)           │
└─────────────────────┬───────────────────────────────────────┘
                      │ Triggers at scheduled time
                      ↓
┌─────────────────────────────────────────────────────────────┐
│                 DvrRecordingService                         │
│                (Single Foreground Service)                  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Recording Manager                                   │  │
│  │  - Manages multiple simultaneous recordings         │  │
│  │  - ThreadPool executor (4 threads)                  │  │
│  │  - Resource monitoring (CPU, memory, bandwidth)     │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Wake Lock Manager                                   │  │
│  │  - Acquires PARTIAL_WAKE_LOCK                       │  │
│  │  - Acquires Wi-Fi lock (if on Wi-Fi)               │  │
│  │  - Releases when all recordings complete           │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Notification Manager                                │  │
│  │  - Shows persistent notification                    │  │
│  │  - Updates count: "Recording X channels"           │  │
│  │  - Low priority for Android TV                      │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Recording Jobs (1-4 concurrent)                     │  │
│  │                                                      │  │
│  │  Job 1: FFmpegKit Executor                          │  │
│  │         - Primary stream URL                        │  │
│  │         - Backup stream URLs (failover)             │  │
│  │         - Output file path                          │  │
│  │         - Progress monitoring                       │  │
│  │                                                      │  │
│  │  Job 2: FFmpegKit Executor                          │  │
│  │  Job 3: FFmpegKit Executor                          │  │
│  │  Job 4: FFmpegKit Executor                          │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │  Stream Failover Handler                             │  │
│  │  - Monitors FFmpeg output for errors                │  │
│  │  - Auto-switches to backup stream on failure        │  │
│  │  - Logs all stream switches                         │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                      │
                      ↓
┌─────────────────────────────────────────────────────────────┐
│                 RecordingDatabase                           │
│  - Scheduled recordings                                     │
│  - Completed recordings                                     │
│  - Recording metadata (start time, duration, file path)     │
│  - Stream URLs (primary + backups)                          │
└─────────────────────────────────────────────────────────────┘
```

#### Key Components

**1. AlarmManager Scheduler**
```kotlin
class DvrScheduler(context: Context) {
    fun scheduleRecording(recording: ScheduledRecording) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            recording.startTime,
            createRecordingPendingIntent(recording)
        )
    }
}
```

**2. DvrRecordingService**
```kotlin
class DvrRecordingService : Service() {
    // Single service manages all recordings
    // Foreground notification shows total count
    // Acquires wake locks once for all recordings
}
```

**3. Recording Jobs**
```kotlin
class RecordingJob(
    val id: Int,
    val streamUrls: List<String>, // Primary + backups
    val outputFile: File,
    val durationSeconds: Int
) {
    fun start() {
        FFmpegKit.executeAsync(buildCommand()) { session ->
            // Handle completion or error
        }
    }
}
```

**4. Failover Handler**
```kotlin
class StreamFailoverHandler {
    fun monitorAndFailover(
        process: FFmpegSession,
        backupUrls: List<String>
    ) {
        // Parse FFmpeg output
        // Detect errors
        // Restart with backup URL if needed
    }
}
```

### Practical Implementation Strategy

#### Phase 1: Basic Recording (Week 1)

**Goals:**
- [ ] Implement DvrRecordingService with foreground service
- [ ] Integrate FFmpegKit
- [ ] Test basic recording from single stream
- [ ] Implement wake lock management

**Deliverables:**
- Service that can record a single stream to MP4
- Persistent notification
- Proper resource cleanup

#### Phase 2: Scheduling (Week 2)

**Goals:**
- [ ] Implement AlarmManager scheduler
- [ ] Add BOOT_COMPLETED receiver
- [ ] Create scheduling database schema
- [ ] Build scheduling UI

**Deliverables:**
- Users can schedule recordings
- Scheduled recordings persist across reboots
- Recordings start automatically at scheduled time

#### Phase 3: Multiple Recordings (Week 3)

**Goals:**
- [ ] Implement concurrent recording support
- [ ] Add resource monitoring
- [ ] Dynamic recording limit based on resources
- [ ] Update notification for multiple recordings

**Deliverables:**
- Service handles 2-4 simultaneous recordings
- System monitors CPU, memory, bandwidth
- Users warned if resources insufficient

#### Phase 4: Failover & Reliability (Week 4)

**Goals:**
- [ ] Implement stream monitoring
- [ ] Add automatic failover to backup streams
- [ ] Handle recording gaps
- [ ] Error notifications

**Deliverables:**
- Automatic failover to backup streams
- Detailed error logging
- User notifications for failures
- Recording metadata includes stream switches

#### Phase 5: Android TV Optimization (Week 5)

**Goals:**
- [ ] Optimize notifications for TV
- [ ] Add Ethernet vs Wi-Fi detection
- [ ] Implement Wi-Fi lock for wireless
- [ ] TV-optimized UI for recording status

**Deliverables:**
- Low-priority notifications on TV
- Warning if using Wi-Fi instead of Ethernet
- Recording status visible in app UI

### Testing Approach

#### Unit Tests

```kotlin
@Test
fun `test recording scheduling with AlarmManager`() {
    val scheduler = DvrScheduler(context)
    val recording = ScheduledRecording(
        id = 1,
        startTime = System.currentTimeMillis() + 60000,
        streamUrl = "https://example.com/stream.m3u8",
        durationMinutes = 60
    )

    scheduler.scheduleRecording(recording)

    // Verify alarm was scheduled
    verify(alarmManager).setExactAndAllowWhileIdle(any(), any(), any())
}
```

#### Integration Tests

```kotlin
@Test
fun `test foreground service handles multiple recordings`() {
    val service = DvrRecordingService()

    // Start 3 recordings
    service.startRecording(recording1)
    service.startRecording(recording2)
    service.startRecording(recording3)

    // Verify all are active
    assertEquals(3, service.activeRecordings.size)

    // Verify notification shows count
    assertTrue(service.notificationText.contains("Recording 3 channels"))
}
```

#### Manual Testing Scenarios

1. **Basic Recording**
   - [ ] Schedule a 5-minute recording
   - [ ] Verify recording starts at scheduled time
   - [ ] Verify recording stops after 5 minutes
   - [ ] Verify output file is playable

2. **Multiple Recordings**
   - [ ] Schedule 3 overlapping recordings
   - [ ] Verify all start and complete successfully
   - [ ] Check CPU/memory usage during recording
   - [ ] Verify all output files are playable

3. **Stream Failover**
   - [ ] Disconnect primary stream mid-recording
   - [ ] Verify automatic switch to backup
   - [ ] Check recording file for gaps
   - [ ] Verify error was logged

4. **Power Management**
   - [ ] Start recording
   - [ ] Turn off screen
   - [ ] Wait 5 minutes
   - [ ] Verify recording continued (check file size)

5. **Reboot Resilience**
   - [ ] Schedule recording for 10 minutes from now
   - [ ] Reboot device
   - [ ] Verify recording still starts at scheduled time

6. **Android TV Specific**
   - [ ] Test notification appearance on TV
   - [ ] Navigate to other apps during recording
   - [ ] Press various remote buttons
   - [ ] Verify recording unaffected

7. **Network Stability**
   - [ ] Test on Wi-Fi
   - [ ] Test on Ethernet
   - [ ] Compare stability and error rates
   - [ ] Verify Wi-Fi lock is acquired

### Configuration Recommendations

```kotlin
object DvrConfig {
    // Resource Limits
    const val MAX_SIMULTANEOUS_RECORDINGS = 4
    const val MIN_FREE_MEMORY_MB = 100
    const val MIN_BANDWIDTH_PER_RECORDING_MBPS = 8

    // Recording Settings
    const val DEFAULT_OUTPUT_FORMAT = "mp4"
    const val SEGMENT_DURATION_SECONDS = 60 // For segmented recording

    // Retry Settings
    const val MAX_FAILOVER_ATTEMPTS = 3
    const val RETRY_DELAY_SECONDS = 5

    // Wake Lock
    const val MAX_WAKE_LOCK_DURATION_HOURS = 10

    // Notifications
    const val NOTIFICATION_UPDATE_INTERVAL_SECONDS = 30
    const val TV_NOTIFICATION_PRIORITY = NotificationCompat.PRIORITY_LOW

    // Storage
    const val MIN_FREE_STORAGE_GB = 1
    const val STORAGE_WARNING_THRESHOLD_GB = 5
}
```

### Required Permissions Summary

```xml
<!-- Manifest permissions -->
<manifest>
    <!-- Foreground Service -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

    <!-- Wake Locks -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <!-- Scheduling -->
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <!-- Network -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

    <!-- Storage -->
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
                     android:maxSdkVersion="32" />

    <!-- Notifications -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
</manifest>
```

---

## Sources

### Foreground Services
- [Foreground service types are required | Android Developers](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [Foreground service types | Background work | Android Developers](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Understanding foreground service and full-screen intent requirements - Play Console Help](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
- [Foreground Services in Android 14: What's Changing?](https://proandroiddev.com/foreground-services-in-android-14-whats-changing-dcd56ad72788)
- [Android Foreground Services: Types, Permissions and Limitations](https://softices.com/blogs/android-foreground-services-types-permissions-use-cases-limitations)
- [Changes to foreground services | Background work | Android Developers](https://developer.android.com/develop/background-work/services/fgs/changes)

### Wake Locks and Power Management
- [Optimize for Doze and App Standby | App quality | Android Developers](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Platform power management with Doze | Android Open Source Project](https://source.android.com/docs/core/power/platform_mgmt)
- [Keeping your Android application running when the device wants to sleep | Developer Portal](https://developer.zebra.com/blog/keeping-your-android-application-running-when-device-wants-sleep-updated-android-pie)
- [Android Developers Blog: Raising the bar on battery performance: excessive partial wake locks metric](https://android-developers.googleblog.com/2025/11/raising-bar-on-battery-performance.html)
- [Excessive partial wake locks | App quality | Android Developers](https://developer.android.com/topic/performance/vitals/excessive-wakelock)

### WorkManager and Scheduling
- [Task scheduling | Background work | Android Developers](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Android WorkManager: A Complete Technical Deep Dive | ProAndroidDev](https://proandroiddev.com/android-workmanager-a-complete-technical-deep-dive-f037c768d87b)
- [Define work requests | Background work | Android Developers](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Understanding Android Background Task Scheduling: WorkManager, JobScheduler, and AlarmManager](https://medium.com/@husayn.fakher/understanding-android-background-task-scheduling-workmanager-jobscheduler-and-alarmmanager-67448cc4c8bb)
- [Schedule exact alarms are denied by default | Android Developers](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)
- [Schedule alarms | Background work | Android Developers](https://developer.android.com/develop/background-work/services/alarms)
- [SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM? Android Alarm Permission Explained](https://codewithninad.com/using-android-schedule-exact-alarm-permission-explained/)

### ExoPlayer and MediaMuxer
- [Supported formats | Android media | Android Developers](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [HLS | Android media | Android Developers](https://developer.android.com/media/media3/exoplayer/hls)
- [How to Implement Android HLS Player with ExoPlayer? - VideoSDK](https://www.videosdk.live/developer-hub/hls/android-hls-player)
- [MediaMuxer | API reference | Android Developers](https://developer.android.com/reference/android/media/MediaMuxer)
- [Supported formats | Android media | Android Developers](https://developer.android.com/media/media3/transformer/supported-formats)

### FFmpeg
- [mobile-ffmpeg | FFmpeg for Android, iOS and tvOS](https://tanersener.github.io/mobile-ffmpeg/)
- [GitHub - arthenica/ffmpeg-kit: FFmpeg Kit for applications](https://github.com/arthenica/ffmpeg-kit)
- [FFmpeg License and Legal Considerations](https://www.ffmpeg.org/legal.html)
- [GitHub - moizhassankh/ffmpeg-kit-android-16KB](https://github.com/moizhassankh/ffmpeg-kit-android-16KB)
- [FFmpeg-Kit + 16 KB Page Size In Android | ProAndroidDev](https://proandroiddev.com/ffmpeg-kit-16-kb-page-size-in-android-d522adc5efa2)

### Stream Failover
- [Error Handling Best Practices for HTTP Live Streaming - WWDC17](https://developer.apple.com/videos/play/wwdc2017/514/)
- [How to Troubleshoot Your HLS Live Stream](https://www.dacast.com/blog/how-to-troubleshoot-your-hls-live-stream/)
- [Unable to resume playing primary HLS audio stream after failover · Issue #5873](https://github.com/google/ExoPlayer/issues/5873)

### Multiple Recordings
- [MediaRecorder overview | Android media | Android Developers](https://developer.android.com/media/platform/mediarecorder)
- [Concurrent capture | Android Open Source Project](https://source.android.com/docs/core/audio/concurrent)
- [Foreground services overview | Background work | Android Developers](https://developer.android.com/develop/background-work/services/fgs)

### Android TV
- [Restrictions on starting a foreground service from the background](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Background Execution Limits | Android Developers](https://developer.android.com/about/versions/oreo/background)
- [GitHub - rogro82/PiPup: Enhanced notifications for Android TV](https://github.com/rogro82/PiPup)

---

## Conclusion

This research document provides a comprehensive overview of implementing DVR background recording functionality for DuckFlix Lite on Android TV. The key recommendations are:

1. **Use FFmpegKit** for recording implementation (faster development, proven reliability)
2. **Use AlarmManager** with `USE_EXACT_ALARM` permission for scheduling
3. **Single foreground service** managing multiple recordings (up to 4 concurrent)
4. **Implement stream failover** with backup URLs and automatic switching
5. **Acquire PARTIAL_WAKE_LOCK** and Wi-Fi lock for uninterrupted recording
6. **Request battery optimization exemption** for reliable background operation
7. **Use low-priority notifications** on Android TV
8. **Recommend Ethernet** over Wi-Fi for best stability

The proposed architecture is production-ready, scalable, and follows Android best practices as of 2026.
