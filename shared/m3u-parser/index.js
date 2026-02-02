/**
 * M3U Parser Module
 *
 * Parses M3U playlist files for IPTV/Live TV channels.
 * Extracts channel metadata and stream URLs.
 */

const fs = require('fs').promises;
const path = require('path');

/**
 * Parse M3U playlist file
 *
 * @param {string} filePath - Path to M3U file
 * @returns {Promise<Array>} Array of channel objects
 */
const parseM3U = async (filePath) => {
  try {
    const content = await fs.readFile(filePath, 'utf-8');
    const lines = content.split('\n');
    const channels = [];
    let currentChannel = null;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();

      if (line.startsWith('#EXTINF:')) {
        // Parse channel info
        const tvgIdMatch = line.match(/tvg-id="([^"]*)"/);
        const tvgNameMatch = line.match(/tvg-name="([^"]*)"/);
        const groupMatch = line.match(/group-title="([^"]*)"/);

        // Extract channel name from the end (after last comma)
        const lastComma = line.lastIndexOf(',');
        const name = lastComma !== -1 ? line.substring(lastComma + 1).trim() : '';

        const tvgId = tvgIdMatch ? tvgIdMatch[1] : '';

        // Skip sports events (no tvg-id or contains specific time patterns)
        if (!tvgId || name.match(/\d{2}\/\d{2}\s+\d{1,2}:\d{2}\s+(AM|PM|ET)/)) {
          currentChannel = null;
          continue;
        }

        currentChannel = {
          id: tvgId,
          name: tvgNameMatch ? tvgNameMatch[1] : name,
          displayName: name,
          group: groupMatch ? groupMatch[1] : 'Uncategorized',
          url: null
        };
      } else if (line && !line.startsWith('#') && currentChannel) {
        // This is the stream URL
        currentChannel.url = line;
        channels.push(currentChannel);
        currentChannel = null;
      }
    }

    console.log(`📺 Parsed ${channels.length} channels from M3U`);
    return channels;
  } catch (err) {
    console.error('Failed to parse M3U:', err);
    return [];
  }
};

/**
 * Parse M3U content from string
 *
 * @param {string} content - M3U file content as string
 * @returns {Array} Array of channel objects
 */
const parseM3UContent = (content) => {
  const lines = content.split('\n');
  const channels = [];
  let currentChannel = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();

    if (line.startsWith('#EXTINF:')) {
      // Parse channel info
      const tvgIdMatch = line.match(/tvg-id="([^"]*)"/);
      const tvgNameMatch = line.match(/tvg-name="([^"]*)"/);
      const groupMatch = line.match(/group-title="([^"]*)"/);

      // Extract channel name from the end (after last comma)
      const lastComma = line.lastIndexOf(',');
      const name = lastComma !== -1 ? line.substring(lastComma + 1).trim() : '';

      const tvgId = tvgIdMatch ? tvgIdMatch[1] : '';

      // Skip sports events (no tvg-id or contains specific time patterns)
      if (!tvgId || name.match(/\d{2}\/\d{2}\s+\d{1,2}:\d{2}\s+(AM|PM|ET)/)) {
        currentChannel = null;
        continue;
      }

      currentChannel = {
        id: tvgId,
        name: tvgNameMatch ? tvgNameMatch[1] : name,
        displayName: name,
        group: groupMatch ? groupMatch[1] : 'Uncategorized',
        url: null
      };
    } else if (line && !line.startsWith('#') && currentChannel) {
      // This is the stream URL
      currentChannel.url = line;
      channels.push(currentChannel);
      currentChannel = null;
    }
  }

  return channels;
};

/**
 * Get list of channels from default M3U file
 *
 * @returns {Promise<Array>} Array of channel objects
 */
const getChannelList = async () => {
  const defaultM3UPath = path.join(__dirname, 'data', 'channels.m3u');
  return parseM3U(defaultM3UPath);
};

/**
 * Filter channels by group
 *
 * @param {Array} channels - Array of channel objects
 * @param {string} group - Group name to filter by
 * @returns {Array} Filtered channel array
 */
const filterByGroup = (channels, group) => {
  return channels.filter(ch => ch.group === group);
};

/**
 * Get unique groups from channel list
 *
 * @param {Array} channels - Array of channel objects
 * @returns {Array<string>} Array of unique group names
 */
const getGroups = (channels) => {
  const groups = new Set();
  for (const channel of channels) {
    groups.add(channel.group);
  }
  return Array.from(groups).sort();
};

/**
 * Find channel by ID
 *
 * @param {Array} channels - Array of channel objects
 * @param {string} channelId - Channel ID to find
 * @returns {Object|null} Channel object or null if not found
 */
const findChannelById = (channels, channelId) => {
  return channels.find(ch => ch.id === channelId) || null;
};

/**
 * Find channels by name (case-insensitive partial match)
 *
 * @param {Array} channels - Array of channel objects
 * @param {string} searchTerm - Search term
 * @returns {Array} Matching channels
 */
const searchChannels = (channels, searchTerm) => {
  const lower = searchTerm.toLowerCase();
  return channels.filter(ch =>
    ch.name.toLowerCase().includes(lower) ||
    ch.displayName.toLowerCase().includes(lower)
  );
};

module.exports = {
  parseM3U,
  parseM3UContent,
  getChannelList,
  filterByGroup,
  getGroups,
  findChannelById,
  searchChannels
};
