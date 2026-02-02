# Context Handoff Template

**Purpose**: Use this template before any `/clear` to preserve critical context.

Copy this template, fill it out, and save as `HANDOFF_<DATE>.md` or update the current handoff doc.

---

## Current Status

**Date**: [YYYY-MM-DD]
**Phase**: [e.g., Phase 3: VOD Implementation]
**Task**: [e.g., TASK 3.7: Implement video player]
**Task Status**: [Not Started | In Progress | Blocked | Complete]

---

## Completed Work

**Phases Complete**:
- [ ] Phase 0: Research & Setup
- [ ] Phase 1: Minimal Server
- [ ] Phase 2: Client Foundation
- [ ] Phase 3: VOD Implementation
- [ ] Phase 4: Live TV Implementation
- [ ] Phase 5: DVR Implementation
- [ ] Phase 6: Polish & Testing
- [ ] Phase 7: Deployment & Documentation
- [ ] Phase 8: Longevity Planning

**Tasks Complete** (list task IDs):
- [e.g., 0.1-0.6, 1.1-1.12, 2.1-2.8, 3.1-3.6]

**Key Achievements**:
- [e.g., Server running at duckflix.tv/lite_service]
- [e.g., Admin panel functional]
- [e.g., VOD search working]

---

## Current Work

**Task ID**: [e.g., 3.7]
**Task Name**: [e.g., Implement video player]

**What's Done**:
- [Bullet point list of work completed on this task]

**What's Left**:
- [Bullet point list of remaining work on this task]

**Current State**:
- [Describe current state of the code, where you left off]

---

## Blockers

**Active Blockers**:
- [List any blockers preventing progress]
- [Or write "None"]

**Decisions Needed**:
- [List any architectural or implementation decisions needed]
- [Or write "None"]

---

## Next Steps

**Immediate** (do this next):
1. [First thing to do when resuming]
2. [Second thing to do]
3. [Third thing to do]

**Short-term** (next 2-3 tasks):
- [Task ID + brief description]
- [Task ID + brief description]
- [Task ID + brief description]

**Upcoming Milestone**:
- [Next major milestone, e.g., "Phase 3 complete: VOD working end-to-end"]

---

## Key Files

**Currently Working On**:
- [Absolute path to file 1, e.g., /Users/aaron/projects/duckflix_lite/client/src/components/VODPlayer.jsx]
- [Absolute path to file 2]
- [Absolute path to file 3]

**Related Files**:
- [Absolute path to related file 1]
- [Absolute path to related file 2]

**Key Directories**:
- Server: `/Users/aaron/projects/duckflix_lite/server`
- Client: `/Users/aaron/projects/duckflix_lite/client`
- Shared: `/Users/aaron/projects/duckflix_lite/shared`
- Docs: `/Users/aaron/projects/duckflix_lite/docs`

---

## Key Decisions

**Architectural Decisions Made**:
- [e.g., Using Video.js instead of ExoPlayer for VOD player]
- [e.g., RD stream URLs fetched client-side, not via server proxy]
- [e.g., IP session check happens before every playback, not just login]

**Deferred Decisions**:
- [e.g., Whether to use ExoPlayer for DVR playback (decide in Phase 5)]

**Changed Requirements**:
- [Any requirements that changed from original plan]
- [Or write "None"]

---

## Testing Status

**Tests Written**:
- [List test files or test types completed]

**Tests Passing**:
- [e.g., All unit tests passing]
- [e.g., Integration tests: 8/10 passing, 2 failing]

**Manual Testing Done**:
- [e.g., Tested VOD search on APK, works]
- [e.g., Tested IP blocking, confirmed working]

**Known Bugs**:
- [Bug 1: Description, severity, workaround if any]
- [Bug 2: Description, severity, workaround if any]

---

## Dependencies

**External Services**:
- Server: [e.g., Running at duckflix.tv/lite_service, stable]
- Zurg: [e.g., Shared with main build, working]
- Prowlarr: [e.g., Shared with main build, working]
- TMDB API: [e.g., Using Aaron's key, working]
- RD API: [e.g., Using test account, working]

**Environment**:
- Node version: [e.g., v20.x]
- Android SDK: [e.g., API 33]
- Capacitor version: [e.g., v6.x]

**Credentials**:
- Server SSH: [e.g., ducky@192.168.4.66, password 'claude']
- Admin account: [e.g., username 'admin', password in .env]

---

## Notes & Warnings

**Critical Notes**:
- [Any important notes for next session]

**Known Pitfalls**:
- [e.g., Don't use ExoPlayer for web builds, only APK]
- [e.g., FFmpeg binary must be extracted to app data, not bundled assets]

**Context to Remember**:
- [Anything important that might not be obvious from code]

---

## Quick Start (for next session)

**To Resume Work**:
1. Read this handoff doc
2. Review MASTER_PLAN.md section for current phase
3. Check TASKS.md for task details
4. Open files listed in "Key Files" section
5. Run tests (if applicable)
6. Continue from "Next Steps" section

**Key Commands**:
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

**Handoff Created By**: [Your name or "Claude"]
**Session Duration**: [e.g., 2 hours]
**Token Usage**: [e.g., 50k/200k]
