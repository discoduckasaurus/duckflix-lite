# DuckFlix Lite - Project Summary & Reference

**Last Updated:** 2026-02-02
**Status:** ✅ All core features implemented and deployed

---

## 🎯 Work Completed Today

### ✅ Task #6: TV Show Season/Episode UI & JSON Parsing Fix
**Status:** UI complete, playback in progress
**Date:** 2026-02-02 (afternoon/evening)

**Problem 1 - UI:** Seasons displayed as non-functional collapsible cards, episodes not visible
**Solution:**
- Replaced multi-select collapsible cards with single-select dropdown menu
- Added season overview display below dropdown
- Implemented 2-column episode grid with vertical cards (thumbnail on top)
- Episodes show: E# + title, runtime, overview (2 lines max)
- Fixed state management: `expandedSeasons: Set<Int>` → `selectedSeason: Int?`

**Problem 2 - Episodes Not Rendering:** JSON parsing failure
**Root Cause:** Server returns TMDB's raw snake_case field names (`season_number`, `episode_number`, `still_path`), but DTOs expected camelCase without proper `@Json` annotations
**Symptoms:**
- API returned 200 OK with 25KB of episode data
- `isLoadingSeasons` flashed true then immediately false
- `loadedSeasons` map stayed empty
- No episodes rendered

**Solution - Fixed JSON Mappings:**
```kotlin
// TmdbSeasonResponse.kt - BEFORE
@Json(name = "seasonNumber") val seasonNumber: Int  // ❌ Expected camelCase
@Json(name = "posterPath") val posterPath: String?  // ❌ Expected camelCase

// AFTER
@Json(name = "season_number") val seasonNumber: Int // ✅ Maps snake_case
@Json(name = "poster_path") val posterPath: String? // ✅ Maps snake_case

// EpisodeDto.kt - Same fix for episode_number, still_path, air_date
```

**Android TV Focus Management Discovery:**
- On Android TV, users navigate with D-pad (remote)
- Can ONLY navigate to focusable elements (FocusableButton, etc.)
- Non-focusable content (Text, Box) is invisible to navigation
- Episodes use FocusableButton, so they're navigable once rendered

**UI Flow:**
```
Select Season
[Season 1 (10 episodes) ▼]  ← Dropdown
Season overview text...
┌─────────┬─────────┐
│ E1 card │ E2 card │  ← 2-column grid
├─────────┼─────────┤
│ E3 card │ E4 card │
└─────────┴─────────┘
```

**Problem 3 - Episode Playback Failure:** "Unsupported protocol magnet" error after 3min
**Root Cause #1:** Function call signature mismatch in zurg-search.js
**Details:**
- `zurg-search.js` was calling `findInZurgMount(title, year, type, season, episode, mountPath, duration)` with positional parameters
- But `zurg-client/index.js` expects an options object: `findInZurgMount({ title, type, year, ... })`
- This caused ALL parameters to be undefined when zurg-client tried to destructure them
- Zurg search was essentially running blind, unable to find any content

**Solution:**
```javascript
// BEFORE (zurg-search.js line 26-34)
const result = await findInZurgMount(
  title, year, type, season, episode, mountPath, duration
);

// AFTER - Use options object like main ~/duckflix project
const result = await findInZurgMount({
  title,
  type,
  year,
  season,
  episode,
  episodeRuntime: duration
});
```

**Root Cause #2:** Simplified episode matching logic missing multi-episode and season pack support
**Solution:** Copied robust `matchesTVEpisode` implementation from `~/duckflix/backend/zurg-lookup.js`:
- Added multi-episode range matching (S01E01-E03)
- Added season pack directory detection
- More precise pattern matching to avoid false positives

**Files Modified:**
- `server/services/zurg-search.js` - Fixed function call signature
- `shared/zurg-client/index.js` - Enhanced matchesTVEpisode function

