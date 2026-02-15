const axios = require('axios');
const { db } = require('../db/init');
const logger = require('../utils/logger');

// In-flight dedup: prevent concurrent scrapes for same tmdbId+mediaType
const inFlight = new Map();

// Global rate limit state
let rateLimitedUntil = 0;

const RT_BASE = 'https://www.rottentomatoes.com';

const rtAxios = axios.create({
  baseURL: RT_BASE,
  timeout: 8000,
  headers: {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9',
  },
  maxRedirects: 5,
  validateStatus: (status) => status < 500,
});

// --- Slug construction ---

function titleToSlug(title) {
  return title
    .toLowerCase()
    .replace(/&/g, 'and')
    .replace(/['']/g, '')         // strip apostrophes
    .replace(/[^a-z0-9]+/g, '_') // non-alphanum → underscore
    .replace(/^_+|_+$/g, '')     // trim leading/trailing underscores
    .replace(/_+/g, '_');         // collapse multiple underscores
}

// --- Score extraction from HTML ---

function extractScores(html) {
  const scores = { criticsScore: null, audienceScore: null, rtUrl: null };

  // Primary: embedded JSON data — "criticsScore":{"score":"96"...}
  const criticsMatch = html.match(/"criticsScore"\s*:\s*\{[^}]*"score"\s*:\s*"?(\d+)"?/);
  if (criticsMatch) {
    scores.criticsScore = parseInt(criticsMatch[1], 10);
  }

  const audienceMatch = html.match(/"audienceScore"\s*:\s*\{[^}]*"score"\s*:\s*"?(\d+)"?/);
  if (audienceMatch) {
    scores.audienceScore = parseInt(audienceMatch[1], 10);
  }

  // Fallback for critics: JSON-LD aggregateRating
  if (scores.criticsScore === null) {
    const jsonLdMatch = html.match(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/);
    if (jsonLdMatch) {
      try {
        const ld = JSON.parse(jsonLdMatch[1]);
        if (ld.aggregateRating && ld.aggregateRating.ratingValue) {
          scores.criticsScore = Math.round(parseFloat(ld.aggregateRating.ratingValue));
        }
      } catch (e) {
        // malformed JSON-LD, skip
      }
    }
  }

  // Extract canonical URL
  const canonicalMatch = html.match(/<link\s+rel="canonical"\s+href="([^"]+)"/);
  if (canonicalMatch) {
    scores.rtUrl = canonicalMatch[1];
  }

  return scores;
}

// --- Search fallback: parse RT search page for matching URL ---

function extractSearchResultUrl(html, title, year, mediaType) {
  // RT search results contain links like /m/slug or /tv/slug
  const prefix = mediaType === 'tv' ? '/tv/' : '/m/';
  const normalizedTitle = title.toLowerCase().replace(/[^a-z0-9]/g, '');

  // Find all search result URLs with their associated text
  const pattern = new RegExp(
    `<a[^>]+href="(${prefix.replace('/', '\\/')}[^"]+)"[^>]*>([\\s\\S]*?)<\\/a>`,
    'gi'
  );

  let match;
  const candidates = [];
  while ((match = pattern.exec(html)) !== null) {
    const url = match[1];
    const text = match[2].replace(/<[^>]+>/g, '').trim();
    candidates.push({ url, text });
  }

  // Also try finding search-page-media-row patterns (newer RT search layout)
  const rowPattern = /data-qa="search-page-media-row"[\s\S]*?href="([^"]+)"[\s\S]*?<\/a>/gi;
  while ((match = rowPattern.exec(html)) !== null) {
    candidates.push({ url: match[1], text: '' });
  }

  // Score candidates by title similarity
  for (const c of candidates) {
    if (!c.url.startsWith(prefix)) continue;
    const normalizedText = c.text.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (normalizedText.includes(normalizedTitle) || normalizedTitle.includes(normalizedText)) {
      return RT_BASE + c.url;
    }
  }

  // If no text match, return first result with correct prefix
  for (const c of candidates) {
    if (c.url.startsWith(prefix)) {
      return RT_BASE + c.url;
    }
  }

  return null;
}

// --- Cache helpers (lazy-init — table created in initDatabase() which runs before first request) ---

let _stmts = null;
function stmts() {
  if (!_stmts) {
    _stmts = {
      get: db.prepare(`
        SELECT * FROM rt_scores
        WHERE tmdb_id = ? AND media_type = ? AND expires_at > ?
      `),
      upsert: db.prepare(`
        INSERT INTO rt_scores (tmdb_id, media_type, title, rt_url, critics_score, audience_score, release_year, fetched_at, expires_at, not_found, created_at, updated_at)
        VALUES (@tmdb_id, @media_type, @title, @rt_url, @critics_score, @audience_score, @release_year, @fetched_at, @expires_at, @not_found, @now, @now)
        ON CONFLICT(tmdb_id, media_type) DO UPDATE SET
          title = @title,
          rt_url = @rt_url,
          critics_score = @critics_score,
          audience_score = @audience_score,
          release_year = @release_year,
          fetched_at = @fetched_at,
          expires_at = @expires_at,
          not_found = @not_found,
          updated_at = @now
      `),
      cleanup: db.prepare(`DELETE FROM rt_scores WHERE expires_at < ?`),
    };
  }
  return _stmts;
}

function getTtlMs(year) {
  const now = new Date();
  const currentYear = now.getFullYear();
  const titleAge = currentYear - (year || currentYear);

  if (titleAge >= 2) return 180 * 24 * 60 * 60 * 1000; // 180 days
  return 14 * 24 * 60 * 60 * 1000; // 14 days
}

