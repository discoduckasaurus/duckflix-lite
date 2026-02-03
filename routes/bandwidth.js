const express = require('express');
const crypto = require('crypto');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');

const router = express.Router();

// Test payload size (5MB)
const TEST_PAYLOAD_SIZE = 5 * 1024 * 1024;

// Pre-generate random payload (do once at startup)
const testPayload = crypto.randomBytes(TEST_PAYLOAD_SIZE);

/**
 * GET /api/bandwidth/test
 * Returns test payload for client to measure download speed
 */
router.get('/test', authenticateToken, (req, res) => {
  logger.info(`Bandwidth test requested by user ${req.user.username}`);

  res.set({
    'Content-Type': 'application/octet-stream',
    'Content-Length': TEST_PAYLOAD_SIZE,
    'Cache-Control': 'no-store',
    'X-Test-Size-Bytes': TEST_PAYLOAD_SIZE
  });

  res.send(testPayload);
});

/**
 * POST /api/bandwidth/report
 * Client reports measured bandwidth
 */
router.post('/report', authenticateToken, (req, res) => {
  try {
    const { measuredMbps, trigger } = req.body;
    const userId = req.user.sub;

    if (typeof measuredMbps !== 'number' || !Number.isFinite(measuredMbps) || measuredMbps <= 0) {
      return res.status(400).json({ error: 'Invalid measuredMbps value' });
    }

    // Cap at reasonable maximum (1 Gbps)
    const cappedMbps = Math.min(measuredMbps, 1000);

    db.prepare(`
      UPDATE users
      SET measured_bandwidth_mbps = ?,
          bandwidth_measured_at = datetime('now')
      WHERE id = ?
    `).run(cappedMbps, userId);

    logger.info(`Bandwidth reported for user ${req.user.username}: ${cappedMbps} Mbps (trigger: ${trigger || 'unknown'})`);

    res.json({
      success: true,
      recorded: cappedMbps,
      maxBitrate: cappedMbps / 1.3 // Return their effective max bitrate
    });
  } catch (error) {
    logger.error('Bandwidth report error:', error);
    res.status(500).json({ error: 'Failed to record bandwidth' });
  }
});

/**
 * GET /api/bandwidth/status
 * Get current user's bandwidth info
 */
router.get('/status', authenticateToken, (req, res) => {
  try {
    const userId = req.user.sub;

    const user = db.prepare(`
      SELECT measured_bandwidth_mbps, bandwidth_measured_at, bandwidth_safety_margin
      FROM users WHERE id = ?
    `).get(userId);

    if (!user || !user.measured_bandwidth_mbps) {
      return res.json({
        hasMeasurement: false,
        needsTest: true
      });
    }

    const safetyMargin = user.bandwidth_safety_margin || 1.3;
    const maxBitrate = user.measured_bandwidth_mbps / safetyMargin;

    res.json({
      hasMeasurement: true,
      measuredMbps: user.measured_bandwidth_mbps,
      measuredAt: user.bandwidth_measured_at,
      safetyMargin: safetyMargin,
      maxBitrateMbps: Math.round(maxBitrate * 10) / 10
    });
  } catch (error) {
    logger.error('Bandwidth status error:', error);
    res.status(500).json({ error: 'Failed to get bandwidth status' });
  }
});

module.exports = router;
