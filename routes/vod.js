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
const { checkRdSession, startRdSession, updateRdHeartbeat, endRdSession } = require('../services/rd-session-tracker');
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

    // Try all Zurg matches in quality order, attempting RD resolution on each
    if (zurgResult.matches && zurgResult.matches.length > 0) {
      const rdApiKey = getUserRdApiKey(userId);

      if (rdApiKey) {
        // Filter for QUALITY matches only (meetsQualityThreshold = true)
        const qualityMatches = zurgResult.matches.filter(m => m.meetsQualityThreshold);

        if (qualityMatches.length === 0) {
          logger.info(`Skipping ${zurgResult.matches.length} garbage Zurg matches (all < 7 MB/min), using Prowlarr`);
        } else {
          logger.info(`Trying ${qualityMatches.length} quality Zurg matches (skipping ${zurgResult.matches.length - qualityMatches.length} garbage)`);
        }

        // Loop through QUALITY Zurg matches only, trying RD resolution on each
        for (let i = 0; i < qualityMatches.length; i++) {
          const file = qualityMatches[i];
          logger.info(`Trying quality match ${i + 1}/${qualityMatches.length}: ${file.fileName} (${file.mbPerMinute} MB/min)`);

          const rdLink = await resolveZurgToRdLink(file.filePath, rdApiKey);

          if (rdLink) {
            // SUCCESS - this Zurg match resolved to RD direct link
            logger.info(`✅ RD resolution succeeded on match ${i + 1}: ${rdLink.substring(0, 60)}...`);

            // Track playback for monitoring (non-blocking)
            try {
              const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
              const userInfo = {
                username: user?.username || 'unknown',
                userId,
                ip: req.ip || req.connection.remoteAddress || 'unknown',
                rdApiKey
              };
              downloadJobManager.trackPlayback({ tmdbId, title, year, type, season, episode }, userInfo, 'rd-via-zurg', rdLink, file.fileName);
            } catch (err) {
              logger.warn('Failed to track playback:', err.message);
            }

            return res.json({
              immediate: true,
              streamUrl: rdLink,
              source: 'rd-via-zurg',
              fileName: file.fileName
            });
          } else {
            logger.warn(`❌ RD resolution failed for match ${i + 1}, trying next...`);
            // Continue to next Zurg match
          }
        }

        // ALL quality matches failed RD resolution, fall back to Prowlarr
        if (qualityMatches.length > 0) {
          logger.warn(`All ${qualityMatches.length} quality Zurg matches failed RD resolution, falling back to Prowlarr`);
        }
      } else {
        logger.warn('No RD API key available, skipping Zurg matches');
      }
    }

    // 2. Server-side link caching disabled - always do fresh search
    //    (Expired links cause bad UX, fresh search will find RD-cached torrents anyway)

    // 3. Create download job for fresh search
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

    // Start download in background (pass host for stream proxy URL generation)
    const serverHost = req.get('host');
    processRdDownload(jobId, { tmdbId, title, year, type, season, episode, userId, serverHost });

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
  const { tmdbId, title, year, type, season, episode, userId, serverHost } = contentInfo;

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

    // Get user bandwidth limit
    let maxBitrateMbps = null;
    try {
      const user = db.prepare('SELECT max_bitrate_mbps FROM users WHERE id = ?').get(userId);
      maxBitrateMbps = user?.max_bitrate_mbps || null;
    } catch (e) {
      logger.warn('Failed to get user bandwidth limit:', e.message);
    }

    // Use unified source resolver - queries Zurg + Prowlarr in parallel
    const { getAllSources } = require('../services/unified-source-resolver');

    let allSources;
    try {
      downloadJobManager.updateJob(jobId, {
        status: 'searching',
        progress: 0,
        message: 'Searching all sources...'
      });

      allSources = await getAllSources({
        title,
        year,
        type,
        season,
        episode,
        tmdbId,
        rdApiKey,
        maxBitrateMbps
      });

      if (!allSources || allSources.length === 0) {
        throw new Error('No sources found');
      }

      logger.info(`Found ${allSources.length} ranked sources (${allSources.filter(s => s.isCached).length} cached)`);
    } catch (err) {
      throw new Error(`No suitable sources found: ${err.message}`);
    }

    // Try each source in ranked order until one works
    const { downloadFromRD } = require('@duckflix/rd-client');
    const { resolveZurgToRdLink } = require('../services/zurg-to-rd-resolver');
    let result = null;
    let lastError = null;
    let successfulSource = null;

    for (let i = 0; i < allSources.length; i++) {
      const source = allSources[i];
      const attemptNum = i + 1;
      const qualityLabel = source.quality || `${source.resolution}p` || 'HD';
      const cached = source.isCached ? 'CACHED' : 'NOT CACHED';
      const sourceType = source.source === 'zurg' ? 'Zurg' : 'Prowlarr';

      logger.info(`Attempt ${attemptNum}/${allSources.length}: [${sourceType}] ${source.title.substring(0, 60)} (${cached})`);

      // Track this attempt
      downloadJobManager.addAttemptedSource(jobId, {
        source: source.source,
        title: source.title,
        hash: source.hash,
        magnet: source.magnet,
        filePath: source.filePath,
        quality: source.quality,
        resolution: source.resolution,
        isCached: source.isCached
      });

      downloadJobManager.updateJob(jobId, {
        status: 'searching',
        progress: 0,
        message: `Trying Source #${attemptNum} ${qualityLabel} (${sourceType})`,
        source: source.title.substring(0, 80)
      });

      try {
        if (source.source === 'zurg') {
          // Try to resolve Zurg file to RD direct link
          const rdLink = await resolveZurgToRdLink(source.filePath, rdApiKey);

          if (rdLink) {
            // Success - Zurg file resolved to RD link
            result = {
              download: rdLink,
              filename: source.filePath.split('/').pop()
            };
            successfulSource = source;
            logger.info(`✅ Zurg source ${attemptNum}/${allSources.length} resolved to RD link`);
            break;
          } else {
            // Zurg file couldn't be resolved to RD, try as proxy
            const streamId = Buffer.from(source.filePath, 'utf-8').toString('base64url');
            const proxyUrl = `https://${serverHost}/api/vod/stream/${streamId}`;
            result = {
              download: proxyUrl,
              filename: source.filePath.split('/').pop()
            };
            successfulSource = source;
            logger.info(`✅ Zurg source ${attemptNum}/${allSources.length} using proxy`);
            break;
          }
        } else {
          // Prowlarr source - download from RD
          result = await downloadFromRD(
            source.magnet,
            rdApiKey,
            season,
            episode,
            (rdProgress, rdMessage) => {
              const rdMatch = rdMessage.match(/Downloading:\s*(\d+)%/);
              if (rdMatch) {
                const actualRdProgress = parseInt(rdMatch[1]);
                downloadJobManager.updateJob(jobId, {
                  status: 'downloading',
                  progress: actualRdProgress,
                  message: `${qualityLabel}: ${actualRdProgress}%`
                });
              }
            }
          );

          if (result && result.download) {
            successfulSource = source;
            logger.info(`✅ Prowlarr source ${attemptNum}/${allSources.length} succeeded`);
            break;
          }
        }
      } catch (error) {
        lastError = error;
        logger.warn(`⚠️  Source ${attemptNum}/${allSources.length} failed: ${error.message} (${error.code || 'no code'})`);

        // If FILE_NOT_FOUND or source issues, try next
        if (error.code === 'FILE_NOT_FOUND' ||
            error.message?.includes('dead') ||
            error.message?.includes('virus') ||
            error.message?.includes('error')) {
          if (attemptNum < allSources.length) {
            logger.info(`   Trying next source...`);
            continue;
          } else {
            logger.error(`❌ All ${allSources.length} sources exhausted`);
          }
        } else {
          // Critical error (RD API issue, etc) - stop trying
          logger.error('Critical error, stopping attempts:', error.message);
          break;
        }
      }
    }

    // Verify we got a result
    if (!result || !result.download) {
      const errorMsg = lastError ?
        (lastError.code === 'FILE_NOT_FOUND' ?
          `Episode not found in any of ${allSources.length} sources (${allSources.filter(s => s.isCached).length} cached, ${allSources.filter(s => s.source === 'zurg').length} Zurg, ${allSources.filter(s => s.source === 'prowlarr').length} Prowlarr)` :
          lastError.message) :
        'Failed to get stream URL';
      throw new Error(errorMsg);
    }

    logger.info(`[RD Download] Got unrestricted link (full): ${result.download}`);

    // Server-side caching disabled (not needed - fresh searches find RD-cached torrents)
    // await rdCacheService.cacheLink({ ... });

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
 * Process RD download with exclusions (for re-search after bad report)
 */
