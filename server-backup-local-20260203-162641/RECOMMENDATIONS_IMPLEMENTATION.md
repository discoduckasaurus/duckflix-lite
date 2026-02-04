# User Recommendations Backend Implementation

**Date**: 2024-02-03
**Status**: Complete
**Tasks**: #1 (Recommendations API), #2 (Trending API)

---

## Overview

Implemented a complete user recommendations and personalization backend following the specifications provided. The system auto-generates personalized recommendations based on user's watchlist and watch history, integrating with TMDB's recommendation engine.

---

## What Was Implemented

### 1. Recommendations Service (`server/services/recommendations-service.js`)

**Features:**
- TMDB recommendations fetching
- Recommendation aggregation and ranking
- File-based storage per user
- Deduplication logic
- Trending content API wrapper

**Key Methods:**
- `addItemRecommendations()` - Fetches and stores TMDB recommendations for an item
- `removeItemRecommendations()` - Cleans up recommendations when item is removed
- `aggregateRecommendations()` - Aggregates, ranks, and paginates recommendations
- `getTrending()` - Fetches TMDB trending content

**Algorithm:**
1. Count occurrences of each recommendation across all sources
2. Sort by count (items appearing in multiple recommendation sets rank higher)
3. Filter out items already in watchlist or continue watching
4. Apply pagination with configurable limit

---

### 2. User Routes (`server/routes/user.js`)

**Endpoints Created:**

#### Recommendations
- `GET /api/user/recommendations` - Get personalized recommendations
  - Query params: `page`, `limit`
  - Returns aggregated recommendations with pagination

#### Trending
- `GET /api/user/trending` - Get TMDB trending content
  - Query params: `mediaType` (all/movie/tv), `timeWindow` (day/week), `page`

#### Watchlist
- `GET /api/user/watchlist` - Get user's watchlist
- `POST /api/user/watchlist` - Add item to watchlist + fetch recommendations
- `DELETE /api/user/watchlist/:tmdbId/:type` - Remove from watchlist

#### Watch Progress (Continue Watching)
- `GET /api/user/watch-progress` - Get continue watching list
- `POST /api/user/watch-progress` - Update playback position + fetch recommendations
- `DELETE /api/user/watch-progress/:tmdbId/:type` - Remove from continue watching

**Side Effects:**
- Adding to watchlist triggers TMDB recommendation fetch (non-blocking)
- First-time watch progress triggers TMDB recommendation fetch (non-blocking)
- Removing items cleans up recommendations if not in other list

---

### 3. Storage Structure

Created three storage directories:

```
/server/db/
├── user_recommendations/{username}.json
├── user_watchlist/{username}.json
└── user_watch_progress/{username}.json
```

**Recommendation File Format:**
```json
{
  "movie_12345": {
    "tmdbId": 12345,
    "type": "movie",
    "title": "Example Movie",
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

---

## Integration Points

### Server Index (`server/index.js`)

Added user routes to the Express app:
```javascript
const userRoutes = require('./routes/user');
app.use('/api/user', userRoutes);
```

### Environment Variables

**Required:**
- `TMDB_API_KEY` - For fetching recommendations and trending

The service gracefully handles missing TMDB key by logging warnings and returning empty results.

---

## Key Features

### 1. Smart Recommendation Ranking

Recommendations are ranked by occurrence count across all sources:
- Movie appears in 3 different recommendation sets → Higher rank
- Movie appears in 1 recommendation set → Lower rank

This surfaces items that are frequently recommended, indicating higher relevance.

### 2. Automatic Deduplication

Recommendations automatically exclude:
- Items already in watchlist
- Items already in continue watching

This ensures users only see new content they haven't added yet.

### 3. Source Tracking

Each recommendation knows its source:
- `"watchlist"` - Recommended based on watchlist item
- `"continue-watching"` - Recommended based on watch history

This allows future enhancements like filtering by source.

### 4. Cleanup Logic

Recommendations are cleaned up intelligently:
- Item in watchlist + continue watching → Keep recommendations
- Item in watchlist only → Remove when deleted from watchlist
- Item in continue watching only → Remove when deleted from progress
- Item in neither → Recommendations automatically cleaned up

### 5. Pagination

Full pagination support:
- Configurable page size (default: 20)
- Returns total count, page info, hasMore flag
- Efficient for large recommendation sets

---

## API Response Formats

### Recommendations Response
```json
{
  "recommendations": [...],
  "total": 150,
  "page": 1,
  "totalPages": 8,
  "hasMore": true
}
```

### Trending Response
```json
{
  "page": 1,
  "results": [...],
  "total_pages": 100,
  "total_results": 2000
}
```

### Watchlist Response
```json
{
  "watchlist": [...]
}
```

### Watch Progress Response
```json
{
  "continueWatching": [...]
}
```

---

## Testing

### Manual Testing Commands

See `TESTING.md` for full examples. Quick test:

```bash
# Login
TOKEN=$(curl -X POST http://localhost:3001/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"changeme123"}' \
  | jq -r '.token')

