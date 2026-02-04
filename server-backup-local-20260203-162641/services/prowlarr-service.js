const axios = require('axios');
const logger = require('../utils/logger');

const PROWLARR_BASE_URL = process.env.PROWLARR_BASE_URL || 'http://localhost:9696';
const PROWLARR_API_KEY = process.env.PROWLARR_API_KEY;

/**
 * Search Prowlarr for content
 *
 * @param {Object} params - Search parameters
 * @param {string} params.title - Content title
 * @param {string} params.year - Release year
 * @param {string} params.type - Content type ('movie' or 'tv')
 * @param {number} [params.season] - Season number (for TV)
 * @param {number} [params.episode] - Episode number (for TV)
 * @returns {Promise<Object>} Best match with magnet link
 */
async function searchContent({ title, year, type, season, episode }) {
  try {
    if (!PROWLARR_API_KEY) {
      throw new Error('Prowlarr API key not configured');
    }

    // Build search query
    let query = title;
    if (year) query += ` ${year}`;
    if (type === 'tv' && season && episode) {
      query += ` S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')}`;
    }

    logger.info(`Searching Prowlarr: ${query}`);

    const response = await axios.get(`${PROWLARR_BASE_URL}/api/v1/search`, {
      params: {
        query,
        type: type === 'movie' ? 'movie' : 'tvsearch'
      },
      headers: {
        'X-Api-Key': PROWLARR_API_KEY
      },
      timeout: 30000
    });

    const results = response.data;

    if (!results || results.length === 0) {
      logger.info('No results found in Prowlarr');
      return null;
    }

    // Sort by seeders (prefer well-seeded content)
    results.sort((a, b) => (b.seeders || 0) - (a.seeders || 0));

    // Filter for quality (prefer results with reasonable file size)
    const qualityResults = results.filter(r => {
      const sizeGB = (r.size || 0) / (1024 * 1024 * 1024);
      // For movies: prefer 1-20GB, for episodes: prefer 0.2-5GB
      if (type === 'movie') {
        return sizeGB >= 1 && sizeGB <= 20 && (r.seeders || 0) >= 5;
      } else {
        return sizeGB >= 0.2 && sizeGB <= 5 && (r.seeders || 0) >= 5;
      }
    });

    const bestResult = qualityResults[0] || results[0];

    logger.info(`Found content: ${bestResult.title} (${Math.round((bestResult.size || 0) / (1024 * 1024 * 1024) * 10) / 10}GB, ${bestResult.seeders || 0} seeders)`);

    return {
      title: bestResult.title,
      magnetUrl: bestResult.magnetUrl || bestResult.downloadUrl,
      size: bestResult.size,
      seeders: bestResult.seeders,
      indexer: bestResult.indexer
    };
  } catch (error) {
    logger.error('Prowlarr search error:', {
      message: error.message,
      code: error.code,
      status: error.response?.status
    });
    throw error;
  }
}

module.exports = {
  searchContent
};
