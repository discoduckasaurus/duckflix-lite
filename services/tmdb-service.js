const axios = require('axios');
const logger = require('../utils/logger');

const TMDB_API_KEY = process.env.TMDB_API_KEY;
const TMDB_BASE_URL = 'https://api.themoviedb.org/3';
const TMDB_TIMEOUT = 10000;

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
        params: { api_key: TMDB_API_KEY },
        timeout: TMDB_TIMEOUT
      });
      runtime = response.data.runtime || 120; // Default 2 hours
    } else {
      // TV - try to get episode runtime
      if (season && episode) {
        try {
          const response = await axios.get(
            `${TMDB_BASE_URL}/tv/${tmdbId}/season/${season}/episode/${episode}`,
            { params: { api_key: TMDB_API_KEY }, timeout: TMDB_TIMEOUT }
          );
          runtime = response.data.runtime || 45; // Default 45 min
        } catch (e) {
          // Fallback to show's average episode runtime
          const showResponse = await axios.get(`${TMDB_BASE_URL}/tv/${tmdbId}`, {
            params: { api_key: TMDB_API_KEY },
            timeout: TMDB_TIMEOUT
          });
          runtime = showResponse.data.episode_run_time?.[0] || 45;
        }
      } else {
        // No episode specified, get show's average
        const response = await axios.get(`${TMDB_BASE_URL}/tv/${tmdbId}`, {
          params: { api_key: TMDB_API_KEY },
          timeout: TMDB_TIMEOUT
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

/**
 * Get next episode info for a TV series
 * @param {number} tmdbId
 * @param {number} currentSeason
 * @param {number} currentEpisode
 * @returns {Promise<Object|null>} Next episode info or null if no next episode
 */
async function getNextEpisode(tmdbId, currentSeason, currentEpisode) {
  try {
    // Get TV show info to know total seasons
    const showResponse = await axios.get(`${TMDB_BASE_URL}/tv/${tmdbId}`, {
      params: { api_key: TMDB_API_KEY },
      timeout: TMDB_TIMEOUT
    });

    const totalSeasons = showResponse.data.number_of_seasons;

    // Get current season info
    const seasonResponse = await axios.get(
      `${TMDB_BASE_URL}/tv/${tmdbId}/season/${currentSeason}`,
      { params: { api_key: TMDB_API_KEY }, timeout: TMDB_TIMEOUT }
    );

    const episodes = seasonResponse.data.episodes;
    const currentEpisodeIndex = episodes.findIndex(ep => ep.episode_number === currentEpisode);

    // Check if there's a next episode in current season
    if (currentEpisodeIndex !== -1 && currentEpisodeIndex < episodes.length - 1) {
      const nextEp = episodes[currentEpisodeIndex + 1];
      return {
        season: currentSeason,
        episode: nextEp.episode_number,
        title: nextEp.name,
        overview: nextEp.overview
      };
    }

    // If last episode of season, check for next season
    if (currentSeason < totalSeasons) {
      try {
        const nextSeasonResponse = await axios.get(
          `${TMDB_BASE_URL}/tv/${tmdbId}/season/${currentSeason + 1}`,
          { params: { api_key: TMDB_API_KEY }, timeout: TMDB_TIMEOUT }
        );

        const firstEp = nextSeasonResponse.data.episodes[0];
        if (firstEp) {
          return {
            season: currentSeason + 1,
            episode: firstEp.episode_number,
            title: firstEp.name,
            overview: firstEp.overview
          };
        }
      } catch (e) {
        logger.warn(`[TMDB] Failed to get next season info:`, e.message);
      }
    }

    // No next episode (series finale)
    return null;
  } catch (error) {
    logger.warn(`[TMDB] Failed to get next episode for ${tmdbId} S${currentSeason}E${currentEpisode}:`, error.message);
    return null;
  }
}

/**
 * Get movie recommendations from TMDB
 * @param {number} tmdbId
 * @returns {Promise<Array>} Recommendations list
 */
async function getMovieRecommendations(tmdbId) {
  try {
    const response = await axios.get(
      `${TMDB_BASE_URL}/movie/${tmdbId}/recommendations`,
      {
        params: {
          api_key: TMDB_API_KEY,
          page: 1
        },
        timeout: TMDB_TIMEOUT
      }
    );

    // Return top 5 recommendations with relevant data
    return response.data.results.slice(0, 5).map(movie => ({
      tmdbId: movie.id,
      title: movie.title,
      year: movie.release_date ? movie.release_date.substring(0, 4) : null,
      posterPath: movie.poster_path ? `https://image.tmdb.org/t/p/w500${movie.poster_path}` : null,
      overview: movie.overview
    }));
  } catch (error) {
    logger.warn(`[TMDB] Failed to get recommendations for ${tmdbId}:`, error.message);
    return [];
  }
}

module.exports = {
  getRuntime,
  getNextEpisode,
  getMovieRecommendations
};
