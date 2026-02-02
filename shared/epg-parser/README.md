# @duckflix/epg-parser

Electronic Program Guide (EPG) parser for multiple sources including TVPass, Pluto TV, and epg.pw.

## Features

- **Multiple EPG Sources**:
  - TVPass.org XML (baseline data)
  - Pluto TV API (8-10 hours, most accurate for Pluto channels)
  - epg.pw XML (extended schedules, 7+ days)
- **Automatic Merging**: Intelligently combines data from all sources
- **Channel Mapping**: Maps external channel IDs to internal channel IDs
- **XML Parsing**: Fast and efficient XML parsing with fast-xml-parser
- **Time Parsing**: Converts EPG time format to Unix timestamps

## Installation

```bash
npm install @duckflix/epg-parser
```

## Usage

### Parse EPG from All Sources

```javascript
const { parseEPGData } = require('@duckflix/epg-parser');

const epgData = await parseEPGData({
  includeTvPass: true,    // Include TVPass.org data
  includePluto: true,     // Include Pluto TV data
  includeEpgPw: true      // Include epg.pw data
});

console.log('Channels:', epgData.channels);
console.log('Programs:', epgData.programs);
```

### Fetch from Individual Sources

```javascript
const { fetchEPG, fetchPlutoEPG, fetchEpgPw } = require('@duckflix/epg-parser');

// Fetch from TVPass.org
const tvpassData = await fetchEPG('https://tvpass.org/epg.xml');

// Fetch from Pluto TV
const plutoPrograms = await fetchPlutoEPG();

// Fetch from epg.pw
const epgPwPrograms = await fetchEpgPw();
```

### Parse EPG Time Format

```javascript
const { parseEpgTime } = require('@duckflix/epg-parser');

// Parse XMLTV time format
const timestamp = parseEpgTime('20260201150000 +0000');
console.log(new Date(timestamp)); // 2026-02-01T15:00:00Z
```

## API

### `parseEPGData(options)`

Parse EPG data from all sources and merge.

**Parameters:**
- `options.includeTvPass` (boolean): Include TVPass EPG (default: true)
- `options.includePluto` (boolean): Include Pluto TV EPG (default: true)
- `options.includeEpgPw` (boolean): Include epg.pw EPG (default: true)

**Returns:** `Promise<{channels: Object, programs: Object}>`

Response format:
```javascript
{
  channels: {
    'channel-id': 'https://icon-url.com/icon.png',
    // ... more channels
  },
  programs: {
    'channel-id': [
      {
        start: 1738422000000,  // Unix timestamp
        stop: 1738425600000,
        title: 'Program Title',
        description: 'Program description',
        category: 'Entertainment',
        icon: 'https://poster-url.com/poster.jpg',
        episodeNum: 'S01E05'
      },
      // ... more programs
    ],
    // ... more channels
  }
}
```

### `fetchEPG(epgUrl)`

Fetch and parse EPG XML from URL.

**Parameters:**
- `epgUrl` (string): URL to EPG XML file

**Returns:** `Promise<{channels: Object, programs: Object}>`

### `fetchPlutoEPG()`

Fetch Pluto TV EPG data from their API.

**Returns:** `Promise<Object>` - Programs mapped by channel ID

### `fetchEpgPw()`

Fetch epg.pw EPG data (extended schedules).

**Returns:** `Promise<Object>` - Programs mapped by channel ID

### `parseEpgTime(epgTime)`

Parse EPG time format (YYYYMMDDHHMMSS +OFFSET).

**Parameters:**
- `epgTime` (string): EPG time string

**Returns:** `number|null` - Unix timestamp in milliseconds

## Channel Mappings

### Pluto TV Channels

The module includes a mapping for Pluto TV channels:

```javascript
const { PLUTO_CHANNEL_MAP } = require('@duckflix/epg-parser');

console.log(PLUTO_CHANNEL_MAP);
// {
//   'comedy-central-pluto-tv': '5ca671f215a62078d2ec0abf'
// }
```

### epg.pw Channels

The module includes extensive mappings for epg.pw channels (60+ channels):

```javascript
const { EPG_PW_CHANNEL_MAP } = require('@duckflix/epg-parser');

console.log(EPG_PW_CHANNEL_MAP);
// {
//   'abc-wabc-new-york-ny': '467679',
//   'amc-eastern-feed': '465032',
//   // ... 60+ more channels
// }
```

## EPG Sources

1. **TVPass.org** (`https://tvpass.org/epg.xml`)
   - Baseline EPG data
   - Channel icons
   - Good coverage for most channels

2. **Pluto TV** (`http://api.pluto.tv/v2/channels`)
   - 8-10 hours of data
   - Most accurate for Pluto-specific channels
   - Includes episode metadata

3. **epg.pw** (`https://epg.pw/xmltv/epg_US.xml`)
   - Extended schedules (7+ days)
   - Large XML file (requires 60s timeout)
   - Best for future program data

## Data Merging Strategy

The module merges EPG data in this order (later sources overwrite earlier):

1. TVPass.org (baseline)
2. epg.pw (extended schedules for mapped channels)
3. Pluto TV (most accurate for Pluto channels)

This ensures the most accurate and comprehensive EPG data.

## License

MIT
