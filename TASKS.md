# DuckFlix Lite - Task Checklist

**Last Updated**: 2026-02-01
**Status**: Phase 0 - Not Started
**Architecture**: Native Android (Kotlin + Jetpack Compose for TV)

This is a simplified checklist for tracking progress. See MASTER_PLAN.md for full details.

---

## Phase 0: Research & Setup (1-2 days) ✅ COMPLETE

- [x] **0.1** - Research Android TV storage APIs
- [x] **0.2** - Research Android background recording
- [x] **0.3** - Decide on target platform (Google TV vs custom)
- [x] **0.4** - Initialize monorepo structure
- [x] **0.5** - Extract shared modules from main build
- [x] **0.6** - Create ARCHITECTURE.md

---

## Phase 1: Minimal Server (3-5 days) ✅ COMPLETE

- [x] **1.1** - Set up Express server scaffold
- [x] **1.2** - Set up SQLite database
- [x] **1.3** - Implement auth middleware (JWT)
- [x] **1.4** - Implement user CRUD API
- [x] **1.5** - Implement sub-account logic
- [x] **1.6** - Implement RD API key management
- [x] **1.7** - Implement IP tracking for VOD sessions
- [x] **1.8** - Implement RD expiry alerts
- [x] **1.9** - Implement EPG/M3U sync
- [x] **1.10** - Implement Zurg search criteria API
- [x] **1.11** - Set up APK hosting
- [x] **1.12** - Deploy server to duckflix.tv/lite_service (ready for deployment)

---

## Phase 2: Native Android Foundation (5-7 days) ✅ COMPLETE

- [x] **2.1** - Initialize Android project with Kotlin + Jetpack Compose for TV
- [x] **2.2** - Set up dependency injection (Hilt)
- [x] **2.3** - Set up Room database (local SQLite)
- [x] **2.4** - Set up Retrofit for API calls
- [x] **2.5** - Implement authentication flow (login screen)
- [x] **2.6** - Implement D-pad navigation system
- [x] **2.7** - Create shared Compose components (cards, buttons, focus)
- [x] **2.8** - Implement settings screen
- [x] **2.9** - Build admin panel web UI (React SPA)

---

## Phase 3: VOD Implementation (7-10 days) ✅ COMPLETE

- [x] **3.1** - Implement TMDB search UI (Compose for TV)
- [x] **3.2** - Implement content detail view (Compose)
- [x] **3.3** - Implement Zurg lookup (server API call)
- [x] **3.4** - Implement Prowlarr search (server API call)
- [x] **3.5** - Implement RD torrent add flow (server API call)
- [x] **3.6** - Implement IP session check (server API call)
- [x] **3.7** - Integrate ExoPlayer for video playback
- [x] **3.8** - Implement direct RD streaming (ExoPlayer with RD URLs)
- [x] **3.9** - Implement subtitle fetching (ExoPlayer subtitle tracks)
- [x] **3.10** - Implement audio track selection (ExoPlayer audio tracks)
- [x] **3.11** - Implement playback progress tracking (Room database)
- [x] **3.12** - Implement VOD heartbeat (background worker)
- [x] **3.13** - Implement error handling (Compose error states)

---

## Phase 4: Live TV Implementation (5-7 days)

- [ ] **4.1** - Implement channel list UI (Compose LazyColumn)
- [ ] **4.2** - Implement EPG display (Compose grid/timeline)
- [ ] **4.3** - Implement live TV player (ExoPlayer with HLS)
- [ ] **4.4** - Implement channel switching (ExoPlayer source changes)
- [ ] **4.5** - Implement backup stream failover (retry logic)
- [ ] **4.6** - Implement channel favorites (Room database)
- [ ] **4.7** - Implement "What's On Now" (EPG filtering)
- [ ] **4.8** - Implement Comedy Central Pluto integration
- [ ] **4.9** - Implement channel logos (Coil image loading)

---

## Phase 5: DVR Implementation (10-14 days)

