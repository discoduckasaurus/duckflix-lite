const express = require('express');
const axios = require('axios');
const logger = require('../utils/logger');

const router = express.Router();

// Simple in-memory cache (1 hour for trending)
const cache = new Map();
const TRENDING_CACHE_TTL = 60 * 60 * 1000; // 1 hour

/**
 * GET /api/public/trending
 * Get TMDB trending content (NO AUTH REQUIRED)
 */
router.get('/trending', async (req, res) => {
  try {
    console.log('[PUBLIC TRENDING] Request received:', req.query);
    const { type = 'all', timeWindow = 'week' } = req.query;

    // Validate type parameter
    const validTypes = ['movie', 'tv', 'all'];
    if (!validTypes.includes(type)) {
      return res.status(400).json({
        error: 'Invalid type parameter. Must be one of: movie, tv, all'
      });
    }

    // Validate timeWindow parameter
    const validTimeWindows = ['week'];
    if (!validTimeWindows.includes(timeWindow)) {
      return res.status(400).json({
        error: 'Invalid timeWindow parameter. Must be: week'
      });
    }

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first (1 hour TTL)
    const cacheKey = `trending_${type}_${timeWindow}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    // Call TMDB trending API
    const url = `https://api.themoviedb.org/3/trending/${type}/${timeWindow}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey
      }
    });

    // Format results consistently with search endpoint
    const results = response.data.results
      .filter(item => {
        // Filter out garbage results: must have poster
        const hasPoster = item.poster_path && item.poster_path.trim().length > 0;

        // Filter out unrated (0 rating) - usually fake/placeholder entries
        const isRated = item.vote_average && item.vote_average > 0;

        // Optional: Also require at least 1 vote to avoid completely unknown entries
        const hasVotes = item.vote_count && item.vote_count > 0;

        return hasPoster && isRated && hasVotes;
      })
      .map(item => ({
        id: item.id,
        title: item.title || item.name,
        year: (item.release_date || item.first_air_date || '').substring(0, 4),
        posterPath: item.poster_path,
        overview: item.overview,
        voteAverage: item.vote_average,
        mediaType: item.media_type || type
      }));

    const responseData = { results };

    // Cache the result (1 hour)
    cache.set(cacheKey, {
      data: responseData,
      timestamp: Date.now()
    });

    res.json(responseData);
  } catch (error) {
    logger.error('TMDB trending error:', error);
    res.status(500).json({ error: 'Failed to fetch trending content' });
  }
});

module.exports = router;
