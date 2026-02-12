# Android Client Debug Steps

I've added comprehensive debug logging to trace where logoPath is getting lost. Here's what to do:

## Step 1: Rebuild and Install APK

```bash
cd /Users/aaron/projects/duckflix_lite/android

# Clean build
./gradlew clean

# Build and install debug APK
./gradlew installDebug

# Or if using single APK detection:
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Step 2: Clear App Data

**IMPORTANT:** Clear the app's cache to ensure you're not using old cached responses:

```bash
# Clear app data
adb shell pm clear com.duckflix.lite

# Or via device:
# Settings → Apps → DuckFlix Lite → Storage → Clear Data
```

## Step 3: Watch Logs in Real-Time

Open a terminal and run:

```bash
adb logcat | grep -E "\[LOGO-DEBUG|logoPath|logoUrl"
```

This will show:
- `[LOGO-DEBUG-HTTP]` - Raw HTTP response from server
- `[LOGO-DEBUG-DETAIL-VM]` - What DetailViewModel received
- `[LOGO-DEBUG-DETAIL]` - When navigating to player
- `[LOGO-DEBUG-NAV]` - Navigation parameters
- `[LOGO-DEBUG]` - VideoPlayerViewModel receiving logoUrl
- `[LOGO-DEBUG-SCREEN]` - SourceSelectionScreen displaying logo

## Step 4: Test with American Dad

1. Launch the app
2. Search for "American Dad"
3. Click on "American Dad!" (the TV show)
4. **WATCH THE LOGS** - You should see:

```
[LOGO-DEBUG-HTTP] GET https://lite.duckflix.tv/api/search/tmdb/1433?type=tv
[LOGO-DEBUG-HTTP] {
[LOGO-DEBUG-HTTP]   "id": 1433,
[LOGO-DEBUG-HTTP]   "title": "American Dad!",
[LOGO-DEBUG-HTTP]   "logoPath": "/chsje6mcnELTJrM2tVlaHQ8dLpk.png",
[LOGO-DEBUG-HTTP]   "posterPath": "/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg",
[LOGO-DEBUG-HTTP]   ...
[LOGO-DEBUG-HTTP] }

[LOGO-DEBUG-DETAIL-VM] ==========================================
[LOGO-DEBUG-DETAIL-VM] Loaded details for: American Dad!
[LOGO-DEBUG-DETAIL-VM] TMDB ID: 1433
[LOGO-DEBUG-DETAIL-VM] logoPath (raw): /chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-DETAIL-VM] logoUrl (computed): https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-DETAIL-VM] posterPath: /3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg
[LOGO-DEBUG-DETAIL-VM] posterUrl: https://image.tmdb.org/t/p/w500/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg
[LOGO-DEBUG-DETAIL-VM] ==========================================
```

5. Click any "Play" button
6. **WATCH THE LOGS** - You should see:

```
[LOGO-DEBUG-DETAIL] Preparing to play: American Dad!
[LOGO-DEBUG-DETAIL] logoUrl from content: https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-DETAIL] logoUrl parameter: https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-DETAIL] posterUrl: https://image.tmdb.org/t/p/w500/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg

[LOGO-DEBUG-NAV] Navigation to player:
[LOGO-DEBUG-NAV]   title: American Dad!
[LOGO-DEBUG-NAV]   posterUrl: https://image.tmdb.org/t/p/w500/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg
[LOGO-DEBUG-NAV]   logoUrl: https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-NAV]   route: player/1433/American%20Dad%21?year=2005&type=tv&season=...&logoUrl=https%3A%2F%2Fimage.tmdb.org%2F...

[LOGO-DEBUG] Received logoUrl from navigation: https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG] Received posterUrl from navigation: https://image.tmdb.org/t/p/w500/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg

[LOGO-DEBUG-SCREEN] SourceSelectionScreen parameters:
[LOGO-DEBUG-SCREEN]   logoUrl: https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-SCREEN]   backdropUrl: https://image.tmdb.org/t/p/w500/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg
[LOGO-DEBUG-SCREEN]   message:
```

## What to Look For

### ❌ Problem 1: logoPath is null in HTTP response
```
[LOGO-DEBUG-HTTP] "logoPath": null
```
**Solution:** Server issue (but you said server is working, so this shouldn't happen)

### ❌ Problem 2: logoPath is there but logoUrl is null
```
[LOGO-DEBUG-DETAIL-VM] logoPath (raw): /chsje6mcnELTJrM2tVlaHQ8dLpk.png
[LOGO-DEBUG-DETAIL-VM] logoUrl (computed): null
```
**Solution:** Bug in TmdbDetailResponse.logoUrl getter (TmdbDtos.kt line 68-75)

### ❌ Problem 3: logoUrl is computed but not passed to navigation
```
[LOGO-DEBUG-DETAIL-VM] logoUrl (computed): https://...
[LOGO-DEBUG-DETAIL] logoUrl from content: https://...
[LOGO-DEBUG-DETAIL] logoUrl parameter: null  ← DIFFERENT!
```
**Solution:** Bug in how DetailScreen calls onPlayClick

### ❌ Problem 4: logoUrl passed to nav but not received by player
```
[LOGO-DEBUG-NAV] logoUrl: https://...
[LOGO-DEBUG] Received logoUrl from navigation: null  ← LOST IN NAVIGATION!
```
**Solution:** Navigation URL encoding issue or argument parsing issue

### ❌ Problem 5: logoUrl received but image fails to load
```
[LOGO-DEBUG-SCREEN] logoUrl: https://...
[ERROR] Failed to load logo: https://... - <error message>
```
**Solution:** Network/Coil image loading issue

## Step 5: Share the Logs

After running the test, copy the full log output and share it. The logs will tell us exactly where the logoPath is getting lost!

## Quick Verification Commands

### Check if app data was cleared:
```bash
adb shell "ls -la /data/data/com.duckflix.lite/cache/ 2>/dev/null | wc -l"
```
Should show 0 or very few files after clearing data.

### Force stop the app:
```bash
adb shell am force-stop com.duckflix.lite
```

### Launch the app:
```bash
adb shell am start -n com.duckflix.lite/.MainActivity
```

### Get full logcat dump (if real-time monitoring isn't working):
```bash
adb logcat -d > /tmp/duckflix_logcat.txt
grep -E "\[LOGO-DEBUG|logoPath|logoUrl" /tmp/duckflix_logcat.txt
```

## Expected Result

If everything is working correctly, you should see the American Dad! logo appear in the center of the loading screen at 220dp height, above the animated "Fabricating Films" text.

The logo should be the transparent PNG from:
`https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png`
