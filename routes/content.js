const express = require('express');
const axios = require('axios');
const { authenticateToken } = require('../middleware/auth');
const logger = require('../utils/logger');

const router = express.Router();

/**
 * GET /api/content/random/episode/:tmdbId
 * Returns a random episode from a TV series (excluding Season 0)
 */
router.get('/random/episode/:tmdbId', authenticateToken, async (req, res) => {
  try {
    const { tmdbId } = req.params;
    const tmdbApiKey = process.env.TMDB_API_KEY;

    if (!tmdbApiKey) {
      logger.error('[Random Episode] TMDB API key not configured');
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    logger.info(`[Random Episode] Fetching series info for TMDB ID: ${tmdbId}`);

    // Fetch TV series details from TMDB
    const seriesUrl = `https://api.themoviedb.org/3/tv/${tmdbId}?api_key=${tmdbApiKey}`;
    const seriesResponse = await axios.get(seriesUrl, { timeout: 10000 });
    const seriesData = seriesResponse.data;

    // Filter out Season 0 (specials) and get all valid seasons
    const validSeasons = seriesData.seasons.filter(season => season.season_number > 0);

    if (validSeasons.length === 0) {
      logger.warn(`[Random Episode] No valid seasons found for TMDB ID: ${tmdbId}`);
      return res.status(404).json({ error: 'No valid seasons found for this series' });
    }

    // Count total episodes across all valid seasons
    const totalEpisodes = validSeasons.reduce((sum, season) => sum + season.episode_count, 0);

    if (totalEpisodes === 0) {
      logger.warn(`[Random Episode] No episodes found for TMDB ID: ${tmdbId}`);
      return res.status(404).json({ error: 'No episodes found for this series' });
    }

    // Pick a random episode number (1-based)
    const randomEpisodeNumber = Math.floor(Math.random() * totalEpisodes) + 1;

    // Map the random number to a specific season and episode
    let episodeCounter = 0;
    let selectedSeason = null;
    let selectedEpisodeNumber = null;

    for (const season of validSeasons) {
      if (episodeCounter + season.episode_count >= randomEpisodeNumber) {
        selectedSeason = season.season_number;
        selectedEpisodeNumber = randomEpisodeNumber - episodeCounter;
        break;
      }
      episodeCounter += season.episode_count;
    }

    // Fetch episode details to get the title
    const episodeUrl = `https://api.themoviedb.org/3/tv/${tmdbId}/season/${selectedSeason}/episode/${selectedEpisodeNumber}?api_key=${tmdbApiKey}`;
    const episodeResponse = await axios.get(episodeUrl, { timeout: 10000 });
    const episodeData = episodeResponse.data;

    const result = {
      season: selectedSeason,
      episode: selectedEpisodeNumber,
      title: episodeData.name
    };

    logger.info(`[Random Episode] Selected: S${selectedSeason}E${selectedEpisodeNumber} - ${episodeData.name}`);

    res.json(result);
  } catch (error) {
    if (error.response && error.response.status === 404) {
      logger.error('[Random Episode] Series not found:', error.message);
      return res.status(404).json({ error: 'Series not found' });
    }

    logger.error('[Random Episode] Error:', error.message);
    res.status(500).json({ error: 'Failed to get random episode' });
  }
});

/**
 * GET /api/content/recommendations/:tmdbId
 * Get movie recommendations for auto-play
 */
router.get('/recommendations/:tmdbId', authenticateToken, async (req, res) => {
  try {
    const { tmdbId } = req.params;

    if (!tmdbId) {
      return res.status(400).json({ error: 'Invalid parameters' });
    }

    logger.info(`Getting recommendations for TMDB ${tmdbId}`);

    // Get recommendations from TMDB
    const { getMovieRecommendations } = require('../services/tmdb-service');
    const recommendations = await getMovieRecommendations(parseInt(tmdbId));

    if (recommendations.length === 0) {
      return res.json({
        recommendations: [],
        count: 0
      });
    }

    // Optional: Quick availability check via Zurg search
    const { searchZurg } = require('../services/zurg-search');
    const recommendationsWithAvailability = await Promise.all(
      recommendations.map(async (movie) => {
        let available = false;
        try {
          const zurgResult = await searchZurg({
            title: movie.title,
            year: movie.year,
            type: 'movie'
          });
          available = !!zurgResult.match || !!zurgResult.fallback;
        } catch (err) {
          logger.debug(`Availability check failed for ${movie.title}:`, err.message);
        }

        return {
          ...movie,
          available
        };
      })
    );

    res.json({
      recommendations: recommendationsWithAvailability,
      count: recommendationsWithAvailability.length
    });
  } catch (error) {
    logger.error('Recommendations error:', error);
    res.status(500).json({
      error: 'Failed to get recommendations',
      message: error.message
    });
  }
});

module.exports = router;
