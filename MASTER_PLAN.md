# DuckFlix Lite - Master Plan (Native Android)

**Status**: Planning Complete, Ready for Implementation
**Target Timeline**: 8-10 weeks
**Last Updated**: 2026-02-01
**Architecture**: Native Android (Kotlin + Jetpack Compose)

---

## Executive Summary

DuckFlix Lite is a radical simplification of the DuckFlix streaming platform, moving from server-side media handling to client-side streaming and recording. This document serves as the single source of truth for the entire project.

**Key Architectural Shifts**:
- **VOD**: Server downloads → Direct RD streaming with user API keys
- **DVR**: Server recordings → On-device recordings with native Foreground Service
- **Users**: Basic auth → RD key management + IP-based restrictions
- **Server**: Full stack → Minimal auth/admin/config server
- **Client**: Web/Capacitor → **Native Android (Kotlin + Jetpack Compose)**

**Target**: Friends & family streaming stick (Google TV or custom Android TV device)

---

## High-Level Architecture

### Code Structure (Monorepo)
```
duckflix_lite/
├── shared/              # Shared with main build
│   ├── zurg-client/     # Node.js module (server-side)
│   ├── tmdb-client/     # Node.js module (server-side)
│   ├── epg-parser/      # Node.js module (server-side)
│   ├── m3u-parser/      # Node.js module (server-side)
│   └── rd-client/       # Node.js module (server-side)
├── server/              # Minimal Lite server (Express + SQLite)
│   ├── auth/
│   ├── admin/           # Web-based admin panel (React SPA)
│   ├── api/
│   └── static/apk/
├── android/             # Native Android APK (Kotlin + Jetpack Compose)
│   ├── app/
│   │   ├── src/main/java/com/duckflix/lite/
│   │   │   ├── ui/              # Jetpack Compose screens
│   │   │   │   ├── vod/
│   │   │   │   ├── livetv/
│   │   │   │   ├── dvr/
│   │   │   │   └── settings/
│   │   │   ├── data/            # Room database, repositories
│   │   │   ├── network/         # Retrofit APIs
│   │   │   ├── player/          # ExoPlayer integration
│   │   │   ├── service/         # DVR Foreground Service
│   │   │   ├── workers/         # WorkManager tasks
│   │   │   └── viewmodels/
│   │   └── build.gradle.kts
│   └── gradle/
└── docs/
```

### Tech Stack

**Android Client** (Native):
- **Language**: Kotlin
- **UI Framework**: Jetpack Compose for Android TV
- **Video Player**: ExoPlayer (direct HLS/MPEG-TS streaming)
- **Database**: Room (SQLite ORM)
- **HTTP Client**: Retrofit + OkHttp
- **JSON**: Moshi or kotlinx.serialization
- **Dependency Injection**: Hilt (Dagger)
- **Background Work**: WorkManager + Foreground Service
- **Storage**: Android scoped storage + MANAGE_EXTERNAL_STORAGE
- **Build**: Gradle + Android SDK

**Server** (Shared):
- **Runtime**: Node.js + Express
- **Database**: SQLite (better-sqlite3)
- **Auth**: JWT (jsonwebtoken)
- **Deployment**: PM2 or systemd at duckflix.tv/lite_service

**Admin Panel** (Web):
- **Framework**: React + Vite
- **Styling**: Tailwind CSS
- **Hosting**: Static files served by Express

**Shared Services** (Universal):
- **Zurg**: Single Docker container (shared with main DuckFlix)
- **Prowlarr**: Single Docker instance (shared with main DuckFlix)
- **EPG sources**: Server fetches/caches, serves to all clients
- **M3U sources**: Server manages, serves to all clients

### Server Responsibilities (MINIMAL)
- User authentication (JWT)
- User/sub-account management
- RD API key storage & validation
- IP tracking (1 IP per account for VOD)
- EPG/M3U source updates (fetch & serve)
- Zurg search criteria config
- APK hosting
- Web admin panel

