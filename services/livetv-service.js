const path = require('path');
const fs = require('fs');
const { db } = require('../db/init');
const logger = require('../utils/logger');

// Pre-compiled prepared statements (avoids db.prepare() overhead on every call)
const stmtGetM3uChannels = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'`);
const stmtGetSpecialChannel = db.prepare(`SELECT stream_url FROM special_channels WHERE id = ? AND is_active = 1`);

// Stream URL cache: channelId -> { urls, cachedAt }
const streamUrlCache = new Map();
const STREAM_URL_CACHE_TTL = 30000; // 30 seconds

// Load config files at module level
const CHANNEL_CONFIG_PATH = path.join(__dirname, '..', 'db', 'channel-config.json');
const BACKUP_STREAMS_PATH = path.join(__dirname, '..', 'db', 'backup-streams.json');

let channelConfig = {};
let backupStreams = {};

try {
  channelConfig = JSON.parse(fs.readFileSync(CHANNEL_CONFIG_PATH, 'utf-8'));
  logger.info(`Loaded channel config: ${Object.keys(channelConfig).length} channels`);
} catch (err) {
  logger.warn('Failed to load channel-config.json:', err.message);
}

try {
  backupStreams = JSON.parse(fs.readFileSync(BACKUP_STREAMS_PATH, 'utf-8'));
  logger.info(`Loaded backup streams: ${Object.keys(backupStreams).length} channels`);
} catch (err) {
  logger.warn('Failed to load backup-streams.json:', err.message);
}

/**
 * Initialize default channel settings for a user based on channel-config.json.
 * Channels with `enabled: false` in config get a user_channel_settings row with is_enabled=0.
 * Only runs if the user has zero existing channel settings.
 */
function initializeUserDefaults(userId) {
  const existing = db.prepare(`
    SELECT COUNT(*) as count FROM user_channel_settings WHERE user_id = ?
  `).get(userId);

  if (existing.count > 0) return;

  const disabledChannels = Object.entries(channelConfig)
    .filter(([, cfg]) => cfg.enabled === false)
    .map(([id]) => id);

  if (disabledChannels.length === 0) return;

  const stmt = db.prepare(`
    INSERT OR IGNORE INTO user_channel_settings (user_id, channel_id, is_enabled, updated_at)
    VALUES (?, ?, 0, datetime('now'))
  `);

  const transaction = db.transaction((channels) => {
    for (const channelId of channels) {
      stmt.run(userId, channelId);
    }
  });

  transaction(disabledChannels);
  logger.info(`Initialized ${disabledChannels.length} default-disabled channels for user ${userId}`);
}

/**
 * Get all enabled channels for a user with merged EPG data
 * @param {number} userId - User ID
 * @returns {Array} Array of channel objects with EPG data
 */
