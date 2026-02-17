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
 * Get APK version info for in-app update checks
 */
router.get('/version', (req, res) => {
  try {
    const versionPath = path.join(APK_DIR, 'version.json');
    const apkPath = path.join(APK_DIR, 'latest.apk');

    if (!fs.existsSync(versionPath)) {
      return res.status(404).json({ error: 'Version info not found' });
    }

    const versionInfo = JSON.parse(fs.readFileSync(versionPath, 'utf8'));

    // Merge in APK file size for download progress calculation
    let size = 0;
    try {
      const realPath = fs.realpathSync(apkPath);
      size = fs.statSync(realPath).size;
    } catch (e) {
      // APK file missing — size stays 0
    }

    res.json({
      versionCode: versionInfo.versionCode,
      versionName: versionInfo.versionName,
      releaseNotes: versionInfo.releaseNotes || '',
      downloadUrl: '/api/apk/latest',
      size
    });
  } catch (error) {
    logger.error('APK version error:', error);
    res.status(500).json({ error: 'Failed to get APK version' });
  }
});

module.exports = router;