async function processRdDownloadWithExclusions(jobId, contentInfo, serverHost, { excludedHashes = [], excludedFilePaths = [] }) {
  const { tmdbId, title, year, type, season, episode, userId } = contentInfo;

  try {
    const rdApiKey = getUserRdApiKey(userId);
    if (!rdApiKey) {
      throw new Error('Real-Debrid API key not configured for this user');
    }

    downloadJobManager.updateJob(jobId, {
      status: 'searching',
      progress: 0,
      message: 'Re-searching alternative sources...'
    });

    // Get user bandwidth limit
    let maxBitrateMbps = null;
    try {
      const user = db.prepare('SELECT max_bitrate_mbps FROM users WHERE id = ?').get(userId);
      maxBitrateMbps = user?.max_bitrate_mbps || null;
    } catch (e) {
      logger.warn('Failed to get user bandwidth limit:', e.message);
    }

    // Search with exclusions
    const { getAllSources } = require('../services/unified-source-resolver');

    let allSources;
    try {
      allSources = await getAllSources({
        title,
        year,
        type,
        season,
        episode,
        tmdbId,
        rdApiKey,
        maxBitrateMbps,
        excludedHashes,
        excludedFilePaths
      });

      if (!allSources || allSources.length === 0) {
        throw new Error('No alternative sources found');
      }

      logger.info(`🔄 Re-search found ${allSources.length} alternative sources (${allSources.filter(s => s.isCached).length} cached)`);
    } catch (err) {
      throw new Error(`No alternative sources found: ${err.message}`);
    }

    // Try alternative sources (same logic as processRdDownload)
    const { downloadFromRD } = require('@duckflix/rd-client');
    const { resolveZurgToRdLink } = require('../services/zurg-to-rd-resolver');
    let result = null;
    let lastError = null;

    for (let i = 0; i < allSources.length; i++) {
      const source = allSources[i];
      const attemptNum = i + 1;
      const qualityLabel = source.quality || `${source.resolution}p` || 'HD';
      const cached = source.isCached ? 'CACHED' : 'NOT CACHED';
      const sourceType = source.source === 'zurg' ? 'Zurg' : 'Prowlarr';

      logger.info(`Re-search attempt ${attemptNum}/${allSources.length}: [${sourceType}] ${source.title.substring(0, 60)} (${cached})`);

      downloadJobManager.addAttemptedSource(jobId, {
        source: source.source,
        title: source.title,
        hash: source.hash,
        magnet: source.magnet,
        filePath: source.filePath,
        quality: source.quality,
        resolution: source.resolution,
        isCached: source.isCached
      });

      downloadJobManager.updateJob(jobId, {
        status: 'searching',
        progress: 0,
        message: `Trying Alternative #${attemptNum} ${qualityLabel}`,
        source: source.title.substring(0, 80)
      });

      try {
        if (source.source === 'zurg') {
          const rdLink = await resolveZurgToRdLink(source.filePath, rdApiKey);

          if (rdLink) {
            result = {
              download: rdLink,
              filename: source.filePath.split('/').pop()
            };
            logger.info(`✅ Alternative Zurg source ${attemptNum}/${allSources.length} resolved`);
            break;
          } else {
            const streamId = Buffer.from(source.filePath, 'utf-8').toString('base64url');
            const proxyUrl = `https://${serverHost}/api/vod/stream/${streamId}`;
            result = {
              download: proxyUrl,
              filename: source.filePath.split('/').pop()
            };
            logger.info(`✅ Alternative Zurg source ${attemptNum}/${allSources.length} using proxy`);
            break;
          }
        } else {
          result = await downloadFromRD(
            source.magnet,
            rdApiKey,
            season,
            episode,
            (rdProgress, rdMessage) => {
              const rdMatch = rdMessage.match(/Downloading:\s*(\d+)%/);
              if (rdMatch) {
                const actualRdProgress = parseInt(rdMatch[1]);
                downloadJobManager.updateJob(jobId, {
                  status: 'downloading',
                  progress: actualRdProgress,
                  message: `${qualityLabel}: ${actualRdProgress}%`
                });
              }
            }
          );

          if (result && result.download) {
            logger.info(`✅ Alternative Prowlarr source ${attemptNum}/${allSources.length} succeeded`);
            break;
          }
        }
      } catch (error) {
        lastError = error;
        logger.warn(`⚠️  Alternative ${attemptNum}/${allSources.length} failed: ${error.message}`);

        if (error.code === 'FILE_NOT_FOUND' ||
            error.message?.includes('dead') ||
            error.message?.includes('virus') ||
            error.message?.includes('error')) {
          if (attemptNum < allSources.length) {
            continue;
          }
        } else {
          break;
        }
      }
    }

    if (!result || !result.download) {
      throw new Error(lastError?.message || 'No alternative sources succeeded');
    }

    // Server-side caching disabled
    // await rdCacheService.cacheLink({ ... });

    downloadJobManager.updateJob(jobId, {
      status: 'completed',
      progress: 100,
      message: 'Alternative source ready!',
      streamUrl: result.download,
      fileName: result.filename,
      fileSize: result.filesize || result.bytes || null
    });

    logger.info(`✅ Re-search job ${jobId} completed with alternative source`);
  } catch (error) {
    logger.error(`Re-search job ${jobId} failed:`, error.message);

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
    const username = req.user.username;
    const clientIp = req.ip;

    logger.info(`[RD Session] Check for user ${username} from ${clientIp}`);

    // Get user's RD API key (inherits from parent if sub-account)
    const rdApiKey = getUserRdApiKey(userId);

    if (!rdApiKey) {
      logger.error(`[RD Session] User ${username} has no RD API key`);
      return res.status(403).json({
        error: 'No RD API key configured',
        message: 'Your account requires a Real-Debrid API key. Please contact an administrator.'
      });
    }

    // Check if this RD key is being used from a different IP
    const sessionCheck = checkRdSession(rdApiKey, clientIp, userId);

    if (!sessionCheck.allowed) {
      const activeSession = sessionCheck.activeSession;
      logger.warn(`[RD Session] BLOCKED: ${username} attempted stream from ${clientIp}, but RD key in use by ${activeSession.username} on ${activeSession.ipAddress}`);

      return res.status(409).json({
        error: 'Real-Debrid key in use elsewhere',
        message: `User or Sub-User is Using This Service Elsewhere, Please Try Again Later`,
        details: {
          activeUser: activeSession.username,
          startedAt: activeSession.startedAt
        }
      });
    }

    // Start new RD session
    startRdSession(rdApiKey, clientIp, userId, username);

    logger.info(`[RD Session] APPROVED: ${username} on ${clientIp} with RD ${rdApiKey.slice(-6)}`);

    res.json({
      success: true,
      message: 'Playback authorized'
    });
  } catch (error) {
    logger.error('[RD Session] Check error:', error);
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

    // Get user's RD API key
    const rdApiKey = getUserRdApiKey(userId);

    if (!rdApiKey) {
      return res.status(403).json({ error: 'No RD API key configured' });
    }

    // Update RD session heartbeat
    updateRdHeartbeat(rdApiKey, clientIp);

    res.json({ success: true });
  } catch (error) {
    logger.error('[RD Session] Heartbeat error:', error);
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

    // Get user's RD API key
    const rdApiKey = getUserRdApiKey(userId);

    if (rdApiKey) {
      // End RD session (removed after 5s if no heartbeat)
      endRdSession(rdApiKey, clientIp);
    }

    logger.info(`[RD Session] Ended for user ${req.user.username} on ${clientIp}`);

    res.json({ success: true });
  } catch (error) {
    logger.error('[RD Session] End error:', error);
    res.status(500).json({ error: 'Session end failed' });
  }
});

