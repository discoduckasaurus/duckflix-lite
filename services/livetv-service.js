const { db } = require('../db/init');
const logger = require('../utils/logger');

/**
 * Get all enabled channels for a user with merged EPG data
 * @param {number} userId - User ID
 * @returns {Array} Array of channel objects with EPG data
 */
async function getChannelsForUser(userId) {
  try {
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
      ...m3uChannels.map(ch => ({
        id: ch.id,
        name: ch.name,
        displayName: ch.name,
        group: ch.group || 'Live',
        url: ch.url,
        logo: ch.logoUrl,
        sortOrder: 999,
        channelNumber: null,
        isSpecial: false
      })),
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

    // 4. Get channel metadata (custom logos, display names)
    const metadata = db.prepare(`
      SELECT channel_id, custom_logo_url, custom_display_name
      FROM channel_metadata
    `).all();

    const metadataMap = new Map(
      metadata.map(m => [m.channel_id, m])
    );

    // 5. Get EPG data for each channel
    const now = Date.now();
    const sixHoursFromNow = now + (6 * 60 * 60 * 1000);

    const channels = allChannels.map((channel, index) => {
      const settings = settingsMap.get(channel.id) || {
        is_enabled: true,
        is_favorite: false,
        sort_order: channel.sortOrder
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
        sortOrder: settings.sort_order || channel.sortOrder,
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

    logger.info(`Loaded ${enabledChannels.length} channels for user ${userId}`);
    return enabledChannels;
  } catch (error) {
    logger.error('getChannelsForUser error:', error);
    throw error;
  }
}

/**
 * Get stream URL for a channel (proxied through server)
 * @param {string} channelId - Channel ID
 * @returns {string} Stream URL
 */
function getStreamUrl(channelId) {
  // The stream endpoint will handle the actual proxying
  // This just returns the channel's URL from the database
  try {
    // Check M3U channels first
    const m3uData = db.prepare(`
      SELECT epg_data
      FROM epg_cache
      WHERE channel_id = 'm3u_channels'
    `).get();

    if (m3uData) {
      const channels = JSON.parse(m3uData.epg_data);
      const channel = channels.find(ch => ch.id === channelId);
      if (channel) {
        return channel.url;
      }
    }

    // Check special channels
    const specialChannel = db.prepare(`
      SELECT stream_url
      FROM special_channels
      WHERE id = ? AND is_active = 1
    `).get(channelId);

    if (specialChannel) {
      return specialChannel.stream_url;
    }

    throw new Error(`Channel ${channelId} not found`);
  } catch (error) {
    logger.error(`getStreamUrl error for ${channelId}:`, error);
    throw error;
  }
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
  toggleChannel,
  toggleFavorite,
  reorderChannels
};
