# Logo Loading Issue - Complete Analysis

## Quick Summary

**Your server is NOT sending logos to the Android app.** The server's `/api/search/tmdb/:id` endpoint doesn't fetch or return the `logoPath` field that the Android client expects.

## Files Created for You

1. **DIAGNOSIS.md** - Detailed technical diagnosis
2. **search.js.patch** - Code changes needed for the server
3. **check_tmdb_logos.sh** - Script to test if TMDB has logos for American Dad & Zootopia
4. **test_logo_fetch.js** - Node.js script to download and inspect logos

## How to Fix

### Step 1: Update Your Server Code

Apply the changes in `search.js.patch` to your server's `routes/search.js` file:

**Around line 295**, change:
```javascript
append_to_response: type === 'tv' ? 'credits,videos,external_ids,seasons' : 'credits,videos,external_ids'
```

To:
```javascript
append_to_response: type === 'tv' ? 'credits,videos,external_ids,seasons,images' : 'credits,videos,external_ids,images'
```

**Around line 306**, add the logoPath extraction:
```javascript
const result = {
  id: data.id,
  title: data.title || data.name,

  // Extract English logo (transparent PNG for loading screen overlays)
  logoPath: (() => {
    if (data.images?.logos?.length > 0) {
      const englishLogo = data.images.logos.find(logo => logo.iso_639_1 === 'en');
      return (englishLogo || data.images.logos[0]).file_path;
    }
    return null;
  })(),

  overview: data.overview,
  posterPath: data.poster_path,
  backdropPath: data.backdrop_path,
  // ... rest of fields
```

Also add these helpful fields while you're at it:
```javascript
  originalLanguage: data.original_language,  // For smart audio track selection
  spokenLanguages: data.spoken_languages?.map(lang => ({
    iso6391: lang.iso_639_1,
    name: lang.name
  })),
```

### Step 2: Test the Logos

Run the test script to see if TMDB has logos for American Dad and Zootopia:

```bash
cd /Users/aaron/projects/duckflix_lite/debug_logos

# Set your TMDB API key (get it from https://www.themoviedb.org/settings/api)
export TMDB_API_KEY='your-tmdb-api-key-here'

# Run the test
./check_tmdb_logos.sh
```

This will:
- Check if TMDB has logos for these titles
- Download the logo PNGs to this directory for you to inspect
- Show you the full URLs

### Step 3: Restart Your Server

After updating the code:
```bash
# SSH to your server
ssh your-server

# Navigate to server directory
cd /path/to/duckflix-lite-server-v2

# Restart the server (or use pm2 restart if using pm2)
npm restart
```

### Step 4: Test on Android

1. Clear the app data or cache (to force refresh)
2. Navigate to American Dad or Zootopia
3. Click Play
4. During the loading screen, you should now see the logo!

## What Was Wrong

### The Server (routes/search.js)
**Before:**
- Endpoint: `GET /api/search/tmdb/:id`
- Only fetched: credits, videos, external_ids
- Response had: posterPath, backdropPath
- Response was MISSING: logoPath ❌

**After:**
- Will also fetch: images (which contains logos)
- Will extract English logo or first available logo
- Will return: logoPath ✓

### The Android Client
The Android client code is **completely correct** and has been working properly all along:

1. ✓ Receives logoPath from server
2. ✓ Converts to full URL (https://image.tmdb.org/t/p/w500/...)
3. ✓ Passes through navigation with URL encoding
4. ✓ Displays in SourceSelectionScreen during loading

## Debug Commands

### Check what your server returns NOW (before fix):
```bash
curl "https://lite.duckflix.tv/api/search/tmdb/1433?type=tv" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.logoPath'
```
Expected: `null` (field doesn't exist)

### Check what your server returns AFTER fix:
```bash
curl "https://lite.duckflix.tv/api/search/tmdb/1433?type=tv" \
  -H "Authorization: Bearer YOUR_TOKEN" | jq '.logoPath'
```
Expected: `"/abc123.png"` (some path)

### Check Android logs:
```bash
adb logcat | grep -E "\[LOGO-DEBUG\]"
```

You should see:
```
[LOGO-DEBUG-DETAIL] logoUrl from content: https://image.tmdb.org/t/p/w500/...
[LOGO-DEBUG-NAV] logoUrl: https://image.tmdb.org/t/p/w500/...
[LOGO-DEBUG] Received logoUrl from navigation: https://image.tmdb.org/t/p/w500/...
[LOGO-DEBUG-SCREEN] logoUrl: https://image.tmdb.org/t/p/w500/...
```

## Test Results

Once you run `check_tmdb_logos.sh`, you'll get actual logo PNG files here:
- `American_Dad_logo.png` - The logo that should appear for American Dad
- `Zootopia_logo.png` - The logo that should appear for Zootopia

Open these files to see what they look like!

## Why This Happened

The server code was written to return `posterPath` and `backdropPath`, but logos weren't included. Logos are a separate field in TMDB's API that requires:
1. Including `images` in the `append_to_response` parameter
2. Parsing the `logos` array from the response
3. Preferring English (`en`) logos when available
4. Returning the `file_path` as `logoPath` in your API response

## Next Steps

1. Run `check_tmdb_logos.sh` to see the actual logos
2. Apply the server code fix
3. Restart your server
4. Test on Android

Let me know what you see when you run the logo check script!
