# DuckFlix Lite

**A lean, APK-first streaming platform for friends & family**

Version: 0.0.1-alpha
Status: Phase 0 - Research & Setup (75% Complete)
Architecture: Native Android (Kotlin + Jetpack Compose for TV)
Target: 8-10 weeks to MVP

---

## What is DuckFlix Lite?

DuckFlix Lite is a radical simplification of the full DuckFlix platform, moving from server-side media handling to client-side streaming and recording.

**Key Differences from Main Build**:
- **VOD**: Direct Real-Debrid streaming (no server downloads)
- **DVR**: On-device recording to SD card (no server storage)
- **Users**: Require RD API keys, IP-based restrictions
- **Server**: Minimal auth/admin/config (no transcoding, no media handling)

**Target Platform**: Android TV (Google TV stick or custom with expandable storage)

---

## Quick Start

**For Developers/Claude**:
1. Read `/MASTER_PLAN.md` for full project plan
2. Read `/CURRENT_HANDOFF.md` for current status
3. Check `/TASKS.md` for task checklist
4. Start Phase 0 tasks (research & setup)

**For Aaron** (when ready to use):
1. Install APK from `duckflix.tv/lite_service/apk/latest.apk`
2. Grant storage permissions
3. Log in with credentials
4. Enjoy VOD, Live TV, and DVR

---

## Project Structure

```
duckflix_lite/
├── README.md              # This file
├── MASTER_PLAN.md         # Complete project plan (38KB, THE source of truth)
├── TASKS.md               # Task checklist (100 tasks)
├── CURRENT_HANDOFF.md     # Current status & next steps
├── HANDOFF_TEMPLATE.md    # Template for context handoffs
├── docs/                  # Documentation & research (TBD in Phase 0)
├── shared/                # Shared modules (TBD in Phase 0)
├── server/                # Minimal lite server (TBD in Phase 1)
└── client/                # Android APK (TBD in Phase 2)
```

---

## Architecture Overview

**Server** (Minimal Express + SQLite):
- User authentication (JWT)
- RD API key management
- IP tracking (1 IP per account for VOD)
- EPG/M3U serving (fetch from sources, cache, serve to clients)
- Admin panel (web UI for user management)
- APK hosting

**Client** (Native Android - Kotlin + Jetpack Compose):
- VOD player (ExoPlayer with direct RD streaming)
- Live TV player (ExoPlayer with M3U streams)
- DVR manager (Foreground Service with FFmpeg background recording)
- Local Room database (EPG, schedules, metadata)

**What Server Does NOT Do**:
- Download media (zero server-side files)
- Transcode (no FFmpeg, no GPU)
- Stream video (clients stream from RD)
- Record DVR (100% client-side)

---

## Phases

1. **Phase 0**: Research & Setup (1-2 days, 6 tasks)
2. **Phase 1**: Minimal Server (3-5 days, 12 tasks)
3. **Phase 2**: Client Foundation (5-7 days, 8 tasks)
4. **Phase 3**: VOD Implementation (7-10 days, 13 tasks)
5. **Phase 4**: Live TV Implementation (5-7 days, 9 tasks)
6. **Phase 5**: DVR Implementation (10-14 days, 25 tasks) ⚠️ HARDEST
7. **Phase 6**: Polish & Testing (5-7 days, 10 tasks)
8. **Phase 7**: Deployment & Documentation (3-5 days, 9 tasks)
9. **Phase 8**: Longevity Planning (2-3 days, 4 tasks)

**Total**: 6-8 weeks (100 tasks)

---

## Current Status

**Phase**: Phase 0 (Research & Setup)
**Progress**: Phase 0 COMPLETE ✅ (6/6 tasks)
**Completed**: All research, monorepo setup, shared modules extracted, architecture locked
**Next Phase**: Phase 1 - Minimal Server (3-5 days)
**Blockers**: None

See `/CURRENT_HANDOFF.md` for details.

---

## Key Documents

**MUST READ** (in order):
1. `/MASTER_PLAN.md` - Complete project plan, architecture decisions, task details
2. `/CURRENT_HANDOFF.md` - Current status, next steps, key decisions made
3. `/TASKS.md` - Simple task checklist (update as you go)

**For Context Clears**:
- Use `/HANDOFF_TEMPLATE.md` to create handoff docs before `/clear`
- Update `/CURRENT_HANDOFF.md` with latest status

---

## Tech Stack

**Server**:
- Node.js + Express
- SQLite (users, sessions, EPG cache)
- JWT authentication
- PM2 or systemd (no Docker for lite server)

**Client** (Native Android):
- Kotlin
- Jetpack Compose for TV
- Room (SQLite ORM)
- ExoPlayer (hardware-accelerated video player)
- Retrofit + OkHttp (HTTP client)
- Hilt (dependency injection)
- FFmpeg (bundled for DVR recording, ~10-15 MB APK increase)

**Shared Services** (with main build):
- Zurg (RD mount)
- Prowlarr (torrent search)
- TMDB (metadata)

---

## Development Commands

*Not yet applicable (Phase 0 - research phase)*

**Will be**:
```bash
# Server
cd /Users/aaron/projects/duckflix_lite/server
npm run dev

# Client
cd /Users/aaron/projects/duckflix_lite/client
npm run dev

# Build APK
cd /Users/aaron/projects/duckflix_lite/client
npm run build:android
```

---

## Success Metrics

**MVP** (after Phase 5):
- Server running at duckflix.tv/lite_service
- APK with VOD, Live TV, DVR working
- Admin panel for user management

**Launch** (after Phase 7):
- 0 crashes in 1 week of beta testing
- < 5 critical bugs in first month
- All users can operate without asking for help

---

## Contact

**Project Owner**: Aaron
**Development Partner**: Claude (Big Dog)
**Server**: ducky@192.168.4.66 (password: 'claude')
**Domain**: duckflix.tv

---

## License

Private project for friends & family use only.

---

**Last Updated**: 2026-02-01
**Handoff Doc**: `/CURRENT_HANDOFF.md`
**Master Plan**: `/MASTER_PLAN.md`

Ruff ruff. Let's ship this.
