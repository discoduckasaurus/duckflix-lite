# Current Handoff - DuckFlix Lite

**Date**: 2026-02-01
**Phase**: Phase 0 - Research & Setup
**Task**: Planning Complete, Ready to Start Task 0.1
**Status**: Ready to Begin Implementation
**Architecture**: Native Android (Kotlin + Jetpack Compose for TV)

---

## Completed Work

**Phases Complete**:
- [x] Planning Phase (MASTER_PLAN.md created)
- [ ] Phase 0: Research & Setup
- [ ] Phase 1-8: (Not started)

**Planning Documents Created**:
- `/Users/aaron/projects/duckflix_lite/MASTER_PLAN.md` - Complete project plan (38KB)
- `/Users/aaron/projects/duckflix_lite/TASKS.md` - Task checklist (100 tasks)
- `/Users/aaron/projects/duckflix_lite/HANDOFF_TEMPLATE.md` - Template for future handoffs
- `/Users/aaron/projects/duckflix_lite/CURRENT_HANDOFF.md` - This document

**Key Achievements**:
- Analyzed existing DuckFlix architecture (main build - Capacitor/React)
- Defined Lite architecture (minimal server, client-side streaming/DVR)
- **Revised to fully native Android** (Kotlin + Jetpack Compose for TV)
- Created 8-phase implementation plan (8-10 weeks estimated)
- Identified 100+ atomic tasks with clear acceptance criteria
- Documented code reuse strategy (shared server modules only)
- Defined critical path and dependencies
- Documented warnings and known pitfalls

---

## Current Work

**Task ID**: Not started yet
**Next Task**: 0.1 - Research Android TV storage APIs

**Phase 0 Objectives**:
1. Research Android TV storage (external SD, permissions for Android 11+)
2. Research background recording (Foreground Service, wake locks, FFmpeg)
3. Decide: Google TV vs. custom stick (Fire TV, etc.)
4. Initialize monorepo structure
5. Extract shared modules from main build
6. Create ARCHITECTURE.md

**Estimated Time**: 1-2 days

---

## Blockers

**Active Blockers**: None

**Decisions Needed**:
- Platform choice: Google TV vs. custom Android TV stick (Task 0.3)
- FFmpeg bundling approach (research in Task 0.2)

---

## Next Steps

**Immediate** (start Phase 0):
1. **TASK 0.1**: Research Android TV storage APIs
   - Focus: External SD card access on Android 11+
   - Focus: MANAGE_EXTERNAL_STORAGE permission
   - Focus: Storage quotas and limits
   - Deliverable: `docs/ANDROID_STORAGE_RESEARCH.md`

2. **TASK 0.2**: Research Android background recording
   - Focus: Foreground Service requirements
   - Focus: Wake lock best practices
   - Focus: FFmpeg bundling (size, licensing, compilation)
   - Deliverable: `docs/BACKGROUND_RECORDING_RESEARCH.md`

3. **TASK 0.3**: Platform decision
   - Evaluate: Google TV (Chromecast with Google TV, etc.)
   - Evaluate: Fire TV Stick
   - Evaluate: Custom Android TV boxes with SD card slots
   - Deliverable: `docs/PLATFORM_DECISION.md`

**Short-term** (rest of Phase 0):
- 0.4: Initialize monorepo (create /server, /client, /shared, /docs)
- 0.5: Extract shared modules from `/Users/aaron/projects/duckflix` to `/shared`
- 0.6: Create ARCHITECTURE.md with all architectural decisions

**Next Milestone**: Phase 0 complete, ready to build minimal server

---

## Key Files

**Planning Documents**:
- `/Users/aaron/projects/duckflix_lite/MASTER_PLAN.md`
- `/Users/aaron/projects/duckflix_lite/TASKS.md`
- `/Users/aaron/projects/duckflix_lite/DuckFlix_Lite_Starter_Prompt.md` (original requirements)

**Main Build** (for reference):
- `/Users/aaron/projects/duckflix/backend/server.js` - Main backend (249KB, Express + SQLite)
- `/Users/aaron/projects/duckflix/backend/livetv.js` - Live TV logic (18KB)
- `/Users/aaron/projects/duckflix/backend/dvr-manager.js` - Server-side DVR (24KB)
- `/Users/aaron/projects/duckflix/backend/zurg-lookup.js` - Zurg integration (13KB)
- `/Users/aaron/projects/duckflix/frontend/android/` - Android build (Capacitor)

