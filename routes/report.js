const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { sendUserReport } = require('../services/email-service');
const logger = require('../utils/logger');

const router = express.Router();

router.use(authenticateToken);

/**
 * POST /api/report
 * Submit a user report
 */
router.post('/', async (req, res) => {
  try {
    const { page, title, tmdbId, message } = req.body;
    const username = req.user.username;
    const userAgent = req.get('user-agent');

    if (!page) {
      return res.status(400).json({ error: 'Page is required' });
    }

    const report = {
      username,
      page,
      title,
      tmdbId,
      message,
      timestamp: new Date().toISOString(),
      userAgent
    };

    const sent = await sendUserReport(report);

    if (sent) {
      logger.info(`Report submitted by ${username} on ${page}`);
      res.json({ success: true, message: 'Report sent successfully' });
    } else {
      res.status(500).json({ error: 'Failed to send report' });
    }
  } catch (error) {
    logger.error('Report submission error:', error);
    res.status(500).json({ error: 'Failed to submit report' });
  }
});

module.exports = router;
