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
const { extractSubtitleStream, analyzeStreamCompatibility } = require('../services/ffprobe-service');
const { remuxWithCompatibleAudio, transcodeAudioToEac3 } = require('../services/audio-processor');
const { syncSubtitle } = require('../services/subtitle-sync');
const path = require('path');

const TRANSCODED_DIR = path.join(__dirname, '..', 'transcoded');
const FUSE_TIMEOUT_MS = 10000;

function withFuseTimeout(promise, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`FUSE timeout: ${label} after ${FUSE_TIMEOUT_MS}ms`)), FUSE_TIMEOUT_MS);
    })
  ]).finally(() => clearTimeout(timer));
}

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

  // Verify file exists and get stats - timeout-protected to prevent FUSE hangs
  let fileSize;
  try {
    const stat = await withFuseTimeout(fs.promises.stat(filePath), 'stream stat');
    fileSize = stat.size;
  } catch (err) {
    if (err.code === 'ENOENT') {
      logger.error(`[Stream Proxy] File not found: ${filePath}`);
      return res.status(404).json({ error: "File not found" });
    }
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

// Serve processed (remuxed/transcoded) files — no auth, jobId acts as security token (same pattern as /stream/:streamId)
router.get("/stream-processed/:jobId", async (req, res) => {
  const { jobId } = req.params;
  const job = downloadJobManager.getJob(jobId);

  if (!job || !job.processedFilePath) {
    return res.status(404).json({ error: "Processed file not found" });
  }

  const filePath = job.processedFilePath;

  // Security: Only allow files within transcoded/ directory
  const resolvedPath = path.resolve(filePath);
  const resolvedDir = path.resolve(TRANSCODED_DIR);
  if (!resolvedPath.startsWith(resolvedDir)) {
    logger.error(`[Stream Processed] Unauthorized path access attempt: ${filePath}`);
    return res.status(403).json({ error: "Forbidden" });
  }

  const fs = require("fs");

  let fileSize;
  try {
    const stat = await fs.promises.stat(filePath);
    fileSize = stat.size;
  } catch (err) {
    if (err.code === 'ENOENT') {
      return res.status(404).json({ error: "File not found" });
    }
    logger.error(`[Stream Processed] Filesystem error: ${err.code || err.message}`);
    return res.status(503).json({ error: "Filesystem temporarily unavailable" });
  }

  const range = req.headers.range;
  const ext = path.extname(filePath).toLowerCase();
  const mimeTypes = {
    ".mp4": "video/mp4",
    ".mkv": "video/x-matroska",
    ".avi": "video/x-msvideo",
    ".mov": "video/quicktime",
    ".webm": "video/webm"
  };
  const contentType = mimeTypes[ext] || "application/octet-stream";

  const handleStreamError = (stream, streamType) => {
    stream.on('error', (err) => {
      logger.error(`[Stream Processed] ${streamType} stream error: ${err.code || err.message}`);
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
    const { tmdbId, title, year, type, season, episode, platform } = req.body;
    const userId = req.user.sub;

    logger.info(`Starting stream URL retrieval for: ${title} (${year})${platform ? ` [${platform}]` : ''}`);

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

    downloadJobManager.createJob(jobId, { tmdbId, title, year, type, season, episode, platform }, userInfo);

    logger.info(`Created download job ${jobId} for ${title}`);

    // Start download in background (pass host for stream proxy URL generation)
    const serverHost = req.get('host');
    processRdDownload(jobId, { tmdbId, title, year, type, season, episode, userId, serverHost, platform });

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

    // Check if bandwidth retest should be suggested
    let suggestBandwidthRetest = false;
    if (job.usedOverBandwidthFallback) {
      suggestBandwidthRetest = true;
    } else if (job.status === 'completed' || job.status === 'error') {
      // Also suggest on completion if measurement is stale (>1 hour)
      try {
        const userId = job.userInfo?.userId;
        if (userId) {
          const user = db.prepare('SELECT bandwidth_measured_at FROM users WHERE id = ?').get(userId);
          if (user?.bandwidth_measured_at) {
            const measuredAt = new Date(user.bandwidth_measured_at + 'Z').getTime();
            if (Date.now() - measuredAt > 60 * 60 * 1000) {
              suggestBandwidthRetest = true;
            }
          }
        }
      } catch (e) { /* ignore */ }
    }

    // Build response
    const response = {
      status: job.status,
      progress: job.progress,
      message: message,
      streamUrl: job.streamUrl,
      fileName: job.fileName,
      quality: job.quality,
      source: job.streamUrl ? 'rd' : null,
      subtitles: job.subtitles || [],
      embeddedSubtitleTracks: job.embeddedSubtitleTracks || [],
      recommendedSubtitleIndex: job.recommendedSubtitleIndex ?? null,
      skipMarkers: job.skipMarkers || null,
      error: job.error,
      suggestBandwidthRetest
    };

    // Include autoplay data for TV episodes (available once job completes)
    if (job.contentInfo?.type === 'tv') {
      if (job.nextEpisode !== undefined) {
        response.hasNextEpisode = job.nextEpisode !== null;
        response.nextEpisode = job.nextEpisode;
      }
    }

    res.json(response);
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
 * Find an existing active prefetch job for the same content+user (deduplication)
 */
function findExistingPrefetchJob(userId, tmdbId, type, season, episode) {
  const allJobs = downloadJobManager.getAllJobs();
  return allJobs.find(job =>
    job.isPrefetch &&
    job.userInfo?.userId === userId &&
    job.contentInfo?.tmdbId == tmdbId &&
    job.contentInfo?.type === type &&
    job.contentInfo?.season == season &&
    job.contentInfo?.episode == episode &&
    (job.status === 'searching' || job.status === 'downloading' || job.status === 'completed')
  ) || null;
}

/**
 * POST /api/vod/prefetch-next
 * Pre-resolve sources for the next episode/movie while user is still watching current content
 */
router.post('/prefetch-next', async (req, res) => {
  try {
    const { tmdbId, title, year, type, currentSeason, currentEpisode, mode } = req.body;
    const userId = req.user.sub;

    logger.info(`[Prefetch] Request for next after ${title} S${currentSeason}E${currentEpisode} (mode: ${mode || 'sequential'})`);

    const { getNextEpisode, getRandomEpisode, getMovieRecommendations } = require('../services/tmdb-service');

    let nextContent = null;

    if (type === 'tv') {
      if (mode === 'random') {
        const randomEp = await getRandomEpisode(parseInt(tmdbId));
        if (randomEp) {
          nextContent = {
            tmdbId: parseInt(tmdbId),
            title,
            year,
            type: 'tv',
            season: randomEp.season,
            episode: randomEp.episode,
            episodeTitle: randomEp.title
          };
        }
      } else {
        // sequential (default)
        const nextEp = await getNextEpisode(parseInt(tmdbId), parseInt(currentSeason), parseInt(currentEpisode));
        if (nextEp) {
          nextContent = {
            tmdbId: parseInt(tmdbId),
            title,
            year,
            type: 'tv',
            season: nextEp.season,
            episode: nextEp.episode,
            episodeTitle: nextEp.title
          };
        }
      }
    } else if (type === 'movie') {
      const recommendations = await getMovieRecommendations(parseInt(tmdbId));
      if (recommendations.length > 0) {
        const rec = recommendations[0];
        nextContent = {
          tmdbId: rec.tmdbId,
          title: rec.title,
          year: rec.year,
          type: 'movie',
          season: null,
          episode: null,
          episodeTitle: null
        };
      }
    }

    if (!nextContent) {
      return res.json({ hasNext: false });
    }

    // Check for existing prefetch job (dedup)
    const existing = findExistingPrefetchJob(userId, nextContent.tmdbId, nextContent.type, nextContent.season, nextContent.episode);
    if (existing) {
      logger.info(`[Prefetch] Reusing existing job ${existing.jobId} for ${nextContent.title} S${nextContent.season}E${nextContent.episode}`);
      return res.json({
        hasNext: true,
        jobId: existing.jobId,
        nextEpisode: {
          tmdbId: nextContent.tmdbId,
          title: nextContent.title,
          season: nextContent.season,
          episode: nextContent.episode,
          episodeTitle: nextContent.episodeTitle
        }
      });
    }

    // Create prefetch job
    const jobId = uuidv4();
    const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
    const rdApiKey = getUserRdApiKey(userId);
    const userInfo = {
      username: user?.username || 'unknown',
      userId,
      ip: req.ip || req.connection.remoteAddress || 'unknown',
      rdApiKey
    };

    downloadJobManager.createJob(jobId, {
      tmdbId: nextContent.tmdbId,
      title: nextContent.title,
      year: nextContent.year,
      type: nextContent.type,
      season: nextContent.season,
      episode: nextContent.episode
    }, userInfo, { isPrefetch: true });

    logger.info(`[Prefetch] Created job ${jobId} for ${nextContent.title} S${nextContent.season}E${nextContent.episode}`);

    // Start download in background
    const serverHost = req.get('host');
    processRdDownload(jobId, {
      tmdbId: nextContent.tmdbId,
      title: nextContent.title,
      year: nextContent.year,
      type: nextContent.type,
      season: nextContent.season,
      episode: nextContent.episode,
      userId,
      serverHost
    });

    res.json({
      hasNext: true,
      jobId,
      nextEpisode: {
        tmdbId: nextContent.tmdbId,
        title: nextContent.title,
        season: nextContent.season,
        episode: nextContent.episode,
        episodeTitle: nextContent.episodeTitle
      }
    });
  } catch (error) {
    logger.error('[Prefetch] Error:', error);
    res.status(500).json({ error: 'Prefetch failed', message: error.message });
  }
});

/**
 * POST /api/vod/prefetch-promote/:jobId
 * Promote a prefetch job to a real playback job (user wants to play the prefetched content)
 */
router.post('/prefetch-promote/:jobId', async (req, res) => {
  try {
    const { jobId } = req.params;
    const userId = req.user.sub;

    const job = downloadJobManager.getJob(jobId);

    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    if (job.userInfo?.userId !== userId) {
      return res.status(403).json({ error: 'Job does not belong to you' });
    }

    // Promote: remove prefetch flag so it appears in Continue Watching etc.
    job.isPrefetch = false;

    logger.info(`[Prefetch] Promoted job ${jobId} (status: ${job.status})`);

    // Look up next episode so client can continue the autoplay chain
    let nextEpisode = null;
    if (job.contentInfo?.type === 'tv') {
      try {
        const { getNextEpisode } = require('../services/tmdb-service');
        const next = await getNextEpisode(
          job.contentInfo.tmdbId,
          job.contentInfo.season,
          job.contentInfo.episode
        );
        if (next) {
          nextEpisode = {
            season: next.season,
            episode: next.episode,
            title: next.title,
            overview: next.overview
          };
        }
      } catch (err) {
        logger.warn('[Prefetch Promote] Failed to get next episode:', err.message);
      }
    }

    res.json({
      status: job.status,
      streamUrl: job.streamUrl || null,
      jobId: job.jobId,
      fileName: job.fileName || null,
      quality: job.quality || null,
      contentInfo: job.contentInfo,
      skipMarkers: job.skipMarkers || null,
      hasNext: !!nextEpisode,
      nextEpisode
    });
  } catch (error) {
    logger.error('[Prefetch Promote] Error:', error);
    res.status(500).json({ error: 'Promote failed', message: error.message });
  }
});

/**
 * Background subtitle fetcher (shared by cached and fresh downloads)
 * Priority: 1) Subtitle cache 2) Embedded 3) OpenSubtitles API
 */
function fetchSubtitlesBackground(jobId, tmdbId, title, year, type, season, episode, streamUrl, serverHost, options = {}) {
  const { hasEnglishSubtitle } = options;

  (async () => {
    try {
      const preferredLang = 'en';

      // Compute OpenSubtitles hash from the video URL (skips local/FUSE URLs, ~200ms)
      const videoHash = await opensubtitlesService.computeOpenSubtitlesHash(streamUrl);

      // Step 1: Check cache — only trust OpenSubtitles-sourced cache (embedded subs may be corrupted)
      const cached = opensubtitlesService.getCachedSubtitle(tmdbId, type, season, episode, preferredLang, videoHash);
      let cachedFileExists = false;
      if (cached) {
        try { await require('fs').promises.access(cached.file_path); cachedFileExists = true; } catch {}
      }
      if (cached && cachedFileExists && cached.opensubtitles_file_id) {
        // If cached subtitle was never synced (no video_hash), sync it now
        if (!cached.video_hash && videoHash) {
          const syncResult = await syncSubtitle(streamUrl, cached.file_path);
          if (syncResult.synced) {
            logger.info(`[SubSync] Synced cached subtitle for ${title} by ${syncResult.offsetMs > 0 ? '+' : ''}${(syncResult.offsetMs / 1000).toFixed(2)}s`);
          }
          // Stamp the hash so we don't re-sync next time
          db.prepare('UPDATE subtitles SET video_hash = ? WHERE id = ?').run(videoHash, cached.id);
        }

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
        logger.info(`Using cached OpenSubtitles subtitle for ${title} (${langResult.standardized || cached.language})`);
        return;
      }

      // Step 2: If container has clean English embedded subs, skip external fetch
      // (ExoPlayer reads them directly from the container — no SRT overlay needed)
      if (hasEnglishSubtitle) {
        logger.info(`Container has clean English subtitle track for ${title}, skipping external fetch`);
        return;
      }

      // Step 3: No English embedded subs — auto-fetch from OpenSubtitles
      logger.info(`No English subtitle in container for ${title}, fetching from OpenSubtitles`);
      try {
        const subtitle = await opensubtitlesService.getSubtitle({
          tmdbId,
          title,
          year,
          type,
          season,
          episode,
          languageCode: preferredLang,
          videoHash
        });

        if (subtitle) {
          // Auto-sync subtitle timing to this video's audio track
          if (!subtitle.cached) {
            const syncResult = await syncSubtitle(streamUrl, subtitle.filePath);
            if (syncResult.synced) {
              logger.info(`[SubSync] Subtitle for ${title} adjusted by ${syncResult.offsetMs > 0 ? '+' : ''}${(syncResult.offsetMs / 1000).toFixed(2)}s`);
            }
          }

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
          return;
        }
      } catch (osErr) {
        logger.debug(`OpenSubtitles unavailable for ${title}: ${osErr.message}`);
      }

      // Step 4: OpenSubtitles failed — fall back to embedded subtitle detection
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

          // Extract and cache embedded subtitle in background (only if no cached sub exists at all)
          if (!cached) {
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
          }

          return;
        }
      }

      // Step 5: Use embedded-cached subtitle as last resort (if it exists but wasn't from OpenSubtitles)
      if (cached && cachedFileExists) {
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
        logger.info(`Using cached embedded subtitle as fallback for ${title}`);
      }
    } catch (err) {
      logger.warn('Failed to auto-fetch subtitles (background):', err.message);
    }
  })();
}

/**
 * Background skip-marker fetcher (chapters + IntroDB).
 * Called after stream URL is resolved, results land on the job via updateJob.
 */
function fetchSkipMarkersBackground(jobId, chapters, contentInfo, imdbId, duration) {
  (async () => {
    try {
      const { getSkipMarkers } = require('../services/skip-markers-service');
      const markers = await getSkipMarkers({
        chapters,
        tmdbId: contentInfo.tmdbId,
        type: contentInfo.type,
        season: contentInfo.season,
        episode: contentInfo.episode,
        imdbId,
        duration
      });

      if (markers.intro || markers.recap || markers.credits) {
        downloadJobManager.updateJob(jobId, { skipMarkers: markers });
        logger.info(`[SkipMarkers] Job ${jobId}: intro=${markers.intro ? markers.intro.source : 'no'}, recap=${markers.recap ? markers.recap.source : 'no'}, credits=${markers.credits ? markers.credits.source : 'no'}`);
      }
    } catch (err) {
      logger.warn(`[SkipMarkers] Background fetch failed: ${err.message}`);
    }
  })();
}

/**
 * Resolve next-episode info and store on the job (non-blocking).
 * Called when a TV episode job completes so the progress response always includes autoplay data.
 */
function resolveNextEpisodeForJob(jobId, tmdbId, type, season, episode) {
  if (type !== 'tv' || !season || !episode) return;

  (async () => {
    try {
      const { getNextEpisode } = require('../services/tmdb-service');
      const next = await getNextEpisode(parseInt(tmdbId), parseInt(season), parseInt(episode));
      const job = downloadJobManager.getJob(jobId);
      if (!job) return;

      if (next) {
        downloadJobManager.updateJob(jobId, {
          nextEpisode: {
            season: next.season,
            episode: next.episode,
            title: next.title,
            overview: next.overview
          }
        });
      } else {
        downloadJobManager.updateJob(jobId, { nextEpisode: null });
      }
    } catch (err) {
      logger.warn(`[Autoplay] Failed to resolve next episode for job ${jobId}:`, err.message);
    }
  })();
}

/**
 * Validate video+audio compatibility and process audio if needed.
 * Returns { accepted: boolean, streamUrl?: string, reason?: string }
 */
async function validateAndProcessSource(candidateUrl, jobId, sourceLabel, serverHost, platform) {
  const analysis = await analyzeStreamCompatibility(candidateUrl);

  // Log full analysis
  logger.info({
    msg: 'Stream analysis',
    source: sourceLabel,
    videoCompatible: analysis.videoCompatible,
    videoReason: analysis.videoReason,
    videoCodec: analysis.videoCodec,
    audioCompatible: analysis.audioCompatible,
    audioNeedsProcessing: analysis.audioNeedsProcessing,
    defaultAudioCodec: analysis.defaultAudioCodec,
    bestCompatibleAudioIndex: analysis.bestCompatibleAudioIndex,
    audioTracks: analysis.audioStreams?.length || 0,
    subTracks: analysis.subtitleStreams?.length || 0,
    subCleanup: analysis.subtitleCleanupNeeded,
    hasEngSub: analysis.hasEnglishSubtitle,
    probeTimeMs: analysis.probeTimeMs,
    timedOut: analysis.timedOut
  });

  // Video check
  if (!analysis.videoCompatible) {
    return { accepted: false, reason: analysis.videoReason };
  }

  const needsAudioWork = !analysis.audioCompatible && !analysis.timedOut;
  const needsSubWork = analysis.subtitleCleanupNeeded;

  // Build embedded subtitle track list for the APK (client-side track selection)
  // This avoids remuxing the entire file just for subtitle metadata cleanup
  const embeddedSubtitleTracks = analysis.subtitleStreams?.length > 0
    ? analysis.subtitleStreams.map(s => ({
        index: s.index,
        language: s.standardizedLanguage || null,
        languageCode: s.languageCode || null,
        title: s.title,
        isRecognized: s.isRecognized,
        isForced: s.isForced,
        isDefault: s.isDefault,
        isSDH: s.isSDH,
        keep: s.isRecognized && !(s.isForced && !s.isDefault) // matches cleanup filter logic
      }))
    : [];

  // Pick recommended track: first English non-forced, or first kept track
  const keptTracks = embeddedSubtitleTracks.filter(t => t.keep);
  const recommendedSubIdx = keptTracks.find(t => t.languageCode === 'en' && !t.isForced)?.index
    ?? keptTracks.find(t => t.languageCode === 'en')?.index
    ?? null;

  const baseResult = {
    videoCodec: analysis.videoCodec,
    videoCodecName: analysis.videoCodecName,
    chapters: analysis.chapters,
    duration: analysis.duration,
    hasEnglishSubtitle: analysis.hasEnglishSubtitle,
    embeddedSubtitleTracks,
    recommendedSubtitleIndex: recommendedSubIdx
  };

  // Audio compatible — pass through original URL (no remux needed)
  if (!needsAudioWork) {
    return { accepted: true, streamUrl: candidateUrl, ...baseResult };
  }

  // Audio needs processing — use .mp4 for web clients (browsers can't play MKV)
  // Subtitle cleanup piggybacks on audio remux for free (file is being downloaded anyway)
  const outputExt = platform === 'web' ? 'mp4' : 'mkv';
  const outputPath = path.join(TRANSCODED_DIR, `${jobId}_${Date.now()}.${outputExt}`);
  const subtitleArgs = needsSubWork ? analysis.cleanSubtitleArgs : null;

  if (analysis.bestCompatibleAudioIndex !== null) {
    downloadJobManager.updateJob(jobId, {
      status: 'processing',
      message: needsSubWork ? 'Remuxing audio + cleaning subtitles...' : 'Remuxing compatible audio track...'
    });

    logger.info(`[AudioProcess] Remuxing audio stream ${analysis.bestCompatibleAudioIndex} for ${sourceLabel}${needsSubWork ? ' (+ sub cleanup)' : ''}`);
    const result = await remuxWithCompatibleAudio(candidateUrl, outputPath, analysis.bestCompatibleAudioIndex, { videoCodecName: analysis.videoCodecName, subtitleArgs });

    if (!result.success) {
      return { accepted: false, reason: 'audio_remux_failed' };
    }
  } else {
    downloadJobManager.updateJob(jobId, {
      status: 'processing',
      message: needsSubWork ? 'Converting audio + cleaning subtitles...' : 'Converting audio to EAC3...'
    });

    logger.info(`[AudioProcess] Transcoding ${analysis.defaultAudioCodec} -> EAC3 for ${sourceLabel}${needsSubWork ? ' (+ sub cleanup)' : ''}`);
    const result = await transcodeAudioToEac3(candidateUrl, outputPath, { videoCodecName: analysis.videoCodecName, subtitleArgs });

    if (!result.success) {
      return { accepted: false, reason: 'audio_transcode_failed' };
    }
  }

  // Store processed file path on job for cleanup + serving
  downloadJobManager.updateJob(jobId, { processedFilePath: outputPath });

  const processedUrl = platform === 'web'
    ? `/api/vod/stream-processed/${jobId}`
    : `https://${serverHost || process.env.SERVER_HOST || `localhost:${process.env.PORT || 3001}`}/api/vod/stream-processed/${jobId}`;

  return { accepted: true, streamUrl: processedUrl, ...baseResult };
}

/**
 * Background RD download processor with progressive updates
 */
async function processRdDownload(jobId, contentInfo) {
  const { tmdbId, title, year, type, season, episode, userId, serverHost, platform } = contentInfo;

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
      const user = db.prepare('SELECT measured_bandwidth_mbps FROM users WHERE id = ?').get(userId);
      maxBitrateMbps = user?.measured_bandwidth_mbps || null;
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
        maxBitrateMbps,
        rdApiKey
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
          // Validate codec + audio compatibility before using cached link
          downloadJobManager.updateJob(jobId, {
            status: 'searching',
            message: 'Validating stream...'
          });

          const validation = await validateAndProcessSource(cached.streamUrl, jobId, 'rd-cache', serverHost, platform);

          if (!validation.accepted) {
            logger.warn({
              msg: 'Stream rejected (cached link)',
              rejected: true,
              source: 'rd-cache',
              reason: validation.reason
            });
            // Fall through to fresh search
          } else {
            // SUCCESS - use cached link (or processed URL if audio was remuxed/transcoded)
            let finalStreamUrl = validation.streamUrl;

            // Web client MKV→MP4 remux for cached links
            if (platform === 'web' && cached.fileName?.toLowerCase().endsWith('.mkv') && !finalStreamUrl.includes('/stream-processed/')) {
              try {
                downloadJobManager.updateJob(jobId, {
                  status: 'processing',
                  message: 'Preparing for web playback...'
                });

                const remuxOutputPath = path.join(TRANSCODED_DIR, `${jobId}_${Date.now()}.mp4`);
                const { execFile } = require('child_process');
                const { promisify } = require('util');
                const execFileAsync = promisify(execFile);

                logger.info(`[Web Remux] Remuxing cached MKV→MP4 for web client: ${cached.fileName}`);

                const remuxArgs = [
                  '-y', '-i', finalStreamUrl,
                  '-c:v', 'copy', '-c:a', 'copy', '-sn'
                ];
                // Safari requires hvc1 tag for HEVC in MP4 (hev1 = black screen with audio)
                // videoCodecName is the raw codec name ("hevc"), not the tag string ("[0][0][0][0]" in MKV)
                if (validation.videoCodecName === 'hevc' || validation.videoCodecName === 'h265') {
                  remuxArgs.push('-tag:v', 'hvc1');
                }
                remuxArgs.push('-movflags', '+faststart', remuxOutputPath);

                await execFileAsync('ffmpeg', remuxArgs, { timeout: 10 * 60 * 1000 });

                downloadJobManager.updateJob(jobId, { processedFilePath: remuxOutputPath });

                finalStreamUrl = `/api/vod/stream-processed/${jobId}`;

                logger.info(`[Web Remux] Done: ${remuxOutputPath}`);
              } catch (remuxErr) {
                logger.warn(`[Web Remux] Failed, falling back to raw URL: ${remuxErr.message}`);
              }
            }

            downloadJobManager.updateJob(jobId, {
              status: 'completed',
              progress: 100,
              message: 'Ready to play! (cached)',
              streamUrl: finalStreamUrl,
              fileName: cached.fileName,
              quality: cached.resolution ? `${cached.resolution}p` : null,
              subtitles: [], // Will be populated in background
              embeddedSubtitleTracks: validation.embeddedSubtitleTracks || [],
              recommendedSubtitleIndex: validation.recommendedSubtitleIndex ?? null
            });

            // Resolve next episode for autoplay (non-blocking)
            resolveNextEpisodeForJob(jobId, tmdbId, type, season, episode);

            // Fetch skip markers in background (chapters from probe + IntroDB)
            const { getImdbId } = require('../services/tmdb-service');
            getImdbId(tmdbId, type)
              .then(imdbId => fetchSkipMarkersBackground(jobId, validation.chapters || [], contentInfo, imdbId, validation.duration))
              .catch(() => fetchSkipMarkersBackground(jobId, validation.chapters || [], contentInfo, null, validation.duration));

            // Fetch subtitles in background — skip external fetch if container has clean English subs
            fetchSubtitlesBackground(jobId, tmdbId, title, year, type, season, episode, cached.streamUrl, serverHost, { hasEnglishSubtitle: validation.hasEnglishSubtitle });

            // Track playback
            try {
              const job = downloadJobManager.getJob(jobId);
              if (job && job.userInfo) {
                downloadJobManager.trackPlayback(
                  contentInfo,
                  job.userInfo,
                  'rd-cached',
                  finalStreamUrl,
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
      season: season,
      episode,
      tmdbId,
      rdApiKey,
      maxBitrateMbps,
      platform
    }, onSourcesReady).then(() => {
      // getAllSources returned — mark search complete so the while loop can exit
      // (Prowlarr callbacks may have already set this, but ensure it's set)
      if (!searchComplete) {
        logger.info('getAllSources returned, marking search complete');
        searchComplete = true;
        if (sourcesAvailableResolve) {
          sourcesAvailableResolve();
          sourcesAvailableResolve = null;
        }
      }
    }).catch(err => {
      logger.warn('Source search error:', err.message);
      searchComplete = true;
      if (sourcesAvailableResolve) sourcesAvailableResolve();
    });

    // Wait for first batch of sources (or search completion)
    await Promise.race([
      sourcesAvailablePromise,
      new Promise(resolve => setTimeout(resolve, 15000)) // Max 15s wait for first sources
    ]);

    if (sourceQueue.length === 0 && !searchComplete) {
      // No sources yet — recreate the promise so we wake up the instant sources arrive via callback
      // Do NOT include searchPromise here: getAllSources may return before Prowlarr callbacks fire
      sourcesAvailablePromise = new Promise(resolve => { sourcesAvailableResolve = resolve; });
      await Promise.race([
        sourcesAvailablePromise,
        new Promise(resolve => setTimeout(resolve, 35000)) // 35s for slow Prowlarr (total max: 50s)
      ]);
    }

    if (sourceQueue.length === 0) {
      throw new Error('No sources found');
    }

    logger.info(`⚡ Starting with ${sourceQueue.length} sources (search ${searchComplete ? 'complete' : 'ongoing'})`);

    // Global safety timeout: prevent the entire source selection loop from hanging forever
    const JOB_MAX_DURATION = 5 * 60 * 1000; // 5 minutes max for entire job
    const jobStartTime = Date.now();

    // Try sources from queue until one works
    while (sourceQueue.length > 0 || !searchComplete) {
      // Safety: abort if job has been running too long
      if (Date.now() - jobStartTime > JOB_MAX_DURATION) {
        logger.error(`⏱️  Job ${jobId} hit global timeout (${JOB_MAX_DURATION/1000}s) after ${attemptNum} attempts`);
        throw new Error(`Timed out after ${attemptNum} source attempts`);
      }
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

      logger.info(`Attempt ${queueInfo}: [${sourceType}] ${source.title.substring(0, 60)} (${cached}${source.overBandwidth ? ', OVER-BW' : ''})`);

      // Flag job if we're falling back to over-bandwidth sources
      if (source.overBandwidth) {
        downloadJobManager.updateJob(jobId, { usedOverBandwidthFallback: true });
      }

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
          // ZURG SOURCE HANDLING:
          // Zurg is a shared P2P network of RD-cached content. If a file is in Zurg,
          // it IS cached on RD's CDN and can be instantly added to any RD account.
          //
          // Strategy:
          // 1. Try to resolve to a direct RD CDN URL (faster, better seeking)
          // 2. If resolution fails, fall back to Zurg FUSE mount streaming
          //    (the file already passed readability check in zurg-search.js)
          const rdLink = await resolveZurgToRdLink(source.filePath, rdApiKey);

          if (rdLink) {
            candidateUrl = rdLink;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Zurg source #${attemptNum} resolved to RD direct link`);
          } else {
            // RD resolution failed — fall back to Zurg FUSE mount streaming.
            // The file IS readable (verified by zurg-search.js isZurgFileReadable).
            // Encode the FUSE path as a base64url stream ID for the /stream/:streamId proxy.
            const streamId = Buffer.from(source.filePath).toString('base64url');
            candidateUrl = `https://${serverHost}/api/vod/stream/${streamId}`;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Zurg source #${attemptNum} using FUSE mount fallback`);
          }
        } else {
          // Prowlarr source - download from RD with timeout for slow downloads
          let lastProgress = 0;
          let lastProgressTime = Date.now();
          let lastRdStatus = null;
          let lastRdSeeders = null;
          let lastRdSpeed = null;
          const SLOW_START_TIMEOUT = 12000; // 12s at 0% with no seeder info yet
          const DEAD_TORRENT_TIMEOUT = 10000; // 10s for confirmed 0-seeder/0-speed
          const ACTIVE_START_TIMEOUT = 30000; // 30s at 0% when seeders/speed detected
          const STALL_TIMEOUT = 60000; // 60s stall at any progress level

          const downloadPromise = downloadFromRD(
            source.magnet,
            rdApiKey,
            season,
            episode,
            (rdProgress, rdMessage, rdMeta) => {
              // Don't update if job already completed or errored (orphaned downloads from
              // timed-out sources keep polling RD for up to 4h — prevent them from
              // overwriting the final job status)
              const currentJob = downloadJobManager.getJob(jobId);
              if (currentJob?.status === 'completed' || currentJob?.status === 'error') return;

              // Track RD metadata for faster dead torrent detection
              if (rdMeta) {
                lastRdStatus = rdMeta.status;
                lastRdSeeders = rdMeta.seeders;
                lastRdSpeed = rdMeta.speed;
              }

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

          // Timeout checker - abort dead torrents fast, general stall detection as fallback
          const timeoutPromise = new Promise((_, reject) => {
            const checkInterval = setInterval(() => {
              const stuckTime = Date.now() - lastProgressTime;

              // Fast-fail: RD reports 0 seeders and 0 speed while downloading → dead torrent
              if (lastRdStatus === 'downloading' && lastRdSeeders === 0 && lastRdSpeed === 0 && stuckTime > DEAD_TORRENT_TIMEOUT) {
                clearInterval(checkInterval);
                reject(new Error(`TIMEOUT: Dead torrent (0 seeders, 0 speed) at ${lastProgress}% for ${Math.round(stuckTime/1000)}s`));
                return;
              }

              // Fast-fail: stuck in magnet conversion (can't find peers for metadata)
              if (lastRdStatus === 'magnet_conversion' && stuckTime > DEAD_TORRENT_TIMEOUT) {
                clearInterval(checkInterval);
                reject(new Error(`TIMEOUT: Magnet conversion stuck for ${Math.round(stuckTime/1000)}s (no peers)`));
                return;
              }

              // Adaptive timeout: if RD reports seeders or speed, give more time
              let timeout;
              if (lastProgress >= 1) {
                timeout = STALL_TIMEOUT; // 60s - already making progress
              } else if (lastRdSeeders > 0 || lastRdSpeed > 0) {
                timeout = ACTIVE_START_TIMEOUT; // 30s - has seeders, just slow to start
              } else {
                timeout = SLOW_START_TIMEOUT; // 12s - no info yet or confirmed dead
              }
              if (stuckTime > timeout) {
                clearInterval(checkInterval);
                reject(new Error(`TIMEOUT: Stuck at ${lastProgress}% for ${Math.round(stuckTime/1000)}s (seeders=${lastRdSeeders ?? '?'}, speed=${lastRdSpeed ?? '?'}) - trying next source`));
              }
            }, 3000); // Check every 3s (matches RD poll interval)

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
              // Suppress unhandled rejection from orphaned downloadFromRD (its 4h polling loop
              // continues after we move on — when it eventually fails, we don't want a crash)
              downloadPromise.catch(() => {});
              // Clean up stuck torrents in background (don't await - just fire and forget)
              cleanupStuckTorrents(rdApiKey, 2, 5).catch(() => {});
              lastError = timeoutError;
              continue; // Try next source
            }
            // 451 = DMCA'd on RD — skip this source, try next
            if (timeoutError.response?.status === 451) {
              logger.warn(`⚠️  Source #${attemptNum} DMCA'd on RD (451 infringing_file), trying next...`);
              downloadPromise.catch(() => {});
              lastError = timeoutError;
              continue;
            }
            throw timeoutError; // Re-throw other errors
          }
        }

        // If we got a candidate URL, validate video+audio compatibility before accepting
        if (candidateUrl) {
          downloadJobManager.updateJob(jobId, {
            status: 'searching',
            message: `Validating stream...`
          });

          const validation = await validateAndProcessSource(candidateUrl, jobId, source.title?.substring(0, 50), serverHost, platform);

          if (!validation.accepted) {
            logger.warn({
              msg: 'Stream rejected',
              rejected: true,
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              reason: validation.reason
            });
            lastError = new Error(`Incompatible: ${validation.reason}`);
            continue; // Try next source
          }

          // Stream accepted (possibly with audio processing)
          result = {
            download: validation.streamUrl,
            filename: candidateFilename,
            videoCodec: validation.videoCodec,
            videoCodecName: validation.videoCodecName,
            chapters: validation.chapters,
            duration: validation.duration,
            hasEnglishSubtitle: validation.hasEnglishSubtitle,
            embeddedSubtitleTracks: validation.embeddedSubtitleTracks,
            recommendedSubtitleIndex: validation.recommendedSubtitleIndex
          };
          successfulSource = source;
          break;
        }
      } catch (error) {
        lastError = error;
        logger.warn(`⚠️  Source #${attemptNum} failed: ${error.message} (${error.code || 'no code'})`);

        // If FILE_NOT_FOUND, DMCA, or source issues, try next
        if (error.code === 'FILE_NOT_FOUND' ||
            error.response?.status === 451 ||
            error.message?.includes('infringing') ||
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

    // Save original RD URL for caching before web remux potentially overwrites it
    const originalRdUrl = result.download;

    // Web client MKV→MP4 remux: browsers can't play MKV, so remux container with -c copy
    // Skip if the stream is already a processed file (audio processing already output MP4 for web)
    if (platform === 'web' && result.filename?.toLowerCase().endsWith('.mkv') && !result.download.includes('/stream-processed/')) {
      try {
        downloadJobManager.updateJob(jobId, {
          status: 'processing',
          message: 'Preparing for web playback...'
        });

        const remuxOutputPath = path.join(TRANSCODED_DIR, `${jobId}_${Date.now()}.mp4`);
        const { execFile } = require('child_process');
        const { promisify } = require('util');
        const execFileAsync = promisify(execFile);

        logger.info(`[Web Remux] Remuxing MKV→MP4 for web client: ${result.filename}`);

        const remuxArgs = [
          '-y', '-i', result.download,
          '-c:v', 'copy', '-c:a', 'copy', '-sn'
        ];
        // Safari requires hvc1 tag for HEVC in MP4 (hev1 = black screen with audio)
        if (result.videoCodecName === 'hevc' || result.videoCodecName === 'h265') {
          remuxArgs.push('-tag:v', 'hvc1');
        }
        remuxArgs.push('-movflags', '+faststart', remuxOutputPath);

        await execFileAsync('ffmpeg', remuxArgs, { timeout: 10 * 60 * 1000 }); // 10 min timeout

        downloadJobManager.updateJob(jobId, { processedFilePath: remuxOutputPath });

        result.download = `/api/vod/stream-processed/${jobId}`;

        logger.info(`[Web Remux] Done: ${remuxOutputPath}`);
      } catch (remuxErr) {
        // Remux failed — fall back to raw URL (MKV). The web client may not play it,
        // but it's better than erroring the whole job.
        logger.warn(`[Web Remux] Failed, falling back to raw URL: ${remuxErr.message}`);
      }
    }

    // Cache the successful RD link (24h TTL with live verification on retrieval)
    // Only cache actual RD direct links, not proxy URLs (which are local Zurg proxies)
    // Use originalRdUrl to cache the real RD link even if we remuxed for web
    const isRdDirectLink = originalRdUrl &&
      !originalRdUrl.includes('/api/vod/stream/') &&
      (originalRdUrl.includes('real-debrid.com') || originalRdUrl.includes('rdb.so'));

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
          streamUrl: originalRdUrl,
          fileName: result.filename,
          resolution,
          fileSizeBytes: result.filesize || result.bytes || null,
          rdApiKey
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
      subtitles: [], // Will be populated in background
      embeddedSubtitleTracks: result.embeddedSubtitleTracks || [],
      recommendedSubtitleIndex: result.recommendedSubtitleIndex ?? null
    });

    // Resolve next episode for autoplay (non-blocking)
    resolveNextEpisodeForJob(jobId, tmdbId, type, season, episode);

    // Fetch skip markers in background (chapters from probe + IntroDB)
    {
      const { getImdbId } = require('../services/tmdb-service');
      getImdbId(tmdbId, type)
        .then(imdbId => fetchSkipMarkersBackground(jobId, result.chapters || [], contentInfo, imdbId, result.duration))
        .catch(() => fetchSkipMarkersBackground(jobId, result.chapters || [], contentInfo, null, result.duration));
    }

    // Auto-fetch subtitles in background — skip external fetch if container has clean English subs
    fetchSubtitlesBackground(jobId, tmdbId, title, year, type, season, episode, originalRdUrl, serverHost, { hasEnglishSubtitle: result.hasEnglishSubtitle });

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
  const { tmdbId, title, year, type, season, episode, userId, platform } = contentInfo;

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
      const user = db.prepare('SELECT measured_bandwidth_mbps FROM users WHERE id = ?').get(userId);
      maxBitrateMbps = user?.measured_bandwidth_mbps || null;
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
        season: season,
        episode,
        tmdbId,
        rdApiKey,
        maxBitrateMbps,
        excludedHashes,
        excludedFilePaths,
        platform
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
          // Zurg source: try RD direct link, fall back to FUSE mount (see main processRdDownload)
          const rdLink = await resolveZurgToRdLink(source.filePath, rdApiKey);

          if (rdLink) {
            candidateUrl = rdLink;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Alternative Zurg source ${attemptNum}/${allSources.length} resolved to RD link`);
          } else {
            // Fall back to FUSE mount streaming (file already verified readable)
            const streamId = Buffer.from(source.filePath).toString('base64url');
            candidateUrl = `https://${serverHost}/api/vod/stream/${streamId}`;
            candidateFilename = source.filePath.split('/').pop();
            logger.info(`✅ Alternative Zurg source ${attemptNum}/${allSources.length} using FUSE mount fallback`);
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
              // Don't update if job already in terminal state (orphaned download protection)
              const currentJob = downloadJobManager.getJob(jobId);
              if (currentJob?.status === 'completed' || currentJob?.status === 'error') return;

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
              downloadPromise.catch(() => {});
              cleanupStuckTorrents(rdApiKey, 2, 5).catch(() => {});
              lastError = timeoutError;
              continue;
            }
            // 451 = DMCA'd on RD — skip this source, try next
            if (timeoutError.response?.status === 451) {
              logger.warn(`⚠️  Alternative source ${attemptNum} DMCA'd on RD (451), trying next...`);
              downloadPromise.catch(() => {});
              lastError = timeoutError;
              continue;
            }
            throw timeoutError;
          }
        }

        // Validate video+audio compatibility before accepting
        if (candidateUrl) {
          downloadJobManager.updateJob(jobId, {
            status: 'searching',
            message: `Validating stream...`
          });

          const validation = await validateAndProcessSource(candidateUrl, jobId, source.title?.substring(0, 50), serverHost, platform);

          if (!validation.accepted) {
            logger.warn({
              msg: 'Stream rejected (re-search)',
              rejected: true,
              source: source.title?.substring(0, 50),
              sourceType: source.source,
              reason: validation.reason
            });
            lastError = new Error(`Incompatible: ${validation.reason}`);
            continue; // Try next source
          }

          result = {
            download: validation.streamUrl,
            filename: candidateFilename,
            videoCodec: validation.videoCodec,
            videoCodecName: validation.videoCodecName,
            chapters: validation.chapters,
            duration: validation.duration,
            hasEnglishSubtitle: validation.hasEnglishSubtitle,
            embeddedSubtitleTracks: validation.embeddedSubtitleTracks,
            recommendedSubtitleIndex: validation.recommendedSubtitleIndex
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

    // Save original RD URL for caching before web remux potentially overwrites it
    const originalRdUrl = result.download;

    // Web client MKV→MP4 remux (same as processRdDownload)
    if (platform === 'web' && result.filename?.toLowerCase().endsWith('.mkv') && !result.download.includes('/stream-processed/')) {
      try {
        downloadJobManager.updateJob(jobId, {
          status: 'processing',
          message: 'Preparing for web playback...'
        });

        const remuxOutputPath = path.join(TRANSCODED_DIR, `${jobId}_${Date.now()}.mp4`);
        const { execFile } = require('child_process');
        const { promisify } = require('util');
        const execFileAsync = promisify(execFile);

        logger.info(`[Web Remux] Remuxing MKV→MP4 for web client: ${result.filename}`);

        const remuxArgs = [
          '-y', '-i', result.download,
          '-c:v', 'copy', '-c:a', 'copy', '-sn'
        ];
        // Safari requires hvc1 tag for HEVC in MP4 (hev1 = black screen with audio)
        if (result.videoCodecName === 'hevc' || result.videoCodecName === 'h265') {
          remuxArgs.push('-tag:v', 'hvc1');
        }
        remuxArgs.push('-movflags', '+faststart', remuxOutputPath);

        await execFileAsync('ffmpeg', remuxArgs, { timeout: 10 * 60 * 1000 });

        downloadJobManager.updateJob(jobId, { processedFilePath: remuxOutputPath });

        result.download = `/api/vod/stream-processed/${jobId}`;

        logger.info(`[Web Remux] Done: ${remuxOutputPath}`);
      } catch (remuxErr) {
        logger.warn(`[Web Remux] Failed, falling back to raw URL: ${remuxErr.message}`);
      }
    }

    // Cache the successful RD link (24h TTL with live verification on retrieval)
    // Only cache actual RD direct links, not proxy URLs
    // Use originalRdUrl to cache the real RD link even if we remuxed for web
    const isRdDirectLink = originalRdUrl &&
      !originalRdUrl.includes('/api/vod/stream/') &&
      (originalRdUrl.includes('real-debrid.com') || originalRdUrl.includes('rdb.so'));

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
          streamUrl: originalRdUrl,
          fileName: result.filename,
          resolution,
          fileSizeBytes: result.filesize || result.bytes || null,
          rdApiKey
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

    // Fetch skip markers in background (chapters from probe + IntroDB)
    {
      const { getImdbId } = require('../services/tmdb-service');
      getImdbId(tmdbId, type)
        .then(imdbId => fetchSkipMarkersBackground(jobId, result.chapters || [], contentInfo, imdbId, result.duration))
        .catch(() => fetchSkipMarkersBackground(jobId, result.chapters || [], contentInfo, null, result.duration));
    }

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
      season: season,
      episode
    });

    if (zurgResult.match) { // Only use good quality, skip fallbacks
      const file = zurgResult.match;

      // Try to resolve Zurg path to direct RD link using user's own RD key
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

      if (streamUrl) {
        logger.info(`Zurg match found, streaming from: ${streamUrl.substring(0, 80)}...`);

        return res.json({
          streamUrl,
          source,
          fileName: file.fileName
        });
      }

      // Zurg file not in user's RD library - skip proxy (would use admin's RD key)
      logger.info(`Zurg match found but not in user's RD library, falling through to Prowlarr`);
    }

    // Not in Zurg (or Zurg file not in user's RD library) - fall back to Prowlarr search + RD
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
      season: season,
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
 *
 * This endpoint is fully synchronous (SQLite queries only) and should complete
 * in <10ms. If it takes longer, the event loop is blocked by something else
 * (likely FUSE mount I/O from Zurg search saturating the libuv thread pool).
 */
router.post('/session/check', (req, res) => {
  const reqStartMs = Date.now();

  // Server-side timeout: if we haven't responded in 8s, something is blocking
  // the event loop. Send an error so the client can retry immediately.
  const timeoutHandle = setTimeout(() => {
    if (!res.headersSent) {
      const elapsed = Date.now() - reqStartMs;
      logger.error(`[RD Session] CHECK TIMEOUT: ${elapsed}ms elapsed — event loop was blocked. ` +
        `UV_THREADPOOL_SIZE=${process.env.UV_THREADPOOL_SIZE || '4(default)'}`);
      res.status(503).json({
        error: 'Session check timed out',
        message: 'Server busy, please retry',
        retryable: true
      });
    }
  }, 8000);

  try {
    const userId = req.user.sub;
    const username = req.user.username;
    const clientIp = req.ip;

    logger.info(`[RD Session] Check for user ${username} from ${clientIp}`);

    // Get user's RD API key (inherits from parent if sub-account)
    const rdApiKey = getUserRdApiKey(userId);

    if (!rdApiKey) {
      clearTimeout(timeoutHandle);
      logger.error(`[RD Session] User ${username} has no RD API key`);
      return res.status(403).json({
        error: 'No RD API key configured',
        message: 'Your account requires a Real-Debrid API key. Please contact an administrator.'
      });
    }

    // Check if this RD key is being used from a different IP
    const sessionCheck = checkRdSession(rdApiKey, clientIp, userId);

    if (!sessionCheck.allowed) {
      clearTimeout(timeoutHandle);
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

    clearTimeout(timeoutHandle);

    const elapsed = Date.now() - reqStartMs;
    if (elapsed > 100) {
      logger.warn(`[RD Session] CHECK SLOW: ${elapsed}ms for ${username} (should be <10ms)`);
    }

    logger.info(`[RD Session] APPROVED: ${username} on ${clientIp} with RD ${rdApiKey.slice(-6)} (${elapsed}ms)`);

    res.json({
      success: true,
      message: 'Playback authorized'
    });
  } catch (error) {
    clearTimeout(timeoutHandle);
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
    const { title } = req.query; // Optional: show title for Zurg pack check
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
    // Requires title — skip if not provided (pack check is an optimization, not required)
    let inCurrentPack = false;
    if (title) {
      try {
        const nextZurgResult = await searchZurg({
          title,
          type: 'tv',
          season: nextEpisode.season,
          episode: nextEpisode.episode
        });

        if (nextZurgResult.match) {
          const currentZurgResult = await searchZurg({
            title,
            type: 'tv',
            season: currentSeason,
            episode: currentEpisode
          });

          if (currentZurgResult.match) {
            const currentPack = currentZurgResult.match.filePath.substring(0, currentZurgResult.match.filePath.lastIndexOf('/'));
            const nextPack = nextZurgResult.match.filePath.substring(0, nextZurgResult.match.filePath.lastIndexOf('/'));

            inCurrentPack = currentPack === nextPack;
            logger.info(`Pack check: ${inCurrentPack ? 'SAME' : 'DIFFERENT'} pack (current: ${currentPack}, next: ${nextPack})`);
          }
        }
      } catch (err) {
        logger.warn('Failed to check pack status:', err.message);
      }
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
 * Find the stream URL for content from an active/completed download job.
 * Used by subtitle endpoints to sync timing before serving.
 */
function findStreamUrlForContent(tmdbId, type, season, episode) {
  const allJobs = downloadJobManager.getAllJobs();
  const job = allJobs.find(j =>
    j.contentInfo?.tmdbId == tmdbId &&
    j.contentInfo?.type === type &&
    j.contentInfo?.season == season &&
    j.contentInfo?.episode == episode &&
    j.streamUrl &&
    (j.status === 'completed' || j.status === 'downloading')
  );
  return job?.streamUrl || null;
}

/**
 * Sync a subtitle file to the video's audio track if not already synced.
 */
async function syncSubtitleForEndpoint(subtitle, tmdbId, type, season, episode) {
  // Check if this subtitle already has a video_hash — means it was previously synced
  if (subtitle.cached) {
    const row = db.prepare('SELECT video_hash FROM subtitles WHERE id = ?').get(subtitle.id);
    if (row?.video_hash) return; // Already synced for this file
  }
  const streamUrl = findStreamUrlForContent(tmdbId, type, season, episode);
  if (!streamUrl) return; // No active job to get stream URL from
  try {
    const videoHash = await opensubtitlesService.computeOpenSubtitlesHash(streamUrl);
    const result = await syncSubtitle(streamUrl, subtitle.filePath);
    if (result.synced) {
      logger.info(`[SubSync] Synced subtitle via direct endpoint: ${result.offsetMs > 0 ? '+' : ''}${(result.offsetMs / 1000).toFixed(2)}s`);
    }
    // Stamp video_hash whether synced or not (offset < threshold = already good)
    if (videoHash) {
      db.prepare('UPDATE subtitles SET video_hash = ? WHERE id = ?').run(videoHash, subtitle.id);
    }
  } catch (err) {
    logger.warn(`[SubSync] Direct endpoint sync failed: ${err.message}`);
  }
}

/**
 * GET /api/vod/subtitles/search
 * Search for subtitles (checks cache first, downloads if needed)
 */
router.get('/subtitles/search', async (req, res) => {
  try {
    const { tmdbId, title, year, type, season, episode, languageCode, force } = req.query;

    if (!tmdbId || !type) {
      return res.status(400).json({ error: 'tmdbId and type are required' });
    }

    const forceDownload = force === 'true';
    logger.info(`Subtitle search: ${title} (${tmdbId}) ${type} S${season}E${episode} (${languageCode || 'en'})${forceDownload ? ' [FORCE]' : ''}`);

    const subtitle = await opensubtitlesService.getSubtitle({
      tmdbId: parseInt(tmdbId),
      title: title || 'Unknown',
      year,
      type,
      season: season ? parseInt(season) : null,
      episode: episode ? parseInt(episode) : null,
      languageCode: languageCode || 'en',
      force: forceDownload
    });

    // Sync timing before responding (uses active job's stream URL)
    await syncSubtitleForEndpoint(subtitle, parseInt(tmdbId), type, season ? parseInt(season) : null, episode ? parseInt(episode) : null);

    res.json({
      success: true,
      subtitle: {
        id: subtitle.id,
        language: subtitle.language,
        languageCode: subtitle.languageCode,
        format: subtitle.format,
        url: `https://${req.get('host')}/api/vod/subtitles/file/${subtitle.id}`,
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
    const { tmdbId, title, year, type, season, episode, languageCode, force } = req.body;

    if (!tmdbId || !type) {
      return res.status(400).json({ error: 'tmdbId and type are required' });
    }

    const forceDownload = force === true || force === 'true';
    logger.info(`Subtitle search: ${title} (${tmdbId}) ${type} S${season}E${episode} (${languageCode || 'en'})${forceDownload ? ' [FORCE]' : ''}`);

    const subtitle = await opensubtitlesService.getSubtitle({
      tmdbId: parseInt(tmdbId),
      title: title || 'Unknown',
      year,
      type,
      season: season ? parseInt(season) : null,
      episode: episode ? parseInt(episode) : null,
      languageCode: languageCode || 'en',
      force: forceDownload
    });

    // Sync timing before responding (uses active job's stream URL)
    await syncSubtitleForEndpoint(subtitle, parseInt(tmdbId), type, season ? parseInt(season) : null, episode ? parseInt(episode) : null);

    res.json({
      success: true,
      subtitle: {
        id: subtitle.id,
        language: subtitle.language,
        languageCode: subtitle.languageCode,
        format: subtitle.format,
        url: `https://${req.get('host')}/api/vod/subtitles/file/${subtitle.id}`,
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

    let fileExists = false;
    if (filePath) {
      try { await require('fs').promises.access(filePath); fileExists = true; } catch {}
    }
    if (!fileExists) {
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