# Add to watchlist
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

# Get recommendations (wait a few seconds for TMDB fetch)
curl "http://localhost:3001/api/user/recommendations" \
  -H "Authorization: Bearer $TOKEN" | jq
```

---

## File Changes

**New Files:**
- `server/services/recommendations-service.js` (231 lines)
- `server/routes/user.js` (353 lines)
- `server/USER_API_DOCUMENTATION.md` (Complete API docs)
- `server/RECOMMENDATIONS_IMPLEMENTATION.md` (This file)

**Modified Files:**
- `server/index.js` (Added user routes import and registration)
- `server/TESTING.md` (Added testing examples for new endpoints)

**New Directories:**
- `server/db/user_recommendations/`
- `server/db/user_watchlist/`
- `server/db/user_watch_progress/`

---

## Performance Considerations

### Non-Blocking TMDB Fetches

TMDB recommendation fetches are asynchronous and non-blocking:
- User gets immediate success response
- Recommendations fetched in background
- Errors logged but don't block user action

### File-Based Storage

Used JSON files instead of database for:
- Simplicity - no schema migrations needed
- Portability - easy to backup/restore
- Performance - direct file I/O is fast for user-scoped data
- Flexibility - schema can evolve without migrations

### Efficient Aggregation

Recommendation aggregation uses:
- Map for O(1) deduplication
- Single pass through all recommendations
- Efficient filtering with Set-like lookups

---

## Future Enhancements

Potential improvements:
1. **Cache TMDB recommendations** - Avoid re-fetching for same items
2. **Recommendation refresh** - Periodic updates of recommendations
3. **Custom weighting** - Adjust ranking based on recency, user rating, etc.
4. **Genre filtering** - Filter recommendations by preferred genres
5. **Collaborative filtering** - Cross-user recommendations
6. **Analytics** - Track which recommendations users click/watch

---

## Compliance with Specification

### Storage ✓
- Location: `/server/db/user_recommendations/{username}.json`
- Structure: Keys are item IDs (e.g., "movie_123456")
- Values contain: `{tmdbId, type, title, source, recommendations[], fetchedAt}`

### Aggregation ✓
- Count occurrences across all sources
- Sort by count (descending)
- Shuffle within same-count groups (deterministic based on tmdbId)

### Deduplication ✓
- Filter out items in watchlist
- Filter out items in continue watching

### Pagination ✓
- Support `page` and `limit` query params
- Default limit: 20

### Response Format ✓
```json
{
  "recommendations": [],
  "total": 0,
  "page": 1,
  "totalPages": 0,
  "hasMore": false
}
```

### Integration ✓
- Fetch TMDB recommendations when items added to watchlist
- Fetch TMDB recommendations when items added to watch progress
- Store first 20 results with source tag
- Delete recommendations when item removed and not in other list

---

## Conclusion

The user recommendations backend is fully implemented and ready for integration with the Android client. All endpoints are tested and documented. The system provides intelligent, personalized recommendations with proper deduplication and cleanup.

**Status**: ✅ Ready for Android integration

Ruff ruff.
