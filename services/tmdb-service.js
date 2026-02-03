const axios = require('axios');
const logger = require('../utils/logger');

const TMDB_API_KEY = process.env.TMDB_API_KEY;
const TMDB_BASE_URL = 'https://api.themoviedb.org/3';

// Simple in-memory cache for runtime (lasts until server restart)
const runtimeCache = new Map();

/**
 * Get runtime for a movie or TV episode
 * @param {number} tmdbId
 * @param {string} type - 'movie' or 'tv'
 * @param {number} [season] - For TV
 * @param {number} [episode] - For TV
 * @returns {Promise<number>} Runtime in minutes
 */
async function getRuntime(tmdbId, type, season, episode) {
  const cacheKey = `${tmdbId}-${type}-${season || 0}-${episode || 0}`;

  if (runtimeCache.has(cacheKey)) {
    return runtimeCache.get(cacheKey);
  }

  try {
    let runtime;

    if (type === 'movie') {
      const response = await axios.get(`${TMDB_BASE_URL}/movie/${tmdbId}`, {
        params: { api_key: TMDB_API_KEY }
      });
      runtime = response.data.runtime || 120; // Default 2 hours
    } else {
      // TV - try to get episode runtime
      if (season && episode) {
        try {
          const response = await axios.get(
            `${TMDB_BASE_URL}/tv/${tmdbId}/season/${season}/episode/${episode}`,
            { params: { api_key: TMDB_API_KEY } }
          );
          runtime = response.data.runtime || 45; // Default 45 min
        } catch (e) {
          // Fallback to show's average episode runtime
          const showResponse = await axios.get(`${TMDB_BASE_URL}/tv/${tmdbId}`, {
            params: { api_key: TMDB_API_KEY }
          });
          runtime = showResponse.data.episode_run_time?.[0] || 45;
        }
      } else {
        // No episode specified, get show's average
        const response = await axios.get(`${TMDB_BASE_URL}/tv/${tmdbId}`, {
          params: { api_key: TMDB_API_KEY }
        });
        runtime = response.data.episode_run_time?.[0] || 45;
      }
    }

    runtimeCache.set(cacheKey, runtime);
    logger.info(`[TMDB] Runtime for ${tmdbId} (${type}): ${runtime} min`);
    return runtime;
  } catch (error) {
    logger.warn(`[TMDB] Failed to get runtime for ${tmdbId}:`, error.message);
    // Return defaults
    return type === 'movie' ? 120 : 45;
  }
}

module.exports = {
  getRuntime
};
