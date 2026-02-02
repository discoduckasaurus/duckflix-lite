# DuckFlix Lite - Project State Tracker

**Last Updated:** 2026-02-02

## Current Milestone
Successfully achieved sourcing and playback for titles! Now building out TV show infrastructure, watchlist, and auto-resume features.

## Environment
- **Dev Machine:** Mac with mounted server access
- **Server:** 192.168.4.66 (user: ducky, SSH key: claude, sudo: duckadmin)
- **Primary Project:** ~/duckflix (shares services with lite version)
- **Content Pipeline:** Server → Client (stable, must preserve)

## Architecture Overview
```
Android TV Client (Kotlin + Compose)
    ↓ HTTPS REST API
Node.js Server (Express + SQLite)
    ↓ HTTP/Docker
Shared Services (Zurg, Prowlarr, TMDB)
```

## Critical Best Practices

### Gradle Builds
- Builds have tripped up before
- Fix: Use fallbacks for service compatibility
- Always test build after dependency changes

### Service Integration
- Some services shared with ~/duckflix primary project
- Check service state before making changes
- Preserve stable content server-to-client pipeline

### Development Workflow
- Avoid UI polish until feature complete
- Focus on functionality first
- Test on actual Android TV if possible

## Feature Status Matrix

| Feature | Status | Implementation Notes |
|---------|--------|---------------------|
| **Search (Movies/TV)** | ✅ Complete | Parallel search, TMDB integration, recent searches, Zurg check |
| **Detail Page (Movies)** | ✅ Complete | Cast carousel, genres, play button with availability check |
| **Detail Page (TV Shows)** | 🟡 Needs Seasons/Episodes | Server endpoint exists (search.js:147-188), needs UI & ViewModel |
| **Video Playback** | ✅ Complete | ExoPlayer, buffering optimization, RD/Zurg streaming |
| **Watch History Tracking** | ✅ Complete | 30s save interval, 90% auto-complete, Room DB persistence |
| **Continue Watching** | ✅ Complete | Homepage section showing in-progress titles |
| **Watchlist** | ✅ Complete | Homepage carousel, heart toggle, auto-removal on completion |
| **Auto-Resume UI** | ✅ Complete | Resume/Restart buttons on movie detail pages, seeks to saved position |
| **Poster Rendering** | ⚠️ Works but Needs Review | No placeholders/error handling, ContentScale not explicit |
| **Live TV** | 🔴 Not Started | EPG/M3U sync exists, UI missing |
| **DVR** | 🔴 Not Started | Planned feature |

## Current Tasks (In Priority Order)

1. ✅ **Search handling** - Complete!
2. ✅ **Title pages for TV shows** - Complete! Seasons/episodes with collapsible UI
3. ✅ **Watchlist with auto-removal** - Complete! Homepage carousel, toggle, auto-removal
4. ✅ **Auto-resume functionality** - Complete! Resume/Restart buttons working
5. ✅ **Poster verification** - Complete! Added ContentScale.Crop for proper rendering

## Implementation Dependencies

### Task 2 (TV Show Title Pages)
- **Server:** Season endpoint at `/api/search/tmdb/season/:showId/:seasonNumber` (exists but unused)
- **Client DTOs:** Need Season/Episode data classes in TmdbDtos.kt
- **ViewModel:** Extend DetailViewModel to fetch seasons when type=tv
- **UI:** Add collapsible season sections with episode grids to DetailScreen.kt

### Task 3 (Watchlist)
**Database Layer:**
```kotlin
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val tmdbId: Int,
    val title: String,
    val type: String,
    val posterUrl: String?,
    val addedAt: Long
)
```

**DAO Methods:**
- `getAll(): Flow<List<WatchlistEntity>>`
- `add(item: WatchlistEntity)`
- `remove(item: WatchlistEntity)`
- `isInWatchlist(tmdbId: Int): Boolean`

**Auto-Removal Logic:**
- Listen to WatchProgress updates
- Remove movies when isCompleted=true (≥90% watched)
- TV shows: Remove on manual delete OR series completion

**UI Integration:**
- Homepage: New watchlist carousel
- Search cards: Heart icon toggle
- Detail screen: Add to watchlist button
- Long-press: Delete confirmation dialog

### Task 4 (Auto-Resume)
**What Exists:**
- WatchProgressEntity saves position/duration every 30s
- WatchProgressDao.getProgress(tmdbId) returns last position
- Continue Watching section on homepage

