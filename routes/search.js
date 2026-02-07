const express = require('express');
const axios = require('axios');
const { authenticateToken } = require('../middleware/auth');
const { getSearchCriteria, updateSearchCriteria } = require('../services/zurg-search');
const { db } = require('../db/init');
const logger = require('../utils/logger');

const router = express.Router();

// Simple in-memory cache (5 minute TTL for search, 1 hour for trending)
const cache = new Map();
const CACHE_TTL = 5 * 60 * 1000;
const TRENDING_CACHE_TTL = 60 * 60 * 1000; // 1 hour

// Evict expired cache entries every 10 minutes to prevent unbounded growth
setInterval(() => {
  const now = Date.now();
  let evicted = 0;
  for (const [key, entry] of cache.entries()) {
    const ttl = key.startsWith('search_') || key.startsWith('discover_') || key.startsWith('now_playing') || key.startsWith('upcoming') || key.startsWith('airing_today') || key.startsWith('season_') || key.startsWith('details_')
      ? CACHE_TTL : TRENDING_CACHE_TTL;
    if (now - entry.timestamp > ttl) {
      cache.delete(key);
      evicted++;
    }
  }
  if (evicted > 0) logger.info(`[Cache] Evicted ${evicted} expired entries, ${cache.size} remaining`);
}, 10 * 60 * 1000);

// Timeout for all TMDB API calls — fail fast instead of hanging 90s on stalled connections
const TMDB_TIMEOUT = 10000;


