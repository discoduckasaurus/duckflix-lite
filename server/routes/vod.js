const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');
const { searchZurg } = require('../services/zurg-search');
const { completeDownloadFlow } = require('../services/rd-service');
const rdCacheService = require('../services/rd-cache-service');
const downloadJobManager = require('../services/download-job-manager');
const { v4: uuidv4 } = require('uuid');

const router = express.Router();

router.use(authenticateToken);

/**
 * POST /api/vod/stream-url/start
 * Start stream URL retrieval (returns jobId if download needed, or immediate streamUrl if cached)
 */
router.post('/stream-url/start', async (req, res) => {
  try {
    const { tmdbId, title, year, type, season, episode } = req.body;
    const userId = req.user.sub;

    logger.info(`Starting stream URL retrieval for: ${title} (${year})`);

    // 1. Check Zurg first (preferred due to cache info)
    const zurgResult = await searchZurg({
      title,
      year,
      type: type === 'tv' ? 'episode' : 'movie',
      season,
      episode
    });

    if (zurgResult.match || zurgResult.fallback) {
      const file = zurgResult.match || zurgResult.fallback;
      const zurgBaseUrl = process.env.ZURG_BASE_URL || 'http://localhost:9999';
      const streamUrl = `${zurgBaseUrl}${file.filePath}`;

      logger.info(`Zurg match found, returning immediately: ${streamUrl}`);

      return res.json({
        immediate: true,
        streamUrl,
        source: 'zurg',
        fileName: file.fileName
      });
    }

    // 2. Check RD cache
    const cachedRd = await rdCacheService.getCachedLink({
      tmdbId,
      type,
      season,
      episode
    });

    if (cachedRd) {
      logger.info(`RD cache hit, returning immediately: ${cachedRd.streamUrl}`);
      return res.json({
        immediate: true,
        streamUrl: cachedRd.streamUrl,
        source: 'rd-cached',
        fileName: cachedRd.fileName
      });
    }

    // 3. Need to download from RD - create job
    const jobId = uuidv4();
    downloadJobManager.createJob(jobId, { tmdbId, title, year, type, season, episode });

    logger.info(`Created download job ${jobId} for ${title}`);

    // Start download in background
    processRdDownload(jobId, { tmdbId, title, year, type, season, episode });

    res.json({
      immediate: false,
      jobId,
      message: 'Download started'
    });
  } catch (error) {
    logger.error('Stream URL start error:', {
      message: error.message,
      stack: error.stack?.split('\n').slice(0, 5)
    });

    res.status(500).json({
      error: 'Stream start failed',
      message: error.message || 'Unable to start stream retrieval'
    });
  }
});

/**
 * GET /api/vod/stream-url/progress/:jobId
 * Get download progress for a job
 */
router.get('/stream-url/progress/:jobId', (req, res) => {
  try {
    const { jobId } = req.params;
    const job = downloadJobManager.getJob(jobId);

    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    res.json({
      status: job.status,
      progress: job.progress,
      message: job.message,
      streamUrl: job.streamUrl,
      fileName: job.fileName,
      source: job.streamUrl ? 'rd' : null,
      error: job.error
    });
  } catch (error) {
    logger.error('Progress check error:', error);
    res.status(500).json({ error: 'Progress check failed' });
  }
});

/**
 * DELETE /api/vod/stream-url/cancel/:jobId
 * Cancel a download job
 */
router.delete('/stream-url/cancel/:jobId', (req, res) => {
  try {
    const { jobId } = req.params;
    downloadJobManager.deleteJob(jobId);
    logger.info(`Cancelled download job ${jobId}`);
    res.json({ cancelled: true });
  } catch (error) {
    logger.error('Cancel job error:', error);
    res.status(500).json({ error: 'Cancel failed' });
  }
});

/**
 * Background RD download processor
 */
