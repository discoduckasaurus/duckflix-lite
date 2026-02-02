# Android TV Storage Research for DuckFlix Lite DVR

**Document Version:** 1.0
**Date:** February 1, 2026
**Purpose:** Comprehensive research on Android TV storage capabilities to inform implementation decisions for DuckFlix Lite's DVR recording feature.

---

## Executive Summary

This document provides detailed research on Android TV storage capabilities, focusing on practical considerations for implementing a DVR recording system. Key findings:

- **Recommended Approach**: Use app-specific directories with optional MANAGE_EXTERNAL_STORAGE permission for advanced users
- **File System Considerations**: FAT32's 4GB limit is a critical constraint for long recordings
- **Storage Requirements**: HD OTA recordings need 1.8-3.6 GB/hour (ATSC 1.0) or 0.4-1.0 GB/hour (ATSC 3.0)
- **Platform Diversity**: Android TV devices vary significantly in storage capabilities (8GB-32GB internal, optional SD card support)

---

## 1. Android Scoped Storage (Android 10+)

### Overview

Since Android 10 (API level 29), Google introduced **scoped storage** to improve user privacy and reduce file system clutter. Apps targeting Android 11 (API level 30) or higher are required to use scoped storage.

### How Scoped Storage Works on Android TV

**Core Principle**: Apps get unrestricted access only to their app-specific directories, with restricted access to shared storage.

**App-Specific Directories**:
- Internal: `/data/data/<package>/files/`
- External: `/sdcard/Android/data/<package>/files/`

These directories are automatically created by the system and don't require any permissions to access.

### Key Characteristics

**Automatic Access**:
- No permissions needed for app-specific directories
- Files are automatically deleted when the app is uninstalled
- Other apps cannot access these files on Android 11+

**External App-Specific Storage**:
```
/sdcard/Android/data/com.duckflix.lite/files/recordings/
```

**Benefits**:
- Zero permission requests for basic functionality
- Clean user experience
- Automatic cleanup on uninstall
- Works consistently across Android TV devices

**Limitations**:
- Files are deleted when app is uninstalled (recordings lost)
- Cannot easily share recordings with other apps
- Limited to device's available storage
- Cannot write to external SD cards without SAF

### Best Practices for DVR Recordings

According to official Android documentation (updated January 2026):

1. **Store recordings in app-specific directories by default**
   - Path: `context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)`
   - No permissions required
   - Survives app updates

2. **Use MediaStore for shared recordings** (if recordings should persist):
   - Recordings accessible via system media apps
   - Requires `READ_MEDIA_VIDEO` permission (Android 13+)
   - System automatically indexes videos

3. **Handle insufficient storage gracefully**:
   - Android TV devices often have limited storage
   - Use `RecordingCallback.onError(RECORDING_ERROR_INSUFFICIENT_SPACE)` from the TV Input Framework
   - Pre-allocate storage when `onCreateRecordingSession()` is called

### DVR-Specific Considerations

The Android TV documentation recommends:
- Complete time-consuming tasks like allocating storage space when `onCreateRecordingSession()` is invoked
- Use best judgment when allocating storage due to limited TV device storage
- Implement proper error handling for insufficient space scenarios

**Known Issues**:
- Some Sony Android TV firmware updates have changed removable media permissions, preventing recordings on external storage
- Scoped storage restrictions can limit flexibility for advanced DVR features

---

## 2. MANAGE_EXTERNAL_STORAGE Permission

### What This Permission Provides

`MANAGE_EXTERNAL_STORAGE` is a special "All Files Access" permission that grants broad storage access:

**Access Includes**:
- Read/write access to all shared storage (internal and SD card)
- Root directory of USB OTG drives and SD cards
- All files in `/sdcard/` (except Android/data, Android/obb from other apps on Android 11+)
- Bypass scoped storage restrictions

**Verification**:
```java
if (Environment.isExternalStorageManager()) {
    // App has all files access
}
```

### How to Request on Android TV

**1. Declare in AndroidManifest.xml**:
```xml
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

**2. Direct user to system settings**:
```java
Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
startActivity(intent);
```

**3. User grants permission**:
- Navigate to: Settings > Apps > Special app access > All files access
- Find your app and toggle permission on

### User Experience Considerations

**For Sideloaded Apps**:
- Google Play Store restrictions don't apply to sideloaded apps
- No need for Play Console approval or declaration forms
- Users installing via APK typically understand advanced permissions

**Manual Grant Process**:
1. App checks if permission is granted
2. If not, show explanation dialog
3. Launch Settings intent
4. User manually toggles permission
5. User returns to app (no automatic callback)
6. App re-checks permission status

**Deep Linking to Settings**:
```java
// Generic all files access settings
Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);

