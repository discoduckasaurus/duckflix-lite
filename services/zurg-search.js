const { findInZurgMount } = require('@duckflix/zurg-client');
const logger = require('../utils/logger');

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

    logger.info(`Searching Zurg: ${title} (${year}) - ${type}`, {
      season,
      episode,
      duration
    });

    const result = await findInZurgMount({
      title,
      year,
      type,
      season,
      episode,
      episodeRuntime: duration
    });

    // CRITICAL: Verify title matches to prevent wrong content (e.g., American Dad vs The Office)
    // The zurg-client sometimes matches by year+episode pattern without verifying title
    if (result.matches && result.matches.length > 0) {
      result.matches = filterByTitle(result.matches, title);

      if (result.matches.length === 0) {
        logger.warn(`Zurg: All ${result.matches?.length || 0} matches filtered out (wrong title)`);
        result.match = null;
        result.fallback = null;
      } else {
        // Update match/fallback based on filtered results
        result.match = result.matches.find(m => m.meetsQualityThreshold) || null;
        result.fallback = result.matches.find(m => !m.meetsQualityThreshold) || null;
      }
    }

    // Also verify single match/fallback
    if (result.match && !titleMatches(result.match.filePath || result.match.fileName, title)) {
      logger.warn(`Zurg: Match filtered out (wrong title): ${result.match.filePath?.substring(0, 60)}`);
      result.match = null;
    }

    if (result.fallback && !titleMatches(result.fallback.filePath || result.fallback.fileName, title)) {
      logger.warn(`Zurg: Fallback filtered out (wrong title): ${result.fallback.filePath?.substring(0, 60)}`);
      result.fallback = null;
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
  updateSearchCriteria
};