async function processRdDownload(jobId, contentInfo) {
  const { tmdbId, title, year, type, season, episode } = contentInfo;

  try {
    // Update: Searching Prowlarr
    downloadJobManager.updateJob(jobId, {
      status: 'searching',
      progress: 5,
      message: 'Searching for content...'
    });

    // Check if RD API key is configured
    const rdApiKey = process.env.RD_API_KEY;
    if (!rdApiKey) {
      throw new Error('Real-Debrid API key not configured');
    }

    // Search Prowlarr for content
    const { searchContent } = require('../services/prowlarr-service');

    const searchResult = await searchContent({
      title,
      year,
      type,
      season,
      episode
    });

    if (!searchResult || !searchResult.magnetUrl) {
      throw new Error('No results found');
    }

    // Update: Found torrent, adding to RD
    downloadJobManager.updateJob(jobId, {
      status: 'downloading',
      progress: 10,
      message: 'Adding torrent to Real-Debrid...'
    });

    // Modified downloadFromRD to report progress
    const { downloadFromRD } = require('@duckflix/rd-client');

    const result = await downloadFromRD(
      searchResult.magnetUrl,
      rdApiKey,
      season,
      episode,
      (progress, message) => {
        // Progress callback
        downloadJobManager.updateJob(jobId, {
          status: 'downloading',
          progress,
          message
        });
      }
    );

    // Cache the result
    await rdCacheService.cacheLink({
      tmdbId,
      title,
      year,
      type,
      season,
      episode,
      streamUrl: result.download,
      fileName: result.filename
    });

    // Update: Completed
    downloadJobManager.updateJob(jobId, {
      status: 'completed',
      progress: 100,
      message: 'Download complete, starting playback...',
      streamUrl: result.download,
      fileName: result.filename
    });

    logger.info(`Download job ${jobId} completed successfully`);
  } catch (error) {
    logger.error(`Job ${jobId} failed:`, {
      message: error.message,
      code: error.code,
      stack: error.stack?.split('\n').slice(0, 3)
    });

    downloadJobManager.updateJob(jobId, {
      status: 'error',
      error: error.message,
      message: `Error: ${error.message}`
    });
  }
}

/**
 * POST /api/vod/stream-url
 * Get streaming URL for content (Zurg first, then Prowlarr->RD fallback)
 * LEGACY ENDPOINT - kept for backward compatibility
 */
router.post('/stream-url', async (req, res) => {
  try {
    const { tmdbId, title, year, type, season, episode } = req.body;
    const userId = req.user.sub;

    logger.info(`Getting stream URL for: ${title} (${year})`);

    // First check Zurg (preferred due to cache info)
    const zurgResult = await searchZurg({
      title,
      year,
      type: type === 'tv' ? 'episode' : 'movie',
      season,
      episode
    });

    if (zurgResult.match || zurgResult.fallback) {
      const file = zurgResult.match || zurgResult.fallback;
      const zurgBaseUrl = process.env.ZURG_BASE_URL || 'http://localhost:9999';
      const streamUrl = `${zurgBaseUrl}${file.filePath}`;

      logger.info(`Zurg match found, streaming from: ${streamUrl}`);

      return res.json({
        streamUrl,
        source: 'zurg',
        fileName: file.fileName
      });
    }

    // Not in Zurg - fall back to Prowlarr search + RD
    logger.info('Content not in Zurg, searching...');

    // Check if RD API key is configured
    const rdApiKey = process.env.RD_API_KEY;
    if (!rdApiKey) {
      return res.status(404).json({
        error: 'Content not found',
        message: 'No sources available for this content.'
      });
    }

    // Search Prowlarr for content
    const { searchContent } = require('../services/prowlarr-service');

    const searchResult = await searchContent({
      title,
      year,
      type,
      season,
      episode
    });

    if (!searchResult || !searchResult.magnetUrl) {
      return res.status(404).json({
        error: 'Content not found',
        message: 'No sources available for this content. Try again later.'
      });
    }

    // Add to Real-Debrid and get stream URL
    logger.info('Preparing stream...');

    let streamResult;
    try {
      streamResult = await completeDownloadFlow(
        rdApiKey,
        searchResult.magnetUrl,
        season,
        episode
      );
    } catch (rdError) {
      logger.error('RD flow error:', {
        message: rdError.message,
        code: rdError.code,
        name: rdError.name,
        stack: rdError.stack?.split('\n').slice(0, 3)
      });

      // Provide specific error messages based on error code/message
      if (rdError.code === 'FILE_NOT_FOUND') {
        return res.status(404).json({
          error: 'Content not found',
          message: season && episode
            ? `Episode S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')} not found in torrent`
            : 'No compatible video file found in the torrent'
        });
      }

      if (rdError.message?.includes('timed out')) {
        return res.status(504).json({
          error: 'Download timeout',
          message: 'Real-Debrid is taking too long to process. Please try again in a few minutes.'
        });
      }

      if (rdError.message?.includes('dead') || rdError.message?.includes('virus') || rdError.message?.includes('error')) {
        return res.status(400).json({
          error: 'Invalid torrent',
          message: 'The torrent is no longer available or contains errors. Please try searching again.'
        });
      }

      // For other RD errors, log and return generic message
      return res.status(500).json({
        error: 'Stream preparation failed',
        message: rdError.message || 'Unable to prepare stream. Please try again.'
      });
    }

    if (!streamResult || !streamResult.streamUrl) {
      return res.status(500).json({
        error: 'Stream unavailable',
        message: 'Unable to prepare stream. Please try again.'
      });
    }

    logger.info(`Stream ready: ${streamResult.streamUrl}`);

    return res.json({
      streamUrl: streamResult.streamUrl,
      source: 'rd',
      fileName: streamResult.fileName || searchResult.title
    });
  } catch (error) {
    logger.error('Stream URL error:', {
      message: error.message,
      code: error.code,
      stack: error.stack?.split('\n').slice(0, 5)
    });

    // User-friendly error messages
    if (error.message && error.message.includes('Prowlarr')) {
      return res.status(500).json({
        error: 'Search failed',
        message: 'Unable to search for content. Please try again later.'
      });
    }

    res.status(500).json({
      error: 'Stream failed',
      message: error.message || 'Unable to start playback. Please try again.'
    });
  }
});

