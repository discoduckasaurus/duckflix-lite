const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');
const { searchZurg } = require('../services/zurg-search');
const { completeDownloadFlow, cleanupStuckTorrents } = require('../services/rd-service');
const rdCacheService = require('../services/rd-cache-service');
const downloadJobManager = require('../services/download-job-manager');
const { getUserRdApiKey, getEffectiveUserId } = require('../services/user-service');
const { resolveZurgToRdLink } = require('../services/zurg-to-rd-resolver');
const { logPlaybackFailure } = require('../services/failure-tracker');
const { checkRdSession, startRdSession, updateRdHeartbeat, endRdSession } = require('../services/rd-session-tracker');
const { v4: uuidv4 } = require('uuid');
const opensubtitlesService = require('../services/opensubtitles-service');
const { detectEmbeddedSubtitles, getBestEmbeddedSubtitle } = require('../services/embedded-subtitle-service');
const { standardizeLanguage } = require('../utils/language-standardizer');
const { extractSubtitleStream, checkCodecCompatibility } = require('../services/ffprobe-service');
const path = require('path');

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

  const fs = require("fs");

  // Verify file exists and get stats - wrap in try-catch for Zurg mount errors
  let fileSize;
  try {
    if (!fs.existsSync(filePath)) {
      logger.error(`[Stream Proxy] File not found: ${filePath}`);
      return res.status(404).json({ error: "File not found" });
    }
    const stat = fs.statSync(filePath);
    fileSize = stat.size;
  } catch (err) {
    logger.error(`[Stream Proxy] Filesystem error: ${err.code || err.message}`);
    return res.status(503).json({ error: "Filesystem temporarily unavailable" });
  }

  const range = req.headers.range;

  // Detect MIME type
  const pathModule = require("path");
  const ext = pathModule.extname(filePath).toLowerCase();
  const mimeTypes = {
    ".mp4": "video/mp4",
    ".mkv": "video/x-matroska",
    ".avi": "video/x-msvideo",
    ".mov": "video/quicktime",
    ".webm": "video/webm"
  };
  const contentType = mimeTypes[ext] || "application/octet-stream";

  // Helper to handle stream errors gracefully (prevents process crash on Zurg mount EIO)
  const handleStreamError = (stream, streamType) => {
    stream.on('error', (err) => {
      logger.error(`[Stream Proxy] ${streamType} stream error: ${err.code || err.message}`);
      if (!res.headersSent) {
        res.status(503).json({ error: "Stream read error" });
      } else {
        res.destroy();
      }
    });
    return stream;
  };

  if (range) {
    const parts = range.replace(/bytes=/, "").split("-");
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
    const chunksize = (end - start) + 1;

    logger.info(`[Stream Proxy] Range: ${start}-${end}/${fileSize}`);

    const file = handleStreamError(fs.createReadStream(filePath, { start, end }), 'Range');
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
    handleStreamError(fs.createReadStream(filePath), 'Full').pipe(res);
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

    // Skip blocking Zurg search here - processRdDownload handles Zurg + Prowlarr in parallel
    // This returns the jobId immediately so client can start showing progress

    // Create download job immediately
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

    // Ensure message is always present (fallback if missing)
    let message = job.message;
    if (!message) {
      if (job.status === 'completed') message = 'Ready!';
      else if (job.status === 'downloading') message = `Downloading: ${job.progress}%`;
      else if (job.status === 'searching') message = 'Finding sources...';
      else if (job.status === 'error') message = job.error || 'Error occurred';
      else message = 'Processing...';
    }

    res.json({
      status: job.status,
      progress: job.progress,
      message: message,
      streamUrl: job.streamUrl,
      fileName: job.fileName,
      quality: job.quality,
      source: job.streamUrl ? 'rd' : null,
      subtitles: job.subtitles || [],
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
 * Background subtitle fetcher (shared by cached and fresh downloads)
 * Priority: 1) Subtitle cache 2) Embedded 3) OpenSubtitles API
 */
