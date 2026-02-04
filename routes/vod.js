const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');
const { searchZurg } = require('../services/zurg-search');
const { completeDownloadFlow } = require('../services/rd-service');
const rdCacheService = require('../services/rd-cache-service');
const downloadJobManager = require('../services/download-job-manager');
const { getUserRdApiKey, getEffectiveUserId } = require('../services/user-service');
const { resolveZurgToRdLink } = require('../services/zurg-to-rd-resolver');
const { logPlaybackFailure } = require('../services/failure-tracker');
const { v4: uuidv4 } = require('uuid');

const router = express.Router();

// Proxy streaming route for Zurg files (no auth required - streamId acts as security token)
router.get("/stream/:streamId", async (req, res) => {
  const { streamId } = req.params;

  // Decode the streamId (base64url encoded file path)
  let filePath;
  try {
    filePath = Buffer.from(streamId, "base64url").toString("utf-8");
  } catch (err) {
    return res.status(400).json({ error: "Invalid stream ID" });
  }

  // Security: Only allow files within Zurg mount
  const zurgMount = process.env.ZURG_MOUNT_PATH || '/mnt/zurg';
  if (!filePath.startsWith(zurgMount)) {
    logger.error(`[Stream Proxy] Unauthorized path access attempt: ${filePath}`);
    return res.status(403).json({ error: "Forbidden" });
  }

  logger.info(`[Stream Proxy] Request for: ${filePath}`);

  // Verify file exists
  if (!require("fs").existsSync(filePath)) {
    logger.error(`[Stream Proxy] File not found: ${filePath}`);
    return res.status(404).json({ error: "File not found" });
  }

  const stat = require("fs").statSync(filePath);
  const fileSize = stat.size;
  const range = req.headers.range;

  // Detect MIME type
  const path = require("path");
  const ext = path.extname(filePath).toLowerCase();
  const mimeTypes = {
    ".mp4": "video/mp4",
    ".mkv": "video/x-matroska",
    ".avi": "video/x-msvideo",
    ".mov": "video/quicktime",
    ".webm": "video/webm"
  };
  const contentType = mimeTypes[ext] || "application/octet-stream";

  if (range) {
    const parts = range.replace(/bytes=/, "").split("-");
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
    const chunksize = (end - start) + 1;

    logger.info(`[Stream Proxy] Range: ${start}-${end}/${fileSize}`);

    const file = require("fs").createReadStream(filePath, { start, end });
    const head = {
      "Content-Range": `bytes ${start}-${end}/${fileSize}`,
      "Accept-Ranges": "bytes",
      "Content-Length": chunksize,
      "Content-Type": contentType,
    };
    res.writeHead(206, head);
    file.pipe(res);
  } else {
    const head = {
      "Content-Length": fileSize,
      "Content-Type": contentType,
      "Accept-Ranges": "bytes"
    };
    res.writeHead(200, head);
    require("fs").createReadStream(filePath).pipe(res);
  }
});

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
      type, // Pass 'tv' or 'movie' directly - zurg-client expects 'tv' not 'episode'
      season,
      episode
    });

    if (zurgResult.match) { // Only use good quality, skip fallbacks
      const file = zurgResult.match;

      logger.info(`Zurg match found: ${file.filePath}`);

      // Try to resolve Zurg path to direct RD link (supports HTTP range requests)
      const rdApiKey = getUserRdApiKey(userId);
      let streamUrl = null;
      let source = 'zurg';

      if (rdApiKey) {
        const rdLink = await resolveZurgToRdLink(file.filePath, rdApiKey);
        if (rdLink) {
          streamUrl = rdLink;
          source = 'rd-via-zurg';
          logger.info(`Using RD direct link: ${streamUrl.substring(0, 60)}...`);
        } else {
          logger.warn('Failed to resolve Zurg to RD, falling back to Zurg WebDAV');
        }
      }

      // Fall back to server proxy if RD resolution failed
      // Use proxy endpoint that properly supports HTTP range requests
      if (!streamUrl) {
        // Encode file path as base64url for proxy endpoint
        const streamId = Buffer.from(file.filePath).toString('base64url');
        const serverHost = process.env.SERVER_HOST || 'lite.duckflix.tv';
        const serverProtocol = process.env.SERVER_PROTOCOL || 'https';
        streamUrl = `${serverProtocol}://${serverHost}/api/vod/stream/${streamId}`;
        source = 'zurg-proxy';
        logger.info(`Using server proxy for Zurg file (RD resolution failed)`);
      }

      logger.info(`Zurg match found, returning: ${streamUrl}`);

      // Track playback for monitoring (non-blocking)
      try {
        const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
        const rdApiKey = getUserRdApiKey(userId);
        const userInfo = {
          username: user?.username || 'unknown',
          userId,
          ip: req.ip || req.connection.remoteAddress || 'unknown',
          rdApiKey
        };
        downloadJobManager.trackPlayback({ tmdbId, title, year, type, season, episode }, userInfo, source, streamUrl, file.fileName);
      } catch (err) {
        logger.warn('Failed to track playback:', err.message);
      }

      return res.json({
        immediate: true,
        streamUrl,
        source,
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

      // Track playback for monitoring (non-blocking)
      try {
        const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
        const rdApiKey = getUserRdApiKey(userId);
        const userInfo = {
          username: user?.username || 'unknown',
          userId,
          ip: req.ip || req.connection.remoteAddress || 'unknown',
          rdApiKey
        };
        downloadJobManager.trackPlayback({ tmdbId, title, year, type, season, episode }, userInfo, 'rd-cached', cachedRd.streamUrl, cachedRd.fileName);
      } catch (err) {
        logger.warn('Failed to track playback:', err.message);
      }

      return res.json({
        immediate: true,
        streamUrl: cachedRd.streamUrl,
        source: 'rd-cached',
        fileName: cachedRd.fileName
      });
    }

    // 3. Need to download from RD - create job
    const jobId = uuidv4();

    // Get user info for monitoring
    const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
    const rdApiKey = getUserRdApiKey(userId);
    const userInfo = {
      username: user?.username || 'unknown',
      userId,
      ip: req.ip || req.connection.remoteAddress || 'unknown',
      rdApiKey
    };

    downloadJobManager.createJob(jobId, { tmdbId, title, year, type, season, episode }, userInfo);

    logger.info(`Created download job ${jobId} for ${title}`);

    // Start download in background
    processRdDownload(jobId, { tmdbId, title, year, type, season, episode, userId });

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

    // Log playback failure
    try {
      const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
      logPlaybackFailure(
        userId,
        user?.username || 'unknown',
        tmdbId,
        title,
        season,
        episode,
        error.message
      );
    } catch (logErr) {
      logger.warn('Failed to log playback failure:', logErr.message);
    }

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
 * Background RD download processor with progressive updates
 */
async function processRdDownload(jobId, contentInfo) {
  const { tmdbId, title, year, type, season, episode, userId } = contentInfo;

  try {
    const rdApiKey = getUserRdApiKey(userId);
    if (!rdApiKey) {
      throw new Error('Real-Debrid API key not configured for this user');
    }

    // Update: Checking sources (no progress % until RD download starts)
    downloadJobManager.updateJob(jobId, {
      status: 'searching',
      progress: 0,
      message: 'Checking sources...'
    });

    // Use content resolver for smart Zurg/Prowlarr selection
    const { resolveContent } = require('../services/content-resolver');

    let resolution;
    try {
      // Update: Searching sources
      downloadJobManager.updateJob(jobId, {
        status: 'searching',
        progress: 0,
        message: 'Finding sources...'
      });

      resolution = await resolveContent({
        title,
        year,
        type,
        season,
        episode,
        rdApiKey
      });

      // Update: Trying source
      const qualityLabel = resolution.quality?.resolution || resolution.quality?.title?.match(/\b(2160p|4K|1080p|720p|480p)\b/i)?.[0] || 'HD';
      downloadJobManager.updateJob(jobId, {
        status: 'searching',
        progress: 0,
        message: `Trying Source #1 ${qualityLabel}`,
        source: `${resolution.quality?.title || 'Unknown'}`
      });
    } catch (err) {
      throw new Error(`No suitable sources found: ${err.message}`);
    }

    // If Zurg selected (shouldn't happen, but handle it)
    if (resolution.source === 'zurg') {
      downloadJobManager.updateJob(jobId, {
        status: 'completed',
        progress: 100,
        message: 'Ready to play',
        streamUrl: `${process.env.ZURG_BASE_URL || 'http://localhost:9999'}${resolution.zurgPath}`,
        fileName: resolution.zurgPath.split('/').pop()
      });
      return;
    }

    const qualityLabel = resolution.quality?.title?.match(/\b(2160p|4K|1080p|720p)\b/i)?.[0] || 'HD';

    // Start RD download - progress bar shows ONLY actual RD download progress
    const { downloadFromRD } = require('@duckflix/rd-client');

    const result = await downloadFromRD(
      resolution.magnetUrl,
      rdApiKey,
      season,
      episode,
      (rdProgress, rdMessage) => {
        // rdProgress from RD client includes setup (10-20%) + download (20-90%)
        // Extract ONLY the actual download progress from RD's torrent progress
        // When rdMessage contains "Downloading: X%", that's the real RD progress
        const rdMatch = rdMessage.match(/Downloading:\s*(\d+)%/);
        if (rdMatch) {
          // Use RD's actual download progress directly
          const actualRdProgress = parseInt(rdMatch[1]);
          downloadJobManager.updateJob(jobId, {
            status: 'downloading',
            progress: actualRdProgress,
            message: `${qualityLabel}: ${actualRdProgress}%`
          });
        }
        // Ignore other phases (adding, selecting, unrestricting) - those are quick
      }
    );

    // Verify we got the link
    if (!result || !result.download) {
      throw new Error('Failed to get stream URL from Real-Debrid');
    }

    logger.info(`[RD Download] Got unrestricted link (full): ${result.download}`);

    // Cache the result for future users
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

    // FINAL: Only NOW set status to 'completed' with the verified stream URL
    // The client will ONLY start playback when it sees status=completed AND streamUrl exists
    downloadJobManager.updateJob(jobId, {
      status: 'completed',
      progress: 100,
      message: 'Ready to play!',
      streamUrl: result.download,
      fileName: result.filename,
      fileSize: result.filesize || result.bytes || null
    });

    // Also track as playback for monitoring dashboard (non-blocking)
    try {
      const job = downloadJobManager.getJob(jobId);
      if (job && job.userInfo) {
        downloadJobManager.trackPlayback(
          contentInfo,
          job.userInfo,
          'rd-download',
          result.download,
          result.filename
        );
      }
    } catch (err) {
      logger.warn('Failed to track download playback:', err.message);
    }

    logger.info(`Download job ${jobId} completed successfully, stream URL ready`);
  } catch (error) {
    logger.error(`Job ${jobId} failed:`, {
      message: error.message,
      code: error.code,
      stack: error.stack?.split('\n').slice(0, 3)
    });

    // Log playback failure
    try {
      const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
      logPlaybackFailure(
        userId,
        user?.username || 'unknown',
        tmdbId,
        title,
        season,
        episode,
        error.message
      );
    } catch (logErr) {
      logger.warn('Failed to log playback failure:', logErr.message);
    }

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
      type, // Pass 'tv' or 'movie' directly - zurg-client expects 'tv' not 'episode'
      season,
      episode
    });

    if (zurgResult.match) { // Only use good quality, skip fallbacks
      const file = zurgResult.match;

      // Try to resolve Zurg path to direct RD link first
      const rdApiKey = getUserRdApiKey(userId);
      let streamUrl = null;
      let source = 'zurg';

      if (rdApiKey) {
        const rdLink = await resolveZurgToRdLink(file.filePath, rdApiKey);
        if (rdLink) {
          streamUrl = rdLink;
          source = 'rd-via-zurg';
          logger.info(`Using RD direct link: ${streamUrl.substring(0, 60)}...`);
        }
      }

      // Fall back to server proxy if RD resolution failed
      if (!streamUrl) {
        // Use server proxy endpoint that properly supports HTTP range requests
        const streamId = Buffer.from(file.filePath).toString('base64url');
        const serverHost = process.env.SERVER_HOST || 'lite.duckflix.tv';
        const serverProtocol = process.env.SERVER_PROTOCOL || 'https';
        streamUrl = `${serverProtocol}://${serverHost}/api/vod/stream/${streamId}`;
        source = 'zurg-proxy';
        logger.info(`Using server proxy for Zurg file`);
      }

      logger.info(`Zurg match found, streaming from: ${streamUrl.substring(0, 80)}...`);

      return res.json({
        streamUrl,
        source,
        fileName: file.fileName
      });
    }

    // Not in Zurg - fall back to Prowlarr search + RD
    logger.info('Content not in Zurg, searching...');

    // Check if RD API key is configured for this user
    const rdApiKey = getUserRdApiKey(userId);
    if (!rdApiKey) {
      return res.status(404).json({
        error: 'Content not found',
        message: 'No Real-Debrid API key configured for your account.'
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
            ? `Episode S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')} not found in selected file`
            : 'No compatible video file found'
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
          error: 'Invalid source',
          message: 'The selected source is no longer available or contains errors. Please try searching again.'
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

    // Log playback failure
    try {
      const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
      logPlaybackFailure(
        userId,
        user?.username || 'unknown',
        tmdbId,
        title,
        season,
        episode,
        error.message
      );
    } catch (logErr) {
      logger.warn('Failed to log playback failure:', logErr.message);
    }

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

    // Get effective user ID (parent for sub-accounts, so they share IP sessions)
    const effectiveUserId = getEffectiveUserId(userId);

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
    `).all(effectiveUserId, clientIp, timeoutThreshold, timeoutThreshold);

    if (activeSessions.length > 0) {
      const activeSession = activeSessions[0];
      logger.warn(`User ${req.user.username} has active VOD session on ${activeSession.ip_address}`);
      return res.status(409).json({
        error: 'Concurrent stream detected',
        message: 'You already have an active stream on another device',
        activeIp: activeSession.ip_address.split('.').slice(0, 2).join('.') + '.*.*' // Partial IP for privacy
      });
    }

    // Update or create session for this IP (use effectiveUserId for sub-accounts)
    db.prepare(`
      INSERT INTO user_sessions (user_id, ip_address, last_vod_playback_at)
      VALUES (?, ?, datetime('now'))
      ON CONFLICT(user_id, ip_address)
      DO UPDATE SET last_vod_playback_at = datetime('now')
    `).run(effectiveUserId, clientIp);

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

/**
 * GET /api/vod/next-episode/:tmdbId/:season/:episode
 * Get next episode info for auto-play
 */
router.get('/next-episode/:tmdbId/:season/:episode', async (req, res) => {
  try {
    const { tmdbId, season, episode } = req.params;
    const currentSeason = parseInt(season);
    const currentEpisode = parseInt(episode);

    if (!tmdbId || isNaN(currentSeason) || isNaN(currentEpisode)) {
      return res.status(400).json({ error: 'Invalid parameters' });
    }

    logger.info(`Getting next episode for TMDB ${tmdbId} S${currentSeason}E${currentEpisode}`);

    // Get next episode from TMDB
    const { getNextEpisode } = require('../services/tmdb-service');
    const nextEpisode = await getNextEpisode(parseInt(tmdbId), currentSeason, currentEpisode);

    if (!nextEpisode) {
      // Series finale - no next episode
      return res.json({
        hasNext: false,
        tmdbId: parseInt(tmdbId)
      });
    }

    // Check if next episode is in same pack as current episode (Zurg search)
    let inCurrentPack = false;
    try {
      // Search for next episode in Zurg
      const nextZurgResult = await searchZurg({
        type: 'tv',
        season: nextEpisode.season,
        episode: nextEpisode.episode
      });

      // If both current and next episode are in Zurg, check if same pack
      if (nextZurgResult.match) {
        const currentZurgResult = await searchZurg({
          type: 'tv',
          season: currentSeason,
          episode: currentEpisode
        });

        if (currentZurgResult.match) {
          // Extract pack directory (everything except filename)
          const currentPack = currentZurgResult.match.filePath.substring(0, currentZurgResult.match.filePath.lastIndexOf('/'));
          const nextPack = nextZurgResult.match.filePath.substring(0, nextZurgResult.match.filePath.lastIndexOf('/'));

          inCurrentPack = currentPack === nextPack;
          logger.info(`Pack check: ${inCurrentPack ? 'SAME' : 'DIFFERENT'} pack (current: ${currentPack}, next: ${nextPack})`);
        }
      }
    } catch (err) {
      logger.warn('Failed to check pack status:', err.message);
      // Not critical - continue without pack info
    }

    res.json({
      hasNext: true,
      nextEpisode: {
        season: nextEpisode.season,
        episode: nextEpisode.episode,
        title: nextEpisode.title,
        overview: nextEpisode.overview
      },
      inCurrentPack,
      tmdbId: parseInt(tmdbId)
    });
  } catch (error) {
    logger.error('Next episode error:', error);
    res.status(500).json({
      error: 'Failed to get next episode',
      message: error.message
    });
  }
});


module.exports = router;
