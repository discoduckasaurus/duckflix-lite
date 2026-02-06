# Live TV Page — APK Implementation Guide

Complete API reference and implementation guide for building the Live TV page in the DuckFlix Lite smart TV APK.

---

## Table of Contents

1. [Server Info](#server-info)
2. [Authentication](#authentication)
3. [API Endpoints](#api-endpoints)
4. [Data Shapes](#data-shapes)
5. [Channel Logos](#channel-logos)
6. [HLS Stream Playback](#hls-stream-playback)
7. [Recommended UI Architecture](#recommended-ui-architecture)
8. [EPG / Program Guide](#epg--program-guide)
9. [Channel Management](#channel-management)
10. [Error Handling](#error-handling)
11. [Smart TV / D-pad Navigation Notes](#smart-tv--d-pad-navigation-notes)

---

## Server Info

- **Base URL**: `https://<server-ip>:3001`
- **Protocol**: HTTPS (self-signed cert — APK must trust it or disable cert verification for this host)
- **Content-Type**: All JSON endpoints accept and return `application/json`

---

## Authentication

All Live TV endpoints (except logo proxy) require a JWT Bearer token.

```
Authorization: Bearer <jwt_token>
```

The token is obtained from the existing login flow (`POST /api/auth/login`). The JWT payload contains:
```json
{
  "sub": 1,            // userId (integer)
  "username": "admin",
  "isAdmin": true,
  "iat": 1706000000,
  "exp": 1706604800
}
```

Token expiry is configurable server-side (default 7 days). On `401` or `403`, redirect to login.

---

## API Endpoints

### 1. GET /api/livetv/channels

**Auth required**: Yes

Fetches all enabled channels for the authenticated user, with current EPG data merged in. The server handles per-user filtering (disabled channels are excluded), sorting (favorites first, then by sort order), and EPG lookup.

**Response** `200 OK`:
```json
{
  "channels": [
    {
      "id": "espn",
      "name": "ESPN",
      "displayName": "ESPN",
      "group": "Live",
      "url": "https://tvpass.org/live/ESPN/sd",
      "logo": "/static/logos/espn.png",
      "sortOrder": 999,
      "channelNumber": 42,
      "isFavorite": true,
      "isEnabled": true,
      "currentProgram": {
        "start": 1706745600000,
        "stop": 1706749200000,
        "title": "SportsCenter",
        "description": "Latest scores and highlights from around the sports world.",
        "category": "Sports",
        "icon": "https://example.com/sportscenter.jpg",
        "episodeNum": "S2025E34"
      },
      "upcomingPrograms": [
        {
          "start": 1706749200000,
          "stop": 1706752800000,
          "title": "NFL Live",
          "description": "...",
          "category": "Sports",
          "icon": null,
          "episodeNum": null
        }
      ]
    }
  ]
}
```

**Key behaviors**:
- Channels are pre-sorted: favorites first, then by user sort order, then by channel number
- `isEnabled` is always `true` in the response (disabled channels are filtered out server-side)
- `currentProgram` is `null` if no EPG data exists or nothing is airing right now
- `upcomingPrograms` contains up to 12 programs within the next 6 hours, sorted by start time
- `logo` is a path like `/static/logos/espn.png` (prepend server base URL) or `null`
- All timestamps are Unix milliseconds
- First load for a new user auto-initializes ~64 channels as disabled (regional sports, foreign language, etc.)

**Error responses**:
- `401` — Missing/invalid token
- `500` — `{ "error": "Failed to load channels" }`

---

### 2. GET /api/livetv/stream/:channelId

**Auth required**: Yes

Proxies the HLS stream for a channel. The server handles stream source fallback (tries primary, original, then backup URLs automatically). This endpoint serves two roles:

**A) Manifest request** (no query params):
```
GET /api/livetv/stream/espn
```
Returns the M3U8 manifest with all segment URLs rewritten to route back through this same proxy endpoint. Response Content-Type: `application/vnd.apple.mpegurl`.

**B) Segment request** (with `?url=` query param):
```
GET /api/livetv/stream/espn?url=https%3A%2F%2Fexample.com%2Fseg001.ts
```
Proxies the raw TS segment or sub-manifest. The manifest rewriting done in (A) means segment URLs already point here — the player just follows them transparently.

**For APK playback, the stream URL to feed to ExoPlayer / your HLS player is simply**:
```
https://<server>:3001/api/livetv/stream/<channelId>
```
The player will fetch the manifest, which contains rewritten segment URLs that also go through the proxy. The player handles this transparently.

**Important**: Include the `Authorization` header on the initial manifest request. Segment requests also require auth since they go through the same authenticated route.

**Error responses**:
- `401` / `403` — Auth issues
- `502` — `{ "error": "All stream sources failed" }` — All stream URLs (primary + backups) failed
- `500` — `{ "error": "Stream proxy failed" }` — Channel not found or server error

---

### 3. PATCH /api/livetv/channels/:id/toggle

**Auth required**: Yes

Toggles a channel's enabled/disabled state for the current user.

**Request**: No body needed. The channel ID is in the URL path.

**Response** `200 OK`:
```json
{
  "success": true,
  "channelId": "espn-deportes",
  "isEnabled": false
}
```

The `isEnabled` field is the *new* state after toggling. If a channel was enabled, it becomes disabled, and vice versa.

---

### 4. POST /api/livetv/channels/:id/favorite

**Auth required**: Yes

Toggles favorite status for a channel.

**Request**: No body needed.

**Response** `200 OK`:
```json
{
  "success": true,
  "channelId": "espn",
  "isFavorite": true
}
```

Favorited channels sort to the top of the channel list on the next `GET /channels` call.

---

### 5. POST /api/livetv/channels/reorder

**Auth required**: Yes

Sets the user's custom channel sort order.

**Request body**:
```json
{
  "channelIds": ["espn", "cnn", "hbo-eastern-feed", "fox-news"]
}
```

The array position becomes the `sort_order` value (index 0 = highest priority). Only channels in the array get updated — others keep their existing sort order.

**Response** `200 OK`:
```json
{
  "success": true,
  "count": 4
}
```

---

### 6. GET /api/logo-proxy?url=<encoded_url>

**Auth required**: NO (public endpoint)

Proxies external logo images to handle CORS/SSL issues. Returns the image binary with correct Content-Type. Falls back to a 1x1 transparent PNG on error. Cached 24 hours via Cache-Control header.

**Usage**: Only needed if a channel's `logo` field is an external URL. Most channels use local logos (`/static/logos/...`) which don't need proxying.

---

## Data Shapes

### Channel Object (from GET /channels)

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Unique channel identifier, e.g. `"espn"`, `"cnn"`, `"hbo-eastern-feed"` |
| `name` | `string` | Raw channel name from M3U source |
| `displayName` | `string` | Clean display name from config, e.g. `"ESPN"`, `"CNN"`, `"HBO"` |
| `group` | `string` | Channel group. Currently all `"Live"` for M3U channels |
| `url` | `string` | Original stream URL (informational — use the proxy endpoint for playback) |
| `logo` | `string\|null` | Logo path like `/static/logos/espn.png`, or `null` if no logo |
| `sortOrder` | `number` | Sort priority (lower = higher in list) |
| `channelNumber` | `number` | 1-based channel number |
| `isFavorite` | `boolean` | Whether user favorited this channel |
| `isEnabled` | `boolean` | Always `true` in response (disabled filtered out) |
| `currentProgram` | `Program\|null` | What's on now, or `null` |
| `upcomingPrograms` | `Program[]` | Next up to 12 programs (6 hour window) |

### Program Object (EPG)

| Field | Type | Description |
|---|---|---|
| `start` | `number` | Start time as Unix timestamp in **milliseconds** |
| `stop` | `number` | End time as Unix timestamp in **milliseconds** |
| `title` | `string` | Program title, defaults to `"Unknown"` |
| `description` | `string` | Synopsis/description, may be empty string |
| `category` | `string\|null` | Genre/category like `"Sports"`, `"News"`, `"Entertainment"` |
| `icon` | `string\|null` | Program thumbnail URL (external) |
| `episodeNum` | `string\|null` | Episode identifier like `"S2E5"` or `"2025-01-31"` |

---

## Channel Logos

Logos are served as static PNG files:

```
https://<server>:3001/static/logos/<channelId>.png
```

Examples:
- `/static/logos/espn.png`
- `/static/logos/cnn.png`
- `/static/logos/hbo-eastern-feed.png`
- `/static/logos/abc-wabc-new-york-ny.png`

There are 177 logos covering all M3U channels. The `logo` field in the channel object already contains the correct path (e.g. `/static/logos/espn.png`), so just prepend the base URL.

**If `logo` is `null`**: Show a placeholder/fallback (channel initials, generic TV icon, etc.).

**If `logo` is an external URL** (possible for special/admin-added channels): Use the logo-proxy endpoint:
```
https://<server>:3001/api/logo-proxy?url=<encodeURIComponent(externalUrl)>
```

**Recommended**: Cache logo images aggressively on the APK side (they rarely change). The server sets `Cache-Control: public, max-age=86400` on the proxy endpoint, but static files can be cached even longer.

---

## HLS Stream Playback

### Basic Integration

The stream URL pattern for any channel is:
```
https://<server>:3001/api/livetv/stream/<channelId>
```

For Android/ExoPlayer:
```kotlin
val streamUrl = "${baseUrl}/api/livetv/stream/${channel.id}"

// ExoPlayer needs auth header on every request through the proxy
val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setDefaultRequestProperties(mapOf(
        "Authorization" to "Bearer $token"
    ))

val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
    .createMediaSource(MediaItem.fromUri(streamUrl))

player.setMediaSource(hlsSource)
player.prepare()
player.play()
```

### Key Points

1. **Auth on every request**: The manifest AND all segment requests go through the authenticated proxy. Configure your HTTP data source factory to include the `Authorization` header on all requests.

2. **Self-signed SSL**: The server uses HTTPS with a self-signed certificate. Your HTTP client/player must trust it. For OkHttp-based clients (ExoPlayer uses OkHttp), you may need a custom `TrustManager`.

3. **Stream fallback is server-side**: The server tries multiple stream sources automatically. If all fail, you get a `502`. The APK doesn't need to manage fallback URLs.

4. **Channel switching**: When switching channels, simply change the stream URL to the new channel's proxy endpoint. No need to stop/restart — just swap the media source.

5. **Buffering / error recovery**: HLS streams can be flaky. Configure ExoPlayer's `LoadErrorHandlingPolicy` for reasonable retry behavior (e.g., 3 retries with 2s backoff). On persistent 502 errors, show a "Stream unavailable" message.

6. **No seek/DVR**: These are live streams. Disable seek UI. Some channels may have DVR capability in the future (the config has `dvrEnabled` flags) but it's not implemented yet.

---

## Recommended UI Architecture

### Screen Layout (Smart TV / 10-foot UI)

```
+------------------------------------------------------------------+
|  LIVE TV                                          [Favorites] [All] |
+------------------------------------------------------------------+
|                                                                    |
|  +--------------------+  +--------------------------------------+  |
|  | CHANNEL LIST       |  | VIDEO PLAYER                         |  |
|  |                    |  |                                       |  |
|  | > [ESPN logo] ESPN |  |          (HLS stream plays here)      |  |
|  |   Now: SportsCenter|  |                                       |  |
|  |                    |  |                                       |  |
|  |   [CNN logo] CNN   |  +--------------------------------------+  |
|  |   Now: Anderson... |  | NOW PLAYING                           |  |
|  |                    |  | ESPN - SportsCenter                   |  |
|  |   [HBO logo] HBO   |  | "Latest scores and highlights..."    |  |
|  |   Now: Movie...    |  | 8:00 PM - 9:00 PM  [progress bar]    |  |
|  |                    |  +--------------------------------------+  |
|  |   [FOX logo] Fox   |  | UP NEXT                               |  |
|  |   Now: News...     |  | 9:00 PM - NFL Live                    |  |
|  |                    |  | 10:00 PM - PTI                         |  |
|  |   ...              |  | 11:00 PM - SportsCenter               |  |
|  +--------------------+  +--------------------------------------+  |
+------------------------------------------------------------------+
```

### Recommended Page States

1. **Loading** — Show skeleton/spinner while fetching `GET /channels`
2. **Channel List** — Left panel with scrollable channel list, right panel with player
3. **Full-screen Player** — Press Enter/Select on a channel to go full-screen; press Back to return to list
4. **Channel Guide (overlay)** — Optional: press a button (e.g., Guide/Info) to show EPG overlay on top of full-screen player
5. **Empty State** — "No channels available" if the response is empty (EPG sync hasn't run yet)
6. **Error State** — Network error, auth expired, etc.

### Data Flow

```
1. Page Mount
   |
   v
2. GET /api/livetv/channels  ──>  Store channel list in state
   |
   v
3. Auto-select first channel (or last-watched from local storage)
   |
   v
4. Set player source to /api/livetv/stream/<channelId>
   |
   v
5. Player starts streaming
   |
   v
6. Poll GET /api/livetv/channels every 5-10 minutes
   to refresh EPG (currentProgram changes over time)
```

### Channel List Item Component

Each item in the channel list should display:
- **Logo** (from `channel.logo`, 40-60px)
- **Display name** (from `channel.displayName`)
- **Current program title** (from `channel.currentProgram?.title` or "No program info")
- **Favorite indicator** (star/heart icon if `channel.isFavorite`)
- **Progress bar** showing how far into the current program we are:
  ```
  progress = (Date.now() - currentProgram.start) / (currentProgram.stop - currentProgram.start)
  ```

### Now Playing Info Bar

When a channel is selected/playing, show:
- Channel logo + display name
- Current program title + description
- Time range: format `start` and `stop` as local times (e.g., "8:00 PM - 9:00 PM")
- Progress bar (same formula as above)
- Up next: first 2-3 items from `upcomingPrograms`

---

## EPG / Program Guide

### Current Program

`channel.currentProgram` is the program airing right now. It's `null` if:
- No EPG data has been synced yet (server just started)
- The channel doesn't have EPG coverage from any of the 3 sources (TVPass, Pluto, epg.pw)
- It's between programs (gap in EPG data)

### Time Calculations

All timestamps are **Unix milliseconds** (same as `Date.now()` in JS or `System.currentTimeMillis()` in Kotlin).

```javascript
// Progress through current program (0.0 to 1.0)
const progress = (Date.now() - program.start) / (program.stop - program.start);

// Time remaining
const remainingMs = program.stop - Date.now();
const remainingMin = Math.ceil(remainingMs / 60000);

// Format time
const startTime = new Date(program.start).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
```

### EPG Refresh Strategy

The server syncs EPG every 30 minutes. The APK should:
- Fetch channels on page mount
- Re-fetch every 5-10 minutes to pick up updated `currentProgram` / `upcomingPrograms`
- Also re-fetch when resuming from background / sleep
- Use the `currentProgram.stop` time to know when the current program ends — you could schedule a targeted refresh at that time to update the "Now Playing" display

### Full Program Guide (Optional Enhancement)

The `upcomingPrograms` array gives up to 12 upcoming shows (6-hour window). This is enough for a "What's On" sidebar or mini-guide. For a full grid EPG guide (like cable TV), you'd need a dedicated endpoint (not yet implemented).

---

## Channel Management

### Favorites

Toggle with `POST /api/livetv/channels/:id/favorite`. After toggling:
- Update local state immediately (optimistic update)
- Favorited channels will sort to top on next full `GET /channels` fetch
- Consider storing the favorite state locally as well for instant UI updates

### Enable/Disable Channels

Toggle with `PATCH /api/livetv/channels/:id/toggle`. Use case: settings page where users can hide channels they don't watch. After toggling to disabled, the channel won't appear in future `GET /channels` responses.

### Channel Reorder

`POST /api/livetv/channels/reorder` with an array of channel IDs in the desired order. Use case: drag-and-drop (or move up/down) in a channel management screen.

### Settings Page Suggestion

Consider a "Manage Channels" settings page where users can:
- See ALL channels (including disabled ones — requires a separate "all channels" endpoint or a query param, which doesn't exist yet; for now just show enabled ones)
- Toggle favorites (star icon)
- Reorder via move up/down buttons (D-pad friendly)

---

## Error Handling

| Scenario | HTTP Status | Response | APK Action |
|---|---|---|---|
| Not authenticated | `401` | `{ "error": "Authentication required" }` | Redirect to login |
| Token expired | `403` | `{ "error": "Invalid or expired token" }` | Redirect to login, clear stored token |
| Channels not loaded yet | `200` | `{ "channels": [] }` | Show "Channels loading... EPG data is being synced" |
| Stream all sources failed | `502` | `{ "error": "All stream sources failed" }` | Show "Stream unavailable" overlay, offer retry |
| Stream proxy error | `500` | `{ "error": "Stream proxy failed" }` | Show error, channel may not exist |
| Channel not found | `500` | Error thrown | Show "Channel not found" |
| Server down | Network error | Connection refused/timeout | Show "Cannot connect to server" with retry |

### Retry Strategy

- **Channel list**: Retry 3 times with 2s backoff, then show error state with manual retry button
- **Stream**: ExoPlayer handles HLS retry internally. Configure 3 retries. On final failure, show overlay with "Retry" and "Try another channel" options
- **Toggle/favorite**: Fire and forget with optimistic UI update. On failure, revert the toggle and show a brief toast

---

## Smart TV / D-pad Navigation Notes

### Focus Management

- Channel list is the primary focus area on page load
- Up/Down arrows navigate the channel list
- Enter/Select on a channel starts playback AND optionally goes full-screen
- Left arrow (when player focused) returns focus to channel list
- Back button: if full-screen player → return to split view; if split view → exit Live TV page

### Channel Switching Shortcuts

- **Channel Up/Down** (if available on remote): Switch to prev/next channel without returning to list
- **Number keys** (0-9): If the remote has number keys, allow direct channel number entry (match against `channelNumber`)
- **Last channel**: Keep track of previously watched channel for a "Last" button

### Performance Tips

- **Preload adjacent channel thumbnails**: While browsing the list, preload logos for visible + 5 above/below
- **Don't block on EPG refresh**: Show stale data immediately, update in background
- **Debounce rapid channel switching**: If user is scrolling through channels quickly, don't start a new stream for each one. Wait 300-500ms after the last key press, or only start playback on Enter/Select
- **Keep player alive**: When switching channels, reuse the same player instance — just swap the media source. Creating/destroying players is expensive

### Recommended Key Mappings

| Remote Key | Action |
|---|---|
| D-pad Up/Down | Navigate channel list |
| D-pad Left | Focus channel list (from player) |
| D-pad Right | Focus player (from channel list) |
| Enter/Select/Center | Play selected channel / toggle full-screen |
| Back | Exit full-screen → exit page |
| Info/Guide | Show EPG overlay |
| Star/Bookmark | Toggle favorite for current channel |
| Ch+/Ch- | Next/previous channel (even in full-screen) |

---

## Quick Reference: Complete API Summary

```
GET    /api/livetv/channels              → { channels: Channel[] }
GET    /api/livetv/stream/:channelId     → HLS manifest (M3U8)
PATCH  /api/livetv/channels/:id/toggle   → { success, channelId, isEnabled }
POST   /api/livetv/channels/:id/favorite → { success, channelId, isFavorite }
POST   /api/livetv/channels/reorder      → { success, count }
GET    /api/logo-proxy?url=<encoded>     → image binary (no auth required)
GET    /static/logos/<channelId>.png     → image binary (no auth required)
```

All endpoints except logo-proxy and static require `Authorization: Bearer <token>` header.