/**
 * POST /api/vod/session/check
 * Check if user can start VOD playback
 */
router.post('/session/check', (req, res) => {
  try {
    const userId = req.user.sub;
    const clientIp = req.ip;

    logger.info(`VOD session check for user ${req.user.username} from ${clientIp}`);

    // Check if user has an active VOD session on a different IP
    const timeoutMs = parseInt(process.env.VOD_SESSION_TIMEOUT_MS) || 120000;
    const timeoutThreshold = new Date(Date.now() - timeoutMs).toISOString();

    const activeSessions = db.prepare(`
      SELECT ip_address, last_vod_playback_at, last_heartbeat_at
      FROM user_sessions
      WHERE user_id = ?
        AND ip_address != ?
        AND (
          last_vod_playback_at > ? OR
          last_heartbeat_at > ?
        )
    `).all(userId, clientIp, timeoutThreshold, timeoutThreshold);

    if (activeSessions.length > 0) {
      const activeSession = activeSessions[0];
      logger.warn(`User ${req.user.username} has active VOD session on ${activeSession.ip_address}`);
      return res.status(409).json({
        error: 'Concurrent stream detected',
        message: 'You already have an active stream on another device',
        activeIp: activeSession.ip_address.split('.').slice(0, 2).join('.') + '.*.*' // Partial IP for privacy
      });
    }

    // Update or create session for this IP
    db.prepare(`
      INSERT INTO user_sessions (user_id, ip_address, last_vod_playback_at)
      VALUES (?, ?, datetime('now'))
      ON CONFLICT(user_id, ip_address)
      DO UPDATE SET last_vod_playback_at = datetime('now')
    `).run(userId, clientIp);

    logger.info(`VOD session approved for user ${req.user.username} on ${clientIp}`);

    res.json({
      success: true,
      message: 'Playback authorized'
    });
  } catch (error) {
    logger.error('VOD session check error:', error);
    res.status(500).json({ error: 'Session check failed' });
  }
});

/**
 * POST /api/vod/session/heartbeat
 * Update VOD session heartbeat
 */
router.post('/session/heartbeat', (req, res) => {
  try {
    const userId = req.user.sub;
    const clientIp = req.ip;

    db.prepare(`
      INSERT INTO user_sessions (user_id, ip_address, last_heartbeat_at)
      VALUES (?, ?, datetime('now'))
      ON CONFLICT(user_id, ip_address)
      DO UPDATE SET last_heartbeat_at = datetime('now')
    `).run(userId, clientIp);

    res.json({ success: true });
  } catch (error) {
    logger.error('VOD heartbeat error:', error);
    res.status(500).json({ error: 'Heartbeat failed' });
  }
});

/**
 * POST /api/vod/session/end
 * End VOD session for current IP
 */
router.post('/session/end', (req, res) => {
  try {
    const userId = req.user.sub;
    const clientIp = req.ip;

    db.prepare(`
      UPDATE user_sessions
      SET last_vod_playback_at = NULL, last_heartbeat_at = NULL
      WHERE user_id = ? AND ip_address = ?
    `).run(userId, clientIp);

    logger.info(`VOD session ended for user ${req.user.username} on ${clientIp}`);

    res.json({ success: true });
  } catch (error) {
    logger.error('VOD session end error:', error);
    res.status(500).json({ error: 'Session end failed' });
  }
});

module.exports = router;
