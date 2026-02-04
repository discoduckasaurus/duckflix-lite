const express = require('express');
const path = require('path');
const fs = require('fs');
const logger = require('../utils/logger');

const router = express.Router();

const APK_DIR = process.env.APK_DIRECTORY || path.join(__dirname, '../static/apk');

/**
 * GET /api/apk/latest
 * Download latest APK
 */
router.get('/latest', (req, res) => {
  try {
    const apkPath = path.join(APK_DIR, 'latest.apk');

    if (!fs.existsSync(apkPath)) {
      return res.status(404).json({ error: 'APK not found' });
    }

    logger.info(`APK download requested from IP: ${req.ip}`);

    res.download(apkPath, 'duckflix-lite.apk');
  } catch (error) {
    logger.error('APK download error:', error);
    res.status(500).json({ error: 'Failed to download APK' });
  }
});

/**
 * GET /api/apk/version
 * Get APK version info
 */
router.get('/version', (req, res) => {
  try {
    const apkPath = path.join(APK_DIR, 'latest.apk');

    if (!fs.existsSync(apkPath)) {
      return res.status(404).json({ error: 'APK not found' });
    }

    const stats = fs.statSync(apkPath);

    // TODO: Read version from metadata file
    res.json({
      version: '1.0.0',
      downloadUrl: '/api/apk/latest',
      size: stats.size,
      lastModified: stats.mtime.toISOString()
    });
  } catch (error) {
    logger.error('APK version error:', error);
    res.status(500).json({ error: 'Failed to get APK version' });
  }
});

module.exports = router;
