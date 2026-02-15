/**
 * Download Progress Tracker
 *
 * Tracks RD download progress for non-cached content
 * Stores progress in-memory and allows resumption
 */

const logger = require('../utils/logger');
const { getTorrentInfo } = require('@duckflix/rd-client');

// Active downloads: Map<userId_tmdbId, DownloadProgress>
const activeDownloads = new Map();
// Track polling timeouts so we can cancel them when downloads are removed
const pollTimeouts = new Map(); // key -> timeoutId

/**
 * DownloadProgress structure:
 * {
 *   userId: string,
 *   tmdbId: number,
 *   title: string,
 *   type: 'movie' | 'tv',
 *   season: number?,
 *   episode: number?,
 *   torrentId: string,
 *   magnetUrl: string,
 *   status: 'queued' | 'downloading' | 'ready' | 'failed',
 *   progress: number (0-100),
 *   rdApiKey: string,
 *   streamUrl: string?, // Available when ready
 *   fileName: string?,
 *   error: string?,
 *   startedAt: number,
 *   updatedAt: number
 * }
 */

function getDownloadKey(userId, tmdbId, season, episode) {
  if (season && episode) {
    return `${userId}_${tmdbId}_s${season}e${episode}`;
  }
  return `${userId}_${tmdbId}`;
}

/**
 * Start tracking a download
 */
function startDownload(options) {
  const { userId, tmdbId, title, type, season, episode, torrentId, magnetUrl, rdApiKey } = options;

  const key = getDownloadKey(userId, tmdbId, season, episode);

  const download = {
    userId,
    tmdbId,
    title,
    type,
    season,
    episode,
    torrentId,
    magnetUrl,
    status: 'downloading',
    progress: 0,
    rdApiKey,
    streamUrl: null,
    fileName: null,
    error: null,
    startedAt: Date.now(),
    updatedAt: Date.now()
  };

  activeDownloads.set(key, download);

  logger.info(`📥 Started tracking download: ${title} (${key})`);

  // Start polling RD for progress
  pollDownloadProgress(key);

  return download;
}

/**
 * Poll RD for download progress
 */
async function pollDownloadProgress(key) {
  // Stop polling if download was removed
  if (!activeDownloads.has(key)) {
    pollTimeouts.delete(key);
    return;
  }

  const download = activeDownloads.get(key);

  if (download.status === 'ready' || download.status === 'failed') {
    pollTimeouts.delete(key);
    return;
  }

  try {
    const info = await getTorrentInfo(download.torrentId, download.rdApiKey);

    // Re-check after async call — download may have been removed while we waited
    if (!activeDownloads.has(key)) {
      pollTimeouts.delete(key);
      return;
    }

    download.status = info.status;
    download.progress = info.progress || 0;
    download.updatedAt = Date.now();

    logger.info(`📊 Download progress: ${download.title} - ${info.status} (${download.progress}%)`);

    if (info.status === 'downloaded') {
      download.status = 'ready';
      download.progress = 100;
      pollTimeouts.delete(key);
      logger.info(`✅ Download ready: ${download.title}`);
    } else if (info.status === 'error' || info.status === 'dead' || info.status === 'virus') {
      download.status = 'failed';
      download.error = `RD torrent failed: ${info.status}`;
      pollTimeouts.delete(key);
      logger.error(`❌ Download failed: ${download.title} - ${info.status}`);
    } else {
      // Continue polling
      const tid = setTimeout(() => pollDownloadProgress(key), 3000);
      pollTimeouts.set(key, tid);
    }
  } catch (err) {
    logger.error(`Error polling download progress for ${key}:`, err.message);

    if (!activeDownloads.has(key)) {
      pollTimeouts.delete(key);
      return;
    }

    // Retry after delay
    const tid = setTimeout(() => pollDownloadProgress(key), 5000);
    pollTimeouts.set(key, tid);
  }
}

/**
 * Get download progress
 */
function getDownloadProgress(userId, tmdbId, season, episode) {
  const key = getDownloadKey(userId, tmdbId, season, episode);
  const download = activeDownloads.get(key);

  if (!download) {
    return null;
  }

  return {
    title: download.title,
    type: download.type,
    season: download.season,
    episode: download.episode,
    status: download.status,
    progress: download.progress,
    streamUrl: download.streamUrl,
    fileName: download.fileName,
    error: download.error,
    startedAt: download.startedAt,
    updatedAt: download.updatedAt
  };
}

/**
 * Get all downloads for a user
 */
function getUserDownloads(userId) {
  const downloads = [];

  for (const [key, download] of activeDownloads.entries()) {
    if (download.userId === userId) {
      downloads.push({
        key,
        title: download.title,
        type: download.type,
        season: download.season,
        episode: download.episode,
        status: download.status,
        progress: download.progress,
        streamUrl: download.streamUrl,
        fileName: download.fileName,
        error: download.error,
        startedAt: download.startedAt,
        updatedAt: download.updatedAt
      });
    }
  }

  return downloads;
}

/**
 * Update download with stream URL
 */
function updateDownloadStreamUrl(userId, tmdbId, season, episode, streamUrl, fileName) {
  const key = getDownloadKey(userId, tmdbId, season, episode);
  const download = activeDownloads.get(key);

  if (download) {
    download.streamUrl = streamUrl;
    download.fileName = fileName;
    download.updatedAt = Date.now();
    logger.info(`📺 Stream URL ready for: ${download.title}`);
  }
}

/**
 * Delete download
 */
function deleteDownload(userId, tmdbId, season, episode) {
  const key = getDownloadKey(userId, tmdbId, season, episode);

  // Cancel any pending poll timeout before removing the download
  const tid = pollTimeouts.get(key);
  if (tid) {
    clearTimeout(tid);
    pollTimeouts.delete(key);
  }

  const deleted = activeDownloads.delete(key);

  if (deleted) {
    logger.info(`🗑️  Deleted download: ${key}`);
  }

  return deleted;
}

/**
 * Clean up old downloads (older than 24 hours)
 */
function cleanupOldDownloads() {
  const now = Date.now();
  const maxAge = 24 * 60 * 60 * 1000; // 24 hours

  for (const [key, download] of activeDownloads.entries()) {
    if (now - download.updatedAt > maxAge) {
      logger.info(`🧹 Cleaning up old download: ${key}`);
      activeDownloads.delete(key);
    }
  }
}

// Run cleanup every hour
setInterval(cleanupOldDownloads, 60 * 60 * 1000);

module.exports = {
  startDownload,
  getDownloadProgress,
  getUserDownloads,
  updateDownloadStreamUrl,
  deleteDownload
};
