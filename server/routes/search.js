const express = require('express');
const axios = require('axios');
const { authenticateToken } = require('../middleware/auth');
const { getSearchCriteria, updateSearchCriteria } = require('../services/zurg-search');
const logger = require('../utils/logger');

const router = express.Router();

// Simple in-memory cache (5 minute TTL)
const cache = new Map();
const CACHE_TTL = 5 * 60 * 1000;

router.use(authenticateToken);

/**
 * GET /api/search/tmdb
 * Search TMDB for movies/TV shows
 * Supports type: 'movie', 'tv', or 'multi' (searches both)
 */
router.get('/tmdb', async (req, res) => {
  try {
    const { query, type = 'multi' } = req.query;

    if (!query) {
      return res.status(400).json({ error: 'Query parameter required' });
    }

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first
    const cacheKey = `search_${type}_${query}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    const searchType = type === 'tv' ? 'tv' : type === 'movie' ? 'movie' : 'multi';
    const url = `https://api.themoviedb.org/3/search/${searchType}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey,
        query,
        include_adult: false
      }
    });

    const results = response.data.results.map(item => ({
      id: item.id,
      title: item.title || item.name,
      year: (item.release_date || item.first_air_date || '').substring(0, 4),
      posterPath: item.poster_path,
      overview: item.overview,
      voteAverage: item.vote_average,
      mediaType: item.media_type || searchType // Include media type for multi-search
    }));

    const responseData = { results };

    // Cache the result
    cache.set(cacheKey, {
      data: responseData,
      timestamp: Date.now()
    });

    res.json(responseData);
  } catch (error) {
    logger.error('TMDB search error:', error);
    res.status(500).json({ error: 'Search failed' });
  }
});

/**
 * GET /api/search/tmdb/:id
 * Get TMDB content details
 */
router.get('/tmdb/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const { type } = req.query;

    if (!id) {
      return res.status(400).json({ error: 'ID parameter required' });
    }

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first
    const contentType = type === 'tv' ? 'tv' : 'movie';
    const cacheKey = `details_${contentType}_${id}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    // Add small delay to avoid rate limits
    await new Promise(resolve => setTimeout(resolve, 250));

    const url = `https://api.themoviedb.org/3/${contentType}/${id}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey,
        append_to_response: type === 'tv' ? 'credits,videos,external_ids,seasons' : 'credits,videos,external_ids'
      }
    });

    const data = response.data;
    const result = {
      id: data.id,
      title: data.title || data.name,
      overview: data.overview,
      posterPath: data.poster_path,
      backdropPath: data.backdrop_path,
      releaseDate: data.release_date || data.first_air_date,
      voteAverage: data.vote_average,
      runtime: data.runtime || (data.episode_run_time && data.episode_run_time[0]),
      genres: data.genres,
      cast: data.credits?.cast?.slice(0, 10).map(c => ({
        name: c.name,
        character: c.character,
        profilePath: c.profile_path
      })) || [],
      externalIds: data.external_ids,
      // TV show specific fields
      ...(type === 'tv' && {
        numberOfSeasons: data.number_of_seasons,
        seasons: data.seasons?.map(s => ({
          id: s.id,
          name: s.name,
          seasonNumber: s.season_number,
          posterPath: s.poster_path,
          episodeCount: s.episode_count,
          overview: s.overview
        }))
      })
    };

    // Cache the result
    cache.set(cacheKey, {
      data: result,
      timestamp: Date.now()
    });

    res.json(result);
  } catch (error) {
    logger.error('TMDB detail error:', error);
    res.status(500).json({ error: 'Failed to fetch details' });
  }
});

/**
 * GET /api/search/tmdb/season/:showId/:seasonNumber
 * Get TV season details (episodes list)
 */