// App-specific (may not work on all Android TV devices)
Uri uri = Uri.fromParts("package", getPackageName(), null);
Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri);
```

### Play Store Restrictions (Not Applicable for DuckFlix Lite)

Google Play has strict policies since May 2021:
- Apps must complete a "Permissions Declaration Form"
- Only approved use cases qualify (file managers, backup apps, antivirus)
- DVR apps may qualify under "media management" but require approval

**For Sideloaded DuckFlix Lite**: These restrictions don't apply, giving flexibility to use this permission.

### Recommended Implementation

**Tiered Approach**:

1. **Default Mode** (no special permissions):
   - Use app-specific directory
   - Simple, works out-of-box
   - Limited to available internal storage

2. **Advanced Mode** (MANAGE_EXTERNAL_STORAGE):
   - Let user choose recording location
   - Support external SD cards
   - Access to larger storage pools
   - Optional, for power users

---

## 3. External SD Card Access

### How Android TV Devices Handle External Storage

Android TV devices handle external storage (SD cards, USB drives) as part of "external storage" alongside internal shared storage:

**Storage Paths**:
- Internal shared storage: `/sdcard/` (actually internal, not removable)
- SD card: `/storage/[UUID]/` or `/sdcard/` (if adopted)
- USB OTG: `/storage/[UUID]/`

### Two Storage Modes

**1. Removable Storage** (Default):
- SD card remains portable
- Can be removed and read on computers
- Apps need SAF or MANAGE_EXTERNAL_STORAGE for write access
- Better for sharing recordings

**2. Adoptable Storage** (Android 6.0+):
- SD card formatted as internal storage
- Encrypted and tied to device
- Apps can use normally (extends `/sdcard/`)
- Cannot be removed without data loss
- Not all Android TV devices support this

### DocumentFile API for SD Card Access

The **Storage Access Framework (SAF)** uses DocumentFile for SD card access:

**User Flow**:
1. App launches folder picker: `ACTION_OPEN_DOCUMENT_TREE`
2. User selects SD card folder
3. App receives persistent URI permission
4. App uses DocumentFile to read/write

**Example Code**:
```java
// Request access
Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
startActivityForResult(intent, REQUEST_CODE);

// Use DocumentFile
Uri treeUri = data.getData();
DocumentFile pickedDir = DocumentFile.fromTreeUri(context, treeUri);
DocumentFile newFile = pickedDir.createFile("video/mp2t", "recording.ts");
```

**Advantages**:
- Works without special permissions
- User explicitly grants access
- Persistent across app restarts

**Disadvantages**:
- Complex API compared to File API
- Slower performance (30-50% slower writes)
- TV-friendly UI picker is challenging
- Limited access scope (only selected folder)

### Storage Access Framework (SAF) Limitations

Important constraints discovered in Android 11+ research:

**Restricted Directories**:
- Cannot access SD card root directory via SAF
- Cannot access `Android/data` or `Android/obb` on SD cards
- Download directory access restricted

**With MANAGE_EXTERNAL_STORAGE**:
- Full root directory access to SD cards and USB drives
- Can bypass SAF limitations
- Direct File API access works normally

### Detecting Available External Storage

**Check for External Storage**:
```java
File[] externalDirs = context.getExternalFilesDirs(null);
// externalDirs[0] = internal
// externalDirs[1+] = SD cards/USB drives (if available)

for (File dir : externalDirs) {
    if (Environment.isExternalStorageRemovable(dir)) {
        // This is a removable SD card/USB
        long freeSpace = dir.getFreeSpace();
    }
}
```

**Storage Capacity Check**:
```java
StatFs stat = new StatFs(path);
long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
long totalBytes = stat.getBlockSizeLong() * stat.getBlockCountLong();
```

### Write Performance on SD Cards

**Performance Considerations**:

1. **SD Card Classes**:
   - Class 10: 10 MB/s minimum write speed
   - UHS-I U1: 10 MB/s minimum
   - UHS-I U3: 30 MB/s minimum (recommended for HD video)
   - V30/V60/V90: Video speed classes for sustained writes

2. **OTA Recording Requirements**:
   - ATSC 1.0 HD: ~13 Mbps ≈ 1.6 MB/s (well within Class 10)
   - ATSC 3.0 4K: ~20-30 Mbps ≈ 2.5-3.75 MB/s (easily handled)

3. **Practical Performance**:
   - Modern SD cards (2024-2026) easily handle DVR recording
   - Buffering can smooth out write variations
   - Sequential writes perform better than random

**File System Performance**:
- exFAT: Better for large files, good performance
- FAT32: Wide compatibility but 4GB file limit
- ext4: Best performance but requires formatting (loses portability)

### Recommended SD Card Specifications for DuckFlix Lite

- Minimum: Class 10 / U1
- Recommended: U3 / V30
- Capacity: 64GB+ (40-60 hours of HD recording)
- Brand: SanDisk, Samsung, or Kingston for reliability

---

## 4. Storage Quotas and Limits

### Android TV Storage Quotas

Android TV uses **ext4 filesystem with quota support** to prevent individual apps from monopolizing storage:

**Quota Rules** (as of 2026):
- No single app can consume more than **90% of disk space**
- No single app can consume more than **50% of available inodes** (file count limit)
- System monitors space consumption per app

**Quota Enforcement**:
```java
// App will receive IOException when quota exceeded
try {
    // Write recording file
} catch (IOException e) {
    // Likely quota exceeded or disk full
}
```

### Maximum File Sizes

**File System Limits**:

1. **ext4** (internal storage):
   - Maximum file size: 16 TB
   - No practical limit for DVR recordings

2. **FAT32** (common on SD cards):
   - **Maximum file size: 4 GB - 1 byte** (4,294,967,295 bytes)
   - **Critical limitation for DVR recordings**
   - A 3-hour HD recording at 10 Mbps = 13.5 GB (exceeds limit)

3. **exFAT** (modern SD cards):
   - Maximum file size: 16 exabytes (no practical limit)
   - Requires Android device support (most modern devices support it)
   - Some older/budget Android TV devices may not support exFAT

**FAT32 Compatibility Issues**:
- Not all Android TV devices support exFAT
- Some manufacturers (e.g., Nokia) refused Microsoft licensing for exFAT
- Device-specific: Samsung and Motorola support exFAT, some Nokia phones don't

### Storage Capacity Requirements by Device Type

**Chromecast with Google TV**:
- Original 4K model: 8 GB total (only ~5 GB available)
- HD model (2022): Slightly more available storage
- Google TV Streamer (2024): **32 GB total** (major improvement)
- External storage support via USB-C hub

**Fire TV Devices**:
- Fire TV Stick: 8 GB total (~4-5 GB available)
- Fire TV 4K Max: 16 GB total
- Some models have SD card slots
- External USB storage support with "MIXED" formatting option

**Nvidia Shield TV**:
- Shield TV: 16 GB internal
- Shield TV Pro: 16 GB internal
- USB 3.0 ports for external storage
- Supports adoptable storage

**Generic Android TV Boxes**:
- Range from 8 GB to 64 GB internal
- Often include SD card slots
- Variable quality and support

### Available Storage Detection

**Check Free Space**:
```java
public long getAvailableSpace(File directory) {
    StatFs stat = new StatFs(directory.getAbsolutePath());
    return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
}
```

**Storage Categories**:
```java
StorageManager storageManager = (StorageManager) getSystemService(STORAGE_SERVICE);
UUID appSpecificInternalDirUuid = storageManager.getUuidForPath(getFilesDir());

