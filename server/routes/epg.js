const express = require('express');
const { db } = require('../db/init');
const { authenticateToken, requireAdmin } = require('../middleware/auth');
const { syncEPG, syncM3U, syncAll } = require('../services/epg-sync');
const logger = require('../utils/logger');

const router = express.Router();

router.use(authenticateToken);

/**
 * GET /api/epg
 * Get EPG data (cached)
 */
router.get('/', (req, res) => {
  try {
    const epgData = db.prepare(`
      SELECT channel_id, epg_data, updated_at
      FROM epg_cache
      WHERE channel_id != 'm3u_channels'
      ORDER BY channel_id
    `).all();

    const channels = epgData.map(row => ({
      channelId: row.channel_id,
      programs: JSON.parse(row.epg_data),
      updatedAt: row.updated_at
    }));

    res.json({ channels });
  } catch (error) {
    logger.error('Get EPG error:', error);
    res.status(500).json({ error: 'Failed to get EPG data' });
  }
});

/**
 * GET /api/m3u
 * Get M3U playlist as JSON
 */
router.get('/m3u', (req, res) => {
  try {
    const m3uData = db.prepare(`
      SELECT epg_data, updated_at
      FROM epg_cache
      WHERE channel_id = 'm3u_channels'
    `).get();

    if (!m3uData) {
      return res.json({ channels: [], updatedAt: null });
    }

    const channels = JSON.parse(m3uData.epg_data);
    res.json({ channels, updatedAt: m3uData.updated_at });
  } catch (error) {
    logger.error('Get M3U error:', error);
    res.status(500).json({ error: 'Failed to get M3U data' });
  }
});

/**
 * POST /api/epg/sync
 * Manually trigger EPG sync (admin only)
 */
router.post('/sync', requireAdmin, async (req, res) => {
  try {
    await syncAll();
    res.json({ success: true, message: 'EPG/M3U sync started' });
  } catch (error) {
    logger.error('Manual sync error:', error);
    res.status(500).json({ error: 'Sync failed' });
  }
});

module.exports = router;
