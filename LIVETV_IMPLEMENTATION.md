# Live TV API Implementation Summary

**Date**: 2026-02-04
**Status**: ✅ Complete (Server-Side Phase 1)

## Overview

Implemented server-side Live TV functionality for DuckFlix Lite, providing the API contract that the Android TV APK will consume. This implementation includes channel management, EPG data integration, stream proxying, and user preferences.

---

## Database Schema

### New Tables Created

#### 1. `user_channel_settings`
Stores per-user channel preferences (enable/disable, favorites, custom ordering).

```sql
- user_id (FK to users)
- channel_id (string)
- is_enabled (boolean)
- is_favorite (boolean)
- sort_order (integer)
- timestamps
```

#### 2. `special_channels`
Admin-defined channels not in the M3U playlist.

```sql
- id (primary key)
- name, display_name, group_name
- stream_url, logo_url
- sort_order, channel_number
- is_active
- timestamps
```

#### 3. `channel_metadata`
Additional channel metadata (custom logos, EPG overrides).

```sql
- channel_id (primary key)
- custom_logo_url
- custom_display_name
- epg_override_id
- notes
```

#### 4. `dvr_recordings`
Future DVR functionality (Phase 5).

```sql
- user_id (FK to users)
- channel info (id, name)
- program info (title, description)
- start_time, end_time (unix ms)
- status, recording_path, file_size
```

---

## API Endpoints Implemented

### 1. `GET /api/livetv/channels`
**Auth**: Required (Bearer token)
**Returns**: All enabled channels for the authenticated user with merged EPG data.

**Response Shape**:
```json
{
  "channels": [
    {
      "id": "espn",
      "name": "ESPN",
      "displayName": "ESPN",
      "group": "Sports",
      "url": "https://tvpass.org/live/ESPN/sd",
      "logo": "https://example.com/logos/espn.png",
      "sortOrder": 1,
      "channelNumber": 1,
      "isFavorite": false,
      "isEnabled": true,
      "currentProgram": {
        "start": 1770187235368,
        "stop": 1770190835368,
        "title": "SportsCenter",
        "description": "Latest sports news and highlights",
        "category": "Sports",
        "icon": null,
        "episodeNum": null
      },
      "upcomingPrograms": [...]
    }
  ]
}
```

**Features**:
- Merges M3U channels + special channels + EPG data + user settings
- Filters to only enabled channels
- Sorts by: favorites first → sort_order → channel_number
- Includes current program (if airing now)
- Includes upcoming programs (next 6 hours, max 12)

### 2. `GET /api/livetv/stream/:channelId`
**Auth**: Required (Bearer token)
**Returns**: HLS stream proxy with manifest rewriting.

**Features**:
- Transparently proxies HLS streams
- Rewrites M3U8 manifests to route segments through server
- Handles both manifest requests and segment requests
- Query param `?url=` or `?segment=` for segment proxying

**Usage**:
- Initial request: `/api/livetv/stream/espn` → returns rewritten M3U8
- Segment request: `/api/livetv/stream/espn?url=https://...` → returns TS segment

### 3. `GET /api/logo-proxy?url=<encodedUrl>`
**Auth**: NOT required (public endpoint)
**Returns**: Proxied image with proper Content-Type.

**Features**:
- Handles CORS/SSL issues for external logos
- 24-hour cache headers
- Returns 1x1 transparent PNG fallback on error
- User-Agent: `DuckFlix/1.0`

### 4. `PATCH /api/livetv/channels/:id/toggle`
**Auth**: Required (Bearer token)
**Body**: None
**Returns**: `{ success: true, channelId, isEnabled }`

Toggles channel enabled/disabled state for the user.

### 5. `POST /api/livetv/channels/:id/favorite`
**Auth**: Required (Bearer token)
**Body**: None
**Returns**: `{ success: true, channelId, isFavorite }`

Toggles favorite status for a channel.

### 6. `POST /api/livetv/channels/reorder`
**Auth**: Required (Bearer token)
**Body**: `{ "channelIds": ["espn", "cnn", "hbo", ...] }`
**Returns**: `{ success: true, count: <number> }`

Updates channel sort order based on array position.