const NOT_FOUND_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7 days

function getCached(tmdbId, mediaType) {
  const row = stmts().get.get(tmdbId, mediaType, Date.now());
  if (!row) return null;

  if (row.not_found) {
    return { available: false, criticsScore: null, audienceScore: null, rtUrl: null, cached: true };
  }

  return {
    tmdbId: row.tmdb_id,
    criticsScore: row.critics_score,
    audienceScore: row.audience_score,
    rtUrl: row.rt_url,
    cached: true,
    available: true,
  };
}

function cacheScores(tmdbId, mediaType, title, year, scores) {
  const now = Date.now();
  const notFound = scores.criticsScore === null && scores.audienceScore === null;
  const ttl = notFound ? NOT_FOUND_TTL_MS : getTtlMs(year);

  stmts().upsert.run({
    tmdb_id: tmdbId,
    media_type: mediaType,
    title,
    rt_url: scores.rtUrl || null,
    critics_score: scores.criticsScore,
    audience_score: scores.audienceScore,
    release_year: year || null,
    fetched_at: now,
    expires_at: now + ttl,
    not_found: notFound ? 1 : 0,
    now,
  });
}

// --- Core scraping logic ---

async function resolveAndExtract(title, year, mediaType) {
  if (Date.now() < rateLimitedUntil) {
    logger.warn('[RT] Global rate limit active, skipping scrape');
    return null;
  }

  const slug = titleToSlug(title);
  const pathPrefix = mediaType === 'tv' ? '/tv/' : '/m/';

  // Try year-qualified slug first (avoids returning wrong movie for remakes/reboots),
  // then fall back to bare slug if the year variant 404s
  const attempts = [];
  if (year) {
    attempts.push(pathPrefix + slug + '_' + year);
  }
  attempts.push(pathPrefix + slug);

  for (const urlPath of attempts) {
    try {
      const response = await rtAxios.get(urlPath);

      if (response.status === 429) {
        rateLimitedUntil = Date.now() + 60000;
        logger.warn('[RT] Rate limited, backing off 60s');
        return null;
      }

      if (response.status === 200 && typeof response.data === 'string') {
        const scores = extractScores(response.data);
        if (scores.criticsScore !== null || scores.audienceScore !== null) {
          if (!scores.rtUrl) scores.rtUrl = RT_BASE + urlPath;
          return scores;
        }
      }
    } catch (err) {
      if (err.code === 'ECONNABORTED') {
        logger.warn(`[RT] Timeout fetching ${urlPath}`);
      } else {
        logger.warn(`[RT] Error fetching ${urlPath}: ${err.message}`);
      }
    }
  }

  // Strategy 3: Search fallback
  try {
    const searchResponse = await rtAxios.get('/search', {
      params: { search: title },
    });

    if (searchResponse.status === 429) {
      rateLimitedUntil = Date.now() + 60000;
      logger.warn('[RT] Rate limited on search, backing off 60s');
      return null;
    }

    if (searchResponse.status === 200 && typeof searchResponse.data === 'string') {
      const resultUrl = extractSearchResultUrl(searchResponse.data, title, year, mediaType);
      if (resultUrl) {
        try {
          const pageResponse = await rtAxios.get(resultUrl.replace(RT_BASE, ''));

          if (pageResponse.status === 429) {
            rateLimitedUntil = Date.now() + 60000;
            return null;
          }

          if (pageResponse.status === 200 && typeof pageResponse.data === 'string') {
            const scores = extractScores(pageResponse.data);
            if (!scores.rtUrl) scores.rtUrl = resultUrl;
            return scores;
          }
        } catch (err) {
          logger.warn(`[RT] Error fetching search result page: ${err.message}`);
        }
      }
    }
  } catch (err) {
    logger.warn(`[RT] Search fallback failed: ${err.message}`);
  }

  // Nothing found — return empty scores (will be cached as not_found)
  return { criticsScore: null, audienceScore: null, rtUrl: null };
}

// --- Public API ---

async function getScores(tmdbId, mediaType, title, year) {
  // Check cache first
  const cached = getCached(tmdbId, mediaType);
  if (cached) return cached;

  // Dedup in-flight requests
  const key = `${tmdbId}:${mediaType}`;
  if (inFlight.has(key)) {
    return inFlight.get(key);
  }

  const promise = (async () => {
    try {
      const scores = await resolveAndExtract(title, year, mediaType);

      if (scores === null) {
        // RT down / rate limited — return nulls, don't cache
        return {
          tmdbId,
          criticsScore: null,
          audienceScore: null,
          rtUrl: null,
          cached: false,
          available: false,
        };
      }

      // Cache result (including not-found)
      cacheScores(tmdbId, mediaType, title, year, scores);

      const notFound = scores.criticsScore === null && scores.audienceScore === null;
      return {
        tmdbId,
        criticsScore: scores.criticsScore,
        audienceScore: scores.audienceScore,
        rtUrl: scores.rtUrl,
        cached: false,
        available: !notFound,
      };
    } catch (err) {
      logger.error(`[RT] Unexpected error for ${title} (${tmdbId}):`, err.message);
      return {
        tmdbId,
        criticsScore: null,
        audienceScore: null,
        rtUrl: null,
        cached: false,
        available: false,
      };
    } finally {
      inFlight.delete(key);
    }
  })();

  inFlight.set(key, promise);
  return promise;
}

function cleanupExpired() {
  const result = stmts().cleanup.run(Date.now());
  if (result.changes > 0) {
    logger.info(`[RT] Cleaned up ${result.changes} expired cache entries`);
  }
  return result.changes;
}

module.exports = { getScores, cleanupExpired };
