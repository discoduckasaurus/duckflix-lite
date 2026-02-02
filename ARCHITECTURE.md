# DuckFlix Lite - Architecture Document

**Version**: 1.0.0
**Last Updated**: 2026-02-01
**Status**: Phase 0 Complete - Ready for Implementation

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Architectural Principles](#architectural-principles)
3. [System Architecture](#system-architecture)
4. [Technology Stack](#technology-stack)
5. [Database Design](#database-design)
6. [API Design](#api-design)
7. [Android Client Architecture](#android-client-architecture)
8. [DVR Recording System](#dvr-recording-system)
9. [Security](#security)
10. [Deployment](#deployment)
11. [Shared Services](#shared-services)
12. [Data Flow Diagrams](#data-flow-diagrams)
13. [Decision Log](#decision-log)

---

## Executive Summary

DuckFlix Lite is a **native Android TV application** with a minimal Node.js backend, designed for friends and family streaming. It represents a radical architectural simplification from the main DuckFlix platform.

**Core Architectural Shift**:
- **Main Build**: Server downloads media → transcodes → streams to clients
- **Lite Build**: Clients stream directly from Real-Debrid → record DVR locally

**Platform**: Native Android (Kotlin + Jetpack Compose for TV)
**Target Devices**: Google TV Streamer ($99), onn 4K Pro ($50), NVIDIA Shield TV Pro ($200)

---

## Architectural Principles

### 1. **Client-Side Heavy**
The Android client handles all media operations. Server only provides auth, configuration, and API coordination.

### 2. **Server Minimalism**
Server does NOT: download media, transcode, stream video, or record DVR. Server only: authenticates users, tracks IP sessions, serves EPG/M3U data, hosts admin panel.

### 3. **Direct Streaming**
All VOD content streams directly from Real-Debrid to client using user's personal RD API key. Zero server bandwidth usage for video.

### 4. **Local DVR**
Recordings stored on device (app-specific directory or SD card). No server storage. Background recording via Android Foreground Service.

### 5. **Shared Services**
Zurg and Prowlarr containers are shared with main DuckFlix build. Single source of truth for content search.

### 6. **Production Quality**
"Billion-dollar app for a small userbase" - comprehensive testing, polished UX, robust error handling.

---

## System Architecture

### High-Level Components

```
┌─────────────────────────────────────────────────────────────┐
│                      Android TV Client                       │
│  (Kotlin + Jetpack Compose + ExoPlayer + Room + Retrofit)  │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │   VOD UI     │  │  Live TV UI  │  │     DVR UI       │ │
│  │              │  │              │  │                  │ │
│  │  - Search    │  │  - Channels  │  │  - Schedules    │ │
│  │  - Player    │  │  - EPG       │  │  - Recordings   │ │
│  │  - Progress  │  │  - Favorites │  │  - Playback     │ │
│  └──────────────┘  └──────────────┘  └──────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │            ExoPlayer (Video Playback)                   │ │
│  │  - HLS/MP4 streaming  - Subtitles  - Audio tracks     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │          DVR Foreground Service (Background)            │ │
│  │  - FFmpeg recording  - Wake locks  - Notifications     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │         Room Database (Local SQLite)                    │ │
│  │  - EPG cache  - Schedules  - Recordings  - Progress    │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            ▲ HTTP/REST
                            │ (Retrofit)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              DuckFlix Lite Server (Node.js)                  │
│                 (duckflix.tv/lite_service)                   │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │     Auth     │  │  User Mgmt   │  │   EPG/M3U Sync   │ │
│  │              │  │              │  │                  │ │
│  │  - JWT       │  │  - CRUD      │  │  - Fetch sources │ │
│  │  - IP track  │  │  - RD keys   │  │  - Cache data    │ │
│  │  - Sessions  │  │  - Alerts    │  │  - Serve JSON    │ │
│  └──────────────┘  └──────────────┘  └──────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │         SQLite Database (Server-side)                   │ │
│  │  - Users  - Sessions  - EPG cache  - M3U sources       │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │         Admin Panel (React SPA)                         │ │
│  │  - User management  - RD keys  - Expiry alerts         │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            ▲ HTTP/Docker Network
                            │
┌───────────────────────────┴─────────────────────────────────┐
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │    Zurg      │  │   Prowlarr   │  │   TMDB API       │ │
│  │  (Docker)    │  │   (Docker)   │  │   (External)     │ │
│  │              │  │              │  │                  │ │
│  │  RD mount    │  │  Torrent     │  │  Metadata        │ │
│  │  WebDAV      │  │  indexing    │  │  search          │ │
│  └──────────────┘  └──────────────┘  └──────────────────┘ │
│                                                              │
│         Shared with Main DuckFlix Build (same host)         │
└─────────────────────────────────────────────────────────────┘
                            ▲
                            │
                     ┌──────┴──────┐
                     │ Real-Debrid │
                     │   (Cloud)   │
                     └─────────────┘
                    Direct client streaming
```

### Component Responsibilities

**Android Client**:
- All UI rendering (Jetpack Compose for TV)
- Video playback (ExoPlayer)
- Direct RD streaming (user's API key)
- Local DVR recording (Foreground Service + FFmpeg)
- Local data storage (Room database)
- EPG/M3U sync from server
- IP session heartbeats
- Playback progress tracking

**DuckFlix Lite Server**:
- User authentication (JWT)
- RD API key management
- IP session tracking (1 IP per account for VOD)
- EPG/M3U data fetching and caching
- Admin panel hosting
- APK file hosting
- API gateway for Zurg/Prowlarr/TMDB

**Shared Services** (Docker):
- **Zurg**: Mounts RD library as filesystem, searches content
- **Prowlarr**: Indexes torrents across multiple sources
- **TMDB**: Metadata, posters, backdrop images

---

## Technology Stack

### Android Client

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| Language | Kotlin | 1.9+ | Modern, concise, null-safe |
| UI Framework | Jetpack Compose for TV | Latest | Declarative UI, D-pad navigation |
| Video Player | ExoPlayer | 2.19+ | Hardware-accelerated, HLS/MP4 |
| Database | Room | 2.6+ | SQLite ORM, type-safe queries |
| HTTP Client | Retrofit | 2.9+ | REST API, coroutines support |
| JSON | Moshi | 1.15+ | Fast JSON parsing |
| Dependency Injection | Hilt | 2.50+ | Compile-time DI, Android-optimized |
| Image Loading | Coil | 2.5+ | Kotlin-first, caching, Compose integration |
| Background Work | WorkManager | 2.9+ | Job scheduling, survives reboots |
| Foreground Service | Android Service | API 21+ | DVR background recording |
| DVR Recording | FFmpeg | 6.0+ | LGPL build, ~10-15 MB APK increase |
| Build System | Gradle | 8.2+ | Kotlin DSL, Android plugin |
| Min API Level | 21 | Android 5.0 | Wide device compatibility |
| Target API Level | 34 | Android 14 | Latest features, security |

### Server

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| Runtime | Node.js | 20+ | JavaScript server |
| Framework | Express | 4.18+ | REST API framework |
| Database | SQLite (better-sqlite3) | 9.2+ | Embedded, fast, no setup |
| Auth | jsonwebtoken | 9.0+ | JWT signing/verification |
| Password Hash | bcryptjs | 2.4+ | Secure password storage |
| HTTP Client | axios | 1.6+ | External API calls |
| Process Manager | PM2 | 5.3+ | Production deployment |
| Logging | winston | 3.11+ | Structured logging |

### Admin Panel

| Component | Technology | Version | Purpose |
|-----------|-----------|---------|---------|
| Framework | React | 18+ | UI library |
| Build Tool | Vite | 5.0+ | Fast dev server, builds |
| Styling | Tailwind CSS | 3.4+ | Utility-first CSS |
| HTTP Client | axios | 1.6+ | API calls |

### Shared Services

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Zurg | Go binary (Docker) | RD WebDAV mount |
| Prowlarr | .NET (Docker) | Torrent indexing |
| TMDB | External API | Metadata |

### Development Tools

| Tool | Purpose |
|------|---------|
| Android Studio | Android IDE |
| Git | Version control |
| Postman | API testing |
| ADB | Android debugging |
| Android TV Emulator | Testing without hardware |

---

## Database Design

### Server Database (SQLite)

**File**: `/var/lib/duckflix_lite/db.sqlite`

```sql
-- Users table
CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  is_admin BOOLEAN DEFAULT 0,
  parent_user_id INTEGER,  -- NULL = parent, else sub-account
  rd_api_key TEXT,         -- NULL for sub-accounts
  rd_expiry_date TEXT,     -- ISO 8601 format
  created_at TEXT DEFAULT (datetime('now')),
  last_login_at TEXT,
  FOREIGN KEY(parent_user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- User sessions (IP tracking for VOD)
CREATE TABLE user_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  ip_address TEXT NOT NULL,
  last_vod_playback_at TEXT,
  last_heartbeat_at TEXT,
  created_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_sessions_user_ip ON user_sessions(user_id, ip_address);
CREATE INDEX idx_sessions_heartbeat ON user_sessions(last_heartbeat_at);

-- EPG cache
CREATE TABLE epg_cache (
  channel_id TEXT PRIMARY KEY,
  epg_data TEXT NOT NULL,  -- JSON array of programs
  updated_at TEXT DEFAULT (datetime('now'))
);

-- M3U sources
CREATE TABLE m3u_sources (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  url TEXT NOT NULL,
  priority INTEGER DEFAULT 0,
  last_fetched_at TEXT,
  is_active BOOLEAN DEFAULT 1
);

-- RD expiry alerts log
CREATE TABLE rd_alerts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  alert_type TEXT NOT NULL,  -- '5_days', '1_day', 'expired'
  sent_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

### Android Client Database (Room)

**Location**: `/data/data/com.duckflix.lite/databases/duckflix.db`

```kotlin
// User entity (cached from server)
@Entity(tableName = "user")
data class User(
    @PrimaryKey val id: Int,
    val username: String,
    val isAdmin: Boolean,
    val parentUserId: Int?,
    val rdExpiryDate: String?,
    val lastSyncAt: Long = System.currentTimeMillis()
)

// EPG Program
@Entity(
    tableName = "epg_programs",
    indices = [Index("channelId"), Index("startTime"), Index("endTime")]
)
data class EpgProgram(
    @PrimaryKey val id: String,  // channelId + startTime hash
    val channelId: String,
    val title: String,
    val description: String?,
    val category: String?,
    val startTime: Long,  // Unix timestamp (ms)
    val endTime: Long,
    val updatedAt: Long = System.currentTimeMillis()
)

// Channel
@Entity(
    tableName = "channels",
    indices = [Index("isFavorite")]
)
data class Channel(
    @PrimaryKey val id: String,
    val name: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val streamUrl: String,
    val backupStreamUrl: String?,
    val tvgId: String?,
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0
)

// DVR Recording Schedule
@Entity(
    tableName = "recording_schedules",
    indices = [Index("startTime"), Index("channelId")]
)
data class RecordingSchedule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val channelId: String,
    val programId: String?,  -- If EPG-based
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val isRecurring: Boolean = false,
    val recurrencePattern: String?,  -- "DAILY", "WEEKLY_MON", etc.
    val status: String = "scheduled",  -- scheduled, recording, completed, failed
    val createdAt: Long = System.currentTimeMillis()
)

// DVR Recording (completed)
@Entity(
    tableName = "recordings",
    indices = [Index("channelId"), Index("startTime")]
)
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val scheduleId: Int?,
    val channelId: String,
    val channelName: String,
    val title: String,
    val filePath: String,  -- /sdcard/Android/data/.../files/recordings/
    val fileSize: Long,  -- bytes
    val thumbnailPath: String?,
    val startTime: Long,
    val endTime: Long,
    val duration: Long,  -- seconds
    val hasErrors: Boolean = false,
    val errorLog: String?,
    val createdAt: Long = System.currentTimeMillis()
)

// Playback Progress
@Entity(
    tableName = "playback_progress",
    indices = [Index("updatedAt")]
)
data class PlaybackProgress(
    @PrimaryKey val contentId: String,  -- tmdb_movie_123 or recording_456
    val contentType: String,  -- "movie", "episode", "recording"
    val title: String,
    val position: Long,  -- milliseconds
    val duration: Long,
    val thumbnailUrl: String?,
    val updatedAt: Long = System.currentTimeMillis()
)
```

---

## API Design

### Base URL

**Production**: `https://duckflix.tv/lite_service/api`
**Development**: `http://localhost:3001/api`

### Authentication

All authenticated endpoints require JWT in header:
```
Authorization: Bearer <jwt_token>
```

### Endpoints

#### Auth

```
POST /api/auth/login
Body: { username, password }
Response: { token, user: { id, username, isAdmin, rdExpiryDate } }

GET /api/auth/me
Headers: Authorization: Bearer <token>
Response: { user: { id, username, isAdmin, parentUserId, rdExpiryDate } }

POST /api/auth/logout
Headers: Authorization: Bearer <token>
Response: { success: true }
```

#### VOD Session Management

```
GET /api/auth/check-vod-session
Headers: Authorization: Bearer <token>
Query: ?ip=<client_ip>
Response: { allowed: true, currentIp: "1.2.3.4" } | { allowed: false, reason: "Already playing from IP 5.6.7.8" }

POST /api/auth/vod-heartbeat
Headers: Authorization: Bearer <token>
Body: { ip: "1.2.3.4" }
Response: { success: true }

POST /api/auth/vod-session-end
Headers: Authorization: Bearer <token>
Response: { success: true }
```

#### Admin - User Management

```
GET /api/admin/users
Headers: Authorization: Bearer <token>
Response: { users: [ { id, username, isAdmin, parentUserId, rdApiKey, rdExpiryDate } ] }

POST /api/admin/users
Headers: Authorization: Bearer <token>
Body: { username, password, isAdmin, parentUserId?, rdApiKey? }
Response: { user: { id, username, ... } }

PUT /api/admin/users/:id
Headers: Authorization: Bearer <token>
Body: { username?, password?, rdApiKey?, rdExpiryDate? }
Response: { user: { id, username, ... } }

DELETE /api/admin/users/:id
Headers: Authorization: Bearer <token>
Response: { success: true }

POST /api/admin/users/:id/rd-key
Headers: Authorization: Bearer <token>
Body: { rdApiKey, rdExpiryDate }
Response: { success: true }
```

#### EPG & M3U

```
GET /api/epg
Headers: Authorization: Bearer <token>
Response: { channels: [ { channelId, programs: [ { title, start, end, description } ] } ] }

GET /api/m3u
Headers: Authorization: Bearer <token>
Response: { channels: [ { id, name, streamUrl, backupStreamUrl, logoUrl, groupTitle } ] }
```

#### Content Search (Passthrough to shared services)

```
GET /api/search/tmdb?query=<title>&type=movie|tv
Headers: Authorization: Bearer <token>
Response: { results: [ { id, title, year, posterPath } ] }

GET /api/search/zurg?title=<title>&year=<year>&type=movie|episode&season=<s>&episode=<e>
Headers: Authorization: Bearer <token>
Response: { match: { filePath, quality, size }, fallback: { ... } }

GET /api/search/prowlarr?query=<title>&year=<year>
Headers: Authorization: Bearer <token>
Response: { results: [ { title, magnet, size, seeders, quality } ] }
```

#### APK Hosting

```
GET /api/apk/latest
Response: (Binary APK file)

GET /api/apk/version
Response: { version: "1.0.0", downloadUrl: "/api/apk/latest" }
```

---

## Android Client Architecture

### Package Structure

```
com.duckflix.lite/
├── ui/                      # Jetpack Compose screens
│   ├── theme/               # App theme, colors, typography
│   ├── components/          # Reusable Compose components
│   ├── home/                # Home screen
│   ├── vod/                 # VOD screens (search, detail, player)
│   ├── livetv/              # Live TV screens (channels, EPG)
│   ├── dvr/                 # DVR screens (schedules, recordings)
│   ├── settings/            # Settings screen
│   └── login/               # Login screen
├── data/                    # Data layer
│   ├── local/               # Room database
│   │   ├── dao/             # Data Access Objects
│   │   ├── entity/          # Room entities
│   │   └── DuckFlixDatabase.kt
│   ├── remote/              # Network layer
│   │   ├── api/             # Retrofit API interfaces
│   │   ├── dto/             # Data Transfer Objects
│   │   └── NetworkModule.kt
│   └── repository/          # Repository pattern
│       ├── UserRepository.kt
│       ├── VodRepository.kt
│       ├── LiveTvRepository.kt
│       └── DvrRepository.kt
├── domain/                  # Business logic
│   ├── model/               # Domain models
│   └── usecase/             # Use cases
├── player/                  # ExoPlayer integration
│   ├── VodPlayer.kt
│   ├── LiveTvPlayer.kt
│   ├── DvrPlayer.kt
│   └── ExoPlayerManager.kt
├── service/                 # Android Services
│   ├── DvrRecordingService.kt  # Foreground Service
│   └── VodHeartbeatService.kt
├── workers/                 # WorkManager tasks
│   ├── EpgSyncWorker.kt
│   ├── RecordingSchedulerWorker.kt
│   └── VodHeartbeatWorker.kt
├── viewmodel/               # ViewModels
│   ├── VodViewModel.kt
│   ├── LiveTvViewModel.kt
│   ├── DvrViewModel.kt
│   └── SettingsViewModel.kt
├── util/                    # Utilities
│   ├── Constants.kt
│   ├── Extensions.kt
│   └── Logger.kt
└── DuckFlixApplication.kt   # Application class (Hilt entry point)
```

### Jetpack Compose Architecture

**Navigation**:
```kotlin
@Composable
fun DuckFlixNavigation(navController: NavHostController) {
    NavHost(navController, startDestination = "home") {
        composable("home") { HomeScreen() }
        composable("vod") { VodScreen() }
        composable("livetv") { LiveTvScreen() }
        composable("dvr") { DvrScreen() }
        composable("settings") { SettingsScreen() }
    }
}
```

**ViewModel Integration**:
```kotlin
@Composable
fun VodScreen(viewModel: VodViewModel = hiltViewModel()) {
    val searchResults by viewModel.searchResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    VodContent(
        searchResults = searchResults,
        isLoading = isLoading,
        onSearch = { query -> viewModel.search(query) }
    )
}
```

**D-pad Navigation**:
```kotlin
@Composable
fun FocusableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 4.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent
            )
    ) {
        // Card content
    }
}
```

---

## DVR Recording System

### Architecture

```
User schedules recording
    ↓
WorkManager schedules job at startTime - 1 minute
    ↓
At trigger time: Start DvrRecordingService (Foreground Service)
    ↓
Service acquires PARTIAL_WAKE_LOCK
    ↓
Service spawns FFmpeg process:
    ffmpeg -i <m3u_url> -c copy -f mpegts -segment_time 3600 output_%03d.ts
    ↓
Service monitors FFmpeg stderr for errors
    ↓
If stream fails: Kill FFmpeg, switch to backup URL, restart
    ↓
At endTime: Send SIGINT to FFmpeg, wait for graceful exit
    ↓
Service saves metadata to Room database
    ↓
Service releases wake lock, stops foreground, shuts down
```

### Implementation Details

**File Segmentation**: 1-hour segments to handle FAT32 4GB limit
**File Naming**: `<channel>_<date>_<time>_<part>.ts`
**Example**: `espn_20260201_1900_001.ts`

**FFmpeg Command**:
```bash
ffmpeg -i "$STREAM_URL" \
  -c copy \
  -f segment \
  -segment_time 3600 \
  -segment_format mpegts \
  -reset_timestamps 1 \
  -strftime 1 \
  "$OUTPUT_DIR/%Y%m%d_%H%M%S_part_%03d.ts"
```

**Wake Lock**:
```kotlin
val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
val wakeLock = powerManager.newWakeLock(
    PowerManager.PARTIAL_WAKE_LOCK,
    "DuckFlix::DvrRecording"
)
wakeLock.acquire(recording.duration.toMillis())
```

**Foreground Service Notification**:
```kotlin
val notification = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("Recording: ${recording.title}")
    .setContentText("${recording.channelName} - ${recording.endTime.format()}")
    .setSmallIcon(R.drawable.ic_record)
    .setOngoing(true)
    .setProgress(100, progress, false)
    .build()

startForeground(NOTIFICATION_ID, notification)
```

---

## Security

### Authentication Flow

1. User enters credentials on Android client
2. Client sends POST `/api/auth/login` with username/password (HTTPS)
3. Server validates credentials with bcrypt
4. Server generates JWT (7-day expiry)
5. Client stores JWT in `EncryptedSharedPreferences`
6. Client includes JWT in `Authorization: Bearer <token>` header for all requests
7. Server validates JWT on every request

### Password Storage

**Server**:
```javascript
const bcrypt = require('bcryptjs');
const hashedPassword = await bcrypt.hash(password, 10);
// Store hashedPassword in database
```

### JWT Structure

```json
{
  "sub": "user_id",
  "username": "aaron",
  "isAdmin": true,
  "iat": 1738425600,
  "exp": 1739030400
}
```

### API Key Security

**RD API Keys**: Stored encrypted on server, never sent to client. Client calls server API, server adds RD key to request.

**TMDB API Key**: Server-side only, never exposed to client.

### IP Restriction Logic

**VOD Playback**:
1. Client requests permission: `GET /api/auth/check-vod-session?ip=<client_ip>`
2. Server checks: Is there an active session from a *different* IP?
3. Active = heartbeat within last 2 minutes
4. If different IP is active: BLOCK
5. If same IP or no active session: ALLOW + record IP
6. Client sends heartbeat every 30s while playing
7. On playback end: Client calls `POST /api/auth/vod-session-end`

**Edge Cases**:
- Multiple devices on same IP: ALLOWED (home network)
- Dynamic IP change mid-session: BLOCKED (but timeout after 2 min)
- VPN disconnect: BLOCKED (IP changes)

### HTTPS

**Production**: All traffic over HTTPS (nginx terminates SSL)
**Development**: HTTP acceptable (local network only)

---

## Deployment

### Server Deployment

**Host**: duckflix.tv (Ubuntu LTS, ducky@192.168.4.66)
**Location**: `/var/lib/duckflix_lite/`
**URL**: `https://duckflix.tv/lite_service`

**Process Manager**: PM2
```bash
pm2 start server/index.js --name duckflix-lite
pm2 save
pm2 startup
```

**Nginx Configuration**:
```nginx
location /lite_service/ {
    proxy_pass http://localhost:3001/;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection 'upgrade';
    proxy_set_header Host $host;
    proxy_cache_bypass $http_upgrade;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

**Database**: `/var/lib/duckflix_lite/db.sqlite`

**APK Hosting**: `/var/lib/duckflix_lite/static/apk/latest.apk`

### Android Deployment

**Distribution**: Manual sideload (no Play Store)

**Build Process**:
```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

**Signing**:
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore duckflix-lite.keystore \
  app-release-unsigned.apk duckflix
```

**Installation**:
1. Download APK from `https://duckflix.tv/lite_service/apk/latest.apk`
2. Enable "Unknown Sources" on Android TV
3. Install via file manager or ADB:
   ```bash
   adb install app-release.apk
   ```

---

## Shared Services

### Service Coordination

**Zurg Container**: Shared between main DuckFlix and Lite
- **Main build**: Uses for downloads + streaming
- **Lite build**: Uses for search only (read-only)
- **Config**: `/mnt/zurg/config.yml` (managed by main build)
- **Access**: Both builds call Zurg WebDAV API

**Prowlarr Container**: Shared instance
- **Main build**: Uses for torrent search + downloads
- **Lite build**: Uses for torrent search only
- **Config**: Prowlarr web UI (managed by main build)
- **Access**: Both builds call Prowlarr REST API

**Conflict Prevention**:
- Lite server never modifies Zurg/Prowlarr config
- Lite only reads from shared services
- Main build owns configuration

---

## Data Flow Diagrams

### VOD Playback Flow

```
Android Client                    Lite Server            Shared Services
      │                                │                        │
      │ 1. Search "Inception"          │                        │
      ├───────────────────────────────>│                        │
      │                                │ 2. TMDB search         │
      │                                ├───────────────────────>│
      │                                │<───────────────────────┤
      │<───────────────────────────────┤ 3. Results             │
      │                                │                        │
      │ 4. Select movie                │                        │
      │ 5. Check VOD session (IP)      │                        │
      ├───────────────────────────────>│                        │
      │<───────────────────────────────┤ 6. Allowed=true        │
      │                                │                        │
      │ 7. Search Zurg                 │                        │
      ├───────────────────────────────>│                        │
      │                                │ 8. Query Zurg          │
      │                                ├───────────────────────>│
      │                                │<───────────────────────┤
      │<───────────────────────────────┤ 9. File path           │
      │                                │                        │
      │ 10. Add to RD (direct)         │                        │
      ├────────────────────────────────┼───────────────────────>│
      │<────────────────────────────────────────────────────────┤
      │ 11. RD stream URL              │                        │
      │                                │                        │
      │ 12. ExoPlayer.play(rdUrl)      │                        │
      │ [Streaming directly from RD]   │                        │
      │                                │                        │
      │ 13. Heartbeat (every 30s)      │                        │
      ├───────────────────────────────>│                        │
      │<───────────────────────────────┤                        │
      │                                │                        │
      │ 14. Playback end               │                        │
      ├───────────────────────────────>│                        │
      │                                │                        │
```

### Live TV Flow

```
Android Client                    Lite Server            EPG Sources
      │                                │                        │
      │ 1. Fetch EPG + M3U             │                        │
      ├───────────────────────────────>│                        │
      │                                │ 2. Check cache         │
      │                                │    (30-min expiry)     │
      │                                │                        │
      │                                │ 3. If expired: fetch   │
      │                                ├───────────────────────>│
      │                                │<───────────────────────┤
      │                                │ 4. EPG XML             │
      │                                │    Parse + cache       │
      │<───────────────────────────────┤ 5. JSON response       │
      │                                │                        │
      │ 6. Store in Room database      │                        │
      │ 7. Display channel list        │                        │
      │                                │                        │
      │ 8. User selects ESPN           │                        │
      │ 9. ExoPlayer.play(m3u_url)     │                        │
      │ [Streaming directly from source]                        │
      │                                │                        │
      │ 10. If stream fails:           │                        │
      │     Switch to backup URL       │                        │
      │                                │                        │
```

### DVR Recording Flow

```
Android Client                    Foreground Service
      │                                │
      │ 1. User schedules recording    │
      │    (EPG or manual)             │
      │ 2. Save to Room database       │
      │ 3. Schedule WorkManager job    │
      │                                │
      │ ... (wait for start time) ...  │
      │                                │
      │                                │ 4. WorkManager triggers
      │                                │ 5. Start DvrRecordingService
      │                                │ 6. Acquire wake lock
      │                                │ 7. Show notification
      │                                │ 8. Spawn FFmpeg:
      │                                │    ffmpeg -i <url> -c copy output.ts
      │                                │ 9. Monitor stderr
      │                                │
      │                                │ ... (recording in progress) ...
      │                                │
      │                                │ 10. If stream fails:
      │                                │     - Kill FFmpeg
      │                                │     - Switch to backup URL
      │                                │     - Restart FFmpeg
      │                                │
      │                                │ 11. At end time:
      │                                │     - SIGINT to FFmpeg
      │                                │     - Wait for exit
      │                                │ 12. Save metadata to Room
      │                                │ 13. Release wake lock
      │                                │ 14. Stop foreground
      │<───────────────────────────────┤ 15. Broadcast complete
      │ 16. Refresh DVR UI             │
      │                                │
```

---

## Decision Log

### Phase 0 Decisions

| Decision | Rationale | Date |
|----------|-----------|------|
| **Native Android (not Capacitor)** | Better DVR support, ExoPlayer integration, Foreground Service reliability | 2026-02-01 |
| **Kotlin + Jetpack Compose** | Modern, declarative UI, first-class Android support | 2026-02-01 |
| **ExoPlayer for video** | Hardware-accelerated, HLS/MP4 support, subtitle/audio tracks | 2026-02-01 |
| **FFmpeg for DVR (not MediaMuxer)** | Battle-tested, handles all formats, 90% faster dev time | 2026-02-01 |
| **Room for local database** | Type-safe, SQLite ORM, Kotlin-first | 2026-02-01 |
| **Retrofit for HTTP** | Industry standard, coroutines, Moshi integration | 2026-02-01 |
| **Server-side Zurg/Prowlarr** | Lightweight APK, share with main build, centralized config | 2026-02-01 |
| **1-hour file segmentation** | Handle FAT32 4GB limit, resume on failure | 2026-02-01 |
| **App-specific storage + MANAGE_EXTERNAL_STORAGE** | Simple default, power-user option | 2026-02-01 |
| **Google TV Streamer as primary target** | 32GB storage, modern platform, $99 price point | 2026-02-01 |
| **Timeline: 8-10 weeks** | Native Android learning curve, DVR complexity | 2026-02-01 |

---

## Next Steps

**Phase 1**: Minimal Server (3-5 days)
- Express + SQLite setup
- JWT authentication
- User CRUD API
- IP tracking logic
- EPG/M3U sync
- Deploy to duckflix.tv/lite_service

**Key Milestones**:
- **After Phase 2**: APK builds and authenticates
- **After Phase 5**: Full MVP (VOD + Live TV + DVR)
- **After Phase 7**: Production launch

---

**Document Version**: 1.0.0
**Last Updated**: 2026-02-01
**Status**: Phase 0 Complete - Architecture Locked
**Next Review**: After Phase 2 (Android foundation complete)