async function getChannelsForUser(userId) {
  try {
    // Initialize defaults for new users
    initializeUserDefaults(userId);

    // 1. Get M3U channels from cache
    const m3uData = db.prepare(`
      SELECT epg_data, updated_at
      FROM epg_cache
      WHERE channel_id = 'm3u_channels'
    `).get();

    if (!m3uData) {
      logger.warn('No M3U channels found in cache');
      return [];
    }

    const m3uChannels = JSON.parse(m3uData.epg_data);

    // 2. Get special channels (admin-defined)
    const specialChannels = db.prepare(`
      SELECT id, name, display_name, group_name, stream_url, logo_url,
             sort_order, channel_number, is_active
      FROM special_channels
      WHERE is_active = 1
    `).all();

    // Combine M3U and special channels
    const allChannels = [
      ...m3uChannels.map(ch => {
        const config = channelConfig[ch.id];
        return {
          id: ch.id,
          name: ch.name,
          displayName: config?.displayName || ch.displayName || ch.name,
          group: ch.group || 'Live',
          url: ch.url,
          logo: config?.logoFile ? `/static/logos/${config.logoFile}` : null,
          sortOrder: 999,
          channelNumber: null,
          isSpecial: false
        };
      }),
      ...specialChannels.map(ch => ({
        id: ch.id,
        name: ch.name,
        displayName: ch.display_name,
        group: ch.group_name,
        url: ch.stream_url,
        logo: ch.logo_url,
        sortOrder: ch.sort_order,
        channelNumber: ch.channel_number,
        isSpecial: true
      }))
    ];

    // 3. Get user channel settings
    const userSettings = db.prepare(`
      SELECT channel_id, is_enabled, is_favorite, sort_order
      FROM user_channel_settings
      WHERE user_id = ?
    `).all(userId);

    const settingsMap = new Map(
      userSettings.map(s => [s.channel_id, s])
    );

    // 4. Get channel metadata (custom logos, display names, admin enable/disable)
    const metadata = db.prepare(`
      SELECT channel_id, custom_logo_url, custom_display_name, is_enabled, sort_order
      FROM channel_metadata
    `).all();

    const metadataMap = new Map(
      metadata.map(m => [m.channel_id, m])
    );

    // 5. Filter out admin-disabled channels before building full objects
    const adminEnabledChannels = allChannels.filter(channel => {
      const meta = metadataMap.get(channel.id);
      // If admin explicitly disabled this channel, skip it entirely
      if (meta && meta.is_enabled === 0) return false;
      return true;
    });

    // 6. Get EPG data for each channel
    const now = Date.now();
    const sixHoursFromNow = now + (6 * 60 * 60 * 1000);

    const channels = adminEnabledChannels.map((channel, index) => {
      const settings = settingsMap.get(channel.id) || {
        is_enabled: true,
        is_favorite: false,
        sort_order: null
      };

      const meta = metadataMap.get(channel.id);

      // Get EPG data for this channel
      let currentProgram = null;
      let upcomingPrograms = [];

      try {
        const epgRow = db.prepare(`
          SELECT epg_data
          FROM epg_cache
          WHERE channel_id = ?
        `).get(channel.id);

        if (epgRow) {
          const programs = JSON.parse(epgRow.epg_data);

          // Find current program
          currentProgram = programs.find(p => p.start <= now && p.stop > now);

          // Find upcoming programs (next 6 hours)
          upcomingPrograms = programs
            .filter(p => p.start >= now && p.start < sixHoursFromNow)
            .sort((a, b) => a.start - b.start);
        }
      } catch (epgError) {
        logger.debug(`No EPG data for channel ${channel.id}`);
      }

      return {
        id: channel.id,
        name: channel.name,
        displayName: meta?.custom_display_name || channel.displayName,
        group: channel.group,
        url: channel.url,
        logo: meta?.custom_logo_url || channel.logo,
        sortOrder: meta?.sort_order ?? channel.sortOrder,
        channelNumber: channel.channelNumber || (index + 1),
        isFavorite: !!settings.is_favorite,
        isEnabled: !!settings.is_enabled,
        currentProgram: currentProgram ? {
          start: currentProgram.start,
          stop: currentProgram.stop,
          title: currentProgram.title || 'Unknown',
          description: currentProgram.desc || currentProgram.description || '',
          category: currentProgram.category || null,
          icon: currentProgram.icon || null,
          episodeNum: currentProgram.episodeNum || null
        } : null,
        upcomingPrograms: upcomingPrograms.slice(0, 12).map(p => ({
          start: p.start,
          stop: p.stop,
          title: p.title || 'Unknown',
          description: p.desc || p.description || '',
          category: p.category || null,
          icon: p.icon || null,
          episodeNum: p.episodeNum || null
        }))
      };
    });

    // Filter to only enabled channels and sort
    const enabledChannels = channels
      .filter(ch => ch.isEnabled)
      .sort((a, b) => {
        // Favorites first
        if (a.isFavorite && !b.isFavorite) return -1;
        if (!a.isFavorite && b.isFavorite) return 1;
        // Then by sort order
        if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
        // Then by channel number
        return a.channelNumber - b.channelNumber;
      });

    // Assign channel numbers AFTER sorting so they reflect final order
    enabledChannels.forEach((ch, i) => {
      ch.channelNumber = i + 1;
    });

    logger.info(`Loaded ${enabledChannels.length} channels for user ${userId}`);
    return enabledChannels;
  } catch (error) {
    logger.error('getChannelsForUser error:', error);
    throw error;
  }
}

// Cached NTV stream map (tvpassId -> { channel_id, channel_name, channel_code, channel_url })
let ntvStreamMap = null;
let ntvStreamMapLoadedAt = 0;
const NTV_MAP_CACHE_TTL = 60000; // 1 minute

// Cached IPTV stream map (tvpassId -> { url, iptv_id, name, group })
let iptvStreamMap = null;
let iptvStreamMapLoadedAt = 0;
const IPTV_MAP_CACHE_TTL = 60000; // 1 minute

