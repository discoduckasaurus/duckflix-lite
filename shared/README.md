# DuckFlix Lite - Shared Modules

This directory contains shared server-side modules extracted from the main DuckFlix build. These modules provide reusable functionality for video streaming, EPG parsing, and debrid services.

## Modules

### 1. @duckflix/zurg-client

Zurg mount client for searching and quality-gating video content from Real-Debrid via Zurg.

**Location:** `/Users/aaron/projects/duckflix_lite/shared/zurg-client/`

**Key Features:**
- Smart title matching with variations (apostrophes, US/UK, abbreviations)
- Quality gating based on MB/minute ratio (filters garbage releases)
- Resolution detection (4K, 1080p, 720p, 480p, 360p)
- TV episode and movie search
- Multiple naming pattern support

**Usage:**
```javascript
const { findInZurgMount } = require('./shared/zurg-client');

const result = await findInZurgMount({
  title: 'The Office',
  type: 'tv',
  season: 3,
  episode: 5
});
```

**Environment Variables:**
- `ZURG_ENABLED`: Set to 'true' to enable
- `ZURG_MOUNT`: Path to Zurg mount (default: `/mnt/zurg`)

---

### 2. @duckflix/epg-parser

Electronic Program Guide (EPG) parser for multiple sources.

**Location:** `/Users/aaron/projects/duckflix_lite/shared/epg-parser/`

**Key Features:**
- Multi-source EPG: TVPass.org, Pluto TV, epg.pw
- Automatic data merging and deduplication
- Channel icon support
- Extended schedules (7+ days from epg.pw)
- 60+ pre-mapped channels

**Usage:**
```javascript
const { parseEPGData } = require('./shared/epg-parser');

const epgData = await parseEPGData({
  includeTvPass: true,
  includePluto: true,
  includeEpgPw: true
});

console.log('Programs:', epgData.programs);
```

**EPG Sources:**
- **TVPass.org**: Baseline data with channel icons
- **Pluto TV**: 8-10 hours, most accurate for Pluto channels
- **epg.pw**: Extended schedules (7+ days)

---

### 3. @duckflix/m3u-parser

M3U playlist parser for IPTV/Live TV channels.

**Location:** `/Users/aaron/projects/duckflix_lite/shared/m3u-parser/`

**Key Features:**
- Parse M3U/M3U8 playlist files
- Extended M3U format support (tvg-id, tvg-name, group-title)
- Built-in channel database (150+ channels)
- Channel search and filtering
- Sports event filtering

**Usage:**
```javascript
const { getChannelList, searchChannels } = require('./shared/m3u-parser');

const channels = await getChannelList();
const espnChannels = searchChannels(channels, 'espn');
```

**Includes:**
- `data/channels.m3u`: 150+ live TV channels (news, sports, entertainment)

---

### 4. @duckflix/rd-client

Real-Debrid API client for torrent caching and unrestricting.

**Location:** `/Users/aaron/projects/duckflix_lite/shared/rd-client/`

**Key Features:**
- Instant availability check (up to 100 hashes)
- Add magnets and torrent files
- Smart video file selection
- Intelligent episode matching for TV packs
- Progress polling with status updates
- Link unrestricting

**Usage:**
```javascript
const { downloadFromRD } = require('./shared/rd-client');

// Download movie
const result = await downloadFromRD(
  'magnet:?xt=urn:btih:...',
  process.env.RD_API_KEY
);

// Download TV episode
const tvResult = await downloadFromRD(
  'magnet:?xt=urn:btih:...',
  process.env.RD_API_KEY,
  3,  // season
  5   // episode
);

console.log('Stream URL:', result.download);
```

**Environment Variables:**
- `RD_API_KEY`: Real-Debrid API token

---

## Installation

Each module can be installed separately or all together:

```bash
# Install all dependencies from shared modules
cd /Users/aaron/projects/duckflix_lite/shared/zurg-client && npm install
cd /Users/aaron/projects/duckflix_lite/shared/epg-parser && npm install
cd /Users/aaron/projects/duckflix_lite/shared/m3u-parser && npm install
cd /Users/aaron/projects/duckflix_lite/shared/rd-client && npm install
```

Or use them directly without installation (local require):

```javascript
const zurgClient = require('./shared/zurg-client');
const epgParser = require('./shared/epg-parser');
const m3uParser = require('./shared/m3u-parser');
const rdClient = require('./shared/rd-client');
```

## Module Dependencies

### zurg-client
- No external dependencies (uses only Node.js built-ins)

### epg-parser
- `axios`: HTTP client for fetching EPG data
- `fast-xml-parser`: Fast and efficient XML parsing

### m3u-parser
- No external dependencies (uses only Node.js built-ins)

### rd-client
- `axios`: HTTP client for Real-Debrid API
- `form-data`: For uploading torrent files

## Architecture

All modules are:
- **Pure extraction**: No logic modification from original source
- **Self-contained**: Minimal dependencies
- **Well-documented**: JSDoc comments throughout
- **CommonJS**: Uses `module.exports` for compatibility
- **Reusable**: Can be used in any Node.js project

## Source Files

These modules were extracted from:
- `/Users/aaron/projects/duckflix/backend/zurg-lookup.js` → zurg-client
- `/Users/aaron/projects/duckflix/backend/livetv.js` → epg-parser, m3u-parser
- `/Users/aaron/projects/duckflix/backend/server.js` → rd-client
- `/Users/aaron/projects/duckflix/backend/channels.m3u` → m3u-parser/data

## Usage in DuckFlix Lite

These modules are designed to be used in the DuckFlix Lite backend server:

```javascript
// Example server integration
const express = require('express');
const zurgClient = require('./shared/zurg-client');
const epgParser = require('./shared/epg-parser');
const m3uParser = require('./shared/m3u-parser');
const rdClient = require('./shared/rd-client');

const app = express();

// Use modules for streaming endpoints
app.get('/stream/:type/:id', async (req, res) => {
  // Find in Zurg first
  const zurgResult = await zurgClient.findInZurgMount({...});

  if (!zurgResult.match) {
    // Fall back to Real-Debrid
    const rdResult = await rdClient.downloadFromRD(...);
  }
});

// Use for live TV
app.get('/livetv/channels', async (req, res) => {
  const channels = await m3uParser.getChannelList();
  const epgData = await epgParser.parseEPGData();
  res.json({ channels, epg: epgData.programs });
});
```

## Environment Variables

Required environment variables for all modules:

```bash
# Zurg
ZURG_ENABLED=true
ZURG_MOUNT=/mnt/zurg

# Real-Debrid
RD_API_KEY=your_api_key_here
```

## License

MIT

---

## Module Versioning

All modules are currently at version 1.0.0. They are stable and production-ready, extracted from the working DuckFlix backend.

## Support

For issues or questions:
1. Check individual module READMEs in their directories
2. Review the original source files in `/Users/aaron/projects/duckflix/backend/`
3. Test with the DuckFlix Lite backend implementation