// Check available space in bytes
long availableBytes = storageManager.getAllocatableBytes(appSpecificInternalDirUuid);

// Request space allocation (throws if insufficient)
long bytesToAllocate = 1024 * 1024 * 1024; // 1 GB
storageManager.allocateBytes(appSpecificInternalDirUuid, bytesToAllocate);
```

### Warning When Storage is Low

**Low Storage Thresholds**:

1. **System Low Storage**:
   - Android shows warning when <10% free space remains
   - Some functionality may be limited
   - Apps should stop recording

2. **App Quota Warning**:
   - Monitor app's storage consumption
   - Warn when approaching 80% of available space
   - Stop recording at 90% threshold

**Implementation**:
```java
public boolean hasEnoughSpace(long requiredBytes) {
    long freeSpace = getRecordingDir().getFreeSpace();
    long lowSpaceThreshold = 500 * 1024 * 1024; // 500 MB buffer

    return freeSpace > (requiredBytes + lowSpaceThreshold);
}

public void checkStorageDuringRecording() {
    if (!hasEnoughSpace(100 * 1024 * 1024)) { // Need at least 100 MB buffer
        stopRecording();
        notifyUserLowStorage();
    }
}
```

### Auto-Cleanup Strategies

**Recommended Approaches**:

1. **Oldest-First Deletion**:
   - Delete oldest recordings when space low
   - Keep configurable minimum free space (e.g., 1 GB)

2. **User-Configurable Retention**:
   - Auto-delete recordings older than X days
   - Default: 7 days for unwatched, 30 days for watched

3. **Storage Limits**:
   - Set maximum storage quota for recordings (e.g., 20 GB)
   - Warn user when approaching limit

4. **Protected Recordings**:
   - Allow users to "lock" important recordings
   - Never auto-delete protected recordings

**Example Implementation**:
```java
public void cleanupOldRecordings() {
    File recordingsDir = getRecordingsDirectory();
    long minFreeSpace = 1024 * 1024 * 1024; // 1 GB

    if (recordingsDir.getFreeSpace() < minFreeSpace) {
        List<File> recordings = getRecordingsSortedByDate();

        for (File recording : recordings) {
            if (!isProtected(recording)) {
                recording.delete();

                if (recordingsDir.getFreeSpace() >= minFreeSpace) {
                    break;
                }
            }
        }
    }
}
```

---

## 5. Recording Storage Requirements

### Typical OTA TV Stream Bitrates (2026)

**ATSC 1.0** (Current Standard - MPEG-2):

Broadcasting standards in 2026 show significant variation:

- **NBC**: ~9.2 Mbps average (1080i MPEG-2)
- **CBS**: ~13.3 Mbps average (1080i MPEG-2)
- **Theoretical Maximum**: ~19 Mbps (full ATSC 1.0 bandwidth)
- **Typical Range**: 5-15 Mbps for major networks

**Real-World Examples**:
- CBS 3-hour NFL game: 17.87 GB (13.3 Mbps)
- NBC 3-hour event: 12.92 GB (9.2 Mbps)

**ATSC 3.0** (NextGen TV - HEVC):

ATSC 3.0 is actively rolling out in 2026:

- **4K HDR Stream**: ~20-30 Mbps (with HEVC compression)
- **HD Stream**: ~4-8 Mbps (HEVC)
- **Storage Efficiency**: ~25% of ATSC 1.0 for equivalent quality
- **Visual Quality**: 4 Mbps HEVC ≈ 16 Mbps MPEG-2

**Compression Comparison**:
- H.265 (HEVC) reduces bandwidth by ~50% vs. H.264
- H.265 allows 20-40% more footage storage on same drive vs. H.264
- H.264 reduces video size by 80% vs. MJPEG, 50% vs. MPEG-4

### Storage Needed Per Hour

**ATSC 1.0 (MPEG-2) Calculations**:

| Bitrate | MB/s | GB/Hour | Example |
|---------|------|---------|---------|
| 5 Mbps  | 0.625 | 2.25 GB | Low-quality local channel |
| 8 Mbps  | 1.0   | 3.6 GB  | Typical HD channel |
| 10 Mbps | 1.25  | 4.5 GB  | High-quality HD channel |
| 13.3 Mbps | 1.66 | 5.98 GB | CBS example |
| 15 Mbps | 1.875 | 6.75 GB | Premium HD |

**Formula**:
```
GB per hour = (Mbps × 3600 seconds) / (8 bits per byte × 1024 MB per GB)
GB per hour ≈ Mbps × 0.45
```

**ATSC 3.0 (HEVC) Calculations**:

| Bitrate | MB/s | GB/Hour | Quality |
|---------|------|---------|---------|
| 4 Mbps  | 0.5  | 1.8 GB  | HD equivalent to 16 Mbps MPEG-2 |
| 8 Mbps  | 1.0  | 3.6 GB  | Very high quality HD |
| 20 Mbps | 2.5  | 9.0 GB  | 4K HDR |
| 30 Mbps | 3.75 | 13.5 GB | Premium 4K HDR |

### Recommended Minimum Storage

**Conservative Estimates (ATSC 1.0 @ 10 Mbps)**:

| Storage | Recording Hours | Notes |
|---------|----------------|-------|
| 16 GB   | ~3.5 hours     | Minimum viable (Chromecast with Google TV base) |
| 32 GB   | ~7 hours       | Acceptable for light use |
| 64 GB   | ~14 hours      | Good for moderate use |
| 128 GB  | ~28 hours      | Recommended minimum for regular DVR use |
| 256 GB  | ~56 hours      | Comfortable buffer for heavy users |
| 512 GB  | ~113 hours     | Enthusiast level |

**Optimistic Estimates (ATSC 3.0 @ 4 Mbps HEVC)**:

| Storage | Recording Hours | Notes |
|---------|----------------|-------|
| 32 GB   | ~17 hours      | Acceptable for ATSC 3.0 users |
| 64 GB   | ~35 hours      | Good baseline |
| 128 GB  | ~71 hours      | Recommended |
| 256 GB  | ~142 hours     | ~6 days continuous |

**Practical Recommendation for DuckFlix Lite**:
- **Minimum**: 32 GB available storage
- **Recommended**: 128 GB+ (via SD card or large internal storage)
- **Optimal**: 256 GB+ for serious DVR users

### Storage for Different Quality Levels

If implementing quality options (transcoding vs. direct recording):

**Direct Recording (Passthrough)**:
- Bitrate: As-broadcast (5-15 Mbps typically)
- Storage: 2.25-6.75 GB/hour
- CPU: Minimal
- Quality: Original broadcast quality

**H.264 Re-encode**:
- Bitrate: 4-8 Mbps (configurable)
- Storage: 1.8-3.6 GB/hour
- CPU: High (may not be viable on low-end Android TV)
- Quality: Slight quality loss

**H.265 Re-encode**:
- Bitrate: 2-4 Mbps (configurable)
- Storage: 0.9-1.8 GB/hour
- CPU: Very high (likely not viable on most Android TV devices)
- Quality: Minimal quality loss with good encoder

**Recommendation**:
DuckFlix Lite should use **passthrough recording** (no re-encoding) to:
- Minimize CPU usage
- Preserve original quality
- Ensure recording reliability
- Avoid real-time encoding failures

Re-encoding can be optional post-processing task for advanced users.

### File Segmentation Strategy

Given FAT32's 4GB limit and practical considerations:

**Segmented Recording Approach**:
- Split recordings into 1-hour segments
- Each segment = ~4.5 GB (safe under FAT32 limit)
- Playlist file (.m3u8) references all segments
- Benefits:
  - FAT32 compatible
  - Easier to manage
  - Resume watching while recording continues
  - Partial recovery if recording fails

**Segment Naming**:
```
recordings/
  program_name_2026-02-01_20-00/
    segment_001.ts
    segment_002.ts
    segment_003.ts
    playlist.m3u8
    metadata.json
