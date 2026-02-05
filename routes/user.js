const express = require('express');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');
const recommendationsService = require('../services/recommendations-service');
const fs = require('fs');
const path = require('path');

const router = express.Router();

// All user routes require authentication
router.use(authenticateToken);

/**
 * GET /api/user/loading-phrases
 * Get loading phrases for slot machine animation
 * Public endpoint - no admin required
 */
router.get('/loading-phrases', (req, res) => {
  try {
    // Prevent caching so clients always get fresh phrases after admin updates
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');

    // Check if phrases are in database
    const phrasesRecord = db.prepare('SELECT value FROM app_settings WHERE key = ?').get('loading_phrases');

    let phrasesA, phrasesB;
    if (phrasesRecord) {
      const data = JSON.parse(phrasesRecord.value);
      phrasesA = data.phrasesA || [];
      phrasesB = data.phrasesB || [];
    } else {
      // Default phrases if not set by admin
      phrasesA = [
        "Analyzing", "Allocating", "Assembling",
        "Buffering", "Bootstrapping", "Building",
        "Calculating", "Calibrating", "Compiling",
        "Downloading", "Decoding", "Deploying",
        "Encrypting", "Establishing", "Extracting",
        "Formatting", "Fetching", "Fragmenting",
        "Generating", "Gathering", "Gridding",
        "Hashing", "Hijacking", "Harmonizing",
        "Initializing", "Installing", "Integrating",
        "Juggling", "Jamming", "Joining",
        "Kindling", "Knitting", "Kneading",
        "Loading", "Linking", "Launching",
        "Materializing", "Mounting", "Mapping",
        "Negotiating", "Normalizing", "Networking",
        "Optimizing", "Organizing", "Orchestrating",
        "Processing", "Parsing", "Preparing",
        "Quantifying", "Querying", "Queueing",
        "Reticulating", "Rendering", "Resolving",
        "Scanning", "Spinning", "Syncing",
        "Transmitting", "Translating", "Transcoding",
        "Uploading", "Updating", "Unpacking",
        "Validating", "Vectorizing", "Virtualizing",
        "Warming", "Weaving", "Wrangling",
        "X-raying", "Xeroxing", "Xylophonicating",
        "Yielding", "Yodeling", "Yanking",
        "Zipping", "Zigzagging", "Zapping"
      ];

      phrasesB = [
        "algorithms", "architectures", "atoms",
        "buffers", "bits", "bandwidths",
        "caches", "codecs", "connections",
        "data", "databases", "downloads",
        "electrons", "encoders", "engines",
        "files", "frameworks", "functions",
        "graphics", "gigabytes", "gateways",
        "hashes", "headers", "hamster wheels",
        "interfaces", "indexes", "inputs",
        "journeys", "junctions", "joules",
        "kernels", "kilobytes", "keys",
        "libraries", "links", "layers",
        "memories", "modules", "matrices",
        "nodes", "networks", "numbers",
        "objects", "operations", "outputs",
        "protocols", "packets", "pipelines",
        "queries", "queues", "quotas",
        "RAM", "resources", "routes",
        "servers", "streams", "splines",
        "threads", "tokens", "tables",
        "units", "uploads", "URLs",
        "variables", "vectors", "vortexes",
        "workflows", "widgets", "wires",
        "x-rays", "xeroxes", "x-factors",
        "yottabytes", "yields", "yarns",
        "zeros", "zones", "zettabytes"
      ];
    }

    res.json({ phrasesA, phrasesB });
  } catch (error) {
    logger.error('Get loading phrases error:', error);
    res.status(500).json({ error: 'Failed to get loading phrases' });
  }
});

// Storage paths
const WATCHLIST_DIR = path.join(__dirname, '..', 'db', 'user_watchlist');
const WATCH_PROGRESS_DIR = path.join(__dirname, '..', 'db', 'user_watch_progress');

// Ensure directories exist
[WATCHLIST_DIR, WATCH_PROGRESS_DIR].forEach(dir => {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
    logger.info(`Created directory: ${dir}`);
  }
});

/**
 * Helper: Load user's watchlist
 */
function loadWatchlist(username) {
  const filePath = path.join(WATCHLIST_DIR, `${username}.json`);
  if (!fs.existsSync(filePath)) {
    return [];
  }
  try {
    const data = fs.readFileSync(filePath, 'utf8');
    return JSON.parse(data);
  } catch (error) {
    logger.error(`Failed to load watchlist for ${username}:`, error);
    return [];
  }
}

/**
 * Helper: Save user's watchlist
 */
function saveWatchlist(username, watchlist) {
  const filePath = path.join(WATCHLIST_DIR, `${username}.json`);
  fs.writeFileSync(filePath, JSON.stringify(watchlist, null, 2), 'utf8');
}