### 7. `POST /api/dvr/recordings/schedule`
**Auth**: Required (Bearer token)
**Status**: 501 Not Implemented (FUTURE - Phase 5)

Placeholder for DVR recording scheduling.

---

## Service Layer

### `services/livetv-service.js`

#### `getChannelsForUser(userId)`
- Merges M3U channels + special channels
- Applies user settings (enabled, favorite, sort_order)
- Applies channel metadata (custom logos, display names)
- Fetches and attaches EPG data (current + upcoming 6 hours)
- Filters to enabled channels only
- Sorts by favorites → sort_order → channel_number
- Returns fully hydrated channel objects

#### `getStreamUrl(channelId)`
- Looks up stream URL from M3U channels or special channels
- Returns raw stream URL (proxying handled by route)

#### `toggleChannel(userId, channelId)`
- Toggles enabled state in `user_channel_settings`
- Upserts if no existing setting
- Returns new state

#### `toggleFavorite(userId, channelId)`
- Toggles favorite state in `user_channel_settings`
- Upserts if no existing setting
- Returns new state

#### `reorderChannels(userId, channelIds)`
- Updates sort_order for each channel based on array index
- Uses SQLite transaction for atomicity

---

## Integration with Existing System

### EPG Data
- Existing `epg_cache` table stores EPG data per channel
- Existing `epg-sync.js` service runs every 30 minutes
- Live TV service reads from this cache and attaches to channels

### M3U Data
- Existing M3U sync stores channels in `epg_cache` with `channel_id = 'm3u_channels'`
- Live TV service reads M3U channels from cache
- Special channels supplement the M3U list

### Authentication
- Uses existing `authenticateToken` middleware
- Bearer token in `Authorization` header
- JWT contains `sub` (user ID) used for queries

---

## Testing Results

### Test Data Inserted
```javascript
// 3 test channels: ESPN, CNN, HBO
// ESPN has EPG data with current and upcoming programs
```

### Endpoints Tested
✅ `GET /api/livetv/channels` - Returns 3 channels with EPG data
✅ `PATCH /api/livetv/channels/:id/toggle` - Toggles enabled state
✅ `POST /api/livetv/channels/:id/favorite` - Toggles favorite state
✅ `POST /api/livetv/channels/reorder` - Updates sort order
✅ `GET /api/logo-proxy` - Proxies images (no auth required)
✅ `GET /api/livetv/stream/:channelId` - Stream proxy endpoint (ready for HLS)

### Sample Response
```bash
$ curl -k -H "Authorization: Bearer <token>" https://localhost:3001/api/livetv/channels

{
  "channels": [
    {
      "id": "espn",
      "name": "ESPN",
      "currentProgram": {
        "title": "SportsCenter",
        "start": 1770187235368,
        "stop": 1770190835368
      },
      "upcomingPrograms": [
        {
          "title": "NFL Live",
          "start": 1770190835368,
          "stop": 1770194435368
        }
      ]
    }
  ]
}
```

---

## Configuration Required

### Environment Variables
```bash
# Optional: M3U playlist URL (for auto-sync)
M3U_SOURCE_URL=https://example.com/playlist.m3u8

# Existing variables (already configured)
JWT_SECRET=<secret>
ADMIN_PASSWORD=<password>
```

### M3U Source
Currently not configured. To enable M3U auto-sync:
1. Set `M3U_SOURCE_URL` in `.env`
2. Restart server
3. M3U will sync hourly (cron job already configured)

---

## Next Steps: Android TV APK Implementation

The server API is now ready for APK integration. Follow the stage-by-stage guide:

### Stage 1: Data Layer (Kotlin)
- Create data models: `LiveTVChannel`, `EPGProgram`, `TimeSlot`
- Create API service with Retrofit/OkHttp
- Create repository with 5-minute cache
- Logo URL resolution helper

