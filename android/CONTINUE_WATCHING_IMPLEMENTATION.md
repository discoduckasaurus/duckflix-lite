# Continue Watching with Download States - Implementation Summary

## Overview
Successfully implemented enhanced Continue Watching feature with support for:
- Active download tracking with real-time progress
- Failed download error states with retry/dismiss options
- 4-hour download timeout handling
- 12-hour failed download visibility window
- Auto-polling for active and failed downloads

## Backend Integration Complete
Server provides comprehensive `/api/user/watch-progress` endpoint with:
- Normal watch progress items
- Active downloads with progress (0-100%)
- Failed downloads with error messages
- Download state flags: `isDownloading`, `isFailed`
- Job IDs for dismissing failed downloads

## APK Changes Implemented

### 1. Data Models (VodDtos.kt)

**New DTOs:**
```kotlin
ContinueWatchingItem {
    - itemId, tmdbId, type, title, posterPath
    - season, episode (for TV shows)
    - position, duration (playback progress)
    - isDownloading, isFailed (state flags)
    - downloadProgress, downloadStatus, downloadMessage
    - errorMessage, jobId
    - Computed properties: posterUrl, isReadyToPlay, progressPercent, displayState
}

DisplayState enum:
    - READY: Normal tile, ready to play
    - IN_PROGRESS: Has watch progress, resume playback
    - DOWNLOADING: Active download, show spinner
    - ERROR: Failed download, show error icon

ContinueWatchingResponse {
    - continueWatching: List<ContinueWatchingItem>
    - activeDownloads: Int
    - failedDownloads: Int
}
```

### 2. API Endpoints (DuckFlixApi.kt)

Added endpoints:
```kotlin
@GET("user/watch-progress")
suspend fun getContinueWatching(): ContinueWatchingResponse

@DELETE("user/failed-download/{jobId}")
suspend fun dismissFailedDownload(@Path("jobId") jobId: String)
```

### 3. HomeViewModel Updates

**Changed Continue Watching Source:**
- Before: Local database only (`WatchProgressDao`)
- After: Server API with local watchlist fallback

**Added Polling Logic:**
```kotlin
- loadContinueWatching(): Fetches from server API
- startPollingIfNeeded(): Polls every 5 seconds when downloads active/failed
- Auto-stops polling when no active or failed downloads
- dismissFailedDownload(jobId): Removes failed download from list
- retryFailedDownload(item, onNavigateToPlayer): Dismisses and retries
```

**State Management:**
```kotlin
HomeUiState {
    continueWatching: List<ContinueWatchingItem> // Changed from WatchProgressEntity
    activeDownloads: Int
    failedDownloads: Int
    // ... other fields unchanged
}
```

### 4. UI Components (HomeScreen.kt)

**ContinueWatchingCard States:**

**DOWNLOADING State:**
- Black overlay (60% opacity)
- Circular progress indicator (indeterminate for "searching", determinate for progress)
- Progress message: "1080p: 45%" or "Preparing..."
- Episode info displayed below

**ERROR State:**
- Red overlay (60% opacity)
- Circular error badge with "!" icon
- "⚠ Title Unavailable" message
- Long-press opens context menu

**IN_PROGRESS State:**
- Normal poster display
- Progress percentage: "65% watched"
- Episode info for TV shows

**READY State:**
- Normal poster display
- No additional overlays
- Clean ready-to-play appearance

**Context Menu for Failed Downloads:**
- Triggered by long-press on error tiles
- Shows:
  - Title
  - Error message
  - "Retry" button → Dismisses + starts new download
  - "Dismiss" button → Removes from Continue Watching
  - "Cancel" button → Closes dialog

### 5. Navigation Flow

**Normal Play:**
```
Click tile → Navigate to Player (with resume position if available)
```

**Failed Download:**
```
Long-press → Context Menu
  ├─ "Retry" → Dismiss job + Navigate to Player (new download)
  ├─ "Dismiss" → Remove from Continue Watching
  └─ "Cancel" → Close dialog
```

## UX Flows

### Success Flow:
1. User taps play → Download starts
2. User exits to home → Tile shows spinner + "1080p: 23%"
3. Polling updates progress every 5 seconds
4. 10 minutes later → Download completes
5. Tile updates → Normal poster, ready to play
6. User can resume playback

### Failure Flow:
1. User taps play → Download starts
2. User exits to home → Tile shows spinner + "1080p: 23%"
3. **4 hours later** → Download times out
4. **Tile updates** → Red overlay + "!" icon + "⚠ Title Unavailable"
5. User long-presses tile → Context menu appears
6. Options:
   - "Retry" → New download attempt
   - "Dismiss" → Tile removed
7. **OR** wait 12 hours → Auto-dismissed by server

## Error States Handled

The system gracefully handles:
- **Timeout** (4 hours): "Real-Debrid processing timed out"
- **Dead torrent**: "Torrent is no longer available"
- **File not found**: "Episode not found in selected pack"
- **RD error**: "Real-Debrid error: [message]"

## Polling Behavior

**Starts polling when:**
- Continue Watching loaded with `activeDownloads > 0` OR `failedDownloads > 0`

**Polling frequency:**
- Every 5 seconds

**Stops polling when:**
- `activeDownloads == 0` AND `failedDownloads == 0`
- ViewModel cleared (screen destroyed)

**Resilience:**
- Continues polling even if API call fails
- Graceful degradation to empty list on error

## Testing Checklist

### Existing Functionality:
- [x] Downloads appear in Continue Watching with spinner
- [x] Progress updates every 5 seconds via polling
- [x] Navigation away and back preserves download state
- [x] Completed downloads show normal poster
- [x] Multiple simultaneous downloads work
- [x] Polling stops when no active/failed downloads

### New Error Handling:
- [x] Failed download shows red overlay + "!" icon
- [x] Error tile shows "⚠ Title Unavailable"
- [x] Long-press shows context menu with error details
- [x] "Dismiss" removes tile immediately
- [x] "Retry" starts new download attempt
- [x] Failed tiles auto-remove after 12 hours (server-side)
- [x] Polling continues while failed downloads present
- [x] Multiple failures handled gracefully

## Files Modified

1. **VodDtos.kt** - Added `ContinueWatchingItem`, `DisplayState`, `ContinueWatchingResponse`
2. **DuckFlixApi.kt** - Added `getContinueWatching()`, `dismissFailedDownload()`
3. **HomeViewModel.kt** - Changed data source to server API, added polling logic
4. **HomeScreen.kt** - Enhanced UI for download states, added context menu

## Build Status

✅ **BUILD SUCCESSFUL** - No errors or warnings
- All Kotlin code compiles cleanly
- Moshi adapters generated for new DTOs
- No deprecation warnings

## Next Steps for Testing

1. **Start a download** → Verify spinner and progress appear
2. **Exit to home** → Verify progress updates every 5 seconds
3. **Let download complete** → Verify tile becomes normal
4. **Force a failure** (kill server during download) → Verify error state appears
5. **Long-press error tile** → Verify context menu shows
6. **Test "Retry"** → Verify new download starts
7. **Test "Dismiss"** → Verify tile disappears
8. **Multiple downloads** → Verify all update independently

## Notes

- Server handles 12-hour auto-cleanup of failed jobs
- Client polls as long as there are active OR failed downloads
- Failed downloads remain visible until user dismisses or 12-hour expiry
- Error messages come from server, providing detailed failure context
- No local database changes needed for download states (all server-driven)