```

---

## 6. Android TV Platform Considerations

### Google TV (Chromecast with Google TV)

**Chromecast with Google TV 4K** (2020-2024):
- Internal Storage: 8 GB total (~5 GB available)
- RAM: 2 GB
- No SD card slot
- External storage: USB-C hub required
- USB OTG support: Yes (via hub)

**Chromecast with Google TV HD** (2022):
- Internal Storage: 8 GB total (~5.5 GB available)
- RAM: 1.5 GB
- No SD card slot
- External storage: USB-C hub required

**Google TV Streamer** (2024 - Current Flagship):
- Internal Storage: **32 GB total** (~25+ GB available)
- RAM: 4 GB
- No SD card slot
- USB-C port for external storage
- Supports USB hubs with multiple ports
- Ethernet built-in

**Storage Expansion**:
- Requires USB-C hub ($15-30)
- USB flash drives work (USB 2.0+ recommended)
- External SSDs supported
- Formatting: FAT32 or exFAT (device-dependent)

**DVR Viability**:
- ❌ Base model (8GB): Not viable without external storage
- ⚠️ With USB storage: Viable but clunky setup
- ✅ Google TV Streamer (32GB): Viable for light DVR use (5-7 hours)
- ✅ With external storage: Fully viable

### Fire TV Devices

**Fire TV Stick** (various generations):
- Internal Storage: 8 GB total (~4-5 GB available)
- RAM: 1-2 GB (depending on model)
- No SD card slot on most models
- USB storage: Via micro-USB OTG adapter

**Fire TV Stick 4K Max** (2023-2024):
- Internal Storage: 16 GB total (~12 GB available)
- RAM: 2 GB
- No SD card slot
- USB storage: Via USB-C OTG

**Fire TV Cube** (3rd Gen, 2022):
- Internal Storage: 16 GB
- RAM: 2 GB
- Ethernet: Built-in
- USB storage: USB-A port available

**Storage Formatting Options**:
- **Removable Storage**: Portable, can use on PC
- **MIXED Storage**: Portion for apps, portion for files
- 4 GB per recording limit on external storage (reported by users)

**DVR Viability**:
- ⚠️ Fire TV Stick 8GB: Limited without external storage
- ✅ Fire TV 4K Max (16GB): Viable for light use
- ✅ Fire TV Cube: Good for DVR with external storage
- ⚠️ 4GB recording limit: May require file segmentation

### Nvidia Shield TV

**Shield TV** (2019-current):
- Internal Storage: 16 GB
- RAM: 2 GB
- No SD card slot
- USB 3.0 ports (2x)
- Ethernet: Built-in
- Most powerful Android TV device

**Shield TV Pro** (2019-current):
- Internal Storage: 16 GB
- RAM: 3 GB
- USB 3.0 ports (2x)
- Ethernet: Built-in
- Designed for Plex server, gaming

**Storage Features**:
- Adoptable storage support
- USB 3.0 for high-speed external drives
- Can act as Plex Media Server
- Excellent for DVR applications

**DVR Viability**:
- ✅ Best-in-class for Android TV DVR
- ✅ Powerful enough for potential transcoding
- ✅ Excellent I/O performance
- ✅ Popular among cord-cutters

### Custom Android TV Boxes

**Typical Specifications** (2024-2026):

Budget Boxes ($30-60):
- Storage: 8-32 GB
- RAM: 1-2 GB
- SD card: Often included
- Build quality: Variable
- Update support: Poor

Mid-Range Boxes ($60-120):
- Storage: 16-64 GB
- RAM: 2-4 GB
- SD card: Usually included
- USB 3.0: Common
- Build quality: Acceptable

Premium Boxes ($120-200):
- Storage: 32-128 GB
- RAM: 4-6 GB
- SD card: Included
- USB 3.0/3.1: Yes
- Build quality: Good
- May include SATA for internal HDD

**Common Features**:
- Most include SD card slots (microSD or full SD)
- USB ports (2-4 ports)
- Variable exFAT support
- Android versions: 9-13 (often outdated)

**DVR Viability**:
- ⚠️ Budget boxes: Hit-or-miss, verify exFAT support
- ✅ Mid-range+: Generally good for DVR
- ⚠️ Caution: Update support, certification status

### Storage Expansion Options Summary

| Platform | Internal | SD Card | USB Storage | Best DVR Setup |
|----------|----------|---------|-------------|----------------|
| Chromecast w/ Google TV (8GB) | 8 GB | ❌ | ✅ (via hub) | USB flash drive via hub |
| Google TV Streamer | 32 GB | ❌ | ✅ | Internal + external SSD |
| Fire TV Stick 4K Max | 16 GB | ❌ | ✅ (via OTG) | External USB drive |
| Nvidia Shield TV | 16 GB | ❌ | ✅ (USB 3.0) | Large USB SSD/HDD |
| Generic Android TV Box | 16-64 GB | ✅ (usually) | ✅ | SD card or USB drive |

### Manufacturer-Specific Limitations

**Storage Compatibility Issues**:

1. **Sony Android TV**:
   - Some firmware updates broke removable media permissions
   - Users report inability to record to external storage post-update
   - May require factory reset or specific firmware version

2. **Budget Brands**:
   - Inconsistent exFAT support
   - May lack proper Android TV certification
   - Storage performance varies widely

3. **Samsung/LG Smart TVs**:
   - Not Android TV (Tizen/webOS)
   - Not compatible with DuckFlix Lite

### Recommendations by Platform

**For Google TV Streamer Users**:
- Use internal 32 GB for ~7 hours of recordings
- External storage for extensive DVR library
- Best user experience (no hub hassle for basic use)

**For Chromecast with Google TV Users**:
- Require external USB storage (via hub)
- Educate users on hub setup
- Consider automatic cleanup strategies

**For Fire TV Users**:
- External storage recommended
- Warn about potential 4GB file limit
- Implement file segmentation

**For Nvidia Shield TV Users**:
- Premium DVR experience
- External USB 3.0 SSD recommended
- Can handle advanced features (transcoding, etc.)

**For Generic Box Users**:
- Verify device compatibility first
- SD card usually most straightforward
- Test exFAT support in app

---

## 7. Recommendations for DuckFlix Lite

### Overall Storage Strategy

**Recommended Approach**: **Tiered Storage Model**

**Tier 1: Simple Mode (Default)**
- Use app-specific external directory
- No permissions required
- Works out-of-box on all devices
- Limited by device internal storage
- Best for: Casual users, limited recordings, devices with adequate storage

**Tier 2: Advanced Mode (Optional)**
- Request MANAGE_EXTERNAL_STORAGE permission
- User selects custom recording location
- Support for external SD cards and USB drives
- Full file system access
- Best for: Power users, extensive DVR libraries, devices with expandable storage

### Should We Use Scoped Storage or MANAGE_EXTERNAL_STORAGE?

**Recommendation: Start with scoped storage, offer MANAGE_EXTERNAL_STORAGE as an option**

**Rationale**:

1. **Better Initial Experience**:
   - No permission requests on first launch
   - Works immediately
   - Follows Android best practices

2. **Flexibility for Advanced Users**:
   - Power users can enable "Advanced Storage Mode"
   - Choose custom recording locations
   - Use external SD cards efficiently

3. **Compliance-Ready**:
   - If ever distributed via Play Store, already compliant
   - Falls back to scoped storage gracefully

**Implementation Plan**:

```
Settings > Storage Options:
  ○ Simple Mode (default)
    - Recordings stored in app folder
    - No setup required
    - [Show location: /sdcard/Android/data/com.duckflix.lite/files/]

  ○ Advanced Mode
    - Choose custom recording location
    - Requires "All Files Access" permission
    - [Grant Permission] button
    - [Choose Location] button (after permission granted)
