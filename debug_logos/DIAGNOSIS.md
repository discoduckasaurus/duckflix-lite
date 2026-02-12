# Logo Loading Issue - Diagnosis Report

## Problem Summary
**No logos are displaying on the loading screen.** The Android client expects a `logoPath` field from the server, but the server is NOT returning it.

## Root Cause
The server's `/api/search/tmdb/:id` endpoint (in `routes/search.js`) does NOT fetch or return logos from TMDB.

### Current Server Response (lines 301-329 of search.js):
```javascript
const result = {
  id: data.id,
  title: data.title || data.name,
  overview: data.overview,
  posterPath: data.poster_path,
  backdropPath: data.backdrop_path,
  releaseDate: data.release_date || data.first_air_date,
  voteAverage: data.vote_average,
  runtime: data.runtime || (data.episode_run_time && data.episode_run_time[0]),
  genres: data.genres,
  cast: [...],
  // ... TV-specific fields
};
```

**Missing:** `logoPath` field!

### What the Android Client Expects (TmdbDetailResponse.kt line 39):
```kotlin
@Json(name = "logoPath") val logoPath: String?, // English logo (transparent PNG)
```

## Code Flow Verification

1. ✓ **DetailScreen.kt** (line 91-92): Logs `content.logoUrl` and passes it to navigation
2. ✓ **DuckFlixApp.kt** (line 155-162): Receives logoUrl and passes to Player route
3. ✓ **DuckFlixApp.kt** (line 54-55): URL-encodes logoUrl into route
4. ✓ **DuckFlixApp.kt** (line 223-226): Defines logoUrl navigation argument
5. ✓ **VideoPlayerViewModel.kt** (line 102): Extracts logoUrl from savedStateHandle
6. ✓ **VideoPlayerViewModel.kt** (line 122-124): Sets logoUrl into UI state
7. ✓ **VideoPlayerScreen.kt** (line 195, 209, 224): Passes logoUrl to SourceSelectionScreen
8. ✓ **SourceSelectionScreen.kt** (line 118-133): Displays logo if logoUrl is not null

**The client code is correct!** The issue is the server.

## Test Cases

### Test 1: American Dad!
- TMDB ID: 1433
- Type: TV Show
- Expected: Should have English logo available

### Test 2: Zootopia
- TMDB ID: 269149
- Type: Movie
- Expected: Should have English logo available

## Manual Test Commands

### Check what TMDB provides for American Dad:
```bash
curl "https://api.themoviedb.org/3/tv/1433/images?api_key=YOUR_API_KEY" | jq '.logos[] | select(.iso_639_1 == "en") | {path: .file_path, width, height}'
```

### Check what TMDB provides for Zootopia:
```bash
curl "https://api.themoviedb.org/3/movie/269149/images?api_key=YOUR_API_KEY" | jq '.logos[] | select(.iso_639_1 == "en") | {path: .file_path, width, height}'
```

### Check what YOUR server returns for American Dad:
```bash
curl "https://lite.duckflix.tv/api/search/tmdb/1433?type=tv" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.logoPath'
```

Expected result: `null` (because the field doesn't exist)

## Solution

The server needs to be updated to:

1. Fetch images from TMDB's `/images` endpoint
2. Extract the English logo (or first logo if no English version)
3. Return the `logoPath` field in the response

### Required Server Changes

Update `routes/search.js` endpoint `/api/search/tmdb/:id` to include:

```javascript
// Around line 295, add images to append_to_response:
const response = await axios.get(url, {
  params: {
    api_key: tmdbApiKey,
    append_to_response: type === 'tv'
      ? 'credits,videos,external_ids,seasons,images'  // Add images
      : 'credits,videos,external_ids,images'  // Add images
  }
});

// Around line 306-329, extract and add logoPath:
const data = response.data;

// Extract English logo (transparent PNG for overlays)
let logoPath = null;
if (data.images && data.images.logos && data.images.logos.length > 0) {
  // Prefer English logo
  const englishLogo = data.images.logos.find(logo => logo.iso_639_1 === 'en');
  logoPath = (englishLogo || data.images.logos[0]).file_path;
}

const result = {
  id: data.id,
  title: data.title || data.name,
  overview: data.overview,
  posterPath: data.poster_path,
  backdropPath: data.backdrop_path,
  logoPath: logoPath,  // ADD THIS LINE
  releaseDate: data.release_date || data.first_air_date,
  voteAverage: data.vote_average,
  runtime: data.runtime || (data.episode_run_time && data.episode_run_time[0]),
  genres: data.genres,
  originalLanguage: data.original_language,  // Also add this for audio track selection
  spokenLanguages: data.spoken_languages?.map(lang => ({
    iso6391: lang.iso_639_1,
    name: lang.name
  })),
  cast: data.credits?.cast?.slice(0, 10).map(c => ({
    name: c.name,
    character: c.character,
    profilePath: c.profile_path,
    id: c.id  // Also add actor ID for navigation
  })) || [],
  externalIds: data.external_ids,
  // ... rest of fields
};
```

## Debug Output Locations

After implementing the fix, check these logs:

1. **Server logs**: Should show `logoPath` in the response
2. **Android logs** (search for `[LOGO-DEBUG]`):
   - VideoPlayerViewModel: "Received logoUrl from navigation"
   - DetailScreen: "logoUrl from content" and "logoUrl parameter"
   - DuckFlixApp: "Navigation to player" with logoUrl
   - SourceSelectionScreen: "SourceSelectionScreen parameters" with logoUrl
   - SourceSelectionScreen: Image loading errors (if any)

## Expected Behavior After Fix

1. Server returns `logoPath: "/abc123.png"` in detail response
2. Android converts it to full URL: `https://image.tmdb.org/t/p/w500/abc123.png`
3. URL gets passed through navigation (URL-encoded)
4. VideoPlayerViewModel receives and sets it in state
5. SourceSelectionScreen displays the logo during loading phases

The logo will appear at 220dp height (see SourceSelectionScreen.kt line 128) centered above the animated loading text.