router.get('/tmdb/trending', async (req, res) => {
  try {
  console.log("[TRENDING] Request received:", req.query);
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
      },
      timeout: TMDB_TIMEOUT
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

router.use(authenticateToken);

/**
 * Helper: Format TMDB results consistently
 */
function formatResults(results) {
  return results
    .filter(item => {
      const hasPoster = item.poster_path && item.poster_path.trim().length > 0;
      const isRated = item.vote_average && item.vote_average > 0;
      const hasVotes = item.vote_count && item.vote_count > 0;
      return hasPoster && isRated && hasVotes;
    })
    .map(item => ({
      id: item.id,
      title: item.title || item.name,
      year: (item.release_date || item.first_air_date || '').substring(0, 4),
      posterPath: item.poster_path,
      backdropPath: item.backdrop_path,
      overview: item.overview,
      voteAverage: item.vote_average,
      voteCount: item.vote_count,
      mediaType: item.media_type || (item.title ? 'movie' : 'tv')
    }));
}

/**
 * GET /api/search/collections/popular
 * Get popular movies and TV shows
 */
router.get('/collections/popular', async (req, res) => {
  try {
    const { type = 'movie', page = 1 } = req.query;
    const contentType = type === 'tv' ? 'tv' : 'movie';

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `popular_${contentType}_${page}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get(`https://api.themoviedb.org/3/${contentType}/popular`, {
      params: {
        api_key: tmdbApiKey,
        page
      },
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Popular collection error:', error);
    res.status(500).json({ error: 'Failed to fetch popular content' });
  }
});

/**
 * GET /api/search/collections/top-rated
 * Get top rated movies and TV shows
 */
router.get('/collections/top-rated', async (req, res) => {
  try {
    const { type = 'movie', page = 1 } = req.query;
    const contentType = type === 'tv' ? 'tv' : 'movie';

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `top_rated_${contentType}_${page}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get(`https://api.themoviedb.org/3/${contentType}/top_rated`, {
      params: {
        api_key: tmdbApiKey,
        page
      },
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Top rated collection error:', error);
    res.status(500).json({ error: 'Failed to fetch top rated content' });
  }
});

/**
 * GET /api/search/collections/now-playing
 * Get movies currently in theaters
 */
router.get('/collections/now-playing', async (req, res) => {
  try {
    const { page = 1 } = req.query;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `now_playing_${page}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get('https://api.themoviedb.org/3/movie/now_playing', {
      params: {
        api_key: tmdbApiKey,
        page
      },
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Now playing collection error:', error);
    res.status(500).json({ error: 'Failed to fetch now playing movies' });
  }
});

/**
 * GET /api/search/collections/upcoming
 * Get upcoming movies
 */
router.get('/collections/upcoming', async (req, res) => {
  try {
    const { page = 1 } = req.query;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `upcoming_${page}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get('https://api.themoviedb.org/3/movie/upcoming', {
      params: {
        api_key: tmdbApiKey,
        page
      },
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Upcoming collection error:', error);
    res.status(500).json({ error: 'Failed to fetch upcoming movies' });
  }
});

/**
 * GET /api/search/collections/airing-today
 * Get TV shows airing today
 */
router.get('/collections/airing-today', async (req, res) => {
  try {
    const { page = 1 } = req.query;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `airing_today_${page}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get('https://api.themoviedb.org/3/tv/airing_today', {
      params: {
        api_key: tmdbApiKey,
        page
      },
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Airing today collection error:', error);
    res.status(500).json({ error: 'Failed to fetch airing today shows' });
  }
});

/**
 * GET /api/search/collections/on-the-air
 * Get TV shows currently on the air
 */
router.get('/collections/on-the-air', async (req, res) => {
  try {
    const { page = 1 } = req.query;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `on_the_air_${page}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get('https://api.themoviedb.org/3/tv/on_the_air', {
      params: {
        api_key: tmdbApiKey,
        page
      },
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('On the air collection error:', error);
    res.status(500).json({ error: 'Failed to fetch on the air shows' });
  }
});

/**
 * GET /api/search/collections/discover
 * Advanced discover with filters
 * Supports: genre, year, rating, runtime, language, streaming provider, network, etc.
 */
router.get('/collections/discover', async (req, res) => {
  try {
    const {
      type = 'movie',
      page = 1,
      genre,
      year,
      minRating,
      maxRating,
      minRuntime,
      maxRuntime,
      language,
      sortBy = 'popularity.desc',
      watchProvider,
      network,
      watchRegion = 'US'
    } = req.query;

    const contentType = type === 'tv' ? 'tv' : 'movie';

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Build cache key from all params
    const cacheKey = `discover_${contentType}_${JSON.stringify(req.query)}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < CACHE_TTL) {
      return res.json(cached.data);
    }

    // Build query params
    const params = {
      api_key: tmdbApiKey,
      page,
      sort_by: sortBy
    };

    if (genre) params.with_genres = genre;
    if (year) {
      if (contentType === 'movie') {
        params.primary_release_year = year;
      } else {
        params.first_air_date_year = year;
      }
    }
    if (minRating) params['vote_average.gte'] = minRating;
    if (maxRating) params['vote_average.lte'] = maxRating;
    if (minRuntime) params['with_runtime.gte'] = minRuntime;
    if (maxRuntime) params['with_runtime.lte'] = maxRuntime;
    if (language) params.with_original_language = language;

    // Streaming provider filter (Netflix, HBO Max, Disney+, etc.)
    if (watchProvider) {
      params.with_watch_providers = watchProvider;
      params.watch_region = watchRegion;
    }

    // Network filter (for TV shows - HBO, AMC, Netflix Originals, etc.)
    if (network && contentType === 'tv') {
      params.with_networks = network;
    }

    const response = await axios.get(`https://api.themoviedb.org/3/discover/${contentType}`, {
      params,
      timeout: TMDB_TIMEOUT
    });

    const results = formatResults(response.data.results);
    const responseData = {
      results,
      page: response.data.page,
      totalPages: response.data.total_pages,
      totalResults: response.data.total_results,
      filters: {
        genre,
        year,
        minRating,
        maxRating,
        language,
        sortBy,
        watchProvider,
        network,
        watchRegion
      }
    };

    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Discover collection error:', error);
    res.status(500).json({ error: 'Failed to discover content' });
  }
});

/**
 * GET /api/search/collections/providers
 * Get list of streaming providers (Netflix, HBO Max, Disney+, etc.)
 */
router.get('/collections/providers', async (req, res) => {
  try {
    const { type = 'movie', region = 'US' } = req.query;
    const contentType = type === 'tv' ? 'tv' : 'movie';

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `providers_${contentType}_${region}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get(`https://api.themoviedb.org/3/watch/providers/${contentType}`, {
      params: {
        api_key: tmdbApiKey,
        watch_region: region
      },
      timeout: TMDB_TIMEOUT
    });

    // Format providers from TMDB
    const tmdbProviders = response.data.results
      .map(p => ({
        id: p.provider_id,
        name: p.provider_name,
        logo: p.logo_path ? `https://image.tmdb.org/t/p/w92${p.logo_path}` : null,
        priority: p.display_priority
      }));

    // Apply admin metadata: filter disabled, apply custom names/logos/sort order
    let metadata = [];
    try {
      metadata = db.prepare(`
        SELECT provider_id, custom_display_name, custom_logo_url, is_enabled, sort_order
        FROM provider_metadata
      `).all();
    } catch (e) {
      // Table may not exist yet on first run
    }
    const metaMap = new Map(metadata.map(m => [m.provider_id, m]));

    const providers = tmdbProviders
      .filter(p => {
        const meta = metaMap.get(p.id);
        // If no metadata row exists, provider is visible (backwards compatible)
        if (!meta) return true;
        return !!meta.is_enabled;
      })
      .map(p => {
        const meta = metaMap.get(p.id);
        return {
          id: p.id,
          name: meta?.custom_display_name || p.name,
          logo: meta?.custom_logo_url || p.logo,
          priority: meta?.sort_order != null && meta.sort_order !== 999 ? meta.sort_order : p.priority
        };
      })
      .sort((a, b) => a.priority - b.priority);

    const responseData = { providers, region };
    cache.set(cacheKey, { data: responseData, timestamp: Date.now() });
    res.json(responseData);
  } catch (error) {
    logger.error('Providers list error:', error);
    res.status(500).json({ error: 'Failed to fetch providers' });
  }
});

/**
 * GET /api/search/collections/networks
 * Get list of TV networks (HBO, AMC, Netflix, etc.)
 */
router.get('/collections/networks', async (req, res) => {
  try {
    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // TMDB doesn't have a networks list endpoint, so we provide common ones
    // These are the most popular TV networks/streaming originals
    const networks = [
      { id: 213, name: 'Netflix', logo: 'https://image.tmdb.org/t/p/w92/wwemzKWzjKYJFfCeiB57q3r4Bcm.png' },
      { id: 1024, name: 'Amazon', logo: 'https://image.tmdb.org/t/p/w92/ifhbNuuVnlwYy5oXA5VIb2YR8AZ.png' },
      { id: 2739, name: 'Disney+', logo: 'https://image.tmdb.org/t/p/w92/gJ8VX6JSu3ciXHuC2dDGAo2lvwM.png' },
      { id: 49, name: 'HBO', logo: 'https://image.tmdb.org/t/p/w92/tuomPhY2UtuPTqqFnKMVHvSb724.png' },
      { id: 3186, name: 'HBO Max', logo: 'https://image.tmdb.org/t/p/w92/aVkuqXFR1CQdkMkGximgKbQwINq.png' },
      { id: 2552, name: 'Apple TV+', logo: 'https://image.tmdb.org/t/p/w92/4KAy34EHvRM25Ih8wb82AuGU7zJ.png' },
      { id: 453, name: 'Hulu', logo: 'https://image.tmdb.org/t/p/w92/pqUTCleNUiTLAVlelGxUgWn1ELh.png' },
      { id: 2697, name: 'Peacock', logo: 'https://image.tmdb.org/t/p/w92/xTHltMrZPAJFLQ6qyCBjAnXSmZt.png' },
      { id: 4330, name: 'Paramount+', logo: 'https://image.tmdb.org/t/p/w92/xbhHHa1YgtpwhC8lb1NQ3ACVcLd.png' },
      { id: 174, name: 'AMC', logo: 'https://image.tmdb.org/t/p/w92/pmvRmATOCaDykE6JrVoeYxlFHw3.png' },
      { id: 19, name: 'FOX', logo: 'https://image.tmdb.org/t/p/w92/1DSpHrWyOORkL9N2QHX7Adt31mQ.png' },
      { id: 16, name: 'CBS', logo: 'https://image.tmdb.org/t/p/w92/nm8d7P7MJNiBLdgIzUK0gkuEA4r.png' },
      { id: 6, name: 'NBC', logo: 'https://image.tmdb.org/t/p/w92/o3OedEP0f9mfZr33jz2BfXOUK5.png' },
      { id: 2, name: 'ABC', logo: 'https://image.tmdb.org/t/p/w92/ndAvF4JLsliGreX87jAc9LdjmJY.png' },
      { id: 71, name: 'The CW', logo: 'https://image.tmdb.org/t/p/w92/ge9hzeaU7nMtQ4PjkFlc68dGAJ9.png' },
      { id: 67, name: 'Showtime', logo: 'https://image.tmdb.org/t/p/w92/Allse9kbjiP6ExaQrnSpIhkurEi.png' },
      { id: 318, name: 'Starz', logo: 'https://image.tmdb.org/t/p/w92/8GJjw3HHsAJYwIWKIPBPfqMxlEa.png' },
      { id: 77, name: 'Syfy', logo: 'https://image.tmdb.org/t/p/w92/wPvJzLaYRgOBu4VNWQFXSIFAMwm.png' },
      { id: 64, name: 'Discovery', logo: 'https://image.tmdb.org/t/p/w92/3vSOkjgntwMlpCCKe9EODxvKn7j.png' },
      { id: 4, name: 'BBC One', logo: 'https://image.tmdb.org/t/p/w92/mVn7xESaTNmjBUyUtGNvDQd3CT1.png' },
      { id: 493, name: 'BBC America', logo: 'https://image.tmdb.org/t/p/w92/teegNMEPYdHLBKRl2O2v6byjqn9.png' },
      { id: 1, name: 'Fuji TV', logo: 'https://image.tmdb.org/t/p/w92/yS5UJjsSdZXML0YikWTYYHLPKhQ.png' },
      { id: 614, name: 'Crunchyroll', logo: 'https://image.tmdb.org/t/p/w92/8Gt4xpeTJgkSWNQBEhWNxNFZ8Hc.png' }
    ];

    res.json({ networks });
  } catch (error) {
    logger.error('Networks list error:', error);
    res.status(500).json({ error: 'Failed to fetch networks' });
  }
});

/**
 * GET /api/search/collections/genres
 * Get list of all genres
 */
router.get('/collections/genres', async (req, res) => {
  try {
    const { type = 'movie' } = req.query;
    const contentType = type === 'tv' ? 'tv' : 'movie';

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    const cacheKey = `genres_${contentType}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    const response = await axios.get(`https://api.themoviedb.org/3/genre/${contentType}/list`, {
      params: {
        api_key: tmdbApiKey
      },
      timeout: TMDB_TIMEOUT
    });

    cache.set(cacheKey, { data: response.data, timestamp: Date.now() });
    res.json(response.data);
  } catch (error) {
    logger.error('Genres list error:', error);
    res.status(500).json({ error: 'Failed to fetch genres' });
  }
});