```

**Permission Request Flow**:

1. User selects "Advanced Mode"
2. Show explanation dialog:
   ```
   Advanced Storage Mode

   This allows you to:
   • Choose where recordings are saved
   • Use external SD cards or USB drives
   • Keep recordings when app is uninstalled

   You'll need to grant "All Files Access" permission in Settings.

   [Cancel]  [Continue]
   ```
3. Launch Settings intent
4. User grants permission
5. Return to app, verify permission
6. Show folder picker for custom location

### Should We Require Devices with SD Card Slots?

**Recommendation: No - Support SD cards but don't require them**

**Rationale**:

1. **Platform Diversity**:
   - Chromecast with Google TV has no SD slot (popular device)
   - Google TV Streamer has no SD slot (flagship device)
   - Fire TV devices typically lack SD slots
   - Nvidia Shield TV has no SD slot
   - Only generic boxes reliably have SD slots

2. **Exclusion Risk**:
   - Requiring SD card would exclude 60-70% of Android TV market
   - Premium devices (Shield, Google TV Streamer) don't have SD slots

3. **Alternative Storage**:
   - USB OTG storage widely supported
   - Can work with internal storage for light use
   - Adoptable storage available on some devices

**Implementation**:
- Detect available storage options
- Guide users to appropriate storage expansion for their device
- Support SD cards where available
- Also support USB drives, external SSDs

### UI/UX for Storage Management

**Storage Status Display**:

```
┌─────────────────────────────────────┐
│ Storage                             │
├─────────────────────────────────────┤
│ Location: Internal Storage          │
│ Available: 4.2 GB / 32 GB           │
│                                     │
│ [████████░░] 87% Used               │
│                                     │
│ Estimated recording time remaining: │
│ ~0.9 hours (at 10 Mbps)             │
│                                     │
│ Recordings: 12 programs, 8.2 GB     │
│                                     │
│ [Manage Storage]  [Change Location] │
└─────────────────────────────────────┘
```

**Low Storage Warning**:

```
┌─────────────────────────────────────┐
│ ⚠ Storage Almost Full               │
├─────────────────────────────────────┤
│ Only 0.3 GB remaining                │
│                                     │
│ Suggestions:                        │
│ • Delete old recordings             │
│ • Add external storage              │
│ • Enable auto-cleanup               │
│                                     │
│ [Manage Recordings]  [Add Storage]  │
└─────────────────────────────────────┘
```

**Storage Setup Wizard** (First Launch):

```
Step 1: Choose Storage Mode
○ Simple Mode (Recommended)
  • No setup required
  • Uses device storage
  • ~X.X GB available

