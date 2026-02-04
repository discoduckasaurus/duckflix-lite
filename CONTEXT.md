# DuckFlix Lite - Project Context

⚠️ **CRITICAL - READ THIS FIRST** ⚠️

## Single Server Architecture

**THERE IS ONLY ONE SERVER:**
- **Live Server:** `/home/ducky/duckflix-lite-server-v2/` (on 192.168.4.66)
- **Local Access:** `/Users/aaron/projects/duckflix_lite/server/` (symlinked to live server)
- **Public URL:** `https://lite.duckflix.tv/api/`
- **PM2 Process:** `duckflix-lite-v2` (PID visible via `pm2 list`)

**When making server changes:**
1. Edit files in `/Users/aaron/projects/duckflix_lite/server/` (symlink auto-syncs)
2. Restart: `ssh ducky@192.168.4.66 "pm2 restart duckflix-lite-v2"`
3. Logs: `ssh ducky@192.168.4.66 "pm2 logs duckflix-lite-v2 --lines 50"`

**IMPORTANT:** No other server folders exist. If you see `duckflix-lite-server-dev` or `duckflix-lite-server`, they are archived backups and should NOT be edited.

---

## Project Overview
Android APK for streaming content with DuckFlix backend integration.

**Latest Milestone:** All core features complete - search, watchlist, auto-resume, TV shows with seasons/episodes ✓

---

## Infrastructure

### Server
- **Host:** 192.168.4.66
- **SSH:** `ssh ducky@192.168.4.66` (passphrase: `claude`, sudo: `duckadmin`)
- **Public Access:** `https://lite.duckflix.tv` (nginx reverse proxy with Let's Encrypt SSL)
- **Node Version:** v20.20.0 (REQUIRED for better-sqlite3 compatibility)
- **Mounted Locally:** Mountain Duck SFTP volume for direct file access

### Android App
- **Build Flavor:** Single production flavor
- **API URL:** Always uses `https://lite.duckflix.tv/api/` (no local IP switching)
- **Network:** Works from any network - home WiFi, cellular, or remote
- **Deployment:** ADB to NVIDIA Shield at 192.168.4.57:5555

### Related Projects
- **Main DuckFlix:** `~/duckflix/backend/` - Reference for content resolution patterns
- **Shared Libraries:** EPG parser, M3U parser, RD client, Zurg client

---

## Network Configuration

### Simplified Single-URL Setup (as of 2026-02-03)
- **All traffic** routes through `https://lite.duckflix.tv/api/`
- **No network detection** - app uses same URL everywhere
- **Nginx routing:** `lite.duckflix.tv` → `http://192.168.4.66:3001` (server runs HTTP, nginx handles SSL)
- **Even at home:** Traffic goes out to internet and back (acceptable trade-off for simplicity)

### Why We Changed This
- Previous setup had local IP detection (192.168.4.66:3001) vs remote (lite.duckflix.tv)
- Caused confusion: Claude edited wrong server folder, logs went to wrong place
- Multiple server folders (v1, dev, v2) made troubleshooting impossible
- **Solution:** Single server, single URL, zero confusion

---

## Known Issues & Quirks

### Resolved Issues
- ✅ Multiple server folders consolidated to v2 only
- ✅ Network detection removed from Android app
- ✅ Local server symlinked to live server for unified editing
- ✅ PM2 processes cleaned up (only duckflix-lite-v2 remains)

### Active Quirks
- Gradle build requires Java 21 (not 25) for AGP 8.2.1 compatibility
- Database uses `fallbackToDestructiveMigration` - version bumps recreate DB
- Server must use Node v20.20.0 (better-sqlite3 native module requirement)

---

## Development Workflow

### Server Code Changes
```bash
# 1. Edit files locally (auto-syncs via symlink)
vim /Users/aaron/projects/duckflix_lite/server/routes/search.js

# 2. Restart server
ssh ducky@192.168.4.66 "pm2 restart duckflix-lite-v2"

# 3. Check logs
ssh ducky@192.168.4.66 "pm2 logs duckflix-lite-v2 --lines 50"
```

### Android APK Changes
```bash
# 1. Edit Kotlin files
vim /Users/aaron/projects/duckflix_lite/android/app/src/main/java/com/duckflix/lite/ui/screens/HomeScreen.kt

# 2. Build APK
cd android && ./gradlew assembleDebug

# 3. Deploy to Shield
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# 4. Launch app
~/Library/Android/sdk/platform-tools/adb -s 192.168.4.57:5555 shell am start -n com.duckflix.lite.debug/com.duckflix.lite.MainActivity
```

### Debugging
- **Android Logs:** `adb -s 192.168.4.57:5555 logcat | grep DuckFlix`
- **Server Logs:** `ssh ducky@192.168.4.66 "pm2 logs duckflix-lite-v2"`
- **Check Main Project:** When stuck, reference `~/duckflix/backend/` for working patterns

---

## Feature Status

### Completed Features ✅
1. **Search** - Movies and TV shows with TMDB integration
2. **TV Shows** - Seasons/episodes with dropdown selection
3. **Watchlist** - Add/remove, long-press delete, auto-removal at 90%
4. **Auto-Resume** - Resume/restart buttons with position tracking
5. **Watch History** - Tracks progress per title
6. **Content Resolution** - Zurg → RD → Prowlarr fallback
7. **Adaptive Bitrate** - Bandwidth-aware quality selection

### Future Enhancements
- Per-episode watch progress for TV shows
- Live TV and DVR features (menu placeholders exist)
- Season-based watchlist tracking for TV series

---

## Best Practices

### For Claude Code Sessions
1. **Always verify file paths** - Edit `/Users/aaron/projects/duckflix_lite/server/`, NOT any other server folder
2. **Check logs from PM2** - `pm2 logs duckflix-lite-v2`, NOT local log files
3. **Restart PM2 after changes** - Code changes require restart
4. **Reference main project** - `~/duckflix/backend/` has proven working patterns
5. **Single URL everywhere** - App uses `lite.duckflix.tv` only, no local IP logic

### For Development
- Use plan mode for complex architectural changes
- Preserve stable content server-to-client pipeline
- Test gradle builds with fallback compatibility
- Keep UI changes minimal until feature-complete
- Don't break existing working features

---

## Quick Reference

### Credentials
```
SSH: ducky@192.168.4.66
  Passphrase: claude
  Sudo: duckadmin

ADB: 192.168.4.57:5555 (NVIDIA Shield)

Test User: Tawnia / jjjjjj
```

### Key Paths
```
Server (live):    /home/ducky/duckflix-lite-server-v2/
Server (local):   /Users/aaron/projects/duckflix_lite/server/ → symlink to above
Android:          /Users/aaron/projects/duckflix_lite/android/
Main Reference:   /Users/aaron/projects/duckflix/backend/
```

### Essential Commands
```bash
# Restart server
ssh ducky@192.168.4.66 "pm2 restart duckflix-lite-v2"

# View server logs
ssh ducky@192.168.4.66 "pm2 logs duckflix-lite-v2"

# Build APK
cd android && ./gradlew assembleDebug

# Deploy APK
adb -s 192.168.4.57:5555 install -r app/build/outputs/apk/debug/app-debug.apk

# View Android logs
adb -s 192.168.4.57:5555 logcat | grep DuckFlix
```

---

**Last Updated:** 2026-02-03 (Single server consolidation)
**Status:** ✅ Fully operational with simplified architecture