router.get('/tmdb/season/:showId/:seasonNumber', async (req, res) => {
  try {
    const { showId, seasonNumber } = req.params;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first
    const cacheKey = `season_${showId}_${seasonNumber}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    // Add small delay to avoid rate limits
    await new Promise(resolve => setTimeout(resolve, 250));

    const url = `https://api.themoviedb.org/3/tv/${showId}/season/${seasonNumber}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey
      }
    });

    // Cache the result
    cache.set(cacheKey, {
      data: response.data,
      timestamp: Date.now()
    });

    res.json(response.data);
  } catch (error) {
    logger.error('TMDB season error:', error);
    res.status(500).json({ error: 'Failed to fetch season details' });
  }
});

/**
 * GET /api/search/zurg
 * Search Zurg for content
 */
router.get('/zurg', async (req, res) => {
  try {
    const { title, year, type, season, episode, duration } = req.query;

    if (!title) {
      return res.status(400).json({ error: 'Title parameter required' });
    }

    const { searchZurg } = require('../services/zurg-search');

    const result = await searchZurg({
      title,
      year,
      type,
      season: season ? parseInt(season, 10) : undefined,
      episode: episode ? parseInt(episode, 10) : undefined,
      duration: duration ? parseInt(duration, 10) : undefined
    });

    res.json(result);
  } catch (error) {
    logger.error('Zurg search error:', error);
    res.status(500).json({ error: 'Zurg search failed' });
  }
});

/**
 * GET /api/search/prowlarr
 * Search Prowlarr for torrents
 */
router.get('/prowlarr', async (req, res) => {
  try {
    const { query, year, type } = req.query;

    if (!query) {
      return res.status(400).json({ error: 'Query parameter required' });
    }

    const prowlarrBaseUrl = process.env.PROWLARR_BASE_URL;
    const prowlarrApiKey = process.env.PROWLARR_API_KEY;

    if (!prowlarrBaseUrl || !prowlarrApiKey) {
      return res.status(500).json({ error: 'Prowlarr not configured' });
    }

    // Build search query
    let searchQuery = query;
    if (year) {
      searchQuery += ` ${year}`;
    }

    // Search Prowlarr
    const searchUrl = `${prowlarrBaseUrl}/api/v1/search`;
    const response = await axios.get(searchUrl, {
      headers: {
        'X-Api-Key': prowlarrApiKey
      },
      params: {
        query: searchQuery,
        type: type === 'tv' ? 'tvsearch' : 'search'
      },
      timeout: 30000
    });

    // Format results
    const results = response.data
      .filter(r => r.seeders > 0) // Only return torrents with seeders
      .map(r => ({
        title: r.title,
        size: r.size,
        seeders: r.seeders,
        leechers: r.leechers || 0,
        magnetUrl: r.magnetUrl || r.downloadUrl,
        indexer: r.indexer,
        publishDate: r.publishDate,
        infoHash: r.infoHash
      }))
      .sort((a, b) => b.seeders - a.seeders) // Sort by seeders
      .slice(0, 20); // Limit to top 20

    res.json({ results });
  } catch (error) {
    logger.error('Prowlarr search error:', error);
    if (error.code === 'ECONNREFUSED') {
      return res.status(503).json({ error: 'Prowlarr service unavailable' });
    }
    res.status(500).json({ error: 'Prowlarr search failed' });
  }
});

/**
 * GET /api/search/zurg/criteria
 * Get Zurg search criteria (quality thresholds)
 */
router.get('/zurg/criteria', (req, res) => {
  try {
    const criteria = getSearchCriteria();
    res.json(criteria);
  } catch (error) {
    logger.error('Get criteria error:', error);
    res.status(500).json({ error: 'Failed to get criteria' });
  }
});

/**
 * PUT /api/search/zurg/criteria
 * Update Zurg search criteria (admin only)
 */
router.put('/zurg/criteria', (req, res) => {
  try {
    const criteria = updateSearchCriteria(req.body);
    res.json(criteria);
  } catch (error) {
    logger.error('Update criteria error:', error);
    res.status(500).json({ error: 'Failed to update criteria' });
  }
});

module.exports = router;
