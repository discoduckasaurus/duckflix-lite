# Client Integration: Adaptive Bitrate

## Bandwidth Testing

### On Login
1. Fetch settings: `GET /api/settings/playback`
2. Download test file: `GET /api/bandwidth/test` (5MB)
3. Measure time, calculate Mbps: `(5 * 8) / (timeSeconds)`
4. Report: `POST /api/bandwidth/report` with `{ measuredMbps, trigger: "login" }`

### After Stutter Fallback
Re-run bandwidth test with `trigger: "stutter_fallback"`

## Stutter Detection (ExoPlayer)

```kotlin
class StutterDetector(
    private val countThreshold: Int,
    private val durationThresholdMs: Long,
    private val windowMs: Long,
    private val singleEventMaxMs: Long
) {
    data class BufferEvent(val timestamp: Long, val durationMs: Long)
    private val events = mutableListOf<BufferEvent>()

    fun onBufferingEnded(durationMs: Long): StutterAction {
        // Single long stutter
        if (durationMs > singleEventMaxMs) {
            return StutterAction.TRIGGER
        }

        // Track event
        events.add(BufferEvent(System.currentTimeMillis(), durationMs))

        // Clean old events
        val cutoff = System.currentTimeMillis() - windowMs
        events.removeAll { it.timestamp < cutoff }

        // Check threshold
        val significant = events.count { it.durationMs > durationThresholdMs }
        return if (significant >= countThreshold) {
            StutterAction.TRIGGER
        } else {
            StutterAction.CONTINUE
        }
    }
}
```

## Fallback Flow

When `StutterAction.TRIGGER`:

1. Pause playback
2. Show dialog: "Having trouble streaming?" [Try Lower Quality] [Keep Trying]
3. If "Try Lower Quality":
   - Call `POST /api/vod/fallback` with current stream info
   - If `success: true` and `newStreamUrl`: switch streams, seek to position
   - If `exhausted: true`: show "Unfortunately we couldn't find any other sources..."
4. Run bandwidth re-test in background

## UI Strings

### Stutter Prompt
```
"Having trouble streaming?"
[Try Lower Quality]  [Keep Trying]
```

### Fallback Exhausted
```
"Unfortunately we couldn't find any other sources. You can try
the one you're on now (try pausing for a few minutes and see
if that does the trick!) or find something else and give this
a try later."
[Try Again]  [Go Back]
```
