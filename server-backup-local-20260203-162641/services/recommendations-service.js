const fs = require('fs');
const path = require('path');
const logger = require('../utils/logger');
const axios = require('axios');

const RECOMMENDATIONS_DIR = path.join(__dirname, '..', 'db', 'user_recommendations');
const TMDB_API_KEY = process.env.TMDB_API_KEY;
const TMDB_BASE_URL = 'https://api.themoviedb.org/3';

// Ensure recommendations directory exists
if (!fs.existsSync(RECOMMENDATIONS_DIR)) {
  fs.mkdirSync(RECOMMENDATIONS_DIR, { recursive: true });
  logger.info(`Created recommendations directory: ${RECOMMENDATIONS_DIR}`);
}

class RecommendationsService {
  /**
   * Get file path for user's recommendations
   */
  getUserRecommendationsPath(username) {
    return path.join(RECOMMENDATIONS_DIR, `${username}.json`);
  }

  /**
   * Load user's stored recommendations
   */
  loadUserRecommendations(username) {
    const filePath = this.getUserRecommendationsPath(username);

    if (!fs.existsSync(filePath)) {
      return {};
    }

    try {
      const data = fs.readFileSync(filePath, 'utf8');
      return JSON.parse(data);
    } catch (error) {
      logger.error(`Failed to load recommendations for ${username}:`, error);
      return {};
    }
  }

  /**
   * Save user's recommendations
   */
  saveUserRecommendations(username, recommendations) {
    const filePath = this.getUserRecommendationsPath(username);

    try {
      fs.writeFileSync(filePath, JSON.stringify(recommendations, null, 2), 'utf8');
      logger.info(`Saved recommendations for ${username} (${Object.keys(recommendations).length} items)`);
    } catch (error) {
      logger.error(`Failed to save recommendations for ${username}:`, error);
      throw error;
    }
  }

  /**
   * Fetch TMDB recommendations for a specific item
   */
  async fetchTmdbRecommendations(tmdbId, type, source) {
    if (!TMDB_API_KEY) {
      logger.warn('TMDB_API_KEY not configured, skipping recommendations fetch');
      return [];
    }

    const mediaType = type === 'movie' ? 'movie' : 'tv';
    const url = `${TMDB_BASE_URL}/${mediaType}/${tmdbId}/recommendations`;

    try {
      const response = await axios.get(url, {
        params: {
          api_key: TMDB_API_KEY,
          language: 'en-US',
          page: 1
        }
      });

      const results = response.data.results || [];

      // Return first 20 results with source tag
      return results.slice(0, 20).map(item => ({
        tmdbId: item.id,
        type: mediaType,
        title: item.title || item.name,
        source: source,
        posterPath: item.poster_path,
        releaseDate: item.release_date || item.first_air_date,
        voteAverage: item.vote_average
      }));
    } catch (error) {
      logger.error(`Failed to fetch TMDB recommendations for ${type} ${tmdbId}:`, error.message);
      return [];
    }
  }

  /**
   * Add item to user recommendations storage and fetch TMDB recommendations
   */
  async addItemRecommendations(username, itemId, tmdbId, type, title, source) {
    const recommendations = this.loadUserRecommendations(username);

    // Fetch TMDB recommendations
    const tmdbRecommendations = await this.fetchTmdbRecommendations(tmdbId, type, source);

    // Store item with its recommendations
    recommendations[itemId] = {
      tmdbId,
      type,
      title,
      source,
      recommendations: tmdbRecommendations,
      fetchedAt: new Date().toISOString()
    };

    this.saveUserRecommendations(username, recommendations);

    logger.info(`Added recommendations for ${username}: ${itemId} (${tmdbRecommendations.length} recommendations)`);
  }

  /**
   * Remove item recommendations
   */
  removeItemRecommendations(username, itemId) {
    const recommendations = this.loadUserRecommendations(username);

    if (recommendations[itemId]) {
      delete recommendations[itemId];
      this.saveUserRecommendations(username, recommendations);
      logger.info(`Removed recommendations for ${username}: ${itemId}`);
    }
  }

  /**
   * Check if item should be removed (not in watchlist AND not in continue watching)
   */
  shouldRemoveItem(itemId, watchlistIds, continueWatchingIds) {
    return !watchlistIds.includes(itemId) && !continueWatchingIds.includes(itemId);
  }

  /**
   * Aggregate and rank recommendations from all sources
   *
   * Algorithm:
   * 1. Collect all recommendations from all stored items
   * 2. Count occurrences of each recommended item
   * 3. Sort by count (descending)
   * 4. Within same-count groups, shuffle for variety
   * 5. Deduplicate against watchlist and continue watching
   * 6. Apply pagination
   */
  aggregateRecommendations(username, watchlistIds = [], continueWatchingIds = [], page = 1, limit = 20) {
    const recommendations = this.loadUserRecommendations(username);

    // Count occurrences of each recommendation
    const recommendationCounts = new Map();

    Object.values(recommendations).forEach(item => {
      (item.recommendations || []).forEach(rec => {
        const key = `${rec.type}_${rec.tmdbId}`;

        if (!recommendationCounts.has(key)) {
          recommendationCounts.set(key, {
            ...rec,
            count: 0
          });
        }

        const current = recommendationCounts.get(key);
        current.count += 1;
      });
    });

    // Convert to array and filter out items in watchlist or continue watching
    let aggregated = Array.from(recommendationCounts.values())
      .filter(rec => {
        const itemId = `${rec.type}_${rec.tmdbId}`;
        return !watchlistIds.includes(itemId) && !continueWatchingIds.includes(itemId);
      });

    // Sort by count (descending), then shuffle within same-count groups
    aggregated.sort((a, b) => {
      if (b.count !== a.count) {
        return b.count - a.count;
      }
      // Within same count, use deterministic but varied ordering based on tmdbId
      return (a.tmdbId % 100) - (b.tmdbId % 100);
    });

    // Calculate pagination
    const total = aggregated.length;
    const totalPages = Math.ceil(total / limit);
    const offset = (page - 1) * limit;
    const paginatedResults = aggregated.slice(offset, offset + limit);

    return {
      recommendations: paginatedResults.map(({ count, ...rec }) => rec), // Remove count from response
      total,
      page,
      totalPages,
      hasMore: page < totalPages
    };
  }

  /**
   * Get TMDB trending content
   */
  async getTrending(mediaType = 'all', timeWindow = 'week', page = 1) {
    if (!TMDB_API_KEY) {
      logger.warn('TMDB_API_KEY not configured, returning empty trending');
      return { results: [], page: 1, total_pages: 0, total_results: 0 };
    }

    const url = `${TMDB_BASE_URL}/trending/${mediaType}/${timeWindow}`;

    try {
      const response = await axios.get(url, {
        params: {
          api_key: TMDB_API_KEY,
          language: 'en-US',
          page
        }
      });

      return response.data;
    } catch (error) {
      logger.error(`Failed to fetch trending ${mediaType}:`, error.message);
      throw error;
    }
  }
}

module.exports = new RecommendationsService();
