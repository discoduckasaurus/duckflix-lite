const express = require('express');
const router = express.Router();
const downloadJobManager = require('../services/download-job-manager');

/**
 * GET /api/monitor/stats
 * Returns current download jobs and recent playbacks for TUI monitor
 */
router.get('/stats', (req, res) => {
  try {
    const activeJobs = downloadJobManager.getAllJobs();
    const completedJobs = downloadJobManager.getCompletedJobs();
    const recentPlaybacks = downloadJobManager.getRecentPlaybacks();

    res.json({
      activeJobs,
      completedJobs,
      recentPlaybacks,
      timestamp: Date.now()
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