router.use(authenticateToken);

/**
 * Calculate relevance score for search result
 * Higher score = more relevant
 */
function calculateRelevanceScore(title, searchQuery) {
  const titleLower = title.toLowerCase().trim();
  const queryLower = searchQuery.toLowerCase().trim();

  // Exact match (case-insensitive) = highest score
  if (titleLower === queryLower) {
    return 1000;
  }

  // Starts with query = very high score
  if (titleLower.startsWith(queryLower)) {
    return 900;
  }

  // Contains exact query as substring = high score
  if (titleLower.includes(queryLower)) {
    return 800;
  }

  // All query words present in order = good score
  const queryWords = queryLower.split(/\s+/);
  const titleWords = titleLower.split(/\s+/);

  let allWordsPresent = true;
  let lastIndex = -1;
  for (const qWord of queryWords) {
    const foundIndex = titleWords.findIndex((tWord, idx) => idx > lastIndex && tWord.includes(qWord));
    if (foundIndex === -1) {
      allWordsPresent = false;
      break;
    }
    lastIndex = foundIndex;
  }

  if (allWordsPresent) {
    return 700;
  }

  // Count matching words (order doesn't matter)
  const matchingWords = queryWords.filter(qWord =>
    titleWords.some(tWord => tWord.includes(qWord) || qWord.includes(tWord))
  );

  const matchRatio = matchingWords.length / queryWords.length;

  // Partial word matches
  if (matchRatio > 0.5) {
    return 500 + (matchRatio * 200); // 500-700 range
  }

  if (matchRatio > 0) {
    return 300 + (matchRatio * 200); // 300-500 range
  }

  // No good matches
  return 0;
}

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
      },
      timeout: TMDB_TIMEOUT
    });

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
      .map(item => {
        const title = item.title || item.name;
        const relevanceScore = calculateRelevanceScore(title, query);

        return {
          id: item.id,
          title,
          year: (item.release_date || item.first_air_date || '').substring(0, 4),
          posterPath: item.poster_path,
          overview: item.overview,
          voteAverage: item.vote_average,
          mediaType: item.media_type || searchType,
          relevanceScore, // Add relevance score to each result
          // Popularity bonus: rating * reviews (helps surface well-known content)
          popularityBonus: Math.log10((item.vote_count || 1) + 1) * (item.vote_average || 0)
        };
      })
      .sort((a, b) => {
        // Primary sort: Relevance score
        const relevanceDiff = b.relevanceScore - a.relevanceScore;
        if (Math.abs(relevanceDiff) > 50) {
          return relevanceDiff;
        }

        // Secondary sort: Popularity (for items with similar relevance)
        const popularityDiff = b.popularityBonus - a.popularityBonus;
        if (Math.abs(popularityDiff) > 5) {
          return popularityDiff;
        }

        // Tertiary sort: Rating
        return (b.voteAverage || 0) - (a.voteAverage || 0);
      });

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
 * GET /api/search/tmdb/trending
 * Get TMDB trending content
 * Supports type: 'movie', 'tv', or 'all' (default: 'all')
 * Supports timeWindow: 'week' (7-day trending)
 */

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

    const url = `https://api.themoviedb.org/3/${contentType}/${id}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey,
        append_to_response: type === 'tv' ? 'credits,videos,external_ids,seasons,images' : 'credits,videos,external_ids,images',
        include_image_language: 'en,null' // Only English and language-neutral images
      },
      timeout: TMDB_TIMEOUT
    });

    const data = response.data;

    // Filter images for English/null only and pick best logo
    const englishLogos = data.images?.logos?.filter(img =>
      img.iso_639_1 === 'en' || img.iso_639_1 === null
    ) || [];
    const logoPath = englishLogos.length > 0 ? englishLogos[0].file_path : null;

    const result = {
      id: data.id,
      title: data.title || data.name,
      overview: data.overview,
      posterPath: data.poster_path,
      backdropPath: data.backdrop_path,
      logoPath: logoPath, // Add English logo
      releaseDate: data.release_date || data.first_air_date,
      voteAverage: data.vote_average,
      runtime: data.runtime || (data.episode_run_time && data.episode_run_time[0]),
      genres: data.genres,
      originalLanguage: data.original_language, // e.g., "en", "ja", "fr"
      spokenLanguages: data.spoken_languages?.map(lang => ({
        iso6391: lang.iso_639_1,
        name: lang.english_name
      })) || [],
      cast: data.credits?.cast?.slice(0, 10).map(c => ({
        id: c.id,
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
 * GET /api/search/person/:personId
 * Get person details (name, bio, profile picture)
 */
router.get('/person/:personId', async (req, res) => {
  try {
    const { personId } = req.params;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first
    const cacheKey = `person_${personId}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    // Fetch person details
    const url = `https://api.themoviedb.org/3/person/${personId}`;
    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey
      },
      timeout: TMDB_TIMEOUT
    });

    const data = response.data;
    const result = {
      id: data.id,
      name: data.name,
      biography: data.biography,
      birthday: data.birthday,
      placeOfBirth: data.place_of_birth,
      profilePath: data.profile_path,
      knownForDepartment: data.known_for_department
    };

    // Cache the result (1 hour)
    cache.set(cacheKey, {
      data: result,
      timestamp: Date.now()
    });

    res.json(result);
  } catch (error) {
    logger.error('Person details error:', error);
    res.status(500).json({ error: 'Failed to fetch person details' });
  }
});

