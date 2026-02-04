# User Features API Documentation

This document describes the user-specific endpoints for recommendations, watchlist, and watch progress tracking.

## Overview

The user API provides personalized features including:
- **Recommendations**: Auto-generated from TMDB based on watchlist and watch history
- **Trending**: TMDB trending content (movies/TV shows)
- **Watchlist**: User's saved items to watch later
- **Watch Progress**: Continue watching functionality with position tracking

## Storage Structure

All user data is stored in JSON files:

```
/server/db/
├── user_recommendations/
│   └── {username}.json        # Auto-generated recommendations
├── user_watchlist/
│   └── {username}.json        # User's watchlist
└── user_watch_progress/
    └── {username}.json        # Continue watching
```

## Authentication

All endpoints require JWT authentication:

```bash
Authorization: Bearer <token>
```

## Endpoints

### 1. Get Recommendations

**GET** `/api/user/recommendations`

Get personalized recommendations aggregated from all watchlist and watch progress items.

**Query Parameters:**
- `page` (optional, default: 1) - Page number
- `limit` (optional, default: 20) - Items per page

**Example Request:**
```bash
curl "http://localhost:3001/api/user/recommendations?page=1&limit=20" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "recommendations": [
    {
      "tmdbId": 12345,
      "type": "movie",
      "title": "Example Movie",
      "source": "watchlist",
      "posterPath": "/abc123.jpg",
      "releaseDate": "2024-01-15",
      "voteAverage": 7.8
    }
  ],
  "total": 150,
  "page": 1,
  "totalPages": 8,
  "hasMore": true
}
```

**Algorithm:**
1. Collects all TMDB recommendations from user's watchlist and watch progress
2. Counts occurrences of each recommendation (items appearing multiple times rank higher)
3. Sorts by count (descending)
4. Deduplicates against current watchlist and continue watching
5. Returns paginated results

---

### 2. Get Trending Content

**GET** `/api/user/trending`

Get TMDB trending content (updated daily/weekly).

**Query Parameters:**
- `mediaType` (optional, default: "all") - Options: "all", "movie", "tv"
- `timeWindow` (optional, default: "week") - Options: "day", "week"
- `page` (optional, default: 1) - Page number

**Example Request:**
```bash
curl "http://localhost:3001/api/user/trending?mediaType=movie&timeWindow=week&page=1" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "page": 1,
  "results": [
    {
      "id": 12345,
      "title": "Trending Movie",
      "poster_path": "/abc123.jpg",
      "release_date": "2024-01-15",
      "vote_average": 8.2,
      "media_type": "movie"
    }
  ],
  "total_pages": 100,
  "total_results": 2000
}
```

---

### 3. Get Watchlist

**GET** `/api/user/watchlist`

Get user's watchlist (items saved to watch later).

**Example Request:**
```bash
curl "http://localhost:3001/api/user/watchlist" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "watchlist": [
    {
      "tmdbId": 12345,
      "type": "movie",
      "title": "Example Movie",
      "posterPath": "/abc123.jpg",
      "releaseDate": "2024-01-15",
      "voteAverage": 7.8,
      "addedAt": "2024-02-01T10:30:00.000Z"
    }
  ]
}
```

---

### 4. Add to Watchlist

**POST** `/api/user/watchlist`

Add an item to watchlist and fetch TMDB recommendations.

**Request Body:**
```json
{
  "tmdbId": 12345,
  "type": "movie",
  "title": "Example Movie",
  "posterPath": "/abc123.jpg",
  "releaseDate": "2024-01-15",
  "voteAverage": 7.8
}
```

**Example Request:**
```bash
curl -X POST "http://localhost:3001/api/user/watchlist" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 12345,
    "type": "movie",
    "title": "Example Movie",
    "posterPath": "/abc123.jpg",
    "releaseDate": "2024-01-15",
    "voteAverage": 7.8
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Added to watchlist",
  "watchlist": [...]
}
```