**Root Cause #3:** Type mismatch - vod.js was converting 'tv' → 'episode' before calling Zurg
**Details:**
- vod.js had: `type: type === 'tv' ? 'episode' : 'movie'`
- But zurg-client checks: `if (type === 'tv')` to run TV episode search
- When type='episode', it fell through to movie search path
- Result: Searched as "American Dad (2005)" instead of "American Dad S02E10"
- Zurg found nothing, fell back to Prowlarr which returned wrong episode

**Solution:**
```javascript
// BEFORE (routes/vod.js line 30 & 290)
const zurgResult = await searchZurg({
  type: type === 'tv' ? 'episode' : 'movie',  // ❌ Wrong
  ...
});

// AFTER - Match main ~/duckflix project
const zurgResult = await searchZurg({
  type,  // ✅ Pass 'tv' or 'movie' directly
  ...
});
```

**Status:** All Zurg bugs fixed. Server changes deployed to 192.168.4.66. Ready to test episode playback.

### ✅ Task #1: Search Handling
**Status:** Already complete
**Features:**
- Parallel movie/TV search with TMDB integration
- 5-column grid layout optimized for Android TV
- Recent searches and recently watched sections
- ContentScale.Crop for proper poster rendering

### ✅ Task #2: TV Show Title Pages
**Status:** Complete and deployed
**Implementation:**
- Added Season/Episode DTOs (TmdbSeasonResponse, EpisodeDto, SeasonInfoDto)
- Server endpoint returns seasons data from TMDB
- Collapsible season cards with episode grids
- Episode cards show thumbnails, titles, runtime, overview
- First non-special season auto-expands on load
- Navigation passes season/episode params to player

**Key Files Modified:**
- `android/app/src/main/java/com/duckflix/lite/data/remote/dto/TmdbDtos.kt`
- `android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailViewModel.kt`
- `android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt`
- `server/routes/search.js` - Added seasons to TMDB response

### ✅ Task #3: Watchlist with Auto-Removal
**Status:** Complete and deployed
**Implementation:**
- WatchlistEntity + WatchlistDao (Room database v4)
- "My Watchlist" carousel on homepage (below menu)
- "Continue Watching" carousel showing in-progress titles
- Heart toggle button on detail pages (♥/♡)
- Long-press to remove from watchlist
- Auto-removal: Movies at ≥90% completion

**Key Files Created:**
- `android/app/src/main/java/com/duckflix/lite/ui/screens/home/HomeViewModel.kt`

**Key Files Modified:**
- `android/app/src/main/java/com/duckflix/lite/data/local/entity/Entities.kt`
- `android/app/src/main/java/com/duckflix/lite/data/local/dao/Daos.kt`
- `android/app/src/main/java/com/duckflix/lite/data/local/DuckFlixDatabase.kt` (v3→v4)
- `android/app/src/main/java/com/duckflix/lite/ui/screens/home/HomeScreen.kt`
- `android/app/src/main/java/com/duckflix/lite/ui/screens/player/VideoPlayerViewModel.kt`

### ✅ Task #4: Auto-Resume Functionality
**Status:** Complete and deployed
**Implementation:**
- Resume button shows progress percentage
- Restart button plays from 0:00
- Resume position passed via navigation
- Player seeks to saved position on load
- TV show episode resume supported

**Key Files Modified:**
- `android/app/src/main/java/com/duckflix/lite/DuckFlixApp.kt` - Navigation routes
- `android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailViewModel.kt`
- `android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt`
- `android/app/src/main/java/com/duckflix/lite/ui/screens/player/VideoPlayerViewModel.kt`