**Key Directories**:
- Main Build: `/Users/aaron/projects/duckflix`
- Lite Build: `/Users/aaron/projects/duckflix_lite`

---

## Key Decisions

**Architectural Decisions Made**:

1. **Monorepo Structure**: `/shared` for reusable modules, `/server` for lite server, `/android` for native Kotlin APK
2. **Server Responsibilities**: Minimal - auth, user mgmt, RD keys, IP tracking, EPG/M3U serving, APK hosting
3. **Server Does NOT**: Download media, transcode, stream video, record DVR
4. **VOD Flow**: Client streams directly from RD (user's API key), server only tracks IP sessions
5. **DVR Flow**: Client-side recording to external SD card, Android Foreground Service, FFmpeg bundled in APK
6. **Live TV**: M3U playlist from server, EPG from server, backup stream failover on client
7. **User Model**: Parent accounts (have RD key) + sub-accounts (inherit parent's RD key)
8. **IP Restriction**: 1 IP per account for VOD (heartbeat-based, 2-min timeout)
9. **Shared Services**: Zurg and Prowlarr containers shared between main build and lite
10. **Code Reuse**: Extract Zurg, TMDB, EPG, M3U, RD clients to `/shared` (server-side only), **no UI reuse** (complete native rewrite)

**Technology Decisions**:
- Server: Express + SQLite (no Docker for lite server, use PM2/systemd)
- **Client: Native Android (Kotlin + Jetpack Compose for TV)**
- **UI Framework: Jetpack Compose for TV (declarative UI)**
- Database (server): SQLite (users, sessions, EPG cache, M3U sources)
- **Database (client): Room (native Android SQLite ORM)**
- **Video Player: ExoPlayer (native hardware-accelerated)**
- **HTTP Client: Retrofit + OkHttp**
- **Dependency Injection: Hilt (Dagger)**
- **Background Work: WorkManager + Foreground Service**
- **DVR Recording: Native Foreground Service with FFmpeg or MediaMuxer**
- **Admin Panel: React SPA (web-based, separate from Android app)**

**Deferred Decisions**:
- DVR encoding: FFmpeg vs. MediaMuxer (Phase 0, Task 0.2)
- Platform target: Google TV vs. custom stick (Phase 0, Task 0.3)

---

## Architecture Summary

**VOD (On-Demand) Flow**:
```
User search → TMDB API (via server passthrough)
  → User selects title
  → Client calls /api/auth/check-vod-session (server checks IP, RD expiry)
  → Server responds: { allowed: true } or { allowed: false, reason: "..." }
  → Client searches Zurg or Prowlarr (via server API)
  → Client adds torrent to RD (user's RD key)
  → Client gets stream URL from RD (user's RD key)
  → Client plays stream DIRECTLY (RD → Client, no server proxy)
  → Client sends heartbeat every 30s (keeps IP session alive)
  → On stop: Client calls /api/auth/vod-session-end
```

**Live TV Flow**:
```
Client fetches EPG + M3U from server (cached locally)
  → User selects channel
  → Client plays M3U stream URL
  → If stream fails → Client tries backup source (seamless switch)
```

**DVR Flow**:
```
User schedules recording (EPG or manual)
  → Client stores in local SQLite
  → At recording time:
    → Client starts Android Foreground Service
    → Service spawns FFmpeg: ffmpeg -i <m3u_url> -c copy /sdcard/DuckFlix/recordings/<show>.ts
    → Service monitors FFmpeg stderr for errors
    → If stream fails → Kill FFmpeg, switch to backup, restart (may have gap)
    → On completion → Save metadata, stop service
  → User plays recording from DVR UI (local player)
```

**Server Database Schema**:
```sql
users (id, username, password_hash, is_admin, parent_user_id, rd_api_key, rd_expiry_date, ...)
user_sessions (id, user_id, ip_address, last_vod_playback_at, last_heartbeat_at)
epg_cache (channel_id, epg_data JSON, updated_at)
m3u_sources (id, url, priority, last_fetched_at)
```

---

## Testing Status

**Tests Written**: None yet (planning phase)

**Manual Testing Done**: None yet

**Known Bugs**: None (not started implementation)

---

## Dependencies

**External Services** (from main build):
- Server: duckflix.tv (nginx, Ubuntu LTS, 192.168.4.66)
- Zurg: Shared container (main build manages)
- Prowlarr: Shared instance (main build manages)
- TMDB API: Aaron's existing key
- Real-Debrid: Aaron's existing account (for testing)

**Development Environment**:
- Machine: macOS (Darwin 24.6.0)
- Node: v20.x (assumed, verify)
- Git: Available (main build at `/Users/aaron/projects/duckflix`)

**Server Access**:
- SSH: ducky@192.168.4.66, password 'claude'
- Web: duckflix.tv (nginx reverse proxy)
- Planned: duckflix.tv/lite_service (lite server endpoint)

---

## Notes & Warnings

**Critical Notes**:
1. **DO NOT touch main build** during lite development (except to extract shared code)
2. **All media goes to `/mnt/nas/duckflix/`** on server (never root) - but lite server doesn't download media anyway
3. **Zurg and Prowlarr are SHARED** between main and lite - be careful with config changes
4. **RD API rate limits** are per IP, not per key - clients must call RD directly, not via server proxy
5. **FFmpeg licensing** (LGPL) - must use LGPL-compliant build or dynamic linking

**Known Pitfalls** (from MASTER_PLAN.md):
- DVR complexity underestimation (Phase 5 is 10-14 days, hardest phase)
- Android background recording is HARD (Foreground Service, permissions, wake locks)
- FFmpeg APK bloat (10-20MB binary)
- IP blocking edge cases (dynamic IPs, VPNs, multiple devices on same IP)
- Storage permissions on Android 11+ (MANAGE_EXTERNAL_STORAGE requires manual grant in Settings)

**Context to Remember**:
- This is a **friends & family only** service (small userbase, high quality bar)
- Target: **Billion-dollar quality** app (not hobbyist project)
- Timeline: **8-10 weeks** (56-70 days realistic for native Android)
- **Architecture**: Fully native Android (Kotlin + Compose), NOT Capacitor/React
- Strategy: **Liberal use of context clears** (frequent handoffs, atomic tasks)
- Priority: **Get it working, then optimize** (no premature optimization)

---

## Quick Start (for next session)

**To Start Phase 0**:
1. Read MASTER_PLAN.md Phase 0 section (lines 260-290)
2. Create `/Users/aaron/projects/duckflix_lite/docs/` directory
3. Start TASK 0.1: Research Android TV storage
   - Google: "Android 11 external storage permissions"
   - Google: "MANAGE_EXTERNAL_STORAGE permission"
   - Google: "Android TV SD card storage limits"
   - Create: `docs/ANDROID_STORAGE_RESEARCH.md`

**Key Commands** (not yet applicable):
```bash
# Create docs directory
mkdir -p /Users/aaron/projects/duckflix_lite/docs

# Check main build structure (for reference)
ls -la /Users/aaron/projects/duckflix
ls -la /Users/aaron/projects/duckflix/backend
ls -la /Users/aaron/projects/duckflix/frontend/android
```

---

## Project Status

**Overall Progress**: 0% (planning complete, implementation not started)

**Phases**:
- [x] Planning: 100% complete
- [ ] Phase 0 (Research): 0% (0/6 tasks)
- [ ] Phase 1 (Server): 0% (0/12 tasks)
- [ ] Phase 2 (Client Foundation): 0% (0/8 tasks)
- [ ] Phase 3 (VOD): 0% (0/13 tasks)
- [ ] Phase 4 (Live TV): 0% (0/9 tasks)
- [ ] Phase 5 (DVR): 0% (0/25 tasks)
- [ ] Phase 6 (Polish): 0% (0/10 tasks)
- [ ] Phase 7 (Deployment): 0% (0/9 tasks)
- [ ] Phase 8 (Longevity): 0% (0/4 tasks)

**Total Tasks**: 100 (96 implementation tasks + 4 longevity tasks)
**Completed**: 0
**Remaining**: 100

**Next Milestone**: Phase 0 complete (6 tasks, ~1-2 days)

---

**Handoff Created By**: Claude (Big Dog)
**Session Type**: Initial Planning Session
**Token Usage**: ~42k/200k
**Ready for Implementation**: YES

Ruff ruff. Let's build this thing.
