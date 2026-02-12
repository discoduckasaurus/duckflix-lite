# Logo Loading Issue - Complete Summary

## Current Status

✅ **Server is CONFIRMED working** - Returns logoPath correctly:
```json
{
  "logoPath": "/chsje6mcnELTJrM2tVlaHQ8dLpk.png",
  "posterPath": "/3J7jRaMB6edkP1IXN7gUZCUxpsJ.jpg",
  "backdropPath": "/mc3rG5M9dFVjMfaCFNfbD5gu2pK.jpg"
}
```

❓ **Android client is the problem** - Logos not appearing on loading screen

## What I Changed

I've added comprehensive debug logging to track exactly where logoPath gets lost:

### File 1: DetailViewModel.kt (lines 62-73)
Added logging when detail response is received from server:
```kotlin
println("[LOGO-DEBUG-DETAIL-VM] logoPath (raw): ${details.logoPath}")
println("[LOGO-DEBUG-DETAIL-VM] logoUrl (computed): ${details.logoUrl}")
```

### File 2: NetworkModule.kt (lines 44-50)
Enhanced HTTP logging to highlight logo-related responses:
```kotlin
if (message.contains("logoPath") || message.contains("/search/tmdb/")) {
    println("[LOGO-DEBUG-HTTP] $message")
}
```

## Possible Issues (Ranked by Likelihood)

### 1. Cached Old Response (MOST LIKELY)
**Problem:** App has cached response from before server was updated

**Test:**
```bash
# Clear app data
adb shell pm clear com.duckflix.lite

# Then test again
```

**Symptoms:**
- `[LOGO-DEBUG-HTTP]` shows `"logoPath": null` or field is missing
- Or HTTP response never shows in logs (using cache)

**Fix:** Clear cache and rebuild app

---

### 2. Moshi Code Generation Issue
**Problem:** Moshi adapter not regenerated after adding logoPath field

**Test:**
```bash
cd android
./gradlew clean
./gradlew build
```

**Symptoms:**
- `[LOGO-DEBUG-HTTP]` shows logoPath in JSON
- `[LOGO-DEBUG-DETAIL-VM]` shows `logoPath (raw): null`

**Fix:** Clean build to regenerate Moshi adapters

---

### 3. Image Loading Failure
**Problem:** logoUrl is passed correctly but Coil can't load the image

**Test:** Look for error in logs:
```
[ERROR] Failed to load logo: https://... - <error message>
```

**Symptoms:**
- All debug logs show correct URLs
- Image still doesn't appear
- Error logged in SourceSelectionScreen.kt line 123

**Fix:** Check network connectivity, CORS, or image URL validity

---

### 4. Navigation URL Encoding Issue
**Problem:** logoUrl gets mangled during URL encoding/decoding

**Test:** Compare these two log lines:
```
[LOGO-DEBUG-NAV]   logoUrl: https://image.tmdb.org/t/p/w500/abc.png
[LOGO-DEBUG]       Received logoUrl: https://image.tmdb.org/t/p/w500/abc.png
```

**Symptoms:**
- URLs don't match (e.g., extra encoding or decoding)
- Special characters in URL are corrupted

**Fix:** Review URL encoding in DuckFlixApp.kt lines 54-55

---

### 5. Null Pointer in Display Logic
**Problem:** logoUrl is correct but screen doesn't show it

**Test:** Check this log:
```
[LOGO-DEBUG-SCREEN]   logoUrl: https://...
```
Should NOT be null.

**Symptoms:**
- All logs show correct logoUrl
- But screen still shows no logo
- No error logs

**Fix:** Check SourceSelectionScreen.kt line 118 - maybe `if (logoUrl != null)` is failing

## How to Debug

Run this command and share the output:

```bash
# Method 1: Real-time monitoring
adb logcat | grep -E "\[LOGO-DEBUG|logoPath|logoUrl"

# Method 2: Full dump after testing
adb logcat -c  # Clear logs
# ... use the app, navigate to American Dad, click play ...
adb logcat -d | grep -E "\[LOGO-DEBUG|logoPath|logoUrl" > /tmp/logo_debug.txt
cat /tmp/logo_debug.txt
```

## Test Procedure

1. **Clear app data:**
   ```bash
   adb shell pm clear com.duckflix.lite
   ```

2. **Rebuild app:**
   ```bash
   cd android
   ./gradlew clean assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Start logging:**
   ```bash
   adb logcat -c
   adb logcat | grep -E "\[LOGO-DEBUG|Failed to load logo"
   ```

4. **Use the app:**
   - Launch DuckFlix Lite
   - Search for "American Dad"
   - Click on "American Dad!"
   - Click any "Play" button
   - Wait for loading screen

5. **Check logs** - You should see:
   ```
   [LOGO-DEBUG-HTTP] GET .../search/tmdb/1433?type=tv
   [LOGO-DEBUG-HTTP] "logoPath": "/chsje6mcnELTJrM2tVlaHQ8dLpk.png"
   [LOGO-DEBUG-DETAIL-VM] logoPath (raw): /chsje6mcnELTJrM2tVlaHQ8dLpk.png
   [LOGO-DEBUG-DETAIL-VM] logoUrl (computed): https://image.tmdb.org/t/p/w500/chsje6mcnELTJrM2tVlaHQ8dLpk.png
   [LOGO-DEBUG-DETAIL] logoUrl from content: https://...
   [LOGO-DEBUG-NAV] logoUrl: https://...
   [LOGO-DEBUG] Received logoUrl: https://...
   [LOGO-DEBUG-SCREEN] logoUrl: https://...
   ```

## What to Share

Please share:
1. **Full log output** from the grep command above
2. **Screenshot** of the loading screen (so we can see if logo appears)
3. **App version** being tested (check build.gradle for versionName)

## Files Changed

The changes are already applied to your codebase:
- ✅ `android/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailViewModel.kt`
- ✅ `android/app/src/main/java/com/duckflix/lite/di/NetworkModule.kt`

## Expected Behavior

When working correctly, the loading screen should display:

```
┌─────────────────────────────────────┐
│                                     │
│     [American Dad! Logo PNG]        │  ← 220dp height, centered
│                                     │
│                                     │
│      Mixing Movies                  │  ← Animated slot machine text
│                                     │
│   One moment, finding the          │  ← Message
│        best source                  │
│                                     │
│         [Cancel]                    │
│                                     │
└─────────────────────────────────────┘
```

The logo is rendered at SourceSelectionScreen.kt lines 118-133.