○ Advanced Mode
  • Choose storage location
  • Use SD card or USB drive
  • Requires permission setup

[Continue]

---

Step 2: Enable Auto-Cleanup (Optional)
Keep storage from filling up

□ Auto-delete watched recordings after 7 days
□ Auto-delete all recordings after 30 days
□ Keep at least 2 GB free space

[Skip]  [Enable]
```

**Recording Location Picker** (Advanced Mode):

```
Choose Recording Location

Available Storage:
○ Internal Storage (4.2 GB free)
○ SD Card (128 GB free) [Recommended]
○ USB Drive "SanDisk" (256 GB free)
○ Custom location...

[Cancel]  [Select]
```

**Storage Management Screen**:

```
┌─────────────────────────────────────┐
│ Manage Recordings                   │
├─────────────────────────────────────┤
│ Sort by: [Date ▼] [Size] [Name]     │
│                                     │
│ ☑ Evening News - Feb 1 (892 MB)     │
│   Watched • 3:12 remaining          │
│                                     │
│ ☑ Movie Night - Jan 31 (4.2 GB)     │
│   Watched • Finished                │
│                                     │
│ □ Live Sports - Jan 30 (3.8 GB) 🔒  │
│   Unwatched • Protected             │
│                                     │
│ Total: 12 recordings, 8.2 GB        │
│                                     │
│ [Delete Selected]  [Free Up Space]  │
└─────────────────────────────────────┘
```

**Auto-Cleanup Settings**:

```
Auto-Cleanup Settings

Keep Storage Available:
[Enabled ▼]

Minimum free space:
• 500 MB  ○ 1 GB  ○ 2 GB  ○ 5 GB

Delete Priority (oldest first):
1. ☑ Watched recordings older than 7 days
2. ☑ All recordings older than 30 days
3. ☐ Any recordings (if still low)

Never Delete:
☑ Protected recordings
☑ Recordings from last 24 hours

[Save]
```

### File Segmentation Strategy

**Recommendation: Implement 1-hour segmentation**

**Benefits**:
1. ✅ FAT32 compatibility (4GB limit)
2. ✅ Resume playback while recording
3. ✅ Easier file management
4. ✅ Partial recovery if recording fails
5. ✅ Faster seeking within recordings

**Implementation**:

```
recordings/
  evening_news_2026-02-01_1800/
    metadata.json          # Program info, segments list
    segment_001.ts         # 00:00-01:00
    segment_002.ts         # 01:00-02:00
    segment_003.ts         # 02:00-02:32 (final segment)
    playlist.m3u8          # HLS playlist for playback