### Server Does NOT Do
- Download media (zero server-side files)
- Transcode (no FFmpeg, no GPU)
- Stream video (clients stream from RD)
- Record DVR (100% client-side)

### Client Responsibilities
- Direct RD streaming (user's key)
- Live TV playback (M3U streams)
- DVR recording (local storage, native Foreground Service)
- Local Room database (EPG, schedules, metadata)
- UI/UX (Netflix-like experience for TV)

---

## Database Schema (Server SQLite)

```sql
-- users table
CREATE TABLE users (
  id INTEGER PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  is_admin BOOLEAN DEFAULT 0,
  parent_user_id INTEGER,  -- NULL = parent account, else sub-account
  rd_api_key TEXT,         -- NULL for sub-accounts
  rd_expiry_date TEXT,
  created_at TEXT,
  last_login_at TEXT,
  FOREIGN KEY(parent_user_id) REFERENCES users(id)
);

-- user_sessions table (IP tracking for VOD restriction)
CREATE TABLE user_sessions (
  id INTEGER PRIMARY KEY,
  user_id INTEGER NOT NULL,
  ip_address TEXT,
  last_vod_playback_at TEXT,
  last_heartbeat_at TEXT,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

-- epg_cache table
CREATE TABLE epg_cache (
  channel_id TEXT PRIMARY KEY,
  epg_data TEXT,  -- JSON blob
  updated_at TEXT
);

-- m3u_sources table
CREATE TABLE m3u_sources (
  id INTEGER PRIMARY KEY,
  url TEXT NOT NULL,
  priority INTEGER,
  last_fetched_at TEXT
);
```

---

## Database Schema (Android Room)

```kotlin
// User entity (local cache of authenticated user)
@Entity(tableName = "user")
data class User(
    @PrimaryKey val id: Int,
    val username: String,
    val isAdmin: Boolean,
    val parentUserId: Int?,
    val rdExpiryDate: String?
)

// EPG Program entity
@Entity(tableName = "epg_programs")
data class EpgProgram(
    @PrimaryKey val id: String,
    val channelId: String,
    val title: String,
    val description: String?,
    val startTime: Long,
    val endTime: Long,
    val updatedAt: Long
)

// Channel entity
@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey val id: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val backupStreamUrl: String?,
    val isFavorite: Boolean = false
)

// Recording Schedule entity
@Entity(tableName = "recording_schedules")
data class RecordingSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val channelId: String,
    val programId: String?,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val isRecurring: Boolean = false,
    val recurrencePattern: String? // "DAILY", "WEEKLY_MON", etc.
)

// Recording entity (completed recordings)
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val scheduleId: Int?,
    val channelId: String,
    val title: String,
    val filePath: String,
    val fileSize: Long,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,
    val hasErrors: Boolean = false,
    val errorLog: String?
)

// Playback Progress entity
@Entity(tableName = "playback_progress")
data class PlaybackProgress(
    @PrimaryKey val contentId: String, // TMDB ID or recording ID
    val contentType: String, // "movie", "episode", "recording"
    val position: Long, // milliseconds
    val duration: Long,
    val updatedAt: Long
)
```

---

## VOD Flow (Client-Side Streaming)

1. User searches → Android client calls server `/api/tmdb/search` (Retrofit)
2. User selects title → Client calls `/api/auth/check-vod-session`
3. Server checks:
   - Valid RD key? (not expired)
   - Already playing from different IP? (if yes, BLOCK)
   - Record IP as active
4. Server responds: `{ allowed: true, ip: "1.2.3.4" }`
5. Client searches Zurg or Prowlarr (via server API)
6. Client adds torrent to RD (user's key, direct RD API call via Retrofit)
7. Client gets stream URL from RD (user's key)
8. Client plays stream with **ExoPlayer** (RD → Client, no server)
9. Client sends heartbeat every 30s via WorkManager (keeps IP session alive)
10. On stop: Client calls `/api/auth/vod-session-end`

**IP Restriction Logic**:
- Server tracks "last heartbeat from IP X" per user
- If heartbeat from IP X within last 2 minutes, and new request from IP Y: BLOCK
- Allows multiple devices on same IP (home network)

---

## Live TV/DVR Flow (Client-Side Recording)

**Live TV**:
1. Client fetches EPG + M3U from server (cached in Room database)
2. User selects channel → ExoPlayer plays M3U stream
3. If stream fails → Switch to backup source (seamless ExoPlayer source switching)

**DVR Recording** (Native Android):
1. User schedules recording (EPG-based or manual)
2. Client stores schedule in Room database
3. At recording time:
   - **WorkManager** triggers exact-time work
   - Starts **Foreground Service** (persistent notification)
   - Service uses **MediaMuxer** or embedded FFmpeg for native recording
   - Option A: ExoPlayer → MediaMuxer (pure Kotlin, smaller APK)
   - Option B: FFmpeg binary (more reliable, ~20MB APK increase)
   - Service monitors recording progress
   - If stream fails → Switch to backup source, restart (may have gap)
   - On completion → Save metadata to Room, stop service
4. User plays recording from DVR UI (ExoPlayer with local file)

**Storage**:
- Primary: `/sdcard/Android/data/com.duckflix.lite/files/recordings/` (app-scoped)
- Optional: External SD card with `MANAGE_EXTERNAL_STORAGE` permission
- Minimum: 32GB for ~40 hours HD recordings

---

## Phase Breakdown (8-10 Weeks)

### Phase 0: Research & Setup (2-3 days)
**Goal**: Make informed architectural decisions, set up monorepo

**Tasks**:
- Research Android TV storage (scoped storage vs MANAGE_EXTERNAL_STORAGE)
- Research Foreground Service best practices (wake locks, notifications)
- Research ExoPlayer + MediaMuxer for recording (vs FFmpeg)
- Decide: MediaMuxer (native) vs FFmpeg (binary)
- Decide: Google TV vs. custom Android TV stick
- Initialize monorepo structure
- Extract shared Node.js modules from main build (server-side only)
- Create ARCHITECTURE.md

**Deliverables**:
- docs/ANDROID_STORAGE_RESEARCH.md
- docs/BACKGROUND_RECORDING_RESEARCH.md
- docs/PLATFORM_DECISION.md
- docs/EXOPLAYER_VS_FFMPEG.md
- Monorepo scaffolding
- Shared server modules extracted

---

### Phase 1: Minimal Server (3-5 days)
**Goal**: Auth, user management, IP tracking, EPG/M3U serving

**Tasks**:
- Express server + SQLite
- JWT auth (login, logout, middleware)
- User CRUD API (admin only)
- Sub-account logic
- RD API key management
- IP tracking for VOD (check, heartbeat, end)
- RD expiry alerts (5 days, 1 day, expired)
- EPG/M3U sync (cron jobs, serve as JSON)
- Zurg criteria API
- APK hosting (static files)
- Deploy to duckflix.tv/lite_service

**API Endpoints**:
- POST /api/auth/login
- GET /api/auth/me
- GET /api/auth/check-vod-session
- POST /api/auth/vod-heartbeat
- POST /api/auth/vod-session-end
- GET/POST/PUT/DELETE /api/admin/users
- POST /api/admin/users/:id/rd-key
- GET /api/admin/rd-expiry-alerts
- GET /api/epg (JSON format for Android client)
- GET /api/m3u (JSON format with channel list)
- GET/PUT /api/zurg/search-criteria
- GET /api/apk/latest

**Testing**:
- Unit: Auth middleware, IP tracking logic
- Integration: Login flow, IP blocking scenario
- Manual: Create users, set RD keys, check expiry alerts

**Acceptance**: Server running at duckflix.tv/lite_service, all APIs working

---

### Phase 2: Android Project Setup (5-7 days)
**Goal**: Native Android project, auth, navigation, Room database

**Tasks**:
- Initialize Android Studio project (Android TV template)
- Configure Gradle (Kotlin DSL, Hilt, Room, Retrofit, ExoPlayer)
- Set up Jetpack Compose for TV (leanback theme)
- Create Retrofit API client (server integration)
- Implement JWT storage (EncryptedSharedPreferences)
- Build login/logout flow (Compose UI)
- Set up Room database (DAOs, migrations)
- Implement EPG/M3U sync (server → Room)
- Build navigation (VOD, Live TV, DVR, Settings)
- Build settings screen (RD expiry, logout, storage info)
- Implement D-pad navigation (Compose focus handling)
- Build React admin panel (separate web app)

**Admin Panel Features** (React SPA):
- User management table (add, edit, delete)
- RD key management
- RD expiry alerts dashboard
- EPG/M3U source config

**Testing**:
- Login/logout flow on Android TV emulator
- EPG/M3U sync (verify Room database)
- Admin panel CRUD (web browser)

**Acceptance**: APK builds to TV, login works, admin panel at duckflix.tv/lite_service/admin

---

### Phase 3: VOD Implementation (8-12 days)
**Goal**: TMDB search, Zurg/Prowlarr, direct RD streaming with ExoPlayer

**Tasks**:
- TMDB search UI (Compose for TV, grid layout)
- Content detail view (movie/show, seasons, episodes)
- Zurg lookup API integration
- Prowlarr search (fallback)
- RD API client (Retrofit, add torrent, get stream)
- IP session check (server API)
- ExoPlayer integration (HLS/MP4 streaming)
- Direct RD streaming (RD URL → ExoPlayer)
- Subtitle support (ExoPlayer native subtitle rendering)
- Audio track selection (ExoPlayer multi-track support)
- Playback progress (Room + optional server sync)
- VOD heartbeat (WorkManager periodic task, every 30s)
- Error handling (RD expired, IP blocked, stream failed)
- Continue watching UI (resume from last position)

**ExoPlayer Features**:
- Play/pause, seek, volume (D-pad controls)
- Subtitle selection (native ExoPlayer UI)
- Audio track selection (native ExoPlayer UI)
- Quality selection (if RD offers multi-bitrate)
- Custom player controls (Compose overlays)
- Error overlays (network, auth, stream)

**Testing**:
- Search movie, play stream (verify direct RD)
- Test IP blocking (2 devices, different IPs)
- Test RD expiry (expired key → block + alert)
- Test subtitles & audio tracks
- Test D-pad controls

**Acceptance**: User can search, play VOD, IP blocking works, subtitles work, TV remote navigation perfect

---

### Phase 4: Live TV Implementation (6-8 days)
**Goal**: Channel list, EPG, live player with ExoPlayer, backup failover

**Tasks**:
- Channel list UI (Compose grid with logos)
- EPG display (timeline with current/next program)
- Live TV player (ExoPlayer with HLS/MPEG-TS)
- Channel switching (up/down with D-pad)
- Backup stream failover (ExoPlayer source switching)
- Channel favorites (Room database)
- "What's On Now" screen (EPG-based)
- Comedy Central Pluto integration (special case)
- Channel logos (Coil image loading, disk cache)
- EPG overlay (show during playback)

**ExoPlayer Live TV Features**:
- HLS/MPEG-TS support (native)
- Low-latency playback (ExoPlayer config)
- Channel switching (smooth source transitions)
- Backup failover (automatic on error)
- EPG overlay (Compose custom UI)
- Volume control (D-pad)

**Testing**:
- Play multiple channels
- Test backup failover (kill stream, verify switch)
- Test EPG display accuracy
- Test channel switching speed

**Acceptance**: Live TV works, backup failover seamless, EPG accurate, D-pad navigation smooth

---

### Phase 5: DVR Implementation (12-16 days) ⚠️ MOST COMPLEX
**Goal**: Local recordings with native Foreground Service

**5.1: Storage Setup (2 days)**
- Request storage permissions (scoped + MANAGE_EXTERNAL_STORAGE)
- Detect external SD card (DocumentFile API)
- Create recording directories
- Storage quota UI (used/available, Compose)
- Recording file manager (list, delete, play)

**5.2: Recording Scheduler (3 days)**
- EPG-based recording UI (select program from EPG)
- Manual recording UI (channel, date, time, duration)
- Recurring recordings (daily, weekly patterns)
- Store schedules in Room database
- WorkManager scheduling (exact-time triggers)

**5.3: Background Recording Service (5-7 days) ⚠️ CRITICAL**
- Android Foreground Service (persistent notification)
- Wake lock (keep CPU awake during recording)
- Notification (show active recordings, progress)
- **Recording Implementation** (choose one):
  - **Option A: ExoPlayer + MediaMuxer** (pure Kotlin, smaller APK)
    - ExoPlayer downloads HLS segments
    - MediaMuxer muxes to MP4/TS container
    - Smaller APK, more complex error handling
  - **Option B: FFmpeg binary** (proven, reliable)
    - Bundle FFmpeg in APK (~20MB)
    - `ffmpeg -i <url> -c copy <output.ts>`
    - Larger APK, simpler integration
- Stream failure detection (monitor errors)
- Backup stream switching (on failure)
- Recording metadata storage (Room)
- Overlapping recordings (multiple service instances or queue)

**5.4: Playback (2 days)**
- DVR playback UI (list recordings, Compose)
- Local video player (ExoPlayer with local file)
- Seek, pause, resume
- Deletion (with confirmation dialog)
- Metadata display (title, date, duration, errors)

**5.5: Edge Cases (2-3 days)**
- Overlapping recordings (queue or multi-process)
- Storage full (stop, alert, cleanup UI)
- Device reboot (reschedule via WorkManager)
- App kill (service survives, notification restores)
- Stream gaps (log in metadata, display in UI)
- Battery optimization exemption (request user permission)

**Testing**:
- Schedule recording, verify starts on time
- Verify file saved to correct location
- Test backup switching during recording
- Test overlapping recordings
- Test storage full scenario
- Test device reboot (recording resumes)
- Test battery optimization (recording continues)

**Acceptance**: DVR works end-to-end, background recording stable, backup switching works, no data loss on reboot

---

### Phase 6: Polish & Testing (6-8 days)
**Goal**: Netflix-quality UX, full regression testing

**Tasks**:
- Profile switcher (Netflix-style, Compose)
- RD expiry popup (5 days, 1 day, expired)
- Contact info ("Contact Aaron" for expired RD)
- Setup guide in admin panel (links for RD key)
- App tour/onboarding (first launch, Compose pager)
- Error logging (client → server, Retrofit)
- Performance optimization (APK size, Room queries, Compose recomposition)
- UI polish (animations, loading states, focus indicators)
- Accessibility (TalkBack support, D-pad focus order)
- Full regression testing (all features)
- TV remote navigation (test all screens with D-pad only)
- 4K TV testing (verify UI scaling)

**Testing**:
- Full regression (VOD, Live TV, DVR)
- Multi-user testing (parent + sub-accounts)
- Stress testing (multiple recordings, large EPG)
- Real-world testing (1 week on target devices)
- Test on multiple Android TV versions (API 21+)

**Acceptance**: No crashes, no major bugs, TV remote navigation perfect, UI scales on 4K TVs

---

### Phase 7: Deployment & Documentation (4-6 days)
**Goal**: Production-ready server + APK, full documentation

**Tasks**:
- APK signing (release keystore, security best practices)
- ProGuard/R8 configuration (code shrinking, obfuscation)
- Automate APK build (GitHub Actions or Gradle script)
- Deploy server to duckflix.tv/lite_service
- Create installation guide (sideload APK, enable unknown sources)
- Create admin guide (for Aaron)
- Create user guide (friends/family, PDF + video)
- Create troubleshooting guide (common Android TV issues)
- Set up monitoring (uptime, logs, crash reporting)
- Plan for updates (versioning, migration strategy)

**Documentation Files**:
- INSTALLATION.md (end users, sideload instructions)
- ADMIN_GUIDE.md (Aaron, server management)
- USER_GUIDE.md (friends/family, with screenshots)
- TROUBLESHOOTING.md (common issues, Android TV quirks)
- DEVELOPMENT.md (future devs/Claude, build instructions)
- ARCHITECTURE.md (technical overview, native Android stack)

**Acceptance**: APK at duckflix.tv/lite_service/apk/latest.apk, server stable, docs complete

---

### Phase 8: Longevity Planning (2-3 days)
**Goal**: Server module sharing with main build, maintenance strategy

**Tasks**:
- Document shared server modules (Zurg, Prowlarr, EPG/M3U)
- Set up dual-deployment (main + lite on same host)
- Create update checklist (main → lite API compatibility)
- Document Zurg/Prowlarr shared access
- Plan for Android client updates (versioning, migrations)
- Document API versioning strategy

**Shared Services** (Universal):
- **Zurg**: Single Docker container at `/mnt/zurg`, both builds use same mount
- **Prowlarr**: Single Docker instance, both builds call same API

**Independent**:
- **Main build**: Full Docker stack (downloads, transcoding, etc.)
- **Lite server**: Minimal Express (PM2/systemd, no media handling)
- **Lite client**: Native Android APK (independent release cycle)

**Acceptance**: Shared services configured, maintenance strategy documented, update process clear

---

## Code Reuse Strategy

**CANNOT REUSE** (Different platforms):
- React UI components (web → native Android)
- Capacitor plugins (not applicable)
- Browser APIs (not applicable)
- Video.js (replaced with ExoPlayer)

**CAN REUSE** (Server-side Node.js modules):
- Zurg client (Express API passthrough)
- TMDB client (server-side search)
- EPG parser (server-side processing)
- M3U parser (server-side processing)
- RD API patterns (reimplemented in Kotlin/Retrofit)

**REWRITE** (Native Android):
- All UI (Jetpack Compose for TV)
- Video player (ExoPlayer)
- Database (Room)
- HTTP client (Retrofit)
- Background work (WorkManager + Foreground Service)
- Storage (Android scoped storage)

**SHARE VIA API** (Server provides):
- JWT auth logic (server validates, client stores token)
- EPG/M3U data (server fetches, client consumes JSON)
- User management (server CRUD, client displays)
- IP tracking (server logic, client sends heartbeats)

---

## Testing Strategy

**Unit Tests** (Kotlin):
- ViewModel logic (LiveData/StateFlow transformations)
- Repository layer (Room DAOs, network error handling)
- API response parsing (Moshi/kotlinx.serialization)
- Recording scheduler logic (WorkManager enqueue)

**Integration Tests** (Android Instrumentation):
- Room database migrations
- Retrofit API calls (MockWebServer)
- EPG/M3U sync flow

**UI Tests** (Compose):
- Login flow (Compose test framework)
- Navigation (D-pad simulation)
- Video player controls

**Manual Testing** (Critical):
- Install on physical Android TV device
- Test all flows (VOD, Live TV, DVR)
- Test edge cases (storage full, network loss, reboot)
- Test TV remote (D-pad, back, home buttons)
- Test on multiple Android TV versions
- Test on different screen sizes (1080p, 4K)

**Beta Testing**:
- 2-3 early users
- 1 week feedback
- Fix critical bugs
- Re-deploy server + APK

---

## Deployment Strategy

**Server**:
- PM2 or systemd (not Docker, keep lean)
- duckflix.tv/lite_service (nginx reverse proxy)
- SQLite database at /var/lib/duckflix_lite/db.sqlite

**APK**:
- Host at duckflix.tv/lite_service/apk/latest.apk
- Manual download + sideload (no Play Store)
- Minimum Android version: API 21 (Android 5.0)
- Target Android version: API 34 (Android 14)

**Updates**:
- Semantic versioning (v1.0.0, v1.1.0, etc.)
- Keep last 3 APKs on server (rollback if needed)
- In-app update checker (optional, server API)

**Monitoring**:
- Uptime (UptimeRobot or similar)
- Logs (server file, daily rotation)
- Crash reporting (client → server, optional Firebase Crashlytics)

---

## Warnings & Pitfalls

**Native Android Complexity**:
- Steeper learning curve than web (Kotlin, Jetpack, Android lifecycle)
- Jetpack Compose for TV less mature than mobile Compose
- D-pad navigation requires careful focus management
- **Mitigation**: Phase 2 is 5-7 days for learning curve

**DVR Complexity** (Same as before, but native):
- Background recording is HARD (Foreground Service, wake locks, battery optimization)
- ExoPlayer + MediaMuxer OR FFmpeg (both have tradeoffs)
- Stream failover is COMPLEX (gaps, retries, metadata)
- **Mitigation**: Phase 5 is 12-16 days, choose MediaMuxer vs FFmpeg in Phase 0

**Storage Permissions**:
- MANAGE_EXTERNAL_STORAGE restricted by Play Store (OK for sideload)
- Scoped storage (Android 10+) limits access
- User must manually grant in Settings
- **Mitigation**: Clear UI guidance, deep link to Settings

**ExoPlayer Learning Curve**:
- Complex API (requires understanding of media format containers)
- HLS/MPEG-TS quirks (some streams may fail)
- **Mitigation**: Start with simple playback in Phase 3, iterate

**Android TV Fragmentation**:
- Different manufacturers (Google, Nvidia, Xiaomi, Fire TV)
- Different Android versions (some on old APIs)
- Different remote controls (D-pad layouts vary)
- **Mitigation**: Test on multiple devices in Phase 6

**IP Blocking Edge Cases** (Same as before):
- Dynamic IPs (changes mid-session)
- VPN (IP changes on connect/disconnect)
- Multiple devices on same IP (should be allowed)
- **Mitigation**: Track heartbeat with 2-min timeout, not session

**FFmpeg Licensing** (If used):
- LGPL (requires dynamic linking or source disclosure)
- **Mitigation**: Use LGPL-compliant build, document in LICENSE

**RD API Rate Limits** (Same as before):
- RD limits per IP (not per key)
- **Mitigation**: Client calls RD directly (not via server proxy)

**Shared Services Conflict** (Same as before):
- Zurg config changes in main build could break lite
- **Mitigation**: Zurg is read-only for lite, document shared config

---

## Success Metrics

**MVP** (after Phase 5):
- Server at duckflix.tv/lite_service
- Native Android APK with VOD, Live TV, DVR
- Admin panel
- Basic error handling

**Launch** (after Phase 7):
- 0 crashes in 1 week beta
- < 5 critical bugs in first month
- All users can operate without help (sideload + use)
- Aaron's sanity intact

**Nice-to-Haves** (v2):
- Advanced analytics
- Social features (watch together)
- Offline downloads (non-DVR, cache for planes)
- Mobile app (phone/tablet, same Kotlin codebase)
- Chromecast support (ExoPlayer Cast extension)

---

## Context Handoff Template

When doing a `/clear`, create a handoff doc with:

1. **Current Phase/Task**: e.g., "Phase 3, Task 3.7: ExoPlayer integration"
2. **Completed**: List of done tasks
3. **Blocked**: Any blockers
4. **Next Steps**: 1-3 tasks only
5. **Key Files**: Absolute paths to Kotlin/XML files
6. **Key Decisions**: Any mid-work decisions (e.g., chose MediaMuxer over FFmpeg)

**Example**:
```
## HANDOFF: ExoPlayer Integration

**Current**: Phase 3, Task 3.7 (ExoPlayer integration)
**Completed**: Phase 0 (all), Phase 1 (all), Phase 2 (all), Phase 3 Tasks 3.1-3.6
**In Progress**: ExoPlayer in /Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/player/VODPlayer.kt
**Blocked**: None
**Next**: 3.7 (finish player), 3.8 (subtitles), 3.9 (audio tracks)
**Files**:
- /Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/player/VODPlayer.kt
- /Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/vod/PlayerScreen.kt
- /Users/aaron/projects/duckflix_lite/server/routes/vod.js
**Decisions**:
- Using ExoPlayer (not Video.js)
- RD URLs fetched client-side via Retrofit
- IP check before every playback
- Using Hilt for dependency injection
```

---

## Timeline Estimate

**Optimistic**: 8 weeks (all goes well, no major Android TV quirks)
**Realistic**: 10 weeks (some bugs, ExoPlayer learning curve, testing time)
**Pessimistic**: 14 weeks (DVR hell, Android permissions battles, D-pad navigation issues)

**Breakdown** (realistic):
- Phase 0: 3 days
- Phase 1: 4 days
- Phase 2: 6 days (Android project setup + learning curve)
- Phase 3: 10 days (ExoPlayer integration)
- Phase 4: 7 days
- Phase 5: 14 days (DVR, most complex)
- Phase 6: 7 days (polish + regression)
- Phase 7: 5 days
- Phase 8: 3 days

**Total**: 59 days (~10 weeks assuming full-time work)

**Additional 2 weeks** vs web version due to:
- Native Android learning curve
- Jetpack Compose for TV (less documentation)
- ExoPlayer complexity
- D-pad navigation (more testing required)

---

## Next Steps

**Immediate Action**:
1. Review this plan (Aaron + Claude)
2. Start Phase 0, Task 0.1 (Android TV storage research)
3. Decide: MediaMuxer vs FFmpeg for DVR (critical decision)
4. Set up Android Studio project (Task 0.4)

**First Milestone** (Phase 1 complete):
- Server running at duckflix.tv/lite_service
- Admin can create users, set RD keys
- IP tracking working

**First User-Visible Milestone** (Phase 3 complete):
- Native Android APK with working VOD (search, play, direct RD streaming via ExoPlayer)
- Proof of concept for friends/family
- D-pad navigation working

**First Full Feature Milestone** (Phase 5 complete):
- VOD + Live TV + DVR all working
- Background recording stable
- Ready for beta testing

---

## Why Native Android vs Capacitor?

**Performance**:
- ExoPlayer >> Video.js (native media codecs, hardware acceleration)
- Jetpack Compose >> React in WebView (60fps animations, D-pad focus)
- Room >> IndexedDB (native SQLite, better concurrency)

**Background Work**:
- Foreground Service + WorkManager >> Capacitor Background Task (reliable, OS-integrated)
- Native wake locks >> Web hacks (guaranteed CPU during recording)

**Storage**:
- Native scoped storage >> Capacitor File API (better permissions, larger files)
- MediaMuxer/FFmpeg >> Web-based recording (impossible in browser)

**TV Experience**:
- Jetpack Compose for TV >> React adapted for TV (built for D-pad, leanback design)
- Native focus management >> JavaScript focus hacks (reliable, OS-integrated)

**Reliability**:
- Survives app kill, device reboot
- Native notifications (proper Foreground Service)
- No WebView crashes or memory leaks

**Tradeoff**:
- Cannot reuse React components (complete rewrite)
- Steeper learning curve (Kotlin, Jetpack, Android lifecycle)
- Longer development time (~2 weeks additional)

**Verdict**: Native Android is the RIGHT choice for a TV-first, DVR-heavy app.

---

Ruff ruff. Let's build this the RIGHT way.

**Document Version**: 2.0 (Native Android)
**Created**: 2026-02-01
**Owner**: Aaron + Claude (Big Dog)