/**
 * Helper: Load user's watch progress
 */
function loadWatchProgress(username) {
  const filePath = path.join(WATCH_PROGRESS_DIR, `${username}.json`);
  if (!fs.existsSync(filePath)) {
    return {};
  }
  try {
    const data = fs.readFileSync(filePath, 'utf8');
    return JSON.parse(data);
  } catch (error) {
    logger.error(`Failed to load watch progress for ${username}:`, error);
    return {};
  }
}

/**
 * Helper: Save user's watch progress
 */
function saveWatchProgress(username, progress) {
  const filePath = path.join(WATCH_PROGRESS_DIR, `${username}.json`);
  fs.writeFileSync(filePath, JSON.stringify(progress, null, 2), 'utf8');
}

/**
 * GET /api/user/trending
 * Get TMDB trending content
 */
router.get('/trending', async (req, res) => {
  try {
    const mediaType = req.query.mediaType || 'all'; // all, movie, tv
    const timeWindow = req.query.timeWindow || 'week'; // day, week
    const page = parseInt(req.query.page) || 1;

    logger.info(`Fetching trending ${mediaType} for ${timeWindow} (page ${page})`);

    const result = await recommendationsService.getTrending(mediaType, timeWindow, page);

    res.json(result);
  } catch (error) {
    logger.error('Trending error:', error);
    res.status(500).json({
      error: 'Failed to load trending',
      message: error.message
    });
  }
});

/**
 * GET /api/user/recommendations
 * Get aggregated recommendations for the user
 */
router.get('/recommendations', async (req, res) => {
  try {
    const username = req.user.username;
    const page = parseInt(req.query.page) || 1;
    const limit = parseInt(req.query.limit) || 20;

    logger.info(`Fetching recommendations for ${username} (page ${page}, limit ${limit})`);

    // Load watchlist and continue watching to deduplicate
    const watchlist = loadWatchlist(username);
    const watchProgress = loadWatchProgress(username);

    const watchlistIds = watchlist.map(item => `${item.type}_${item.tmdbId}`);
    const continueWatchingIds = Object.keys(watchProgress);

    // Get aggregated recommendations
    const result = recommendationsService.aggregateRecommendations(
      username,
      watchlistIds,
      continueWatchingIds,
      page,
      limit
    );

    logger.info(`Returning ${result.recommendations.length} recommendations for ${username}`);

    res.json(result);
  } catch (error) {
    logger.error('Recommendations error:', error);
    res.status(500).json({
      error: 'Failed to load recommendations',
      message: error.message
    });
  }
});

/**
 * GET /api/user/watchlist
 * Get user's watchlist
 */
router.get('/watchlist', (req, res) => {
  try {
    const username = req.user.username;
    const watchlist = loadWatchlist(username);

    logger.info(`Loaded watchlist for ${username} (${watchlist.length} items)`);

    res.json({ watchlist });
  } catch (error) {
    logger.error('Watchlist load error:', error);
    res.status(500).json({
      error: 'Failed to load watchlist',
      message: error.message
    });
  }
});

/**
 * POST /api/user/watchlist
 * Add item to watchlist and fetch recommendations
 */
router.post('/watchlist', async (req, res) => {
  try {
    const username = req.user.username;
    const { tmdbId, type, title, posterPath, releaseDate, voteAverage } = req.body;

    if (!tmdbId || !type || !title) {
      return res.status(400).json({
        error: 'Missing required fields',
        message: 'tmdbId, type, and title are required'
      });
    }

    const itemId = `${type}_${tmdbId}`;
    const watchlist = loadWatchlist(username);

    // Check if already in watchlist
    if (watchlist.find(item => `${item.type}_${item.tmdbId}` === itemId)) {
      return res.status(400).json({
        error: 'Already in watchlist',
        message: 'This item is already in your watchlist'
      });
    }

    // Add to watchlist
    watchlist.push({
      tmdbId,
      type,
      title,
      posterPath,
      releaseDate,
      voteAverage,
      addedAt: new Date().toISOString()
    });

    saveWatchlist(username, watchlist);

    // Fetch and store TMDB recommendations (non-blocking)
    recommendationsService.addItemRecommendations(
      username,
      itemId,
      tmdbId,
      type,
      title,
      'watchlist'
    ).catch(error => {
      logger.error(`Failed to fetch recommendations for ${itemId}:`, error);
    });

    logger.info(`Added ${itemId} to watchlist for ${username}`);

    res.json({
      success: true,
      message: 'Added to watchlist',
      watchlist
    });
  } catch (error) {
    logger.error('Watchlist add error:', error);
    res.status(500).json({
      error: 'Failed to add to watchlist',
      message: error.message
    });
  }
});

