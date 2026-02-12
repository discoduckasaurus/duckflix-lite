/**
 * ZURG SEARCH SERVICE
 * ====================
 *
 * WHAT IS ZURG:
 * Zurg is a SHARED P2P NETWORK that indexes torrents cached on Real-Debrid's CDN.
 * It exposes these as a FUSE filesystem mount at /mnt/zurg (configurable via ZURG_MOUNT_PATH).
 *
 *   - Zurg is NOT just the admin's personal RD library — it shows content from the
 *     entire Zurg community network. Any torrent cached by ANY RD user can appear here.
 *   - Files appearing in Zurg are ALREADY cached on RD's CDN, meaning they can be
 *     instantly added to any RD account (no seeder download / no wait).
 *   - Zurg search is FAST (~1s) compared to Prowlarr (~14-20s), making it the
 *     preferred first source in unified-source-resolver.js.
 *
 * SEARCH FLOW:
 * 1. searchZurg() calls findInZurgMount() from @duckflix/zurg-client
 *    → Searches the FUSE mount directories for matching video files
 * 2. Results are filtered by:
 *    a. Title match (70%+ word overlap) — prevents wrong-show matches
 *    b. File readability (1KB read test) — catches stale/expired mount entries
 * 3. Returns { match, fallback, matches[] } where:
 *    - match = best quality result that meets quality threshold
 *    - fallback = result below threshold (still playable, just lower quality)
 *
 * STALE FILES:
 * The Zurg FUSE mount can show files that are no longer accessible (EIO errors).
 * This happens when a torrent was removed from the network cache but Zurg hasn't
 * refreshed yet. isZurgFileReadable() catches these before they reach the user.
 *
 * DOWNSTREAM:
 * Zurg sources feed into unified-source-resolver.js which combines them with Prowlarr
 * sources. In vod.js, Zurg sources are resolved to direct RD URLs via
 * zurg-to-rd-resolver.js, with fallback to FUSE mount streaming if resolution fails.
 */

const { findInZurgMount } = require('@duckflix/zurg-client');
const logger = require('../utils/logger');
const fs = require('fs').promises;

const FUSE_TIMEOUT_MS = 10000;

/**
 * Race a promise against a timeout. Prevents FUSE hangs from consuming libuv threads indefinitely.
 */
function withFuseTimeout(promise, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`FUSE timeout: ${label} after ${FUSE_TIMEOUT_MS}ms`)), FUSE_TIMEOUT_MS);
    })
  ]).finally(() => clearTimeout(timer));
}

/**
 * Quick validation that a Zurg file is actually readable (not stale/expired from cache).
 * Reads first 1KB to detect EIO errors before returning file as valid source.
 * A file passing this check IS streamable via the FUSE mount.
 * Timeout-protected to prevent FUSE hangs from blocking the thread pool.
 */
async function isZurgFileReadable(filePath) {
  if (!filePath) return false;

  let fd;
  try {
    fd = await withFuseTimeout(fs.open(filePath, 'r'), `open ${filePath.substring(0, 60)}`);
    const buffer = Buffer.alloc(1024);
    const { bytesRead } = await withFuseTimeout(fd.read(buffer, 0, 1024, 0), `read ${filePath.substring(0, 60)}`);
    await fd.close().catch(() => {});
    return bytesRead >= 100;
  } catch (err) {
    if (fd) await fd.close().catch(() => {});
    logger.warn(`Zurg file check failed: ${err.message} - ${filePath.substring(0, 60)}`);
    return false;
  }
}

/**
 * Normalize title for comparison (remove special chars, lowercase)
 */
function normalizeTitle(title) {
  if (!title) return '';
  return title
    .toLowerCase()
    .replace(/[^a-z0-9\s]/g, '') // Remove special chars
    .replace(/\s+/g, ' ')        // Normalize spaces
    .trim();
}

/**
 * Check if a file path/name contains the expected title
 */
function titleMatches(filePath, expectedTitle) {
  const normalizedExpected = normalizeTitle(expectedTitle);
  const normalizedPath = normalizeTitle(filePath);

  // Check if the expected title words appear in the path
  const expectedWords = normalizedExpected.split(' ').filter(w => w.length > 2);

  // At least 70% of significant words should match
  const matchingWords = expectedWords.filter(word => normalizedPath.includes(word));
  const matchRatio = matchingWords.length / expectedWords.length;

  return matchRatio >= 0.7;
}

/**
 * Filter matches to only include those that match the expected title
 */
function filterByTitle(matches, expectedTitle) {
  if (!matches || !Array.isArray(matches)) return [];

  return matches.filter(match => {
    const pathOrName = match.filePath || match.fileName || '';
    const isMatch = titleMatches(pathOrName, expectedTitle);

    if (!isMatch) {
      logger.debug(`Filtered out wrong title: ${pathOrName.substring(0, 80)} (expected: ${expectedTitle})`);
    }

    return isMatch;
  });
}

