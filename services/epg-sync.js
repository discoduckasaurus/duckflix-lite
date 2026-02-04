const cron = require('node-cron');
const axios = require('axios');
const { fetchEPG } = require('@duckflix/epg-parser');
const { parseM3UContent } = require('@duckflix/m3u-parser');
const { db } = require('../db/init');
const logger = require('../utils/logger');

/**
 * Fetch and cache M3U playlist
 */
async function syncM3U() {
  try {
    const m3uUrl = process.env.M3U_SOURCE_URL;
    if (!m3uUrl) {
      logger.warn('M3U_SOURCE_URL not configured, skipping M3U sync');
      return;
    }

    logger.info('Fetching M3U playlist...');
    const response = await axios.get(m3uUrl, { timeout: 30000 });
    const channels = await parseM3UContent(response.data);

    logger.info(`Parsed ${channels.length} channels from M3U`);

    // Store channels in database
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `);

    const channelData = channels.map(ch => ({
      id: ch.id,
      name: ch.name,
      url: ch.url,
      logoUrl: ch.logoUrl,
      group: ch.group
    }));

    stmt.run('m3u_channels', JSON.stringify(channelData));

    logger.info('M3U sync complete');
  } catch (error) {
    logger.error('M3U sync failed:', error);
  }
}

/**
 * Fetch and cache EPG data
 */
async function syncEPG() {
  try {
    logger.info('Fetching EPG data...');

    const { channels, programs } = await fetchEPG({
      includeTvPass: true,
      includePluto: true,
      includeEpgPw: true
    });

    logger.info(`Fetched EPG for ${Object.keys(programs).length} channels`);

    // Store EPG data per channel
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `);

    for (const [channelId, channelPrograms] of Object.entries(programs)) {
      stmt.run(channelId, JSON.stringify(channelPrograms));
    }

    logger.info('EPG sync complete');
  } catch (error) {
    logger.error('EPG sync failed:', error);
  }
}

/**
 * Sync both EPG and M3U data
 */
async function syncAll() {
  logger.info('Starting EPG/M3U sync...');
  await Promise.all([syncEPG(), syncM3U()]);
  logger.info('EPG/M3U sync finished');
}

/**
 * Start cron jobs for periodic syncing
 */
function startSyncJobs() {
  // Sync EPG every 30 minutes
  cron.schedule('*/30 * * * *', () => {
    logger.info('Cron: EPG sync triggered');
    syncEPG().catch(err => logger.error('Cron EPG sync error:', err));
  });

  // Sync M3U every hour
  cron.schedule('0 * * * *', () => {
    logger.info('Cron: M3U sync triggered');
    syncM3U().catch(err => logger.error('Cron M3U sync error:', err));
  });

  logger.info('EPG/M3U cron jobs started (EPG: 30min, M3U: 1hr)');

  // Run initial sync
  syncAll().catch(err => logger.error('Initial sync error:', err));
}

module.exports = {
  syncEPG,
  syncM3U,
  syncAll,
  startSyncJobs
};