/**
 * DELETE /api/user/watchlist/:tmdbId/:type
 * Remove item from watchlist and potentially clean up recommendations
 */
router.delete('/watchlist/:tmdbId/:type', (req, res) => {
  try {
    const username = req.user.username;
    const { tmdbId, type } = req.params;
    const itemId = `${type}_${tmdbId}`;

    const watchlist = loadWatchlist(username);
    const initialLength = watchlist.length;

    // Remove from watchlist
    const updatedWatchlist = watchlist.filter(
      item => `${item.type}_${item.tmdbId}` !== itemId
    );

    if (updatedWatchlist.length === initialLength) {
      return res.status(404).json({
        error: 'Not found',
        message: 'Item not in watchlist'
      });
    }

    saveWatchlist(username, updatedWatchlist);

    // Check if item should be removed from recommendations
    const watchProgress = loadWatchProgress(username);
    const continueWatchingIds = Object.keys(watchProgress);

    if (!continueWatchingIds.includes(itemId)) {
      // Item not in continue watching either, remove recommendations
      recommendationsService.removeItemRecommendations(username, itemId);
    }

    logger.info(`Removed ${itemId} from watchlist for ${username}`);

    res.json({
      success: true,
      message: 'Removed from watchlist',
      watchlist: updatedWatchlist
    });
  } catch (error) {
    logger.error('Watchlist remove error:', error);
    res.status(500).json({
      error: 'Failed to remove from watchlist',
      message: error.message
    });
  }
});

/**
 * GET /api/user/watch-progress
 * Get user's watch progress (continue watching) including in-progress downloads
 */
router.get('/watch-progress', (req, res) => {
  try {
    const username = req.user.username;
    const userId = req.user.sub;
    const progress = loadWatchProgress(username);

    // Convert to array format
    const continueWatching = Object.entries(progress).map(([itemId, data]) => ({
      itemId,
      ...data
    }));

    // Add in-progress and failed downloads from download job manager
    const downloadJobManager = require('../services/download-job-manager');
    const activeJobs = downloadJobManager.getAllJobs();

    // Filter jobs for this user that are active OR failed
    const userJobs = activeJobs.filter(job =>
      job.userInfo?.userId === userId &&
      (job.status === 'searching' || job.status === 'downloading' || job.status === 'error')
    );

    // Convert downloads to continue watching format
    const downloadItems = userJobs.map(job => {
      const { contentInfo } = job;
      const itemId = `${contentInfo.type}_${contentInfo.tmdbId}`;

      const isFailed = job.status === 'error';
      const isDownloading = job.status === 'searching' || job.status === 'downloading';

      return {
        itemId,
        tmdbId: contentInfo.tmdbId,
        type: contentInfo.type,
        title: contentInfo.title,
        season: contentInfo.season,
        episode: contentInfo.episode,
        // Download state
        isDownloading,
        isFailed,
        downloadProgress: job.progress,
        downloadStatus: job.status,
        downloadMessage: isFailed ? job.error : job.message,
        errorMessage: isFailed ? job.error : null,
        jobId: job.jobId,
        // No playback position yet
        position: 0,
        duration: 0,
        updatedAt: new Date(job.createdAt).toISOString()
      };
    });

    // Merge downloads at the beginning (most recent activity)
    const allItems = [...downloadItems, ...continueWatching];

    const activeCount = downloadItems.filter(d => d.isDownloading).length;
    const failedCount = downloadItems.filter(d => d.isFailed).length;

    logger.info(`Loaded watch progress for ${username} (${continueWatching.length} items, ${activeCount} downloading, ${failedCount} failed)`);

    res.json({
      continueWatching: allItems,
      activeDownloads: activeCount,
      failedDownloads: failedCount
    });
  } catch (error) {
    logger.error('Watch progress load error:', error);
    res.status(500).json({
      error: 'Failed to load watch progress',
      message: error.message
    });
  }
});

/**
 * POST /api/user/watch-progress
 * Update watch progress and fetch recommendations if new
 */