/**
 * Search Zurg mount for content
 *
 * @param {Object} params - Search parameters
 * @param {string} params.title - Content title
 * @param {string|number} params.year - Release year
 * @param {string} params.type - 'movie' or 'episode'
 * @param {number} [params.season] - Season number (for episodes)
 * @param {number} [params.episode] - Episode number (for episodes)
 * @param {number} [params.duration] - Content duration in minutes
 * @returns {Promise<Object>} Search result with match and fallback
 */
async function searchZurg({ title, year, type, season, episode, duration }) {
  try {
    const mountPath = process.env.ZURG_MOUNT_PATH || '/mnt/zurg';

    // Clean title: decode URL encoding (+) and remove problematic chars for search
    const cleanTitle = title
      .replace(/\+/g, ' ')  // URL-encoded spaces
      .replace(/[!?]/g, '') // Punctuation that breaks search
      .trim();

    logger.info(`Searching Zurg: ${cleanTitle} (${year}) - ${type}`, {
      originalTitle: title,
      season,
      episode,
      duration
    });

    const result = await withFuseTimeout(
      findInZurgMount({
        title: cleanTitle,
        year,
        type,
        season,
        episode,
        episodeRuntime: duration
      }),
      `findInZurgMount(${cleanTitle})`
    );

    // Debug: Log what zurg-client returned
    logger.debug(`Zurg-client returned: matches=${result.matches?.length || 0}, match=${!!result.match}, fallback=${!!result.fallback}`);
    if (result.matches?.length > 0) {
      logger.debug(`Zurg matches: ${result.matches.map(m => m.filePath?.substring(0, 60)).join(', ')}`);
    }

    // CRITICAL: Verify title matches to prevent wrong content (e.g., American Dad vs The Office)
    // The zurg-client sometimes matches by year+episode pattern without verifying title
    if (result.matches && result.matches.length > 0) {
      result.matches = filterByTitle(result.matches, cleanTitle);

      // CRITICAL: Validate files are actually readable (not stale/expired from RD)
      // This prevents blank screen issues when Zurg cache has stale entries
      const validMatches = [];
      for (const match of result.matches) {
        const isReadable = await isZurgFileReadable(match.filePath);
        if (isReadable) {
          validMatches.push(match);
        } else {
          logger.warn(`Zurg: Filtered stale/unreadable file: ${match.filePath?.substring(0, 60)}`);
        }
      }
      result.matches = validMatches;

      if (result.matches.length === 0) {
        logger.warn(`Zurg: All matches filtered out (wrong title or stale)`);
        result.match = null;
        result.fallback = null;
      } else {
        // Update match/fallback based on filtered results
        result.match = result.matches.find(m => m.meetsQualityThreshold) || null;
        result.fallback = result.matches.find(m => !m.meetsQualityThreshold) || null;
      }
    }

    // Also verify single match/fallback (title + readability)
    if (result.match) {
      const matchOk = titleMatches(result.match.filePath || result.match.fileName, cleanTitle);
      const readable = matchOk && await isZurgFileReadable(result.match.filePath);
      if (!matchOk) {
        logger.warn(`Zurg: Match filtered out (wrong title): ${result.match.filePath?.substring(0, 60)}`);
        result.match = null;
      } else if (!readable) {
        logger.warn(`Zurg: Match filtered out (stale/unreadable): ${result.match.filePath?.substring(0, 60)}`);
        result.match = null;
      }
    }

    if (result.fallback) {
      const fallbackOk = titleMatches(result.fallback.filePath || result.fallback.fileName, cleanTitle);
      const readable = fallbackOk && await isZurgFileReadable(result.fallback.filePath);
      if (!fallbackOk) {
        logger.warn(`Zurg: Fallback filtered out (wrong title): ${result.fallback.filePath?.substring(0, 60)}`);
        result.fallback = null;
      } else if (!readable) {
        logger.warn(`Zurg: Fallback filtered out (stale/unreadable): ${result.fallback.filePath?.substring(0, 60)}`);
        result.fallback = null;
      }
    }

    if (result.match) {
      logger.info(`Zurg match found: ${result.match.filePath}`);
    } else if (result.fallback) {
      logger.warn(`Zurg: Only fallback available (low quality)`);
    } else {
      logger.info(`Zurg: No match found`);
    }

    return result;
  } catch (error) {
    logger.error('Zurg search error:', error);
    return { match: null, fallback: null, error: error.message };
  }
}

/**
 * Get Zurg search criteria (quality thresholds)
 */
function getSearchCriteria() {
  const { QUALITY_THRESHOLDS } = require('@duckflix/zurg-client');
  return {
    minMbPerMinute: QUALITY_THRESHOLDS.MIN_MB_PER_MINUTE,
    episodeDurations: QUALITY_THRESHOLDS.EPISODE_DURATION,
    movieDuration: QUALITY_THRESHOLDS.MOVIE_DURATION
  };
}

/**
 * Update Zurg search criteria
 */
function updateSearchCriteria(criteria) {
  // In the shared module, these are constants
  // For now, we'll just return the current criteria
  // In a future version, we could allow dynamic configuration
  logger.warn('Zurg criteria update not implemented (using defaults)');
  return getSearchCriteria();
}

module.exports = {
  searchZurg,
  getSearchCriteria,
  updateSearchCriteria,
  normalizeTitle,
  titleMatches
};