### Stage 2: ViewModel & State
- `LiveTVViewModel` with `LiveTVUiState`
- Navigation state machine (D-pad handling)
- Time slot generation (12 x 30-min slots, user's timezone)
- Program lookup helpers (current, upcoming, width calculation)
- Auto-refresh every 5 minutes

### Stage 3: EPG Grid Composable
- `LiveTVScreen` with sticky header + scrollable grid
- `LiveTVHeader` (PiP + channel info + timeline)
- `EPGGrid` (channels + programs with dynamic widths)
- Scroll synchronization (header ↔ grid)
- Current time indicator (red line)
- Focus management (custom, not Compose default)

### Stage 4: PiP Video Player
- ExoPlayer integration with HLS support
- Stream URL: `${baseUrl}/api/livetv/stream/${channelId}`
- PiP size: 320dp x 180dp in header
- Fullscreen mode
- Channel switching in fullscreen
- Error handling & retry logic

### Stage 5: D-pad Navigation
- Key event interception (consume all directional keys)
- Auto-scroll on navigation (vertical + horizontal)
- Focus visual indicators (gold border + glow)
- Circular navigation (wrap at bottom, header at top)
- Scroll state synchronization

### Stage 6: Channel Selection & Launch
- Logo click → select channel + fullscreen
- Program cell click → switch channel (if live) or DVR (if future)
- Channel switching overlay (fade in/out)
- Back button behavior (fullscreen → grid → previous screen)

### Stage 7: Timestamps & Polish
- Unix milliseconds → user's local timezone
- Program state styling (now-playing, past, scheduled, empty, focused)
- Animations (pulse, focus, channel switch overlay)
- Row alternating colors + highlighted row
- Channel logo loading with fallback

### Stage 8: DVR (FUTURE - Phase 5)
- Recording modal
- Recording indicators in EPG
- Manual recording form
- Recording list/management

---

## API Contract Stability

✅ The API responses are **stable** and ready for APK consumption.
✅ All endpoints follow RESTful conventions.
✅ All timestamps are **Unix milliseconds** (Long in Kotlin).
✅ All endpoints require Bearer token auth (except logo-proxy).
✅ Error responses follow standard format: `{ "error": "message" }`

---

## Files Modified/Created

### Created
- `services/livetv-service.js` - Service layer for Live TV logic
- `routes/livetv.js` - API routes for Live TV endpoints
- `LIVETV_IMPLEMENTATION.md` - This document

### Modified
- `db/init.js` - Added Live TV database tables
- `index.js` - Registered Live TV routes

---

## Server Status

✅ Server running on port 3001 (HTTPS)
✅ Database tables created
✅ Test data inserted
✅ All endpoints tested and working
✅ Ready for APK integration

---

## Notes for APK Development

1. **Base URL**: `https://lite.duckflix.tv` (production) or `https://localhost:3001` (dev)

2. **Authentication**:
   - Login: `POST /api/auth/login` with `{ username, password }`
   - Get token from response: `response.token`
   - Include in all requests: `Authorization: Bearer <token>`

3. **Timestamps**:
   - All EPG times are Unix milliseconds (Long)
   - Convert to user's local time: `Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())`

4. **Logo URLs**:
   - Absolute HTTP/HTTPS → proxy: `${baseUrl}/api/logo-proxy?url=${encodeURIComponent(logo)}`
   - Relative path (starts with `/`) → prepend: `${baseUrl}${logo}`
   - Null → show fallback (cyan circle with initials)

5. **Stream URLs**:
   - Always use: `${baseUrl}/api/livetv/stream/${channelId}`
   - Server handles HLS manifest rewriting
   - ExoPlayer should use HlsMediaSource

6. **EPG Refresh**:
   - Refresh channel data every 5 minutes while on Live TV screen
   - Regenerate time slots every 30 minutes
   - Use ViewModel scope with delay loop

7. **Error Handling**:
   - 401 Unauthorized → token expired, re-login
   - 403 Forbidden → insufficient permissions
   - 500 Internal Server Error → show error, retry with backoff
   - 502 Bad Gateway → stream unavailable, show message

---

## Support

For issues or questions about the server API:
- Check logs: `pm2 logs duckflix-lite-v2`
- Database inspection: Use Node.js with `require('./db/init').db`
- Test endpoints: Use curl with Bearer token

For APK development questions, refer to the stage-by-stage implementation guide in the original plan document.

---

**End of Server-Side Phase 1 Implementation** ✅