/**
 * Load the NTV stream map from epg_cache (lazy-load + TTL cache).
 * Returns {channelId: {channel_id, channel_name, channel_code, channel_url}} or empty object.
 */
function getNTVStreamMap() {
  const now = Date.now();
  if (ntvStreamMap && (now - ntvStreamMapLoadedAt) < NTV_MAP_CACHE_TTL) {
    return ntvStreamMap;
  }

  try {
    const row = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'ntv_stream_map'
    `).get();

    ntvStreamMap = row ? JSON.parse(row.epg_data) : {};
  } catch (err) {
    logger.debug(`Error loading ntv_stream_map: ${err.message}`);
    ntvStreamMap = {};
  }

  ntvStreamMapLoadedAt = now;
  return ntvStreamMap;
}

/**
 * Load the IPTV stream map from epg_cache (lazy-load + TTL cache).
 * Returns {channelId: {url, iptv_id, name, group}} or empty object.
 */
function getIPTVStreamMap() {
  const now = Date.now();
  if (iptvStreamMap && (now - iptvStreamMapLoadedAt) < IPTV_MAP_CACHE_TTL) {
    return iptvStreamMap;
  }

  try {
    const row = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'iptv_stream_map'
    `).get();

    iptvStreamMap = row ? JSON.parse(row.epg_data) : {};
  } catch (err) {
    logger.debug(`Error loading iptv_stream_map: ${err.message}`);
    iptvStreamMap = {};
  }

  iptvStreamMapLoadedAt = now;
  return iptvStreamMap;
}

/**
 * Get stream URLs for a channel, ordered by priority:
 * 1. IPTV (direct URL from external M3U provider)
 * 2. NTV (ntv://{channel_id} marker — resolved on-demand in livetv.js)
 * 3. backup-streams.json primary (if exists)
 * 4. M3U/special channel original URL
 * 5. backup-streams.json backups[]
 *
 * @param {string} channelId - Channel ID
 * @returns {string[]} Array of stream URLs to try in order
 */
function getStreamUrls(channelId) {
  // Check cache first
  const cached = streamUrlCache.get(channelId);
  if (cached && (Date.now() - cached.cachedAt) < STREAM_URL_CACHE_TTL) {
    return cached.urls;
  }

  const urls = [];
  const backup = backupStreams[channelId];
  const iptvMap = getIPTVStreamMap();
  const ntvMap = getNTVStreamMap();

  // 1. IPTV primary (direct URL from external M3U provider)
  if (iptvMap[channelId]?.url) {
    urls.push(iptvMap[channelId].url);
  }

  // 2. NTV (marker URL — resolved on-demand since tokens are ephemeral)
  if (ntvMap[channelId]) {
    urls.push(`ntv://${ntvMap[channelId].channel_id}`);
  }

  // 3. Primary override from backup-streams.json
  if (backup?.primary) {
    if (!urls.includes(backup.primary)) {
      urls.push(backup.primary);
    }
  }

  // 4. Original M3U / special channel URL
  try {
    const m3uData = stmtGetM3uChannels.get();

    if (m3uData) {
      const channels = JSON.parse(m3uData.epg_data);
      const channel = channels.find(ch => ch.id === channelId);
      if (channel?.url) {
        if (!urls.includes(channel.url)) {
          urls.push(channel.url);
        }
      }
    }
  } catch (err) {
    logger.debug(`Error looking up M3U URL for ${channelId}: ${err.message}`);
  }

  // Check special channels
  try {
    const specialChannel = stmtGetSpecialChannel.get(channelId);

    if (specialChannel?.stream_url && !urls.includes(specialChannel.stream_url)) {
      urls.push(specialChannel.stream_url);
    }
  } catch (err) {
    logger.debug(`Error looking up special channel URL for ${channelId}: ${err.message}`);
  }

  // 5. Backup streams
  if (backup?.backups?.length) {
    for (const backupUrl of backup.backups) {
      if (!urls.includes(backupUrl)) {
        urls.push(backupUrl);
      }
    }
  }

  if (urls.length === 0) {
    throw new Error(`Channel ${channelId} not found`);
  }

  // Per-channel source deprioritization (e.g., move NTV to last for channels with bad NTV feeds)
  const chConf = channelConfig[channelId];
  if (chConf?.deprioritize?.length && urls.length > 1) {
    const depri = new Set(chConf.deprioritize.map(s => s.toUpperCase()));
    const labelUrl = (url) => {
      if (url.startsWith('ntv://') || /cdn-live\.|cdn-google\./.test(url)) return 'NTV';
      if (url.includes('tvpass.org')) return 'TVPASS';
      if (url.includes('smartcdn.org') || url.includes('iptv-')) return 'IPTV';
      return 'OTHER';
    };
    const keep = urls.filter(u => !depri.has(labelUrl(u)));
    const demoted = urls.filter(u => depri.has(labelUrl(u)));
    if (keep.length > 0 && demoted.length > 0) {
      urls.length = 0;
      urls.push(...keep, ...demoted);
      logger.debug(`[StreamUrls] ${channelId}: deprioritized ${chConf.deprioritize.join(',')} (${keep.length} primary, ${demoted.length} demoted)`);
    }
  }

  // Cache the result
  streamUrlCache.set(channelId, { urls: [...urls], cachedAt: Date.now() });

  return urls;
}

