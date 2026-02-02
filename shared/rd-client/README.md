# @duckflix/rd-client

Real-Debrid API client for torrent caching, unrestricting, and streaming.

## Features

- **Instant Availability Check**: Check if torrents are cached (up to 100 hashes at once)
- **Add Torrents**: Support for magnet links and torrent files
- **Smart File Selection**: Automatically finds best video file or specific episodes
- **Episode Matching**: Intelligent episode detection for TV series packs
- **Progress Polling**: Waits for torrent processing with status updates
- **Link Unrestricting**: Convert RD links to direct download URLs
- **Error Handling**: Comprehensive error handling and cleanup

## Installation

```bash
npm install @duckflix/rd-client
```

## Usage

### Quick Start - Download from Magnet/Torrent

```javascript
const { downloadFromRD } = require('@duckflix/rd-client');

const apiKey = process.env.RD_API_KEY;

// Download a movie
const result = await downloadFromRD(
  'magnet:?xt=urn:btih:...',
  apiKey
);
console.log('Download URL:', result.download);
console.log('Filename:', result.filename);

// Download a TV episode
const tvResult = await downloadFromRD(
  'magnet:?xt=urn:btih:...',
  apiKey,
  3,  // season
  5   // episode
);
```

### Check Instant Availability

```javascript
const { checkInstantAvailability } = require('@duckflix/rd-client');

const hashes = [
  'abc123...',
  'def456...'
];

const cached = await checkInstantAvailability(hashes, apiKey);
console.log('Cached hashes:', Array.from(cached));
```

### Manual Workflow

```javascript
const {
  addMagnet,
  getTorrentInfo,
  findBestVideoFile,
  selectFiles,
  unrestrictLink
} = require('@duckflix/rd-client');

// 1. Add magnet/torrent
const torrentId = await addMagnet('magnet:?xt=urn:btih:...', apiKey);

// 2. Get torrent info
const info = await getTorrentInfo(torrentId, apiKey);

// 3. Find video file
const videoFile = findBestVideoFile(info.files, 1, 1); // S01E01

// 4. Select file
await selectFiles(torrentId, [videoFile.originalIndex + 1], apiKey);

// 5. Wait for download (poll getTorrentInfo until status is 'downloaded')
// ... polling logic ...

// 6. Unrestrict the link
const finalInfo = await getTorrentInfo(torrentId, apiKey);
const result = await unrestrictLink(finalInfo.links[0], apiKey);
console.log('Stream URL:', result.download);
```

### Add Torrent File

```javascript
const { addTorrent } = require('@duckflix/rd-client');

// From URL
const torrentId = await addTorrent(
  'https://example.com/file.torrent',
  apiKey
);

// From buffer
const fs = require('fs');
const buffer = fs.readFileSync('/path/to/file.torrent');
const torrentId = await addTorrent(buffer, apiKey);
```

## API

### `downloadFromRD(magnetOrTorrent, apiKey, season?, episode?)`

Complete download workflow: add -> select -> wait -> unrestrict.

**Parameters:**
- `magnetOrTorrent` (string|Buffer): Magnet link, torrent URL, or buffer
- `apiKey` (string): Real-Debrid API key
- `season` (number, optional): Season number for TV shows
- `episode` (number, optional): Episode number for TV shows

**Returns:** `Promise<{download: string, filename: string}>`

**Throws:**
- Error with `code: 'FILE_NOT_FOUND'` if video file or episode not found

### `checkInstantAvailability(hashes, apiKey)`

Check if torrents are instantly available (cached).

**Parameters:**
- `hashes` (string[]): Array of torrent info hashes (up to 100)
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<Set<string>>` - Set of cached hashes (lowercase)

### `addMagnet(magnetLink, apiKey)`

Add magnet link to Real-Debrid.

**Parameters:**
- `magnetLink` (string): Magnet link
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<string>` - Torrent ID

### `addTorrent(torrentFileOrUrl, apiKey)`

Add torrent file to Real-Debrid.

**Parameters:**
- `torrentFileOrUrl` (Buffer|string): Torrent file buffer or URL
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<string>` - Torrent ID

### `getTorrentInfo(torrentId, apiKey)`

Get torrent info from Real-Debrid.

**Parameters:**
- `torrentId` (string): Torrent ID
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<Object>` - Torrent info object

Torrent info format:
```javascript
{
  id: 'ABC123',
  status: 'downloaded',  // or 'queued', 'downloading', 'error', etc.
  files: [
    { id: 1, path: 'Show.S01E01.mkv', bytes: 1234567890, selected: 1 }
  ],
  links: ['https://...']
}
```

### `selectFiles(torrentId, fileIds, apiKey)`

Select files in torrent.

**Parameters:**
- `torrentId` (string): Torrent ID
- `fileIds` (string|number[]): 'all' or array of file IDs (1-indexed)
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<void>`

### `deleteTorrent(torrentId, apiKey)`

Delete torrent from Real-Debrid.

**Parameters:**
- `torrentId` (string): Torrent ID
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<void>`

### `unrestrictLink(link, apiKey)`

Unrestrict a link to get download URL.

**Parameters:**
- `link` (string): Link to unrestrict
- `apiKey` (string): Real-Debrid API key

**Returns:** `Promise<{download: string, filename: string}>`

### `findBestVideoFile(files, season?, episode?)`

Find best video file in torrent files list.

**Parameters:**
- `files` (Array): Array of file objects from torrent info
- `season` (number, optional): Season number for TV shows
- `episode` (number, optional): Episode number for TV shows

**Returns:** `Object|null` - Selected file object with `originalIndex` property

## Episode Matching

The module uses intelligent episode matching with multiple patterns:

### Specific Patterns (Preferred)
- `S03E05`, `S03.E05`, `S03_E05` - Standard format
- `3x05` - Alternate format
- `Season 3 Episode 5` - Long format
- `0305` - Concatenated format

### Loose Patterns (With Season Context)
- `E05` - Only if path contains season folder/name
- Episode number alone - Only with season context

This prevents false matches in multi-season packs while still handling various naming schemes.

## Status Values

Real-Debrid torrent statuses:
- `magnet_conversion` - Converting magnet to torrent
- `waiting_files_selection` - Waiting for file selection
- `queued` - In download queue
- `downloading` - Currently downloading
- `downloaded` - Ready for streaming
- `error` - Processing error
- `dead` - Dead torrent
- `virus` - Virus detected

## Configuration

Set your Real-Debrid API key:

```javascript
const apiKey = process.env.RD_API_KEY;
```

Get your API key from: https://real-debrid.com/apitoken

## Error Handling

```javascript
try {
  const result = await downloadFromRD(magnet, apiKey, 1, 5);
} catch (err) {
  if (err.code === 'FILE_NOT_FOUND') {
    console.log('Episode not found in torrent');
  } else {
    console.error('RD error:', err.message);
  }
}
```

## Timeouts

- Instant availability: 30s (handled by axios)
- Add magnet/torrent: 30s (handled by axios)
- Processing wait: 120s (2 minutes max, polls every 3s)

## License

MIT