/**
 * POST /api/vod/report-bad
 * Report current stream as bad and force re-search
 */
router.post('/report-bad', async (req, res) => {
  try {
    const { jobId, reason } = req.body;
    const userId = req.user.sub;

    if (!jobId) {
      return res.status(400).json({ error: 'Job ID required' });
    }

    const job = downloadJobManager.getJob(jobId);

    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    // Report all attempted sources as bad
    const { reportBadLink } = require('../services/bad-link-tracker');
    const attemptedSources = downloadJobManager.getAttemptedSources(jobId);
    const { contentInfo } = job;

    let reportedCount = 0;

    // Report the last successful source (currently playing) as bad
    if (job.streamUrl) {
      reportBadLink({
        streamUrl: job.streamUrl,
        hash: job.hash,
        source: job.source,
        reportedBy: userId,
        reason: reason || 'User reported stream as bad'
      });
      reportedCount++;
      logger.info(`🚩 User ${userId} reported bad stream for ${contentInfo.title}: ${job.streamUrl.substring(0, 60)}`);
    }

    // Get user info for re-search
    const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
    const rdApiKey = getUserRdApiKey(userId);
    const userInfo = {
      username: user?.username || 'unknown',
      userId,
      ip: req.ip || req.connection.remoteAddress || 'unknown',
      rdApiKey
    };

    // Create new job for re-search
    const newJobId = uuidv4();
    downloadJobManager.createJob(newJobId, contentInfo, userInfo);

    // Start re-search in background, excluding all previously tried sources
    const serverHost = req.get('host');
    const excludedHashes = attemptedSources
      .filter(s => s.hash)
      .map(s => s.hash.toLowerCase());
    const excludedFilePaths = attemptedSources
      .filter(s => s.filePath)
      .map(s => s.filePath);

    logger.info(`🔄 Re-searching ${contentInfo.title}, excluding ${excludedHashes.length} hashes, ${excludedFilePaths.length} file paths`);

    processRdDownloadWithExclusions(newJobId, contentInfo, serverHost, { excludedHashes, excludedFilePaths });

    res.json({
      success: true,
      newJobId,
      reportedCount,
      message: 'Bad stream reported, searching for alternative sources...',
      excludedCount: attemptedSources.length
    });
  } catch (error) {
    logger.error('Report bad error:', error);
    res.status(500).json({ error: 'Failed to report bad stream' });
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
