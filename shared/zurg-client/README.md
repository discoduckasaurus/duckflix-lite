# @duckflix/zurg-client

Zurg mount client for searching and quality-gating video content from Real-Debrid via Zurg.

## Features

- **Smart Title Matching**: Handles apostrophes, US/UK variations, years, and common abbreviations
- **Quality Gating**: Filters out low-quality releases based on MB/minute ratio
- **Resolution Detection**: Automatically detects 4K, 1080p, 720p, 480p, 360p from filenames
- **Episode Matching**: Supports multiple episode naming patterns (S01E01, 1x01, etc.)
- **Fallback Support**: Returns both quality matches and garbage fallback for comparison

## Installation

```bash
npm install @duckflix/zurg-client
```

## Usage

```javascript
const { findInZurgMount } = require('@duckflix/zurg-client');

// Search for a TV episode
const result = await findInZurgMount({
  title: 'The Office',
  type: 'tv',
  year: 2005,
  season: 3,
  episode: 5,
  episodeRuntime: 22  // Optional: TMDB runtime for accurate quality calculation
});

if (result.match) {
  console.log('Found quality match:', result.match.filePath);
  console.log('Quality:', result.match.quality);
  console.log('MB/min:', result.match.mbPerMinute);
}

// Search for a movie
const movieResult = await findInZurgMount({
  title: 'Inception',
  type: 'movie',
  year: 2010
});
```

## Configuration

Set environment variables:

- `ZURG_ENABLED`: Set to `'true'` to enable Zurg lookups (default: disabled)
- `ZURG_MOUNT`: Path to Zurg mount (default: `/mnt/zurg`)

## API

### `findInZurgMount(options)`

Search for content in Zurg mount with quality gating.

**Parameters:**
- `options.title` (string): Content title
- `options.type` (string): 'tv' or 'movie'
- `options.year` (string|number): Year (optional)
- `options.season` (number): Season number (required for TV)
- `options.episode` (number): Episode number (required for TV)
- `options.tmdbId` (string): TMDB ID (optional)
- `options.episodeRuntime` (number): Actual episode runtime in minutes (optional)

**Returns:** `Promise<{match: Object|null, fallback: Object|null}>`

Match object contains:
- `filePath`: Full path to video file
- `fileName`: Filename
- `fileSize`: Size in bytes
- `sizeMB`: Size in megabytes
- `mbPerMinute`: Quality metric (MB per minute)
- `meetsQualityThreshold`: Boolean - meets minimum quality
- `resolution`: Resolution number (2160, 1080, 720, etc.)
- `quality`: Resolution label ('4K', '1080p', etc.)
- `estimatedDuration`: Duration used for calculation
- `source`: 'zurg'

### `validateZurgPath(filePath)`

Validate that a Zurg path exists and is a valid file.

**Parameters:**
- `filePath` (string): Path to validate

**Returns:** `Promise<boolean>`

### `generateTitleVariations(title, year)`

Generate title variations for better matching.

**Parameters:**
- `title` (string): Title to generate variations for
- `year` (string|number): Optional year

**Returns:** `string[]` - Array of variations

### `parseResolution(filename)`

Parse resolution from filename.

**Parameters:**
- `filename` (string): Filename to parse

**Returns:** `{resolution: number, label: string}`

### `calculateQualityScore(fileSizeBytes, durationMinutes)`

Calculate quality score based on file size and duration.

**Parameters:**
- `fileSizeBytes` (number): File size in bytes
- `durationMinutes` (number): Duration in minutes

**Returns:** `{sizeMB: number, mbPerMinute: number, meetsThreshold: boolean}`

## Quality Thresholds

The module uses the following quality thresholds:

- **Minimum MB/min**: 7 MB/min (accepts decent 720p, rejects garbage)
- **Episode durations**:
  - Sitcom: 22 minutes
  - Drama: 45 minutes
  - Anime: 24 minutes
  - Default: 42 minutes
- **Movie duration**: 100 minutes (default estimate)

Special cases:
- RuPaul's Drag Race: 60 minutes
- Uses TMDB runtime when provided for accurate calculations

## License

MIT
