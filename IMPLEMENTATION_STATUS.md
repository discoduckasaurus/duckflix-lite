# DuckFlix Lite - Implementation Status

**Last Updated**: 2026-02-02

## Recent Milestone
✅ Successful video sourcing and playback for titles

## Architecture Summary
- **Language**: Kotlin (100%)
- **UI**: Jetpack Compose for TV
- **Architecture**: MVVM + Clean Architecture
- **Database**: Room (SQLite)
- **Networking**: Retrofit + OkHttp + Moshi
- **Video**: Media3 (ExoPlayer)
- **DI**: Hilt

## Server Infrastructure
- **Server**: 192.168.4.66:3001
- **SSH**: User: ducky, Passphrase: claude, Sudo: duckadmin
- **Primary Project**: ~/duckflix (may share services)
- **Stable**: Content server to client pipeline ✅

## Feature Implementation Tracker

### 1. Search Handling (Task #6) - ✅ MOSTLY COMPLETE
**Status**: Search works for both movies and TV shows in parallel
**What Exists**:
- Dual movie/TV search with async/await
- Recent searches persisted to database
- Recently watched section
- 5-column grid (TV-optimized)
- FocusableCard with D-pad navigation

**Needs**:
- [ ] Debounced input (optimization)
- [ ] Watchlist heart toggle on cards (depends on #8)

### 2. Title Page - Seasons/Episodes/Specials (Task #7) - 🔴 NOT STARTED
**Status**: Basic detail page exists, needs TV show structure
**What Exists**:
- DetailScreen shows: backdrop, poster, metadata, cast, overview, play button
- DetailViewModel fetches TMDB details
- Zurg availability check

**Needs**:
- [ ] Extend API for season data: `/tv/{id}/season/{season_number}`
- [ ] Extend API for episode data: `/tv/{id}/season/{season_number}/episode/{episode_number}`
- [ ] Add Season DTOs to TmdbDtos.kt
- [ ] Create season list UI in DetailScreen (collapsible sections)
- [ ] Create episode grid UI
- [ ] Handle specials (season 0)
- [ ] Navigate to episode detail on selection
- [ ] Create EpisodeDetailScreen or enhance DetailScreen for episodes

**Files to Modify**:
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/remote/DuckFlixApi.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/remote/dto/TmdbDtos.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailViewModel.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt`

### 3. Per-User Watchlist (Task #8) - 🔴 NOT STARTED
**Status**: No watchlist infrastructure exists
**Requirements**:
- Homepage watchlist section
- Long-press delete mechanism
- Auto-remove movies after full watch (>=90% progress)
- Auto-remove TV shows after series completion or manual removal
- Optional: Season-based watchlist

**Needs**:
- [ ] Create WatchlistEntity (tmdbId, title, type, posterUrl, addedAt)
- [ ] Create WatchlistDao (getAll, add, remove, isInWatchlist)
- [ ] Add watchlist table to DuckFlixDatabase schema (bump version to 4)
- [ ] Create WatchlistViewModel
- [ ] Add heart toggle button to DetailScreen
- [ ] Add watchlist section to HomeScreen
- [ ] Add heart icon overlay to search cards
- [ ] Implement long-press delete (ComposeTVMaterial gestures)
- [ ] Auto-remove logic: listen to WatchProgress updates, remove from watchlist if completed

**Files to Create/Modify**:
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/local/entity/Entities.kt` (add WatchlistEntity)
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/local/dao/Daos.kt` (add WatchlistDao)
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/local/DuckFlixDatabase.kt` (add table, bump version)
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/home/HomeScreen.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/search/SearchScreen.kt`

### 4. Auto-Resume Functionality (Task #9) - 🟡 PARTIALLY COMPLETE
**Status**: Watch progress is saved, resume logic needs implementation
**What Exists**:
- WatchProgressEntity with position/duration/isCompleted
- VideoPlayerViewModel saves progress every 30s and on exit
- WatchProgressDao queries for continue watching

**Needs**:
- [ ] Read saved position in DetailViewModel
- [ ] Show Resume button (with episode info for TV) vs Restart button
- [ ] Pass resume position to VideoPlayerViewModel
- [ ] Seek to saved position after stream loads
- [ ] For TV shows: Track season/episode in WatchProgressEntity (schema change needed)
- [ ] For TV shows: Show "Resume Season X Episode Y" with progress

**Implementation Plan**:
1. Extend WatchProgressEntity to include season/episode (optional, nullable for movies)
2. In DetailScreen: Query watchProgressDao.getProgress(tmdbId)
3. If progress exists and not completed: Show "Resume" button with details
4. Always show "Restart" button
5. Pass resume position to VideoPlayerScreen via navigation args
6. In VideoPlayerViewModel: After stream URL loads, seek to position

**Files to Modify**:
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/local/entity/Entities.kt` (extend WatchProgressEntity)
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/data/local/DuckFlixDatabase.kt` (bump version)
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailViewModel.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/player/VideoPlayerViewModel.kt`
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/DuckFlixApp.kt` (navigation args)

### 5. Poster Image Verification (Task #10) - 🔴 NOT STARTED
**Status**: Need automated testing
**Requirements**:
- Verify poster images populate correctly
- Verify images fill rounded containers properly
- Investigate missing posters (potential errors)

**Approach**:
- Use Playwright or Puppeteer for browser automation
- OR use Android UI testing (Espresso/Compose Test)
- Check if posterUrl is null vs network error vs 404
- Verify Coil image loading errors

**Files to Investigate**:
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/search/SearchScreen.kt` (Coil AsyncImage)
- `/Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt` (Coil AsyncImage)
- TMDB poster URLs: `https://image.tmdb.org/t/p/w500{posterPath}`

## Implementation Order (Recommended)

1. **Auto-Resume** (Task #9) - Builds on existing infrastructure, high user value
2. **Watchlist** (Task #8) - Independent feature, adds homepage content
3. **Title Pages for TV Shows** (Task #7) - Complex, requires TMDB API work and UI changes
4. **Poster Verification** (Task #10) - UI polish, can be done last
5. **Search Debouncing** (Task #6) - Minor optimization

## Database Schema Changes Needed

**Version 4** (for Watchlist + Auto-Resume):
```kotlin
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val type: String,
    val posterUrl: String?,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val type: String,
    val year: String?,
    val posterUrl: String?,
    val position: Long,
    val duration: Long,
    val lastWatchedAt: Long,
    val isCompleted: Boolean = false,
    // NEW FIELDS:
    val seasonNumber: Int? = null,  // For TV shows
    val episodeNumber: Int? = null  // For TV shows
)
```

## Important Notes

### Gradle Build
- **Fallback to destructive migration** enabled (`.fallbackToDestructiveMigration()`)
- If build fails: Check compatibility with primary ~/duckflix project services
- Common issue: Version conflicts in shared dependencies

### API Gateway
- Server at 192.168.4.66:3001 acts as API gateway
- TMDB requests proxied through backend (not direct to TMDB API)
- Zurg availability checked in parallel with TMDB details
- Stream URLs retrieved via `/vod/start-stream` endpoint

### Content Pipeline (STABLE - DO NOT BREAK)
- VOD session management: checkVodSession → startStreamUrl → pollDownloadProgress → play
- ExoPlayer configured with optimized buffers (5-30s)
- Progress saved every 30s and on exit
- Separate OkHttpClient for video (no auth interceptor, handles RD redirects)

### TV Navigation Best Practices
- Always use FocusableCard/FocusableButton for D-pad navigation
- Grid layouts: 5 columns for search results, flexible for detail pages
- Border + surface color change on focus (4.dp border)
- Remember focus state with `MutableInteractionSource`
- Scrollable layouts: Use `rememberScrollState()` and `verticalScroll()`

### TMDB API Patterns
- Movies: Single call to `/search/tmdb/{id}?type=movie`
- TV Shows: Base call + additional calls for seasons/episodes
  - Series: `/search/tmdb/{id}?type=tv`
  - Season: `/search/tmdb/{id}/season/{season_number}`
  - Episode: `/search/tmdb/{id}/season/{season_number}/episode/{episode_number}`
  - Specials: Season 0
- Poster: `https://image.tmdb.org/t/p/w500{posterPath}`
- Backdrop: `https://image.tmdb.org/t/p/w1280{backdropPath}`

## Known Issues / Tech Debt
- [ ] Debounced search input (optimization)
- [ ] Track selection not fully implemented (skeleton exists)
- [ ] Settings screen minimal
- [ ] Live TV screen TODO
- [ ] DVR screen TODO
- [ ] No server-side watchlist sync (local only)
- [ ] No user profile management
- [ ] Error handling could be more granular
- [ ] No retry logic for failed API calls
- [ ] No offline mode

## Next Session Commands
```bash
# Check server status
ssh ducky@192.168.4.66  # Password: claude

# Build APK (from android directory)
cd /Users/aaron/projects/duckflix_lite/android
./gradlew assembleDebug

# Install APK to device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat | grep DuckFlix
```
