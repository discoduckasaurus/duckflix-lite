const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { addMagnet, getTorrentInfo, selectFiles, getUnrestrictedLink } = require('../services/rd-service');
const logger = require('../utils/logger');

const router = express.Router();

router.use(authenticateToken);

/**
 * POST /api/rd/add-magnet
 * Add a magnet link to Real-Debrid
 */
router.post('/add-magnet', async (req, res) => {
  try {
    const { magnetUrl } = req.body;

    if (!magnetUrl) {
      return res.status(400).json({ error: 'Magnet URL required' });
    }

    const rdApiKey = req.user.rdApiKey;
    if (!rdApiKey) {
      return res.status(400).json({ error: 'Real-Debrid API key not configured for user' });
    }

    logger.info(`Adding magnet to RD for user ${req.user.username}`);

    const result = await addMagnet(rdApiKey, magnetUrl);
    res.json(result);
  } catch (error) {
    logger.error('Add magnet error:', error);
    if (error.response?.status === 403) {
      return res.status(403).json({ error: 'RD quota exceeded or invalid key' });
    }
    res.status(500).json({ error: 'Failed to add magnet' });
  }
});

/**
 * GET /api/rd/torrent/:id
 * Get torrent info from Real-Debrid
 */
router.get('/torrent/:id', async (req, res) => {
  try {
    const { id } = req.params;

    const rdApiKey = req.user.rdApiKey;
    if (!rdApiKey) {
      return res.status(400).json({ error: 'Real-Debrid API key not configured' });
    }

    const info = await getTorrentInfo(rdApiKey, id);
    res.json(info);
  } catch (error) {
    logger.error('Get torrent info error:', error);
    res.status(500).json({ error: 'Failed to get torrent info' });
  }
});

/**
 * POST /api/rd/torrent/:id/select
 * Select files from a torrent
 */
router.post('/torrent/:id/select', async (req, res) => {
  try {
    const { id } = req.params;
    const { fileIds } = req.body;

    if (!fileIds || !Array.isArray(fileIds)) {
      return res.status(400).json({ error: 'File IDs array required' });
    }

    const rdApiKey = req.user.rdApiKey;
    if (!rdApiKey) {
      return res.status(400).json({ error: 'Real-Debrid API key not configured' });
    }

    await selectFiles(rdApiKey, id, fileIds);
    res.json({ success: true });
  } catch (error) {
    logger.error('Select files error:', error);
    res.status(500).json({ error: 'Failed to select files' });
  }
});

/**
 * POST /api/rd/unrestrict
 * Get unrestricted download link from Real-Debrid
 */
router.post('/unrestrict', async (req, res) => {
  try {
    const { link } = req.body;

    if (!link) {
      return res.status(400).json({ error: 'Link required' });
    }

    const rdApiKey = req.user.rdApiKey;
    if (!rdApiKey) {
      return res.status(400).json({ error: 'Real-Debrid API key not configured' });
    }

    const result = await getUnrestrictedLink(rdApiKey, link);
    res.json(result);
  } catch (error) {
    logger.error('Unrestrict link error:', error);
    res.status(500).json({ error: 'Failed to unrestrict link' });
  }
});

module.exports = router;
