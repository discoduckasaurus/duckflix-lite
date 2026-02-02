# @duckflix/m3u-parser

M3U playlist parser for IPTV/Live TV channels with support for extended M3U format.

## Features

- **Parse M3U Files**: Extract channel metadata and stream URLs
- **Extended M3U Support**: Parse tvg-id, tvg-name, group-title attributes
- **Sports Event Filtering**: Automatically filters out temporary sports events
- **Channel Search**: Find channels by name or ID
- **Group Filtering**: Filter channels by category/group
- **Built-in Channel Data**: Includes default channels.m3u with 150+ live TV channels

## Installation

```bash
npm install @duckflix/m3u-parser
```

## Usage

### Parse Default Channel List

```javascript
const { getChannelList } = require('@duckflix/m3u-parser');

const channels = await getChannelList();
console.log(`Found ${channels.length} channels`);
console.log(channels[0]);
// {
//   id: 'cnn',
//   name: 'CNN US',
//   displayName: 'CNN US',
//   group: 'Live',
//   url: 'https://tvpass.org/live/CNN/sd'
// }
```

### Parse Custom M3U File

```javascript
const { parseM3U } = require('@duckflix/m3u-parser');

const channels = await parseM3U('/path/to/playlist.m3u');
```

### Parse M3U Content from String

```javascript
const { parseM3UContent } = require('@duckflix/m3u-parser');

const m3uString = `#EXTM3U
#EXTINF:-1 tvg-id="cnn" tvg-name="CNN" group-title="News",CNN
https://example.com/cnn.m3u8`;

const channels = parseM3UContent(m3uString);
```

### Search and Filter Channels

```javascript
const {
  getChannelList,
  searchChannels,
  filterByGroup,
  getGroups,
  findChannelById
} = require('@duckflix/m3u-parser');

const channels = await getChannelList();

// Search by name
const cnnChannels = searchChannels(channels, 'cnn');

// Filter by group
const liveChannels = filterByGroup(channels, 'Live');
const nhlChannels = filterByGroup(channels, 'NHL');

// Get all groups
const groups = getGroups(channels);
console.log(groups); // ['Live', 'NHL', 'NBA', 'NFL', ...]

// Find by ID
const espn = findChannelById(channels, 'espn');
```

## API

### `parseM3U(filePath)`

Parse M3U playlist file.

**Parameters:**
- `filePath` (string): Path to M3U file

**Returns:** `Promise<Array>` - Array of channel objects

### `parseM3UContent(content)`

Parse M3U content from string.

**Parameters:**
- `content` (string): M3U file content as string

**Returns:** `Array` - Array of channel objects

### `getChannelList()`

Get list of channels from default M3U file (includes 150+ channels).

**Returns:** `Promise<Array>` - Array of channel objects

### `filterByGroup(channels, group)`

Filter channels by group.

**Parameters:**
- `channels` (Array): Array of channel objects
- `group` (string): Group name to filter by

**Returns:** `Array` - Filtered channel array

### `getGroups(channels)`

Get unique groups from channel list.

**Parameters:**
- `channels` (Array): Array of channel objects

**Returns:** `Array<string>` - Array of unique group names

### `findChannelById(channels, channelId)`

Find channel by ID.

**Parameters:**
- `channels` (Array): Array of channel objects
- `channelId` (string): Channel ID to find

**Returns:** `Object|null` - Channel object or null if not found

### `searchChannels(channels, searchTerm)`

Find channels by name (case-insensitive partial match).

**Parameters:**
- `channels` (Array): Array of channel objects
- `searchTerm` (string): Search term

**Returns:** `Array` - Matching channels

## Channel Object Format

Each channel object contains:

```javascript
{
  id: 'espn',                           // Channel ID (from tvg-id)
  name: 'ESPN',                          // Channel name (from tvg-name)
  displayName: 'ESPN',                   // Display name (from EXTINF line)
  group: 'Live',                         // Category/group
  url: 'https://tvpass.org/live/ESPN/sd' // Stream URL
}
```

## Default Channel List

The module includes a default `channels.m3u` file with 150+ channels including:

- **News**: CNN, Fox News, MSNBC, BBC News
- **Sports**: ESPN, ESPN2, NFL Network, NBA TV, NHL Network
- **Entertainment**: HBO, Showtime, Starz, Comedy Central
- **Networks**: ABC, NBC, CBS, FOX (major markets)
- **Regional Sports**: Fanduel Sports Networks, NBC Sports
- **Specialty**: Food Network, Discovery, National Geographic

Sports events are automatically filtered out (temporary PPV events, specific games with dates/times).

## M3U Format

The parser supports extended M3U format:

```
#EXTM3U x-tvg-url="https://tvpass.org/epg.xml"
#EXTINF:-1 tvg-id="cnn" tvg-name="CNN US" group-title="Live",CNN US
https://tvpass.org/live/CNN/sd
```

Supported attributes:
- `tvg-id`: Channel identifier
- `tvg-name`: Channel name
- `group-title`: Category/group name
- Display name: Text after last comma in EXTINF line

## License

MIT