/**
 * Get single stream URL for a channel (backwards compat)
 * @param {string} channelId - Channel ID
 * @returns {string} First/best stream URL
 */
function getStreamUrl(channelId) {
  return getStreamUrls(channelId)[0];
}

/**
 * Toggle channel enabled/disabled for a user
 * @param {number} userId - User ID
 * @param {string} channelId - Channel ID
 * @returns {boolean} New enabled state
 */
function toggleChannel(userId, channelId) {
  try {
    // Get current state
    const current = db.prepare(`
      SELECT is_enabled
      FROM user_channel_settings
      WHERE user_id = ? AND channel_id = ?
    `).get(userId, channelId);

    const newState = current ? !current.is_enabled : false;

    // Upsert
    db.prepare(`
      INSERT INTO user_channel_settings (user_id, channel_id, is_enabled, updated_at)
      VALUES (?, ?, ?, datetime('now'))
      ON CONFLICT(user_id, channel_id) DO UPDATE SET
        is_enabled = excluded.is_enabled,
        updated_at = datetime('now')
    `).run(userId, channelId, newState ? 1 : 0);

    logger.info(`Channel ${channelId} ${newState ? 'enabled' : 'disabled'} for user ${userId}`);
    return newState;
  } catch (error) {
    logger.error('toggleChannel error:', error);
    throw error;
  }
}

/**
 * Toggle favorite status for a channel
 * @param {number} userId - User ID
 * @param {string} channelId - Channel ID
 * @returns {boolean} New favorite state
 */
function toggleFavorite(userId, channelId) {
  try {
    // Get current state
    const current = db.prepare(`
      SELECT is_favorite
      FROM user_channel_settings
      WHERE user_id = ? AND channel_id = ?
    `).get(userId, channelId);

    const newState = current ? !current.is_favorite : true;

    // Upsert
    db.prepare(`
      INSERT INTO user_channel_settings (user_id, channel_id, is_favorite, updated_at)
      VALUES (?, ?, ?, datetime('now'))
      ON CONFLICT(user_id, channel_id) DO UPDATE SET
        is_favorite = excluded.is_favorite,
        updated_at = datetime('now')
    `).run(userId, channelId, newState ? 1 : 0);

    logger.info(`Channel ${channelId} favorite=${newState} for user ${userId}`);
    return newState;
  } catch (error) {
    logger.error('toggleFavorite error:', error);
    throw error;
  }
}

/**
 * Reorder channels for a user
 * @param {number} userId - User ID
 * @param {Array<string>} channelIds - Array of channel IDs in desired order
 */
function reorderChannels(userId, channelIds) {
  try {
    const updateStmt = db.prepare(`
      INSERT INTO user_channel_settings (user_id, channel_id, sort_order, updated_at)
      VALUES (?, ?, ?, datetime('now'))
      ON CONFLICT(user_id, channel_id) DO UPDATE SET
        sort_order = excluded.sort_order,
        updated_at = datetime('now')
    `);

    const transaction = db.transaction((channels) => {
      channels.forEach((channelId, index) => {
        updateStmt.run(userId, channelId, index);
      });
    });

    transaction(channelIds);

    logger.info(`Reordered ${channelIds.length} channels for user ${userId}`);
  } catch (error) {
    logger.error('reorderChannels error:', error);
    throw error;
  }
}

module.exports = {
  getChannelsForUser,
  getStreamUrl,
  getStreamUrls,
  getNTVStreamMap,
  getIPTVStreamMap,
  toggleChannel,
  toggleFavorite,
  reorderChannels
};