/**
 * GET /api/search/person/:personId/credits
 * Get combined movie and TV credits for a person
 * Results ranked by combination of recency and rating
 */
router.get('/person/:personId/credits', async (req, res) => {
  try {
    const { personId } = req.params;

    const tmdbApiKey = process.env.TMDB_API_KEY;
    if (!tmdbApiKey) {
      return res.status(500).json({ error: 'TMDB API key not configured' });
    }

    // Check cache first (1 hour TTL for person credits)
    const cacheKey = `person_credits_${personId}`;
    const cached = cache.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < TRENDING_CACHE_TTL) {
      return res.json(cached.data);
    }

    // Fetch combined credits from TMDB
    const url = `https://api.themoviedb.org/3/person/${personId}/combined_credits`;
    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey
      },
      timeout: TMDB_TIMEOUT
    });

    const now = new Date();
    const currentYear = now.getFullYear();

    // Combine cast entries from movies and TV
    const allCredits = response.data.cast || [];

    // Filter and score credits
    const scoredCredits = allCredits
      .filter(item => {
        // Must have poster and rating
        const hasPoster = item.poster_path && item.poster_path.trim().length > 0;
        const isRated = item.vote_average && item.vote_average > 0;
        const hasVotes = item.vote_count && item.vote_count > 0;
        return hasPoster && isRated && hasVotes;
      })
      .map(item => {
        // Calculate recency score (0-100)
        const releaseYear = parseInt((item.release_date || item.first_air_date || '').substring(0, 4)) || 0;
        const yearsSinceRelease = currentYear - releaseYear;

        // Recency score: 100 for current year, decreases by 5 per year, minimum 0
        const recencyScore = Math.max(0, 100 - (yearsSinceRelease * 5));

        // Rating score (0-100): normalize vote_average from 0-10 to 0-100
        const ratingScore = (item.vote_average || 0) * 10;

        // Popularity bonus: log scale based on vote count
        const popularityBonus = Math.min(20, Math.log10((item.vote_count || 1) + 1) * 5);

        // Combined score: 40% recency, 40% rating, 20% popularity
        const combinedScore = (recencyScore * 0.4) + (ratingScore * 0.4) + (popularityBonus * 0.2);

        return {
          id: item.id,
          title: item.title || item.name,
          year: releaseYear || null,
          posterPath: item.poster_path,
          overview: item.overview,
          voteAverage: item.vote_average,
          voteCount: item.vote_count,
          mediaType: item.media_type,
          character: item.character,
          releaseDate: item.release_date || item.first_air_date,
          combinedScore,
          recencyScore,
          ratingScore
        };
      })
      // Sort by combined score (descending)
      .sort((a, b) => b.combinedScore - a.combinedScore);

    const responseData = {
      personId: parseInt(personId),
      personName: response.data.cast?.[0]?.name || 'Unknown', // TMDB doesn't return person name in credits endpoint
      results: scoredCredits
    };

    // Cache the result (1 hour)
    cache.set(cacheKey, {
      data: responseData,
      timestamp: Date.now()
    });

    res.json(responseData);
  } catch (error) {
    logger.error('Person credits error:', error);
    res.status(500).json({ error: 'Failed to fetch person credits' });
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

    const url = `https://api.themoviedb.org/3/tv/${showId}/season/${seasonNumber}`;

    const response = await axios.get(url, {
      params: {
        api_key: tmdbApiKey
      },
      timeout: TMDB_TIMEOUT
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
      .filter(r => r.seeders >= 5) // Only return torrents with at least 5 seeders
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
