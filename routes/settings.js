const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');

const router = express.Router();

/**
 * GET /api/settings/playback
 * Get playback settings for client (stutter thresholds, etc)
 */
router.get('/playback', authenticateToken, (req, res) => {
  try {
    const settings = {};

    const rows = db.prepare(`
      SELECT key, value FROM app_settings
      WHERE key IN ('stutter_count_threshold', 'stutter_duration_seconds',
                    'stutter_window_minutes', 'single_stutter_max_seconds')
    `).all();

    for (const row of rows) {
      settings[row.key] = parseFloat(row.value);
    }

    res.json({
      stutterCountThreshold: settings.stutter_count_threshold || 3,
      stutterDurationSeconds: settings.stutter_duration_seconds || 3,
      stutterWindowMinutes: settings.stutter_window_minutes || 5,
      singleStutterMaxSeconds: settings.single_stutter_max_seconds || 10
    });
  } catch (error) {
    logger.error('Settings fetch error:', error);
    res.status(500).json({ error: 'Failed to fetch settings' });
  }
});

module.exports = router;
