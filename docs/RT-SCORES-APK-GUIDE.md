# Rotten Tomatoes Scores — APK Implementation Guide

Backend API reference and implementation guide for displaying Rotten Tomatoes Tomatometer (critics %) and Popcornmeter (audience %) scores on the DuckFlix Lite detail screen.

---

## Table of Contents

1. [Overview](#overview)
2. [API Endpoint](#api-endpoint)
3. [Response Shapes](#response-shapes)
4. [Integration Flow](#integration-flow)
5. [Kotlin Implementation](#kotlin-implementation)
6. [UI Design Notes](#ui-design-notes)
7. [Edge Cases & Error Handling](#edge-cases--error-handling)
8. [Caching Behavior (Server-Side)](#caching-behavior-server-side)

---

## Overview

The server scrapes Rotten Tomatoes scores on-demand and caches them in SQLite. The APK fetches scores as a **separate, non-blocking call** alongside the existing TMDB detail request. This means:

- The detail screen loads instantly with TMDB data (existing flow — unchanged)
- RT scores load asynchronously and fade/pop in when ready (1-3s for first fetch, instant for cached)
- If RT scores fail or are unavailable, the screen still works perfectly with TMDB data alone

**Do NOT block the detail screen on RT scores.** Fire-and-forget alongside the TMDB detail call.

---

## API Endpoint

### `GET /api/search/tmdb/rt-scores/:tmdbId`

**Authentication**: Required (JWT Bearer token, same as all `/api/search/` endpoints)

**Query Parameters**:

| Parameter | Type   | Required | Description                          |
|-----------|--------|----------|--------------------------------------|
| `type`    | string | Yes      | `"movie"` or `"tv"`                  |
| `title`   | string | Yes      | Original title (URL-encoded)         |
| `year`    | number | No       | Release year (improves match accuracy for movies) |

**Example Requests**:

```
GET /api/search/tmdb/rt-scores/278?type=movie&title=The%20Shawshank%20Redemption&year=1994
GET /api/search/tmdb/rt-scores/1396?type=tv&title=Breaking%20Bad&year=2008
GET /api/search/tmdb/rt-scores/550?type=movie&title=Fight%20Club&year=1999
```

---

## Response Shapes

### Scores Found

```json
{
  "tmdbId": 278,
  "criticsScore": 91,
  "audienceScore": 98,
  "rtUrl": "https://www.rottentomatoes.com/m/shawshank_redemption",
  "available": true,
  "cached": true
}
```

### Scores Not Found (title not on RT)

```json
{
  "tmdbId": 99999,
  "criticsScore": null,
  "audienceScore": null,
  "rtUrl": null,
  "available": false,
  "cached": false
}
```

### Partial Scores (one score found, other missing)

```json
{
  "tmdbId": 12345,
  "criticsScore": 87,
  "audienceScore": null,
  "rtUrl": "https://www.rottentomatoes.com/m/some_movie",
  "available": true,
  "cached": false
}
```

### Field Reference

| Field          | Type     | Description                                                  |
|----------------|----------|--------------------------------------------------------------|
| `tmdbId`       | number   | Echo of the requested TMDB ID                                |
| `criticsScore` | int/null | Tomatometer (0-100%). `null` if unavailable.                 |
| `audienceScore`| int/null | Popcornmeter / Audience Score (0-100%). `null` if unavailable.|
| `rtUrl`        | string/null | Direct URL to the RT page. `null` if not found.           |
| `available`    | boolean  | `true` if at least one score was found                       |
| `cached`       | boolean  | `true` if served from server cache (instant), `false` if freshly scraped |

---

## Integration Flow

### When to Fetch

Call the RT scores endpoint when the user navigates to a **detail screen** (movie or TV show). You already have all the required parameters from the TMDB detail response:

```
TMDB Detail Response:
  id          → tmdbId parameter
  title/name  → title parameter
  releaseDate → extract year
  (type)      → type parameter (you already know this from navigation context)
```

### Sequence Diagram

```
User taps movie/show
  │
  ├── GET /api/search/tmdb/:id?type=movie     (existing — loads detail screen)
  │     └── Response: { title, overview, voteAverage, posterPath, ... }
  │           └── Render detail screen immediately with TMDB data
  │
  └── GET /api/search/tmdb/rt-scores/:id?...  (NEW — parallel, non-blocking)
        └── Response: { criticsScore, audienceScore, ... }
              └── Update UI: show RT scores (fade in / animate)
```

Both calls fire in parallel. The detail screen renders as soon as the TMDB call returns. RT scores update the UI when they arrive.

### Where to Get the Parameters

From the TMDB detail response you already have:

```kotlin
// From your existing detail screen data
val tmdbId = detailResponse.id           // e.g. 278
val title = detailResponse.title         // e.g. "The Shawshank Redemption"
val type = if (isTvShow) "tv" else "movie"
val year = detailResponse.releaseDate?.take(4)  // "1994-09-23" → "1994"
```

---

## Kotlin Implementation

### Data Class

```kotlin
data class RTScores(
    val tmdbId: Int,
    val criticsScore: Int?,
    val audienceScore: Int?,
    val rtUrl: String?,
    val available: Boolean,
    val cached: Boolean
)
```

### API Interface (Retrofit)

```kotlin
@GET("api/search/tmdb/rt-scores/{tmdbId}")
suspend fun getRTScores(
    @Path("tmdbId") tmdbId: Int,
    @Query("type") type: String,
    @Query("title") title: String,
    @Query("year") year: Int? = null
): Response<RTScores>
```

### ViewModel Integration

```kotlin
// In your detail screen ViewModel
private val _rtScores = MutableStateFlow<RTScores?>(null)
val rtScores: StateFlow<RTScores?> = _rtScores

fun loadDetail(tmdbId: Int, type: String) {
    viewModelScope.launch {
        // Existing TMDB detail call (unchanged)
        launch {
            val detail = api.getDetail(tmdbId, type)
            _detail.value = detail

            // Once we have title/year, fetch RT scores
            launch {
                fetchRTScores(
                    tmdbId = tmdbId,
                    type = type,
                    title = detail.title,
                    year = detail.releaseDate?.take(4)?.toIntOrNull()
                )
            }
        }
    }
}

private suspend fun fetchRTScores(tmdbId: Int, type: String, title: String, year: Int?) {
    try {
        val response = api.getRTScores(tmdbId, type, title, year)
        if (response.isSuccessful) {
            _rtScores.value = response.body()
        }
    } catch (e: Exception) {
        // Silent failure — RT scores are optional
        Log.w("RTScores", "Failed to fetch RT scores for $tmdbId: ${e.message}")
    }
}
```

### Alternative: Fire Both Calls in Parallel from the Start

If you already know the title and year from the list screen (e.g., from search results or continue watching), you can fire both calls simultaneously without waiting for the TMDB detail response:

```kotlin
fun loadDetail(tmdbId: Int, type: String, titleHint: String?, yearHint: Int?) {
    viewModelScope.launch {
        // Fire in parallel
        val detailJob = async { api.getDetail(tmdbId, type) }
        val rtJob = if (titleHint != null) {
            async { api.getRTScores(tmdbId, type, titleHint, yearHint) }
        } else null

        // TMDB detail finishes first → render screen
        _detail.value = detailJob.await()

        // RT scores arrive → update UI
        rtJob?.let {
            try {
                val response = it.await()
                if (response.isSuccessful) _rtScores.value = response.body()
            } catch (e: Exception) {
                Log.w("RTScores", "RT fetch failed: ${e.message}")
            }
        }
    }
}
```

---

## UI Design Notes

### Score Display

Both scores are **percentages (0-100)**. Display with `%` suffix.

**Tomatometer (critics)**:
- Fresh: >= 60% — show red tomato icon
- Rotten: < 60% — show green splat icon
- No score: hide or show dash

**Audience Score (Popcornmeter)**:
- Full popcorn: >= 60%
- Spilled popcorn: < 60%
- No score: hide or show dash

### Layout Suggestion

Place RT scores near the existing TMDB rating on the detail screen:

```
┌─────────────────────────────────────────┐
│  The Shawshank Redemption (1994)        │
│  ★ 8.7  |  🍅 91%  |  🍿 98%           │
│          TMDB   Critics   Audience      │
└─────────────────────────────────────────┘
```

Or as a separate row below the title:

```
┌─────────────────────────────────────────┐
│  The Shawshank Redemption               │
│  1994 · 2h 22m · R                      │
│                                         │
│  ★ 8.7/10    🍅 91%    🍿 98%           │
│  TMDB        Critics   Audience         │
└─────────────────────────────────────────┘
```

### Animation

Since RT scores load async, animate them in:

```kotlin
// Compose example
AnimatedVisibility(
    visible = rtScores != null && rtScores.available,
    enter = fadeIn(animationSpec = tween(300))
) {
    RTScoreRow(rtScores!!)
}
```

### When `available = false`

Don't show RT scores at all. Don't show "N/A" or empty placeholders — just hide the RT section. The TMDB rating stands alone.

### When Only One Score Exists

Show whichever score is available. If `criticsScore` is `null` but `audienceScore` is not (or vice versa), display the one that exists and hide the other.

---

## Edge Cases & Error Handling

| Scenario                    | Server Response                           | APK Behavior                        |
|-----------------------------|-------------------------------------------|-------------------------------------|
| Title found, both scores    | `available: true`, both scores non-null   | Show both scores                    |
| Title found, partial scores | `available: true`, one score null         | Show available score, hide missing  |
| Title not on RT             | `available: false`, both scores null      | Hide RT section entirely            |
| Server error / timeout      | HTTP 500 or network error                 | Hide RT section, log warning        |
| RT rate-limited             | `available: false`, both scores null      | Hide RT section (server retries next request after 60s cooldown) |
| Auth expired                | HTTP 401/403                              | Normal auth refresh flow            |

**Never crash or show error UI** for RT score failures. These are supplementary data — always degrade gracefully to showing only the TMDB rating.

---

## Caching Behavior (Server-Side)

The server handles all caching — the APK does **not** need its own RT score cache. Just call the endpoint every time the detail screen opens.

| Title Age         | Cache TTL | Notes                                    |
|-------------------|-----------|------------------------------------------|
| >= 2 years old    | 180 days  | Scores are stable, rarely change         |
| < 2 years old     | 14 days   | Scores still aggregating from new reviews|
| Not found on RT   | 7 days    | Avoids re-scraping missing titles        |

- **First request for a title**: 1-3 seconds (scrapes RT — `cached: false`)
- **Subsequent requests**: instant from SQLite cache (`cached: true`)
- **Concurrent requests**: Server deduplicates — if 3 users open the same movie simultaneously, only 1 scrape happens

The `cached` field in the response is informational — the APK doesn't need to act on it differently. It's useful for debugging ("why was this slow?" → `cached: false` means first fetch).

---

## Quick Reference

```
Endpoint:    GET /api/search/tmdb/rt-scores/:tmdbId
Auth:        Bearer token (required)
Params:      type (required), title (required), year (optional)
Success:     { tmdbId, criticsScore, audienceScore, rtUrl, available, cached }
Error:       { error: "..." } with appropriate HTTP status
Scores:      0-100 integer (percentage) or null
```