router.post('/watch-progress', async (req, res) => {
  try {
    const username = req.user.username;
    const {
      tmdbId,
      type,
      title,
      posterPath,
      releaseDate,
      position,
      duration,
      season,
      episode
    } = req.body;

    if (!tmdbId || !type || !title || position === undefined || duration === undefined) {
      return res.status(400).json({
        error: 'Missing required fields',
        message: 'tmdbId, type, title, position, and duration are required'
      });
    }

    const itemId = `${type}_${tmdbId}`;
    const progress = loadWatchProgress(username);
    const isNew = !progress[itemId];

    // Update progress
    progress[itemId] = {
      tmdbId,
      type,
      title,
      posterPath,
      releaseDate,
      position,
      duration,
      season,
      episode,
      updatedAt: new Date().toISOString()
    };

    saveWatchProgress(username, progress);

    // If this is a new item in continue watching, fetch recommendations (non-blocking)
    if (isNew) {
      recommendationsService.addItemRecommendations(
        username,
        itemId,
        tmdbId,
        type,
        title,
        'continue-watching'
      ).catch(error => {
        logger.error(`Failed to fetch recommendations for ${itemId}:`, error);
      });

      logger.info(`Added ${itemId} to watch progress for ${username} (new)`);
    } else {
      logger.info(`Updated watch progress for ${itemId} for ${username}`);
    }

    res.json({
      success: true,
      message: 'Watch progress updated'
    });
  } catch (error) {
    logger.error('Watch progress update error:', error);
    res.status(500).json({
      error: 'Failed to update watch progress',
      message: error.message
    });
  }
});

/**
 * DELETE /api/user/watch-progress/:tmdbId/:type
 * Remove item from watch progress and potentially clean up recommendations
 */
router.delete('/watch-progress/:tmdbId/:type', (req, res) => {
  try {
    const username = req.user.username;
    const { tmdbId, type } = req.params;
    const itemId = `${type}_${tmdbId}`;

    const progress = loadWatchProgress(username);

    if (!progress[itemId]) {
      return res.status(404).json({
        error: 'Not found',
        message: 'Item not in watch progress'
      });
    }

    delete progress[itemId];
    saveWatchProgress(username, progress);

    // Check if item should be removed from recommendations
    const watchlist = loadWatchlist(username);
    const watchlistIds = watchlist.map(item => `${item.type}_${item.tmdbId}`);

    if (!watchlistIds.includes(itemId)) {
      // Item not in watchlist either, remove recommendations
      recommendationsService.removeItemRecommendations(username, itemId);
    }

    logger.info(`Removed ${itemId} from watch progress for ${username}`);

    res.json({
      success: true,
      message: 'Removed from watch progress'
    });
  } catch (error) {
    logger.error('Watch progress remove error:', error);
    res.status(500).json({
      error: 'Failed to remove from watch progress',
      message: error.message
    });
  }
});

/**
 * DELETE /api/user/failed-download/:jobId
 * Dismiss a failed download from Continue Watching
 */
router.delete('/failed-download/:jobId', (req, res) => {
  try {
    const userId = req.user.sub;
    const { jobId } = req.params;
    const downloadJobManager = require('../services/download-job-manager');

    const job = downloadJobManager.getJob(jobId);

    // Verify job exists and belongs to user
    if (!job) {
      return res.status(404).json({
        error: 'Job not found',
        message: 'Download job does not exist'
      });
    }

    if (job.userInfo?.userId !== userId) {
      return res.status(403).json({
        error: 'Forbidden',
        message: 'This job does not belong to you'
      });
    }

    // Only allow dismissing failed jobs
    if (job.status !== 'error') {
      return res.status(400).json({
        error: 'Invalid operation',
        message: 'Can only dismiss failed downloads'
      });
    }

    // Delete the job
    downloadJobManager.deleteJob(jobId);

    logger.info(`User ${req.user.username} dismissed failed download ${jobId}`);

    res.json({
      success: true,
      message: 'Failed download dismissed'
    });
  } catch (error) {
    logger.error('Failed download dismiss error:', error);
    res.status(500).json({
      error: 'Failed to dismiss',
      message: error.message
    });
  }
});

/**
 * GET /api/user/download-notifications
 * Check if user has any recently completed downloads (for notifications)
 */
router.get('/download-notifications', (req, res) => {
  try {
    const userId = req.user.sub;
    const downloadJobManager = require('../services/download-job-manager');
    const completedJobs = downloadJobManager.getCompletedJobs();

    // Filter for this user's completed jobs from last 10 minutes
    const tenMinutesAgo = Date.now() - (10 * 60 * 1000);
    const recentCompletions = completedJobs.filter(job =>
      job.userInfo?.userId === userId &&
      job.status === 'completed' &&
      job.completedAt > tenMinutesAgo
    );

    // Format for notifications
    const notifications = recentCompletions.map(job => ({
      jobId: job.jobId,
      tmdbId: job.contentInfo.tmdbId,
      title: job.contentInfo.title,
      type: job.contentInfo.type,
      season: job.contentInfo.season,
      episode: job.contentInfo.episode,
      completedAt: job.completedAt,
      streamUrl: job.streamUrl,
      fileName: job.fileName
    }));

    res.json({
      notifications,
      count: notifications.length
    });
  } catch (error) {
    logger.error('Download notifications error:', error);
    res.status(500).json({
      error: 'Failed to get notifications',
      message: error.message
    });
  }
});

module.exports = router;