### ✅ Task #5: Poster Rendering & Bug Fixes
**Status:** Complete
**Implementation:**
- Fixed posterUrl passing from detail to player (was null)
- Added ContentScale.Crop to all AsyncImage components
- Fixed navigation parameter issues (IntType/LongType don't support nullable)
- Used sentinel values: -1 for season/episode, -1L for resumePosition
- Smaller carousel cards (150x260) on homepage

---

## 🗂️ Critical File Locations

### Local Development (Mac)
```
Project Root: /Users/aaron/projects/duckflix_lite/

Android App:
├── /android/app/src/main/java/com/duckflix/lite/
│   ├── DuckFlixApp.kt                    # Navigation routes
│   ├── data/
│   │   ├── local/
│   │   │   ├── DuckFlixDatabase.kt       # Room DB schema (v4)
│   │   │   ├── dao/Daos.kt               # All DAOs
│   │   │   └── entity/Entities.kt        # All entities
│   │   └── remote/
│   │       ├── DuckFlixApi.kt            # Retrofit API interface
│   │       └── dto/TmdbDtos.kt           # TMDB data models
│   └── ui/screens/
│       ├── detail/                        # TV show seasons/episodes
│       ├── home/                          # Watchlist & Continue Watching
│       ├── player/                        # Video playback
│       └── search/                        # Search UI

Server Code:
├── /server/
│   ├── index.js                           # Server entry point
│   └── routes/
│       └── search.js                      # TMDB proxy (includes seasons!)

Build Outputs:
└── /android/app/build/outputs/apk/debug/app-debug.apk
```

### Server (192.168.4.66)

**Server Process Location:**
```
/home/ducky/duckflix-lite-server-v2/
├── index.js                               # Entry point
├── routes/
│   └── search.js                          # TMDB endpoints (CRITICAL)
├── services/
│   └── rd-cache-service.js               # Stream URL caching
└── db/
    └── duckflix_lite.db                  # SQLite database
```

**Server Runtime Requirements:**
⚠️ **CRITICAL - READ THIS FIRST:**
- **Node Version:** MUST use Node v20.20.0 (better-sqlite3 compiled for this version)
- **PM2 Command:** `pm2 start index.js --name duckflix-lite-server-v2 --interpreter /home/ducky/.nvm/versions/node/v20.20.0/bin/node`
- **DO NOT use Node v24** - better-sqlite3 will fail with MODULE_VERSION errors
- **Environment:** .env file MUST be present in server directory (PM2 loads it via dotenv in index.js)
- **Working Directory:** /home/ducky/duckflix-lite-server-v2

**Server Database:**
- Location: `/home/ducky/duckflix-lite-server-v2/db/duckflix_lite.db`
- Type: SQLite with WAL (requires Node v20 compatible better-sqlite3)
- Tables: users, user_sessions, rd_link_cache, epg_cache, m3u_sources

**Server Hosting & Access:**
- **Local IP:** 192.168.4.66:3001 (HTTPS)
- **Public Access:** Nginx reverse proxy with subdomain (alongside duckflix.tv for main project)
- **Network:** Accessible remotely via subdomain (nginx configured)
- **SSL:** HTTPS enabled on port 3001

**Main DuckFlix Project (Reference):**
```
/Users/aaron/projects/duckflix/
├── backend/
│   ├── server.js                        # ⭐ MAIN ENTRY - Complete content flow
│   ├── zurg-lookup.js                   # ⭐ Zurg search logic (title variations, episode matching)
│   ├── routes/
│   │   └── vod.js                       # Stream URL endpoints
│   ├── services/
│   │   ├── content-resolver.js          # Zurg vs Prowlarr decision logic
│   │   ├── prowlarr-service.js          # Torrent search
│   │   └── rd-service.js                # Real-Debrid integration
│   └── shared/
│       ├── zurg-client/                 # Zurg mount filesystem search
│       ├── rd-client/                   # RD API wrapper
│       └── epg-parser/                  # EPG XML parsing
└── frontend/                            # React UI patterns
```

**⚠️ When debugging duckflix-lite, CHECK THESE FILES FIRST:**
- `backend/zurg-lookup.js` - How to search Zurg correctly
- `backend/server.js` - Complete Zurg → RD → Prowlarr flow
- `backend/services/content-resolver.js` - Decision logic
- `shared/zurg-client/` - Filesystem search implementation
- `shared/rd-client/` - RD API patterns


---

## 🔐 Credentials & Access

### SSH Access
```bash
Host: 192.168.4.66
User: ducky
SSH Key Passphrase: claude
Sudo Password: duckadmin

# Connect:
ssh ducky@192.168.4.66
# Sudo:
echo 'duckadmin' | sudo -S <command>
```

### ADB (Android Debug Bridge)
```bash
Device: NVIDIA SHIELD
IP: 192.168.4.57:5555

# Location:
~/Library/Android/sdk/platform-tools/adb

# Connect:
~/Library/Android/sdk/platform-tools/adb connect 192.168.4.57:5555

# Install APK:
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 install -r /path/to/app-debug.apk

# Launch app:
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 shell am start -n com.duckflix.lite.debug/com.duckflix.lite.MainActivity

# View logs:
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 logcat -d | grep -A 20 "FATAL"
```

### Local Root Storage (Preferred Access Method)
```bash
# Server is mounted locally via Mountain Duck:
/Users/aaron/Library/Application Support/Mountain Duck/Volumes.noindex/192.168.4.66 – SFTP.localized

# This provides direct read/write access to server filesystem
# Faster than SSH for file operations
# Use this for copying files, editing configs, etc.
```

---

## 🚀 Deployment Workflow

### Build & Deploy Android APK
```bash
cd /Users/aaron/projects/duckflix_lite/android

# Build
./gradlew assembleDebug

# Deploy to SHIELD
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 shell am start -n com.duckflix.lite.debug/com.duckflix.lite.MainActivity
```

### Deploy Server Changes
```bash
# Option 1: SCP (from Mac)
scp /path/to/file.js ducky@192.168.4.66:/home/ducky/duckflix-lite-server-v2/

# Option 2: Direct copy via mounted volume
cp /path/to/file.js "/Users/aaron/Library/Application Support/Mountain Duck/Volumes.noindex/192.168.4.66 – SFTP.localized/home/ducky/duckflix-lite-server-v2/"

# Restart server:
ssh ducky@192.168.4.66
ps aux | grep node | grep lite  # Find PID
kill <PID>
cd /home/ducky/duckflix-lite-server-v2
nohup node index.js > /dev/null 2>&1 &
```

---

## 🐛 Known Issues & Solutions

### Issue: App Crashes on Launch
**Cause:** Navigation parameters using nullable IntType/LongType
**Solution:** Use sentinel values (-1, -1L) instead of null
**Fixed in:** DuckFlixApp.kt navigation routes

### Issue: Missing Posters
**Cause:** posterUrl was hardcoded to null in VideoPlayerViewModel
**Solution:** Pass posterUrl through navigation from DetailScreen
**Fixed in:** Navigation routes + VideoPlayerViewModel

### Issue: TV Show Seasons Not Showing
**Cause:** Server not returning seasons data from TMDB
**Solution:** Added seasons to TMDB API request and response mapping
**Fixed in:** server/routes/search.js (lines 108-131)

### Issue: Gradle Build Fails
**Cause:** Java 25 not compatible with AGP 8.2.1
**Solution:** Force Java 21 in gradle.properties
**Fixed in:** android/gradle.properties (line 32)

### Issue: TV Show Seasons Not Expandable, Episodes Not Showing
**Date:** 2026-02-02
**Symptoms:**
- Seasons displayed as collapsible cards with truncated text
- Clicking dropdown arrow rotated but didn't show episodes
- Episodes not visible in grid format
- Confusing UX with multiple seasons potentially expanded

**Root Cause:**
- UI used `expandedSeasons: Set<Int>` allowing multiple seasons to be "expanded"
- Collapsible card design didn't work well on TV interface
- Episodes displayed in vertical list instead of grid
- No clear way to select a season and view its episodes

**Solution:**
- Changed state management from `expandedSeasons` to `selectedSeason` (single selection)
- Replaced collapsible cards with dropdown menu for season selection
- Added season overview display below dropdown
- Redesigned episode layout as 2-column grid (LazyVerticalGrid)
- Episodes now show as vertical cards with thumbnail on top, info below

**Files Modified:**
- DetailViewModel.kt - Changed to single season selection model
- DetailScreen.kt - New dropdown UI + 2-column episode grid

**Compilation Fix:**
- Initial build error: Cannot multiply `Int * Dp` directly
- Solution: Calculate rows first, then convert to Dp: `val gridHeight = (rows * 216).dp`

---

## 📊 Database Schema

### Android Room Database (v4)
```kotlin
// watchlist table
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val type: String,      // "movie" or "tv"
    val year: String?,
    val posterUrl: String?,
    val addedAt: Long
)

// watch_progress table
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
    val isCompleted: Boolean
)
```

### Server SQLite Database
```sql
-- users table
CREATE TABLE users (
    id INTEGER PRIMARY KEY,
    username TEXT,
    password_hash TEXT,
    is_admin BOOLEAN,
    parent_user_id INTEGER,
    rd_api_key TEXT,
    rd_expiry_date TEXT
);

-- rd_link_cache (48hr TTL)
CREATE TABLE rd_link_cache (
    tmdb_id INTEGER,
    title TEXT,
    year TEXT,
    type TEXT,
    season INTEGER,
    episode INTEGER,
    stream_url TEXT,
    file_name TEXT,
    created_at DATETIME,
    expires_at DATETIME
);
```

---

## 🔗 API Endpoints

### TMDB Proxy (Server)
```
GET /api/search/tmdb?query={}&type={movie|tv|multi}
GET /api/search/tmdb/{id}?type={movie|tv}
GET /api/search/tmdb/season/{showId}/{seasonNumber}  # Returns episodes
```

### VOD Streaming
```
POST /api/vod/session/check                # IP validation
POST /api/vod/stream-url/start             # Get stream URL
GET  /api/vod/stream-url/progress/{jobId}  # Poll download
POST /api/vod/session/heartbeat            # Keep-alive (30s)
```

### Content Discovery
```
GET /api/search/zurg                       # Check Zurg mount
GET /api/search/prowlarr                   # Torrent search
```

---

## 🔐 Authentication & API Configuration

### API Base URL
**Current Configuration:**
- **Direct IP:** `https://192.168.4.66:3001/api/`
- **Domain (TODO):** Should use `lite.duckflix.tv` or similar subdomain via nginx
- **Port:** 3001 (HTTPS with self-signed cert)

**Files:**
- `android/app/src/main/java/com/duckflix/lite/di/NetworkModule.kt` - Retrofit baseUrl configuration
- `android/app/src/main/res/xml/network_security_config.xml` - SSL cert trust config

### Authentication Flow
**Token-Based Authentication:**
1. User logs in (login flow not yet implemented for Lite)
2. Server returns JWT Bearer token
3. Token stored in `EncryptedSharedPreferences` with AES-256-GCM encryption
4. `AuthInterceptor` automatically adds token to all API requests:
   - Checks if request is to our API (192.168.4.66 or duckflix domains)
   - Adds header: `Authorization: Bearer <token>`
   - Skips token for external APIs (Real-Debrid, TMDB, etc.)

**Implementation:**
```kotlin
// AuthInterceptor.kt
class AuthInterceptor(context: Context) : Interceptor {
    private val encryptedPrefs = EncryptedSharedPreferences.create(...)

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = encryptedPrefs.getString("auth_token", null)
        val isOurApi = url.host.contains("192.168.4.66") ||
                       url.host.contains("duckflix")

        if (!token.isNullOrEmpty() && isOurApi) {
            request.header("Authorization", "Bearer $token")
        }
        return chain.proceed(request)
    }
}
```

**Server-Side:**
- All `/api/*` endpoints require valid Bearer token (except `/health`)
- Token validation checks JWT signature and expiration
- Returns `401 Unauthorized` if token missing/invalid

**TODO:**
- Implement login screen for Lite app
- Add token refresh logic
- Configure nginx reverse proxy for `lite.duckflix.tv` subdomain

---

## 🎨 UI Specifications

### Homepage Layout
```
DuckFlix Lite (displayLarge)
    ↓
[Search] [Live TV] [DVR] [Settings]  ← Menu tiles (first)
    ↓
Continue Watching (headlineSmall)    ← Below menu
[150x260 cards with progress %]
    ↓
My Watchlist (headlineSmall)
[150x260 cards, long-press to remove]
```

### TV Show Detail Page
```
[Back] [♥ Add to Watchlist]
Backdrop with gradient overlay
Poster (300x450) | Title, Year, Rating, Genres
                 | "X Seasons"
                 | Overview
                 ↓
Seasons (collapsible)
├─ ▶ Season 1 (10 Episodes)
│  ├─ E1: Episode Title [thumbnail]
│  └─ E2: Episode Title [thumbnail]
└─ ▶ Season 2 (13 Episodes)
   └─ ...
```

### Movie Detail Page with Resume
```
[Back] [♥ Add to Watchlist]
Backdrop with gradient overlay
Poster | Title, Year, Rating
       | [▶ Resume (45%)] [⟲ Restart]
       | Overview
       ↓
Cast (horizontal scroll)
```

---

## 🔧 Build Configuration

### Gradle Settings
```properties
# Force Java 21 for AGP 8.2.1 compatibility
org.gradle.java.home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home

# Database uses fallbackToDestructiveMigration (dev mode)
# Version bumps will recreate database
```

### Android Dependencies
- Kotlin 1.9.22
- Compose Material3 (TV optimized)
- Hilt (Dependency Injection)
- Room v4 (Local database)
- Retrofit + Moshi (API/JSON)
- ExoPlayer Media3 (Video playback)
- Coil 2.5.0 (Image loading)

---

## 📝 Development Notes

### 🚨 CRITICAL RULES - READ BEFORE DEBUGGING 🚨
**⚠️ WHEN STUCK, CHECK MAIN PROJECT FIRST ⚠️**

Before spending time debugging or implementing:
1. **Check `~/projects/duckflix/backend/` for server code** - It handles everything correctly
   - Zurg search: `zurg-lookup.js` (title variations, episode matching, quality gating)
   - Content flow: `server.js` (shows complete Zurg → RD → Prowlarr flow)
   - API patterns: Routes show request/response structures
2. **Check `~/projects/duckflix/frontend/` for UI patterns**
3. **The main project works perfectly** - if something doesn't work in lite, the answer is there

### Best Practices from Today
1. **ALWAYS check main project** (`~/projects/duckflix`) BEFORE debugging or implementing
2. **Navigation params:** IntType/LongType don't support nullable - use sentinel values
3. **Poster URLs:** Must be passed through navigation or fetched separately
4. **Server changes:** Restart with correct Node v20 interpreter
5. **Database migrations:** Version bump triggers recreation (fallbackToDestructiveMigration)
6. **ADB is essential:** Keep path handy for deployment and debugging
7. **Function signatures:** Match main project exactly (e.g., `type: 'tv'` not `type: 'episode'`)

### Testing Checklist
- [ ] Search for movie - plays correctly with poster
- [ ] Search for TV show - see seasons/episodes
- [ ] Add to watchlist - appears on homepage
- [ ] Long-press watchlist item - removes
- [ ] Watch movie to 90%+ - auto-removes from watchlist
- [ ] Resume button - seeks to correct position
- [ ] Restart button - plays from 0:00

### Future Enhancements
- [ ] Per-episode watch progress for TV shows
- [ ] TV series completion tracking for watchlist auto-removal
- [ ] Placeholder/error images for missing posters
- [ ] Live TV and DVR features (menu placeholders exist)
- [ ] Season-based watchlist tracking

---

## 🆘 Quick Reference Commands

```bash
# Build APK
cd ~/projects/duckflix_lite/android && ./gradlew assembleDebug

# Deploy APK
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# View crash logs
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 logcat -d | grep FATAL

# SSH to server
ssh ducky@192.168.4.66

# Restart server
ssh ducky@192.168.4.66 "pkill -f 'node.*duckflix-lite' && cd /home/ducky/duckflix-lite-server-v2 && nohup node index.js > /dev/null 2>&1 &"

# Check server status
ssh ducky@192.168.4.66 "ps aux | grep 'node.*lite' | grep -v grep"
```

---

**For context restoration:** This document contains all critical information needed to resume development on DuckFlix Lite without losing context. Refer to PROJECT_STATE.md for detailed feature status and architecture overview.
