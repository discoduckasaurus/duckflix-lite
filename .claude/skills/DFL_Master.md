# DuckFlix Lite - Master Reference
**Role**: You are the mediator/coordinator between Server Claude and APK Claude
**Usage**: When user says "Please use the DFL_Master skill first...", read this before proceeding

---

## PROJECT OVERVIEW

**DuckFlix Lite** = Streaming platform with user-specific RD API keys
- **Server**: Node.js/Express at `~/duckflix-lite-server-v2` (this repo)
- **APK**: Kotlin/Compose Android TV (user's Mac, separate)
- **Main Project**: `~/duckflix` (legacy, contains shared services we depend on)
- **Ignore**: `~/duckflix-lite` (just APK assets)

**Architecture**:
- Server = lookup/resolution service ONLY (not media server)
- Streams content directly from user's Real-Debrid account
- Zurg mount at `/mnt/zurg` contains cached torrents
- When Zurg→RD resolution fails: Use server proxy endpoint for HTTP range requests

---

## CREDENTIALS & ACCESS

**Server**:
- Location: `~/duckflix-lite-server-v2`
- URL: `https://lite.duckflix.tv` (nginx proxy)
- Local: `https://localhost:3001` (HTTPS, self-signed cert)
- Admin: `username: admin, password: q`
- PM2: `pm2 list` (runs as fork mode, not cluster - sqlite3 issue)

**Database**:
- SQLite at `./db/duckflix_lite.db` (better-sqlite3)
- Admin user auto-created on first run

**Environment**:
- `.env` file in root
- TMDB_API_KEY, PROWLARR_*, ZURG_* configured
- SERVER_HOST=lite.duckflix.tv, SERVER_PROTOCOL=https

---

## KEY FILES & STRUCTURE

```
duckflix-lite-server-v2/
├── index.js                    # Main entry point
├── ecosystem.config.js         # PM2 config (fork mode!)
├── .env                        # Environment config
├── db/
│   └── init.js                 # Database schema + migrations
├── routes/
│   ├── vod.js                  # VOD streaming (main route)
│   ├── content.js              # Random episode, recommendations
│   ├── admin-dashboard.js      # Admin APIs (8 endpoints)
│   ├── search.js               # TMDB search
│   └── auth.js                 # JWT authentication
├── services/
│   ├── content-resolver.js     # Zurg/Prowlarr selection logic
│   ├── zurg-search.js          # Zurg mount search
│   ├── zurg-to-rd-resolver.js  # Zurg→RD link resolution
│   ├── torrent-ranker.js       # Audio scoring (+50 ENG, -30 foreign)
│   ├── failure-tracker.js      # Playback failure logging
│   └── tmdb-service.js         # TMDB API (next episode, recommendations)
├── middleware/
│   └── auth.js                 # JWT + admin middleware
└── utils/
    └── logger.js               # Winston logging
```

**Shared modules** (in `~/duckflix` as `@duckflix/*`):
- `@duckflix/zurg-client` - Zurg search (title variations, quality scoring)
- `@duckflix/rd-client` - RD API wrapper
- `@duckflix/epg-parser`, `@duckflix/m3u-parser` - Live TV

---

## COMPLETED FEATURES

### Round 1 (Server)
1. **Random Episode API**: `GET /api/content/random/episode/:tmdbId`
   - Excludes S0, treats series as flat list
2. **Admin Dashboard**: 8 endpoints
   - Dashboard, users, failures, health, loading phrases
3. **Failure Tracking**: Auto-logs playback errors to database

### Round 2 (Server)
1. **Audio Track Scoring**: In `torrent-ranker.js`
   - +50 English (ENG/DUAL/MULTI)
   - +20 Subtitles (SUBS/SUBTITLES)
   - -30 Foreign-only (ITA/FRE/GER when no ENG)
2. **Next Episode API**: `GET /api/vod/next-episode/:tmdbId/:season/:episode`
   - Returns next episode + `inCurrentPack` flag
   - Handles season boundaries, series finale
3. **Recommendations API**: `GET /api/content/recommendations/:tmdbId`
   - TMDB recommendations + Zurg availability check

### APK Phase 1 (In Progress)
- Navigation fixes (back button flow)
- Logout button functionality
- Search Enter key
- Playback UI improvements (seek, audio/sub menu)
- Loading animation, login flow, UI layout

---

## CRITICAL BUGS FIXED

**Fallout Playback Issue** (Jan 2024):
- **Problem**: Zurg WebDAV doesn't support HTTP range requests properly
- **Root Cause**: When torrents age out of RD's 100-torrent list, Zurg→RD resolver fails, falls back to WebDAV URL which returns 200 instead of 206
- **Solution**: Server proxy endpoint at `/api/vod/stream/:streamId` (before auth)
  - Properly handles range requests from Zurg mount
  - Secured by base64url-encoded path, only allows Zurg mount access
- **Files Modified**: `routes/vod.js` (lines 16-85, 58-68, 400-409)

**Title Name Variations** (Progressive fixes):
- Special chars: `!`, `(US)`, `(2004)`, apostrophes
- Fixed in `@duckflix/zurg-client/index.js` `generateTitleVariations()`

---

## YOUR ROLE AS MEDIATOR

When user provides a feature request or bug:

1. **Analyze**: Determine if Server, APK, or both
2. **Break Down**: Create clear, parallel or sequential work plan
3. **Provide Prompts**:
   - For Server work: Detailed prompt with file locations, technical specs
   - For APK work: Detailed prompt with API endpoints, UI requirements
4. **Specify Coordination**:
   - **Parallel**: Both can work simultaneously (no dependencies)
   - **Sequential**: Server first, then APK (APK needs server APIs)
   - **Tandem**: Coordinated back-and-forth

**Example Plan Structure**:
```
## Feature: [Name]

### Server Work (Priority X)
- Endpoint: GET /api/...
- File: services/...
- Logic: ...

### APK Work (Priority Y)
- Depends on: [Server APIs or independent]
- UI: ...
- Integration: ...

### Sequencing:
[Parallel / Server→APK / Tandem]
```

---

## COMMON GOTCHAS

1. **better-sqlite3**: Always breaks with PM2 cluster mode
   - Solution: `exec_mode: 'fork'` in ecosystem.config.js
   - May need: `npm rebuild better-sqlite3` or `rm -rf node_modules/better-sqlite3 && npm install better-sqlite3 --build-from-source`

2. **HTTPS vs HTTP**: Server runs HTTPS locally
   - Use `curl -k` for self-signed cert
   - Nginx handles SSL termination in production

3. **APK Location**: On user's Mac, not server
   - Always specify when APK work needed
   - Provide "prompt for local Claude"

4. **Zurg Mount**: Required for content
   - Path: `/mnt/zurg`
   - Contains: `__all__/`, `shows/`, `movies/`

5. **RD 100-Torrent Limit**: Only active 100 torrents kept
   - Older torrents need proxy endpoint, not direct RD links

---

## API QUICK REFERENCE

**Auth**: `POST /api/auth/login` → JWT token

**VOD**:
- `POST /api/vod/stream-url/start` - Start stream (returns jobId or immediate URL)
- `GET /api/vod/next-episode/:tmdbId/:season/:episode` - Next episode
- `GET /api/vod/stream/:streamId` - Proxy stream (no auth, base64url path)

**Content**:
- `GET /api/content/random/episode/:tmdbId` - Random episode
- `GET /api/content/recommendations/:tmdbId` - Movie recommendations

**Search**:
- `GET /api/search/tmdb?query=...&type=...` - TMDB search
- `GET /api/search/tmdb/:id?type=...` - TMDB details

**Admin** (requires admin token):
- `GET /api/admin/dashboard` - Stats overview
- `GET /api/admin/users` - User list
- `GET /api/admin/failures` - Playback failures

---

## DEPLOYMENT

**PM2**: `pm2 restart duckflix-lite-v2`
**Nginx**: Reverse proxy at lite.duckflix.tv
**Logs**: `pm2 logs duckflix-lite-v2` or `~/.pm2/logs/`

**Quick Restart**:
```bash
cd ~/duckflix-lite-server-v2
npm rebuild better-sqlite3  # If needed
pm2 restart duckflix-lite-v2
```

---

## NEXT PRIORITIES

**APK Phase 2** (Waiting for Phase 1 completion):
- Random episode button
- Auto-play toggle (TV + movies)
- English audio auto-select
- Admin dashboard UI

**Server Round 3** (Optional polish):
- Bandwidth test endpoint
- Search history
- Analytics
- User preferences

---

**Remember**: You coordinate, not implement. Provide clear prompts for Server Claude (me) and APK Claude (user's Mac). Keep work parallel when possible, sequential when necessary.
