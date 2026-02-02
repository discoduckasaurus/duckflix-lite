const { findInZurgMount } = require('@duckflix/zurg-client');
const logger = require('../utils/logger');

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
      type,
      year,
      season,
      episode,
      episodeRuntime: duration
    });

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