### 5.1: Storage Setup
- [ ] **5.1.1** - Request external storage permission
- [ ] **5.1.2** - Detect external SD card
- [ ] **5.1.3** - Create DVR directory
- [ ] **5.1.4** - Implement storage quota UI
- [ ] **5.1.5** - Implement recording file manager

### 5.2: Recording Scheduler
- [ ] **5.2.1** - Implement EPG-based recording UI
- [ ] **5.2.2** - Implement manual recording UI
- [ ] **5.2.3** - Implement recurring recordings
- [ ] **5.2.4** - Store schedules in SQLite

### 5.3: Background Recording Service (Native Android)
- [ ] **5.3.1** - Implement Android Foreground Service with notification
- [ ] **5.3.2** - Implement wake lock for screen-off recording
- [ ] **5.3.3** - Implement notification
- [ ] **5.3.4** - Bundle FFmpeg in APK
- [ ] **5.3.5** - Implement FFmpeg recording logic
- [ ] **5.3.6** - Implement stream failure detection
- [ ] **5.3.7** - Implement backup stream switching
- [ ] **5.3.8** - Store recording metadata

### 5.4: Playback
- [ ] **5.4.1** - Implement DVR playback UI
- [ ] **5.4.2** - Implement local video player
- [ ] **5.4.3** - Implement seek/pause/resume
- [ ] **5.4.4** - Implement deletion

### 5.5: Edge Cases
- [ ] **5.5.1** - Handle overlapping recordings
- [ ] **5.5.2** - Handle storage full
- [ ] **5.5.3** - Handle device reboot
- [ ] **5.5.4** - Handle app kill
- [ ] **5.5.5** - Handle stream gaps

---

## Phase 6: Polish & Testing (5-7 days)

- [ ] **6.1** - Implement profile switcher
- [ ] **6.2** - Implement RD expiry popup
- [ ] **6.3** - Implement contact info for expired RD
- [ ] **6.4** - Implement setup guide in admin panel
- [ ] **6.5** - Implement app tour/onboarding
- [ ] **6.6** - Implement error logging
- [ ] **6.7** - Performance optimization
- [ ] **6.8** - UI polish
- [ ] **6.9** - Accessibility (TV remote)
- [ ] **6.10** - Full regression testing

---

## Phase 7: Deployment & Documentation (3-5 days)

- [ ] **7.1** - Set up APK signing
- [ ] **7.2** - Automate APK build
- [ ] **7.3** - Deploy server to production
- [ ] **7.4** - Create installation guide
- [ ] **7.5** - Create admin guide
- [ ] **7.6** - Create user guide
- [ ] **7.7** - Create troubleshooting guide
- [ ] **7.8** - Set up monitoring
- [ ] **7.9** - Plan for updates

---

## Phase 8: Longevity Planning (2-3 days)

- [ ] **8.1** - Assess merge feasibility
- [ ] **8.2** - Set up shared server services
- [ ] **8.3** - Create update checklist
- [ ] **8.4** - Document dual-deployment

---

## Progress Summary

**Total Tasks**: 100
**Completed**: 40 (6 Phase 0 + 12 Phase 1 + 9 Phase 2 + 13 Phase 3)
**In Progress**: 0
**Remaining**: 60

**Current Phase**: Phase 3 (VOD Implementation) - COMPLETE ✅
**Next Phase**: Phase 4 (Live TV Implementation)

---

## Quick Reference

**Key Files**:
- `/Users/aaron/projects/duckflix_lite/MASTER_PLAN.md` - Full plan
- `/Users/aaron/projects/duckflix_lite/TASKS.md` - This checklist
- `/Users/aaron/projects/duckflix_lite/docs/ARCHITECTURE.md` - Architecture decisions (TBD)

**Key Commands**:
- Mark task complete: Change `- [ ]` to `- [x]`
- Update progress summary after each phase
- Create handoff doc before `/clear`

---

Ruff ruff.
