# User API Quick Reference

Quick reference for the new user personalization endpoints.

## Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/user/recommendations` | Get personalized recommendations |
| GET | `/api/user/trending` | Get TMDB trending content |
| GET | `/api/user/watchlist` | Get user's watchlist |
| POST | `/api/user/watchlist` | Add to watchlist |
| DELETE | `/api/user/watchlist/:tmdbId/:type` | Remove from watchlist |
| GET | `/api/user/watch-progress` | Get continue watching |
| POST | `/api/user/watch-progress` | Update watch position |
| DELETE | `/api/user/watch-progress/:tmdbId/:type` | Remove from continue watching |

## Quick Examples

### Get Recommendations
```bash
curl "http://localhost:3001/api/user/recommendations?page=1&limit=20" \
  -H "Authorization: Bearer $TOKEN"
```

### Get Trending
```bash
curl "http://localhost:3001/api/user/trending?mediaType=movie&timeWindow=week" \
  -H "Authorization: Bearer $TOKEN"
```

### Add to Watchlist
```bash
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
```

### Update Watch Progress
```bash
curl -X POST http://localhost:3001/api/user/watch-progress \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tmdbId": 27205,
    "type": "movie",
    "title": "Inception",
    "position": 1800000,
    "duration": 8880000
  }'
```

## Required Fields

### Watchlist Item
- `tmdbId` (number) - TMDB ID
- `type` (string) - "movie" or "tv"
- `title` (string) - Item title
- `posterPath` (string, optional) - Poster image path
- `releaseDate` (string, optional) - Release date
- `voteAverage` (number, optional) - TMDB rating

### Watch Progress
- `tmdbId` (number) - TMDB ID
- `type` (string) - "movie" or "tv"
- `title` (string) - Item title
- `position` (number) - Playback position in milliseconds
- `duration` (number) - Total duration in milliseconds
- `season` (number, optional) - For TV shows
- `episode` (number, optional) - For TV shows

## Response Formats

### Recommendations
```json
{
  "recommendations": [
    {
      "tmdbId": 12345,
      "type": "movie",
      "title": "Example Movie",
      "posterPath": "/abc.jpg",
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

### Trending
```json
{
  "page": 1,
  "results": [...],
  "total_pages": 100,
  "total_results": 2000
}
```

## Storage Locations

```
/server/db/
├── user_recommendations/{username}.json
├── user_watchlist/{username}.json
└── user_watch_progress/{username}.json
```

## Environment Variables

```bash
TMDB_API_KEY=your_api_key_here  # Required for recommendations
```

## Documentation

- Full API docs: `USER_API_DOCUMENTATION.md`
- Implementation details: `RECOMMENDATIONS_IMPLEMENTATION.md`
- Testing examples: `TESTING.md`

---

**Status**: ✅ Production Ready