**Side Effects:**
- Fetches up to 20 TMDB recommendations for this item
- Stores recommendations with source tag "watchlist"
- Updates user's recommendations file

---

### 5. Remove from Watchlist

**DELETE** `/api/user/watchlist/:tmdbId/:type`

Remove an item from watchlist.

**Example Request:**
```bash
curl -X DELETE "http://localhost:3001/api/user/watchlist/12345/movie" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "success": true,
  "message": "Removed from watchlist",
  "watchlist": [...]
}
```

**Side Effects:**
- If item is also NOT in continue watching, removes its TMDB recommendations
- Otherwise, keeps recommendations (item still in continue watching)

---

### 6. Get Watch Progress

**GET** `/api/user/watch-progress`

Get user's watch progress (continue watching).

**Example Request:**
```bash
curl "http://localhost:3001/api/user/watch-progress" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "continueWatching": [
    {
      "itemId": "movie_12345",
      "tmdbId": 12345,
      "type": "movie",
      "title": "Example Movie",
      "posterPath": "/abc123.jpg",
      "releaseDate": "2024-01-15",
      "position": 3600000,
      "duration": 7200000,
      "season": null,
      "episode": null,
      "updatedAt": "2024-02-01T10:30:00.000Z"
    }
  ]
}
```

**Fields:**
- `position`: Playback position in milliseconds
- `duration`: Total duration in milliseconds
- `season`/`episode`: For TV shows only

---

### 7. Update Watch Progress

**POST** `/api/user/watch-progress`

Update or create watch progress for an item.

**Request Body:**
```json
{
  "tmdbId": 12345,
  "type": "movie",
  "title": "Example Movie",
  "posterPath": "/abc123.jpg",
  "releaseDate": "2024-01-15",
  "position": 3600000,
  "duration": 7200000,
  "season": null,
  "episode": null
}
```

**Example Request:**
```bash
curl -X POST "http://localhost:3001/api/user/watch-progress" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 12345,
    "type": "movie",
    "title": "Example Movie",
    "posterPath": "/abc123.jpg",
    "releaseDate": "2024-01-15",
    "position": 3600000,
    "duration": 7200000
  }'
```

**Response:**
```json
{
  "success": true,
  "message": "Watch progress updated"
}
```

**Side Effects:**
- If this is a NEW item in continue watching, fetches TMDB recommendations
- Stores recommendations with source tag "continue-watching"
- For existing items, only updates position/duration

---

### 8. Remove from Watch Progress

**DELETE** `/api/user/watch-progress/:tmdbId/:type`

Remove an item from continue watching.

**Example Request:**
```bash
curl -X DELETE "http://localhost:3001/api/user/watch-progress/12345/movie" \
  -H "Authorization: Bearer $TOKEN"
```

**Response:**
```json
{
  "success": true,
  "message": "Removed from watch progress"
}
```

**Side Effects:**
- If item is also NOT in watchlist, removes its TMDB recommendations
- Otherwise, keeps recommendations (item still in watchlist)

---

## Recommendation Logic

### How Recommendations Work

1. **Collection**: When an item is added to watchlist or watch progress, the system fetches up to 20 recommendations from TMDB's `/recommendations` endpoint

2. **Storage**: Recommendations are stored per-item in the user's recommendations file:
   ```json
   {
     "movie_12345": {
       "tmdbId": 12345,
       "type": "movie",
       "title": "Source Movie",
       "source": "watchlist",
       "recommendations": [
         {
           "tmdbId": 67890,
           "type": "movie",
           "title": "Recommended Movie",
           "source": "watchlist",
           "posterPath": "/xyz.jpg",
           "releaseDate": "2024-01-01",
           "voteAverage": 8.0
         }
       ],
       "fetchedAt": "2024-02-01T10:00:00.000Z"
     }
   }
   ```