function fetchSubtitlesBackground(jobId, tmdbId, title, year, type, season, episode, streamUrl, serverHost) {
  (async () => {
    try {
      const preferredLang = 'en';

      // Step 1: Check cache first (OpenSubtitles downloads are cached indefinitely)
      const cached = opensubtitlesService.getCachedSubtitle(tmdbId, type, season, episode, preferredLang);
      if (cached && require('fs').existsSync(cached.file_path)) {
        const langResult = standardizeLanguage(cached.language);
        downloadJobManager.updateJob(jobId, {
          subtitles: [{
            id: cached.id,
            language: langResult.standardized || cached.language,
            languageCode: cached.language_code,
            format: cached.format,
            url: `https://${serverHost}/api/vod/subtitles/file/${cached.id}`,
            source: 'cache'
          }]
        });
        logger.info(`Using cached subtitle for ${title} (${langResult.standardized || cached.language})`);
        return;
      }

      // Step 2: Check embedded subtitles
      const embeddedResult = await detectEmbeddedSubtitles(streamUrl, preferredLang);

      if (embeddedResult.hasEmbedded && !embeddedResult.shouldFallbackToApi) {
        const best = getBestEmbeddedSubtitle(embeddedResult.subtitles, preferredLang);
        if (best) {
          downloadJobManager.updateJob(jobId, {
            subtitles: [{
              language: best.language,
              languageCode: best.languageCode,
              streamIndex: best.index,
              source: 'embedded',
              format: best.codec
            }]
          });
          logger.info(`Using embedded subtitle: ${best.language} (stream ${best.index})`);

          // Extract and cache embedded subtitle in background
          (async () => {
            try {
              const tempPath = path.join(opensubtitlesService.SUBTITLES_DIR, `temp_${tmdbId}_${Date.now()}.srt`);
              const extracted = await extractSubtitleStream(streamUrl, best.index, tempPath);

              if (extracted) {
                const cachedSub = opensubtitlesService.cacheExtractedSubtitle({
                  tmdbId,
                  title,
                  year,
                  type,
                  season,
                  episode,
                  language: best.language,
                  languageCode: best.languageCode,
                  extractedFilePath: tempPath
                });

                if (cachedSub) {
                  logger.info(`Extracted and cached embedded subtitle for future use: ${cachedSub.fileName}`);
                }
              }
            } catch (extractErr) {
              logger.debug(`Failed to extract embedded subtitle (non-critical): ${extractErr.message}`);
            }
          })();

          return;
        }
      }

      // Step 3: Fall back to OpenSubtitles API
      logger.info(`Fetching from OpenSubtitles: ${embeddedResult.fallbackReason || 'no cache or embedded match'}`);
      const subtitle = await opensubtitlesService.getSubtitle({
        tmdbId,
        title,
        year,
        type,
        season,
        episode,
        languageCode: preferredLang
      });

      if (subtitle) {
        const langResult = standardizeLanguage(subtitle.language);
        downloadJobManager.updateJob(jobId, {
          subtitles: [{
            id: subtitle.id,
            language: langResult.standardized || subtitle.language,
            languageCode: subtitle.languageCode,
            format: subtitle.format,
            url: `https://${serverHost}/api/vod/subtitles/file/${subtitle.id}`,
            source: 'opensubtitles'
          }]
        });
        logger.info(`Fetched subtitle from OpenSubtitles for ${title} (now cached)`);
      }
    } catch (err) {
      logger.warn('Failed to auto-fetch subtitles (background):', err.message);
    }
  })();
}

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
      message: 'Checking cache...'
    });

    // Get user bandwidth limit
    let maxBitrateMbps = null;
    try {
      const user = db.prepare('SELECT max_bitrate_mbps FROM users WHERE id = ?').get(userId);
      maxBitrateMbps = user?.max_bitrate_mbps || null;
    } catch (e) {
      logger.warn('Failed to get user bandwidth limit:', e.message);
    }

    // Step 1: Check RD link cache first (24h TTL with live verification)
    try {
      const cached = await rdCacheService.getCachedLink({
        tmdbId,
        type,
        season,
        episode,
        maxBitrateMbps
      });

      if (cached) {
        logger.info(`[RD Cache] Found cached link for ${title}, verifying...`);
        downloadJobManager.updateJob(jobId, {
          status: 'searching',
          progress: 0,
          message: 'Verifying cached link...'
        });

        const isValid = await rdCacheService.verifyLink(cached.streamUrl);

        if (isValid) {
          // Validate codec compatibility before using cached link
          downloadJobManager.updateJob(jobId, {
            status: 'searching',
            message: 'Validating codec...'
          });

          const codecCheck = await checkCodecCompatibility(cached.streamUrl);

          if (!codecCheck.compatible) {
            logger.warn({
              msg: 'Codec rejected (cached link)',
              rejected: true,
              source: 'rd-cache',
              codec: codecCheck.codec,
              reason: codecCheck.reason,
              probeTimeMs: codecCheck.probeTimeMs,
              timedOut: codecCheck.timedOut
            });
            // Fall through to fresh search
          } else {
            // Compatible or timed out (assume compatible)
            if (codecCheck.timedOut) {
              logger.warn({
                msg: 'Codec probe timeout on cached link (assuming compatible)',
                source: 'rd-cache',
                probeTimeMs: codecCheck.probeTimeMs,
                timedOut: true
              });
            } else {
              logger.info({
                msg: 'Codec validated (cached link)',
                source: 'rd-cache',
                codec: codecCheck.codec,
                probeTimeMs: codecCheck.probeTimeMs
              });
            }

            // SUCCESS - use cached link directly
            downloadJobManager.updateJob(jobId, {
              status: 'completed',
              progress: 100,
              message: 'Ready to play! (cached)',
              streamUrl: cached.streamUrl,
              fileName: cached.fileName,
              quality: cached.resolution ? `${cached.resolution}p` : null,
              subtitles: [] // Will be populated in background
            });

            // Fetch subtitles in background (same as normal flow)
            fetchSubtitlesBackground(jobId, tmdbId, title, year, type, season, episode, cached.streamUrl, serverHost);

            // Track playback
            try {
              const job = downloadJobManager.getJob(jobId);
              if (job && job.userInfo) {
                downloadJobManager.trackPlayback(
                  contentInfo,
                  job.userInfo,
                  'rd-cached',
                  cached.streamUrl,
                  cached.fileName
                );
              }
            } catch (err) {
              logger.warn('Failed to track cached playback:', err.message);
            }

            logger.info(`Download job ${jobId} completed from cache`);
            return; // Exit early - no need to search
          }
        } else {
          // Cached link is dead - invalidate and continue to fresh search
          logger.warn(`[RD Cache] ❌ Cached link dead, invalidating and searching fresh`);
          // Note: We don't have cache ID here, but it will expire naturally
        }
      }
    } catch (cacheErr) {
      logger.warn(`[RD Cache] Cache check failed: ${cacheErr.message}`);
      // Continue to normal source selection
    }

    // Step 2: Use unified source resolver with streaming - start trying as sources arrive
    downloadJobManager.updateJob(jobId, {
      status: 'searching',
      progress: 0,
      message: 'Searching sources...'
    });

    const { getAllSources } = require('../services/unified-source-resolver');
    const { downloadFromRD } = require('@duckflix/rd-client');
    const { resolveZurgToRdLink } = require('../services/zurg-to-rd-resolver');

    // Source queue and state for streaming
    let sourceQueue = [];
    let triedHashes = new Set();
    let searchComplete = false;
    let result = null;
    let lastError = null;
    let successfulSource = null;
    let attemptNum = 0;

    // Promise that resolves when we have sources to try
    let sourcesAvailableResolve = null;
    let sourcesAvailablePromise = new Promise(resolve => { sourcesAvailableResolve = resolve; });

    // Callback for streaming sources
    const onSourcesReady = (sources, isComplete) => {
      // Add new sources to queue (filter already tried)
      for (const source of sources) {
        const key = source.hash?.toLowerCase() || source.filePath || source.title;
        if (!triedHashes.has(key)) {
          sourceQueue.push(source);
        }
      }

      // Re-sort queue by score (higher first)
      sourceQueue.sort((a, b) => (b.score || 0) - (a.score || 0));

      if (isComplete) {
        searchComplete = true;
        logger.info(`🔍 Search complete: ${sourceQueue.length} sources in queue`);
      }

      // Signal that sources are available
      if (sourcesAvailableResolve) {
        sourcesAvailableResolve();
        sourcesAvailableResolve = null;
      }
    };

    // Start search with streaming callback (non-blocking)
    const searchPromise = getAllSources({
      title,
      year,
      type,
      season,
      episode,
      tmdbId,
      rdApiKey,
      maxBitrateMbps
    }, onSourcesReady).catch(err => {
      logger.warn('Source search error:', err.message);
      searchComplete = true;
      if (sourcesAvailableResolve) sourcesAvailableResolve();
    });

    // Wait for first batch of sources (or search completion)
    await Promise.race([
      sourcesAvailablePromise,
      new Promise(resolve => setTimeout(resolve, 10000)) // Max 10s wait for first sources
    ]);

    if (sourceQueue.length === 0 && !searchComplete) {
      // No sources yet — recreate the promise so we wake up the instant sources arrive via callback
      // Do NOT include searchPromise here: getAllSources may return before Prowlarr callbacks fire
      sourcesAvailablePromise = new Promise(resolve => { sourcesAvailableResolve = resolve; });
      await Promise.race([
        sourcesAvailablePromise,
        new Promise(resolve => setTimeout(resolve, 20000)) // 20s for slow Prowlarr (total max: 30s)
      ]);
    }

    if (sourceQueue.length === 0) {
      throw new Error('No sources found');
    }

    logger.info(`⚡ Starting with ${sourceQueue.length} sources (search ${searchComplete ? 'complete' : 'ongoing'})`);

    // Try sources from queue until one works
    while (sourceQueue.length > 0 || !searchComplete) {
      // Wait for sources if queue is empty but search is ongoing
      if (sourceQueue.length === 0 && !searchComplete) {
        sourcesAvailablePromise = new Promise(resolve => { sourcesAvailableResolve = resolve; });
        await Promise.race([
          sourcesAvailablePromise,
          new Promise(resolve => setTimeout(resolve, 3000))
        ]);
        if (sourceQueue.length === 0) {
          if (searchComplete) break;
          continue;
        }
      }

      const source = sourceQueue.shift();
      if (!source) continue;

      // Mark as tried
      const key = source.hash?.toLowerCase() || source.filePath || source.title;
      if (triedHashes.has(key)) continue;
      triedHashes.add(key);

      attemptNum++;
      const qualityLabel = source.quality || `${source.resolution}p` || 'HD';
      const cached = source.isCached ? 'CACHED' : 'NOT CACHED';
      const sourceType = source.source === 'zurg' ? 'Zurg' : 'Prowlarr';
      const queueInfo = searchComplete ? `${attemptNum}` : `${attemptNum}+`;

      logger.info(`Attempt ${queueInfo}: [${sourceType}] ${source.title.substring(0, 60)} (${cached})`);

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
        message: `Trying ${qualityLabel} (${sourceType})`,
        source: source.title.substring(0, 80)
      });

      try {
        let candidateUrl = null;
        let candidateFilename = null;

        if (source.source === 'zurg') {
          // Try to resolve Zurg file to RD direct link
          const rdLink = await resolveZurgToRdLink(source.filePath, rdApiKey);

          if (rdLink) {
            candidateUrl = rdLink;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Zurg source #${attemptNum} resolved to RD link`);
          } else {
            // Zurg file couldn't be resolved to RD, try as proxy
            const streamId = Buffer.from(source.filePath, 'utf-8').toString('base64url');
            candidateUrl = `https://${serverHost}/api/vod/stream/${streamId}`;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Zurg source #${attemptNum} using proxy`);
          }
        } else {
          // Prowlarr source - download from RD with timeout for slow downloads
          let lastProgress = 0;
          let lastProgressTime = Date.now();
          const SLOW_DOWNLOAD_TIMEOUT = 20000; // 20 seconds at <1%

          const downloadPromise = downloadFromRD(
            source.magnet,
            rdApiKey,
            season,
            episode,
            (rdProgress, rdMessage) => {
              // Don't update if job already completed (another source succeeded)
              const currentJob = downloadJobManager.getJob(jobId);
              if (currentJob?.status === 'completed') return;

              const rdMatch = rdMessage.match(/Downloading:\s*(\d+)%/);
              if (rdMatch) {
                const actualRdProgress = parseInt(rdMatch[1]);

                // Track progress changes
                if (actualRdProgress > lastProgress) {
                  lastProgress = actualRdProgress;
                  lastProgressTime = Date.now();
                }

                downloadJobManager.updateJob(jobId, {
                  status: 'downloading',
                  progress: actualRdProgress,
                  message: `${qualityLabel}: ${actualRdProgress}%`
                });
              }
            }
          );

          // Timeout checker - abort if stuck at <1% for >60s
          const timeoutPromise = new Promise((_, reject) => {
            const checkInterval = setInterval(() => {
              const stuckTime = Date.now() - lastProgressTime;
              if (lastProgress < 1 && stuckTime > SLOW_DOWNLOAD_TIMEOUT) {
                clearInterval(checkInterval);
                reject(new Error(`TIMEOUT: Stuck at ${lastProgress}% for ${Math.round(stuckTime/1000)}s - trying next source`));
              }
            }, 5000); // Check every 5 seconds

            // Clean up interval when download completes
            downloadPromise.finally(() => clearInterval(checkInterval));
          });

          try {
            const rdResult = await Promise.race([downloadPromise, timeoutPromise]);

            if (rdResult && rdResult.download) {
              candidateUrl = rdResult.download;
              candidateFilename = rdResult.filename;
              logger.info(`✅ Prowlarr source #${attemptNum} succeeded`);
            }
          } catch (timeoutError) {
            if (timeoutError.message.includes('TIMEOUT')) {
              logger.warn(`⏱️  ${timeoutError.message}`);
              // Clean up stuck torrents in background (don't await - just fire and forget)
              cleanupStuckTorrents(rdApiKey, 2, 5).catch(() => {});
              lastError = timeoutError;
              continue; // Try next source
            }
            throw timeoutError; // Re-throw other errors
          }
        }

        // If we got a candidate URL, validate codec compatibility before accepting
        if (candidateUrl) {
          downloadJobManager.updateJob(jobId, {
            status: 'searching',
            message: `Validating codec...`
          });

          const codecCheck = await checkCodecCompatibility(candidateUrl);

          // Log codec validation result
          if (!codecCheck.compatible) {
            logger.warn({
              msg: 'Codec rejected',
              rejected: true,
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              codec: codecCheck.codec,
              reason: codecCheck.reason,
              probeTimeMs: codecCheck.probeTimeMs,
              timedOut: codecCheck.timedOut
            });
            lastError = new Error(`Incompatible codec: ${codecCheck.reason}`);
            continue; // Try next source
          } else if (codecCheck.timedOut) {
            logger.warn({
              msg: 'Codec probe timeout (assuming compatible)',
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              probeTimeMs: codecCheck.probeTimeMs,
              timedOut: true
            });
          } else {
            logger.info({
              msg: 'Codec validated',
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              codec: codecCheck.codec,
              probeTimeMs: codecCheck.probeTimeMs
            });
          }

          // Codec is compatible - accept this source
          result = {
            download: candidateUrl,
            filename: candidateFilename
          };
          successfulSource = source;
          break;
        }
      } catch (error) {
        lastError = error;
        logger.warn(`⚠️  Source #${attemptNum} failed: ${error.message} (${error.code || 'no code'})`);

        // If FILE_NOT_FOUND or source issues, try next
        if (error.code === 'FILE_NOT_FOUND' ||
            error.message?.includes('dead') ||
            error.message?.includes('virus') ||
            error.message?.includes('error') ||
            error.message?.includes('Unsupported protocol') ||
            error.message?.includes('magnet:') ||
            error.message?.includes('Redirected request failed')) {
          // Continue to next source (queue handled by while loop)
          if (sourceQueue.length > 0 || !searchComplete) {
            logger.info(`   Trying next source...`);
            continue;
          } else {
            logger.error(`❌ All ${attemptNum} sources exhausted`);
          }
        } else {
          // Critical error (RD API issue, etc) - stop trying
          logger.error('Critical error, stopping attempts:', error.message);
          break;
        }
      }

      // Break out if we got a result
      if (result && result.download) {
        break;
      }
    }

    // Verify we got a result
    if (!result || !result.download) {
      const errorMsg = lastError ?
        (lastError.code === 'FILE_NOT_FOUND' ?
          `Episode not found in ${attemptNum} sources tried` :
          lastError.message) :
        'Failed to get stream URL';
      throw new Error(errorMsg);
    }

    logger.info(`[RD Download] Got unrestricted link (full): ${result.download}`);

    // Cache the successful RD link (24h TTL with live verification on retrieval)
    // Only cache actual RD direct links, not proxy URLs (which are local Zurg proxies)
    const isRdDirectLink = result.download &&
      !result.download.includes('/api/vod/stream/') &&
      (result.download.includes('real-debrid.com') || result.download.includes('rdb.so'));

    if (isRdDirectLink) {
      try {
        // Parse resolution from filename or source
        const resolution = successfulSource?.resolution ||
          (result.filename?.match(/\b(2160|1080|720|480)p?\b/i)?.[1] ?
            parseInt(result.filename.match(/\b(2160|1080|720|480)p?\b/i)[1]) : null);

        await rdCacheService.cacheLink({
          tmdbId,
          title,
          year,
          type,
          season,
          episode,
          streamUrl: result.download,
          fileName: result.filename,
          resolution,
          fileSizeBytes: result.filesize || result.bytes || null
        });
      } catch (cacheErr) {
        logger.warn(`Failed to cache RD link: ${cacheErr.message}`);
      }
    } else {
      logger.debug(`Skipping cache for non-RD link: ${result.download?.substring(0, 50)}...`);
    }

    // FINAL: Set status to 'completed' IMMEDIATELY
    // The client will ONLY start playback when it sees status=completed AND streamUrl exists
    downloadJobManager.updateJob(jobId, {
      status: 'completed',
      progress: 100,
      message: 'Ready to play!',
      streamUrl: result.download,
      fileName: result.filename,
      fileSize: result.filesize || result.bytes || null,
      quality: successfulSource?.quality || null,
      subtitles: [] // Will be populated in background
    });

    // Auto-fetch subtitles in background (truly non-blocking)
    fetchSubtitlesBackground(jobId, tmdbId, title, year, type, season, episode, result.download, serverHost);

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
    let successfulSource = null;

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
        let candidateUrl = null;
        let candidateFilename = null;

        if (source.source === 'zurg') {
          const rdLink = await resolveZurgToRdLink(source.filePath, rdApiKey);

          if (rdLink) {
            candidateUrl = rdLink;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Alternative Zurg source ${attemptNum}/${allSources.length} resolved`);
          } else {
            const streamId = Buffer.from(source.filePath, 'utf-8').toString('base64url');
            candidateUrl = `https://${serverHost}/api/vod/stream/${streamId}`;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Alternative Zurg source ${attemptNum}/${allSources.length} using proxy`);
          }
        } else {
          // Prowlarr source with timeout for slow downloads
          let lastProgress = 0;
          let lastProgressTime = Date.now();
          const SLOW_DOWNLOAD_TIMEOUT = 60000; // 60 seconds at <1%

          const downloadPromise = downloadFromRD(
            source.magnet,
            rdApiKey,
            season,
            episode,
            (rdProgress, rdMessage) => {
              // Don't update if job already completed (another source succeeded)
              const currentJob = downloadJobManager.getJob(jobId);
              if (currentJob?.status === 'completed') return;

              const rdMatch = rdMessage.match(/Downloading:\s*(\d+)%/);
              if (rdMatch) {
                const actualRdProgress = parseInt(rdMatch[1]);

                if (actualRdProgress > lastProgress) {
                  lastProgress = actualRdProgress;
                  lastProgressTime = Date.now();
                }

                downloadJobManager.updateJob(jobId, {
                  status: 'downloading',
                  progress: actualRdProgress,
                  message: `${qualityLabel}: ${actualRdProgress}%`
                });
              }
            }
          );

          const timeoutPromise = new Promise((_, reject) => {
            const checkInterval = setInterval(() => {
              const stuckTime = Date.now() - lastProgressTime;
              if (lastProgress < 1 && stuckTime > SLOW_DOWNLOAD_TIMEOUT) {
                clearInterval(checkInterval);
                reject(new Error(`TIMEOUT: Stuck at ${lastProgress}% for ${Math.round(stuckTime/1000)}s - trying next source`));
              }
            }, 5000);

            downloadPromise.finally(() => clearInterval(checkInterval));
          });

          try {
            const rdResult = await Promise.race([downloadPromise, timeoutPromise]);

            if (rdResult && rdResult.download) {
              candidateUrl = rdResult.download;
              candidateFilename = rdResult.filename;
              logger.info(`✅ Alternative Prowlarr source ${attemptNum}/${allSources.length} succeeded`);
            }
          } catch (timeoutError) {
            if (timeoutError.message.includes('TIMEOUT')) {
              logger.warn(`⏱️  ${timeoutError.message}`);
              // Clean up stuck torrents in background
              cleanupStuckTorrents(rdApiKey, 2, 5).catch(() => {});
              lastError = timeoutError;
              continue;
            }
            throw timeoutError;
          }
        }

        // Validate codec compatibility before accepting
        if (candidateUrl) {
          downloadJobManager.updateJob(jobId, {
            status: 'searching',
            message: `Validating codec...`
          });

          const codecCheck = await checkCodecCompatibility(candidateUrl);

          // Log codec validation result
          if (!codecCheck.compatible) {
            logger.warn({
              msg: 'Codec rejected (re-search)',
              rejected: true,
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              codec: codecCheck.codec,
              reason: codecCheck.reason,
              probeTimeMs: codecCheck.probeTimeMs,
              timedOut: codecCheck.timedOut
            });
            lastError = new Error(`Incompatible codec: ${codecCheck.reason}`);
            continue; // Try next source
          } else if (codecCheck.timedOut) {
            logger.warn({
              msg: 'Codec probe timeout (assuming compatible)',
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              probeTimeMs: codecCheck.probeTimeMs,
              timedOut: true
            });
          } else {
            logger.info({
              msg: 'Codec validated (re-search)',
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              codec: codecCheck.codec,
              probeTimeMs: codecCheck.probeTimeMs
            });
          }

          result = {
            download: candidateUrl,
            filename: candidateFilename
          };
          successfulSource = source;
          break;
        }
      } catch (error) {
        lastError = error;
        logger.warn(`⚠️  Alternative ${attemptNum}/${allSources.length} failed: ${error.message}`);

        if (error.code === 'FILE_NOT_FOUND' ||
            error.message?.includes('dead') ||
            error.message?.includes('virus') ||
            error.message?.includes('error') ||
            error.message?.includes('Unsupported protocol') ||
            error.message?.includes('magnet:') ||
            error.message?.includes('Redirected request failed')) {
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

    // Cache the successful RD link (24h TTL with live verification on retrieval)
    // Only cache actual RD direct links, not proxy URLs
    const isRdDirectLink = result.download &&
      !result.download.includes('/api/vod/stream/') &&
      (result.download.includes('real-debrid.com') || result.download.includes('rdb.so'));

    if (isRdDirectLink) {
      try {
        const resolution = successfulSource?.resolution ||
          (result.filename?.match(/\b(2160|1080|720|480)p?\b/i)?.[1] ?
            parseInt(result.filename.match(/\b(2160|1080|720|480)p?\b/i)[1]) : null);

        await rdCacheService.cacheLink({
          tmdbId,
          title,
          year,
          type,
          season,
          episode,
          streamUrl: result.download,
          fileName: result.filename,
          resolution,
          fileSizeBytes: result.filesize || result.bytes || null
        });
      } catch (cacheErr) {
        logger.warn(`Failed to cache RD link: ${cacheErr.message}`);
      }
    }

    downloadJobManager.updateJob(jobId, {
      status: 'completed',
      progress: 100,
      message: 'Alternative source ready!',
      streamUrl: result.download,
      fileName: result.filename,
      fileSize: result.filesize || result.bytes || null,
      quality: successfulSource?.quality || null
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

    // Check active jobs first, then completed job history (jobs get cleaned up after 5 min)
    let job = downloadJobManager.getJob(jobId);
    if (!job) {
      job = downloadJobManager.getCompletedJobs().find(j => j.jobId === jobId);
    }

    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    // Report all attempted sources as bad
    const { reportBadLink } = require('../services/bad-link-tracker');
    const attemptedSources = job.attemptedSources || [];
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

/**
 * GET /api/vod/subtitles/search
 * Search for subtitles (checks cache first, downloads if needed)
 */
router.get('/subtitles/search', async (req, res) => {
  try {
    const { tmdbId, title, year, type, season, episode, languageCode } = req.query;

    if (!tmdbId || !type) {
      return res.status(400).json({ error: 'tmdbId and type are required' });
    }

    logger.info(`Subtitle search: ${title} (${tmdbId}) ${type} S${season}E${episode} (${languageCode || 'en'})`);

    const subtitle = await opensubtitlesService.getSubtitle({
      tmdbId: parseInt(tmdbId),
      title: title || 'Unknown',
      year,
      type,
      season: season ? parseInt(season) : null,
      episode: episode ? parseInt(episode) : null,
      languageCode: languageCode || 'en'
    });

    res.json({
      success: true,
      subtitle: {
        id: subtitle.id,
        language: subtitle.language,
        languageCode: subtitle.languageCode,
        format: subtitle.format,
        url: `${req.protocol}://${req.get('host')}/api/vod/subtitles/file/${subtitle.id}`,
        cached: subtitle.cached,
        fileSize: subtitle.fileSize
      }
    });
  } catch (error) {
    logger.error('Subtitle search error:', error);
    res.status(500).json({
      error: 'Failed to get subtitles',
      message: error.message
    });
  }
});

/**
 * POST /api/vod/subtitles/search
 * Search for subtitles (same as GET but accepts body params for APK compatibility)
 */
router.post('/subtitles/search', async (req, res) => {
  try {
    const { tmdbId, title, year, type, season, episode, languageCode } = req.body;

    if (!tmdbId || !type) {
      return res.status(400).json({ error: 'tmdbId and type are required' });
    }

    logger.info(`Subtitle search: ${title} (${tmdbId}) ${type} S${season}E${episode} (${languageCode || 'en'})`);

    const subtitle = await opensubtitlesService.getSubtitle({
      tmdbId: parseInt(tmdbId),
      title: title || 'Unknown',
      year,
      type,
      season: season ? parseInt(season) : null,
      episode: episode ? parseInt(episode) : null,
      languageCode: languageCode || 'en'
    });

    res.json({
      success: true,
      subtitle: {
        id: subtitle.id,
        language: subtitle.language,
        languageCode: subtitle.languageCode,
        format: subtitle.format,
        url: `${req.protocol}://${req.get('host')}/api/vod/subtitles/file/${subtitle.id}`,
        cached: subtitle.cached,
        fileSize: subtitle.fileSize
      }
    });
  } catch (error) {
    logger.error('Subtitle search error:', error);
    res.status(500).json({
      error: 'Failed to get subtitles',
      message: error.message
    });
  }
});

/**
 * GET /api/vod/subtitles/file/:id
 * Serve subtitle file by ID
 */
router.get('/subtitles/file/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const filePath = opensubtitlesService.getSubtitleFilePath(parseInt(id));

    if (!filePath || !require('fs').existsSync(filePath)) {
      return res.status(404).json({ error: 'Subtitle file not found' });
    }

    logger.info(`Serving subtitle file: ${filePath}`);

    // Set appropriate headers for SRT file
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.setHeader('Content-Disposition', `inline; filename="${require('path').basename(filePath)}"`);

    // Stream the file
    require('fs').createReadStream(filePath).pipe(res);
  } catch (error) {
    logger.error('Subtitle file serve error:', error);
    res.status(500).json({
      error: 'Failed to serve subtitle file',
      message: error.message
    });
  }
});

/**
 * GET /api/vod/subtitles/stats
 * Get subtitle storage statistics (admin only)
 */
router.get('/subtitles/stats', async (req, res) => {
  try {
    if (!req.user.isAdmin) {
      return res.status(403).json({ error: 'Admin access required' });
    }

    const stats = opensubtitlesService.getStorageStats();
    const quota = opensubtitlesService.checkDailyQuota();

    res.json({
      storage: stats,
      quota: {
        used: quota.count,
        limit: quota.limit,
        remaining: quota.remaining,
        exceeded: quota.exceeded
      }
    });
  } catch (error) {
    logger.error('Subtitle stats error:', error);
    res.status(500).json({
      error: 'Failed to get subtitle stats',
      message: error.message
    });
  }
});



/**
 * POST /api/vod/fallback
 * Request a lower quality stream when playback is stuttering
 * Used by the adaptive bitrate system to get a fallback stream
 */
router.post('/fallback', authenticateToken, async (req, res) => {
  try {
    const { tmdbId, type, year, season, episode, duration, currentBitrate } = req.body;
    const userId = req.user.sub;

    logger.info('[Fallback] Request for lower quality stream', {
      tmdbId,
      type,
      year,
      currentBitrate,
      userId
    });

    // For now, return a 501 Not Implemented
    // This endpoint would need integration with the content resolver
    // to find an alternative lower-quality source
    res.status(501).json({
      error: 'Fallback not implemented',
      message: 'Lower quality fallback sources are not currently available. Please try again later.'
    });
  } catch (error) {
    logger.error('Fallback error:', error);
    res.status(500).json({
      error: 'Fallback failed',
      message: error.message || 'Unable to get fallback stream'
    });
  }
});

module.exports = router;
