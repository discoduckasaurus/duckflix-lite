# DuckFlix Lite - Project Context

## Project Overview
Android APK for streaming content with DuckFlix backend integration.

**Latest Milestone:** Successful sourcing and playback for titles ✓

## Infrastructure
- **Server:** 192.168.4.66 (mounted at Mountain Duck volume)
- **SSH Access:** user `ducky`, key phrase `claude`, sudo with `duckadmin`
- **Related Project:** ~/duckflix (primary project with server hosting)
- **Stable Pipeline:** Content server to client (PRESERVE THIS)

## Known Issues & Quirks
- Gradle build issues sometimes occur - usually fixed with fallbacks for service compatibility
- Services may be shared between primary duckflix project and lite service
- Need to be careful not to break existing stable pipelines

## Current Task Priority
1. Search handling (TV shows + Movies)
2. Title page structure (seasons, episodes, specials via TMDB)
3. Per-user watchlist (homepage, long-press delete, auto-removal)
4. Auto-resume functionality (watch history based)
5. Poster verification (population + rounded tile fill)

## Best Practices
- Use plan mode for complex architectural changes
- Preserve stable content server-to-client pipeline
- Test gradle builds with fallback compatibility
- Utilize TMDB API for metadata
- Keep UI work minimal until feature complete

## API Resources
- TMDB API integration available
- Watch history infrastructure in place (foundation for auto-resume)