3. **Aggregation**: When fetching recommendations:
   - All recommendations from all stored items are collected
   - Each recommendation is counted (how many times it appears)
   - Sorted by count (most frequent first)
   - Filtered to remove items already in watchlist or continue watching
   - Paginated for display

4. **Cleanup**: Recommendations are removed when:
   - Item is removed from watchlist AND not in continue watching
   - Item is removed from continue watching AND not in watchlist

### Example Flow

```
1. User adds "The Matrix" to watchlist
   → Fetches 20 recommendations from TMDB
   → Stores with source="watchlist"

2. User starts watching "Inception"
   → Creates watch progress entry
   → Fetches 20 recommendations from TMDB
   → Stores with source="continue-watching"

3. User requests recommendations
   → Aggregates all 40 recommendations
   → "Interstellar" appears in both lists (count=2)
   → "The Prestige" only in Matrix recommendations (count=1)
   → Returns sorted: ["Interstellar", "The Prestige", ...]
   → Filters out "The Matrix" and "Inception" (already added)
```

---

## Error Responses

All endpoints return standard error responses:

```json
{
  "error": "Error category",
  "message": "Detailed error message"
}
```

**Common Status Codes:**
- `400` - Bad Request (missing required fields)
- `401` - Unauthorized (invalid/missing token)
- `404` - Not Found (item doesn't exist)
- `500` - Internal Server Error

---

## Testing Examples

### Complete User Flow

```bash
# 1. Login
TOKEN=$(curl -X POST http://localhost:3001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme123"}' \
  | jq -r '.token')

# 2. Add to watchlist
curl -X POST http://localhost:3001/api/user/watchlist \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 603,
    "type": "movie",
    "title": "The Matrix",
    "posterPath": "/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg",
    "releaseDate": "1999-03-30",
    "voteAverage": 8.2
  }'

# 3. Start watching something
curl -X POST http://localhost:3001/api/user/watch-progress \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 27205,
    "type": "movie",
    "title": "Inception",
    "posterPath": "/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg",
    "releaseDate": "2010-07-15",
    "position": 1800000,
    "duration": 8880000,
    "voteAverage": 8.4
  }'

# 4. Get recommendations (wait a few seconds for TMDB fetch)
curl "http://localhost:3001/api/user/recommendations?page=1&limit=10" \
  -H "Authorization: Bearer $TOKEN" | jq

# 5. Get trending
curl "http://localhost:3001/api/user/trending?mediaType=movie&timeWindow=week" \
  -H "Authorization: Bearer $TOKEN" | jq

# 6. View watchlist
curl http://localhost:3001/api/user/watchlist \
  -H "Authorization: Bearer $TOKEN" | jq

# 7. View continue watching
curl http://localhost:3001/api/user/watch-progress \
  -H "Authorization: Bearer $TOKEN" | jq
```

---

## Configuration

### Required Environment Variables

```bash
# Required for recommendations and trending
TMDB_API_KEY=your_tmdb_api_key_here
```

### Optional Settings

The recommendation service will gracefully handle missing TMDB API key by:
- Logging a warning
- Returning empty recommendations
- Continuing normal operation for watchlist/progress tracking

---

## File Structure

```
server/
├── routes/
│   └── user.js                           # User endpoints
├── services/
│   └── recommendations-service.js        # Recommendation logic
├── db/
│   ├── user_recommendations/             # Per-user recommendation storage
│   ├── user_watchlist/                   # Per-user watchlist storage
│   └── user_watch_progress/              # Per-user watch progress
└── USER_API_DOCUMENTATION.md             # This file
```

---

## Notes

- All timestamps are ISO 8601 format (UTC)
- Item IDs are formatted as `{type}_{tmdbId}` (e.g., "movie_12345", "tv_67890")
- Recommendations are fetched asynchronously to avoid blocking user requests
- Files are created on-demand when users first interact with features
- Storage is file-based JSON for simplicity and portability