```

**Metadata Format** (metadata.json):
```json
{
  "title": "Evening News",
  "channel": "NBC",
  "startTime": "2026-02-01T18:00:00Z",
  "endTime": "2026-02-01T20:32:00Z",
  "duration": 9120,
  "segments": [
    {
      "file": "segment_001.ts",
      "duration": 3600,
      "size": 4536870912
    },
    {
      "file": "segment_002.ts",
      "duration": 3600,
      "size": 4536870912
    },
    {
      "file": "segment_003.ts",
      "duration": 1920,
      "size": 2419425280
    }
  ],
  "totalSize": 11493167104,
  "watched": false,
  "protected": false
}
```

### Minimum System Requirements

**Recommended Minimum Requirements for DuckFlix Lite**:

✅ **Supported Devices**:
- Android TV 9.0 or higher
- 2 GB RAM minimum (recommended: 3 GB+)
- 16 GB internal storage OR external storage support
- 1 GB available storage minimum (recommended: 32 GB+)

⚠️ **Marginal Devices** (limited functionality):
- Chromecast with Google TV 4K (8GB) - requires external storage
- Fire TV Stick (8GB) - requires external storage
- Generic boxes with <16GB storage

✅ **Optimal Devices**:
- Google TV Streamer (32GB)
- Nvidia Shield TV / Pro
- Fire TV Cube
- Mid-range+ Android TV boxes with SD card or large internal storage

### Storage Recommendations to Users

**In-App Guidance**:

```
Storage Recommendations

For best DVR experience:

Minimum Setup:
• 32 GB available storage
• Records ~7 hours of HD TV

Recommended Setup:
• 128 GB or larger SD card/USB drive
• Records ~28 hours of HD TV
• Allows comfortable buffer

Optimal Setup:
• 256 GB+ SD card/USB drive
• Records 50+ hours of HD TV
• Never worry about space

External Storage Tips:
• Use Class 10 / U3 SD card or faster
• exFAT format for files >4GB
• SanDisk, Samsung, Kingston recommended