**What's Missing:**
- Resume button on detail screen (when progress exists)
- Restart button on detail screen
- For TV: "Resume Season X Episode Y" with episode progress
- Navigation to correct episode for TV shows

**Implementation:**
- DetailViewModel: Check for existing progress on load
- DetailScreen UI: Show Resume (primary) + Restart (secondary) when progress exists
- Pass `resumePosition` to VideoPlayerScreen via nav args
- For TV: Pass season/episode to player

### Task 5 (Poster Verification)
**Current Implementation:**
- TMDB URLs: `https://image.tmdb.org/t/p/w500{posterPath}`
- Coil AsyncImage with fixed dimensions
- No placeholder/error images
- No ContentScale specified

**Test Requirements:**
1. Posters populate (network request succeeds)
2. Posters fill rounded tiles correctly (no stretching/gaps)
3. Missing posters show error state (not blank)

**Improvements Needed:**
```kotlin
AsyncImage(
    model = posterUrl,
    contentScale = ContentScale.Crop,
    placeholder = painterResource(R.drawable.poster_placeholder),
    error = painterResource(R.drawable.poster_error)
)
```

## Database Schema

### Room (Client-Side)
- **watch_progress:** position, duration, isCompleted, lastWatchedAt
- **recent_searches:** tmdbId, title, type, searchedAt
- **watchlist:** (planned) tmdbId, title, type, addedAt

### SQLite (Server-Side)
- **users:** auth, RD API keys, parent/sub-accounts
- **user_sessions:** IP tracking for concurrent stream prevention
- **rd_link_cache:** 48hr TTL for stream URLs
- **epg_cache:** Live TV guide data

## API Endpoints

### TMDB (via Server Proxy)
- `GET /api/search/tmdb?query={}&type={movie|tv|multi}` - Search
- `GET /api/search/tmdb/{id}?type={movie|tv}` - Details
- `GET /api/search/tmdb/season/{showId}/{seasonNumber}` - Season data (exists, unused)

### VOD Streaming
- `POST /api/vod/session/check` - IP validation
- `POST /api/vod/stream-url/start` - Initiate stream
- `GET /api/vod/stream-url/progress/{jobId}` - Poll download
- `POST /api/vod/session/heartbeat` - Keep-alive (30s interval)

### Content Discovery
- `GET /api/search/zurg` - Check Zurg mount
- `GET /api/search/prowlarr` - Torrent search (fallback)

## Known Issues

1. **Gradle builds can fail** - Use service compatibility fallbacks
2. **Missing poster error handling** - No placeholder/error images
3. **TV show detail pages incomplete** - Need season/episode UI
4. **No watchlist feature** - Full implementation needed
5. **Auto-resume UI missing** - Progress tracked but no buttons

## Key Files Reference

### Android App
- **SearchViewModel.kt** - Search logic, parallel queries
- **SearchScreen.kt** - Search UI, 5-column grid
- **DetailViewModel.kt** - Title page logic, Zurg checks
- **DetailScreen.kt** - Title page UI, cast carousel
- **VideoPlayerViewModel.kt** - ExoPlayer, progress tracking
- **DuckFlixDatabase.kt** - Room database schema
- **Daos.kt** - Database access methods
- **TmdbDtos.kt** - Data transfer objects for TMDB

### Server
- **routes/search.js** - TMDB proxy, caching (5min TTL)
- **routes/vod.js** - VOD session management, stream URLs
- **services/rd-cache-service.js** - Stream URL caching (48hr)
- **db/init.js** - SQLite schema initialization

### Shared Services
- **@duckflix/zurg-client** - Zurg WebDAV integration
- **@duckflix/rd-client** - Real-Debrid API client
- **@duckflix/prowlarr-service** - Torrent search
- **@duckflix/m3u-parser** - Live TV stream parsing
- **@duckflix/epg-parser** - TV guide parsing

## Next Steps Priority

Based on user request order and dependencies:
1. ⏭️ Update Task #1 to "complete" (search already works!)
2. Start Task #2 (TV show title pages) - foundational for Task #4
3. Implement Task #4 (Auto-resume) - uses Task #2's episode navigation
4. Implement Task #3 (Watchlist) - independent feature
5. Execute Task #5 (Poster verification) - quick validation

## Notes
- Server runs on port 3001 (HTTPS with self-signed cert)
- Android app connects via local network IP
- ExoPlayer uses separate OkHttpClient (no auth, handles RD redirects)
- TMDB API key stored server-side only (never sent to client)
- JWT tokens expire after 7 days
- VOD sessions enforce 1 IP per account (2min timeout)