[Shop for Storage] [I Have Storage] [Skip]
```

### Technical Implementation Checklist

**Phase 1: Basic Storage (Simple Mode)**:
- [x] Use app-specific external directory
- [x] Implement storage quota checking
- [x] File segmentation (1-hour segments)
- [x] Low storage warnings
- [x] Basic auto-cleanup (oldest first)

**Phase 2: Advanced Storage (Advanced Mode)**:
- [x] MANAGE_EXTERNAL_STORAGE permission flow
- [x] Custom location picker
- [x] SD card detection and display
- [x] USB drive detection and display
- [x] exFAT compatibility check

**Phase 3: Storage Management**:
- [x] Recordings management UI
- [x] Bulk delete operations
- [x] Protected recordings feature
- [x] Auto-cleanup settings
- [x] Storage statistics and visualization

**Phase 4: Platform Optimization**:
- [x] Device-specific guidance (Chromecast, Fire TV, Shield)
- [x] USB hub setup instructions for Chromecast
- [x] Adoptable storage detection
- [x] Performance monitoring (SD card write speed)

---

## Conclusion

### Key Takeaways

1. **Scoped Storage is Mandatory** (Android 10+)
   - Use app-specific directories by default
   - MediaStore for shared content
   - MANAGE_EXTERNAL_STORAGE for power users only

2. **Storage Diversity is Critical**
   - Android TV devices range from 8 GB to 128 GB
   - SD card support is inconsistent
   - USB storage is widely supported but requires adapters

3. **File Size Limitations Matter**
   - FAT32's 4 GB limit affects many SD cards
   - Implement file segmentation for compatibility
   - exFAT support varies by device/manufacturer

4. **Storage Requirements are Substantial**
   - ATSC 1.0: ~4.5 GB/hour typical
   - ATSC 3.0: ~1.8 GB/hour typical
   - Minimum 32 GB recommended for basic DVR use
   - 128 GB+ for comfortable experience

5. **Platform Matters**
   - Nvidia Shield TV: Best-in-class for DVR
   - Google TV Streamer: Good with 32 GB
   - Chromecast/Fire Stick: Need external storage
   - Generic boxes: Variable, test compatibility

### Final Recommendation

**DuckFlix Lite Storage Strategy**:

1. **Default to Simple Mode** (app-specific storage)
   - Zero friction onboarding
   - Works on all devices
   - Android best practices

2. **Offer Advanced Mode** (custom location + MANAGE_EXTERNAL_STORAGE)
   - Unlocks full potential
   - Required for serious DVR use
   - Clear permission explanation

3. **Implement Smart Storage Management**
   - Auto-cleanup with user control
   - Storage warnings and guidance
   - File segmentation for compatibility

4. **Provide Clear User Guidance**
   - Device-specific setup instructions
   - Storage expansion recommendations
   - Realistic expectations (hours available)

5. **Support All Storage Types**
   - Internal storage (all devices)
   - SD cards (where available)
   - USB drives (with adapters)
   - Flexible, user-driven approach

This approach balances simplicity for casual users with power for enthusiasts, while remaining compatible across the diverse Android TV ecosystem.

---

## Sources

### Official Android Documentation
- [Data and file storage overview | Android Developers](https://developer.android.com/training/data-storage)
- [Android storage use cases and best practices | Android Developers](https://developer.android.com/training/data-storage/use-cases)
- [Scoped storage | Android Open Source Project](https://source.android.com/docs/core/storage/scoped)
- [Manage all files on a storage device | Android Developers](https://developer.android.com/training/data-storage/manage-all-files)
- [Storage updates in Android 11 | Android Developers](https://developer.android.com/about/versions/11/privacy/storage)
- [Request special permissions | Android Developers](https://developer.android.com/training/permissions/requesting-special)
- [Support content recording | Android TV | Android Developers](https://developer.android.com/training/tv/tif/content-recording)
- [Access media files from shared storage | Android Developers](https://developer.android.com/training/data-storage/shared/media)

### Storage Technology & File Systems
- [exFAT - Wikipedia](https://en.wikipedia.org/wiki/ExFAT)
- [exFAT vs FAT32 – Full Comparison 2026](https://recoverit.wondershare.com/computer-tips/exfat-vs-fat32.html)
- [What is FAT32 maximum file size limit?](https://www.winability.com/fat32-max-file-size-limit/)
- [Understanding the FAT32 File Size Limit: What You Need to Know](https://www.hitpaw.com/video-compression-tips/fat32-file-size-limit.html)
- [File Too Large for Destination: How to Copy Files Larger than 4GB to FAT32](https://www.easeus.com/partition-master/copy-file-larger-than-4gb-to-usb-drive.html)

### Android TV Platform Information
- [Get more storage for your Android TV - Android TV Help](https://support.google.com/androidtv/answer/6299083?hl=en)
- [Google TV and Android TV reportedly pushing for at least 16GB of storage on new devices](https://9to5google.com/2022/08/08/google-tv-storage-report/)
- [How much RAM and storage does the Google TV Streamer have? | Android Central](https://www.androidcentral.com/streaming-tv/google-tv-streamer-how-much-ram-storage)
- [The Google TV Streamer still supports external storage](https://9to5google.com/2024/08/07/google-tv-streamer-external-storage/)

### Chromecast & Google TV Storage
- [The Chromecast with Google TV storage space is just not enough](https://www.androidauthority.com/chromecast-with-google-tv-storage-3061235/)
- [Chromecast with Google TV gets official support guide on how to free up & get more storage](https://9to5google.com/2021/11/02/chromecast-google-tv-storage/)
- [How To Expand Storage on Chromecast with Google TV](https://googlechromecast.com/how-to-expand-storage-on-chromecast-with-google-tv/)
- [Free up storage on Chromecast with Google TV - Streaming Help](https://support.google.com/chromecast/answer/11276506?hl=en-IN)
- [Chromecast with Google TV (HD) unlocks a bit more storage for users versus 4K model](https://9to5google.com/2022/09/26/chromecast-hd-storage-google-tv/)

### DVR & Recording Information
- [Setting up DVR storage on a certified Android TV device - HDHomeRun](http://info.hdhomerun.com/info/dvr:android)
- [How to Build a Whole-Home IPTV DVR System (2026)](https://troypoint.com/iptv-dvr/)
- [HDHomeRun's new app turns an Android TV box into a full DVR - FlatpanelsHD](https://flatpanelshd.com/news.php?id=1463465822&subaction=showfull)
- [Tablo on Google TV and Android TV - Tablo TV](https://www.tablotv.com/for-androidtv/)

### ATSC & Broadcast Standards
- [Tablo ATSC 3.0 QUAD HDMI – A DVR for NextGen TV](https://www.tablotv.com/blog/tablo-atsc-3-quad-hdmi-a-tablo-dvr-for-nextgen-tv/)
- [What Resolution is Over the Air TV? ATSC 1.0 and the Future](https://www.antennaland.com/what-picture-resolutions-are-on-ota-tv/)
- [ATSC 3.0: Everything you need to know - Digital Trends](https://www.digitaltrends.com/home-theater/atsc-3-0-ota-broadcast-standard-4k-dolby-atmos/)
- [How To: ATSC 3.0 & the HDHomeRun QUATRO 4K](https://freetime.mikeconnelly.com/archives/10383)

### Storage Requirements & Bitrates
- [Calculating How Much Storage You Need for your DVR](https://optiviewusa.com/calculating-how-much-storage-you-need-for-your-dvr/)
- [IP Camera Bandwidth Calculator: Easy Guide 2026](https://www.backstreet-surveillance.com/blog/post/how-to-calculate-the-bandwidth-needed-for-your-ip-security-cameras)
- [CCTV Storage Calculator | DIY Formula & Storage Saving Tips](https://reolink.com/blog/cctv-storage-calculation-formula/)

### Community & Developer Resources
- [GitHub - anggrayudi/SimpleStorage: Simplify Android Storage Access Framework](https://github.com/anggrayudi/SimpleStorage)
- [Use MANAGE_EXTERNAL_STORAGE permission on Android TV - NewPipe GitHub Issue](https://github.com/TeamNewPipe/NewPipe/issues/8359)
- [Android 11 storage FAQ | Medium](https://medium.com/androiddevelopers/android-11-storage-faq-78cefea52b7c)
- [How to Enable Android TV Adoptable Storage](https://troypoint.com/android-tv-adoptable-storage/)

---

**Document Status**: Complete
**Next Steps**: Implementation planning based on recommendations
**Review Date**: Review when Android 15 releases or major Android TV platform changes occur
