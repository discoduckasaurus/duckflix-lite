const axios = require('axios');
const crypto = require('crypto');
const logger = require('../utils/logger');

const http = require('http');
const https = require('https');

const PROWLARR_BASE_URL = process.env.PROWLARR_BASE_URL || 'http://localhost:9696';
const PROWLARR_API_KEY = process.env.PROWLARR_API_KEY;

// Inline concurrency limiter — caps concurrent Prowlarr API calls
function createLimiter(concurrency) {
  let active = 0;
  const queue = [];
  function next() {
    if (active >= concurrency || queue.length === 0) return;
    active++;
    const { fn, resolve, reject } = queue.shift();
    fn().then(resolve, reject).finally(() => { active--; next(); });
  }
  return function limit(fn) {
    return new Promise((resolve, reject) => {
      queue.push({ fn, resolve, reject });
      next();
    });
  };
}

const prowlarrLimiter = createLimiter(6);

// Connection-pooled axios instance for Prowlarr
const prowlarrAxios = axios.create({
  baseURL: PROWLARR_BASE_URL,
  headers: { 'X-Api-Key': PROWLARR_API_KEY },
  timeout: 45000,
  httpAgent: new http.Agent({ keepAlive: true, maxSockets: 10 }),
  httpsAgent: new https.Agent({ keepAlive: true, maxSockets: 10 }),
});

// Retry wrapper — matches opensubtitles-service.js pattern
async function prowlarrRequestWithRetry(config, retryCount = 0) {
  const maxRetries = 2;
  const retryDelay = Math.pow(2, retryCount) * 1000; // 1s, 2s

  try {
    return await prowlarrAxios(config);
  } catch (error) {
    const status = error.response?.status;
    const isRetryable = status === 502 || status === 503 ||
      error.code === 'ECONNABORTED' || error.code === 'ECONNRESET' || error.code === 'ETIMEDOUT';

    if (isRetryable && retryCount < maxRetries) {
      logger.warn(`Prowlarr ${status || error.code} error, retrying in ${retryDelay}ms (attempt ${retryCount + 1}/${maxRetries})`);
      await new Promise(resolve => setTimeout(resolve, retryDelay));
      return prowlarrRequestWithRetry(config, retryCount + 1);
    }
    throw error;
  }
}

// Blocklist for problematic release groups
const BLOCKLIST_GROUPS = ['YIFY', 'YTS', 'RARBG']; // Often blocked by RD

// Minimum seeders to consider a torrent viable
const MIN_SEEDERS = 5;

/**
 * Search Prowlarr with multiple queries and return ranked results
 */
async function searchTorrentsWithProwlarr(searchQuery, extraParams = {}) {
  if (!PROWLARR_API_KEY) {
    logger.warn('Prowlarr not configured');
    return [];
  }

  try {
    const response = await prowlarrRequestWithRetry({
      method: 'get',
      url: '/api/v1/search',
      params: {
        query: searchQuery,
        type: 'search',
        ...extraParams
      },
    });

    const results = response.data
      .filter(r => r.seeders >= MIN_SEEDERS)
      .filter(r => {
        const sizeGB = r.size / (1024 * 1024 * 1024);
        return sizeGB > 0.05 && sizeGB < 100;
      })
      // Filter out blocklisted groups
      .filter(r => {
        const title = r.title || '';
        return !BLOCKLIST_GROUPS.some(group => title.toUpperCase().includes(group));
      })
      .sort((a, b) => b.seeders - a.seeders)
      .slice(0, 50); // Return up to 50 results

    logger.info(`Found ${results.length} torrents for: ${searchQuery}`);

    // Process results - extract magnet links or use download URL
    const processed = results.map((r) => {
      try {
        let magnet = null;
        let hash = null;

        // Priority 1: Use magnetUrl if it exists
        if (r.magnetUrl && r.magnetUrl.startsWith('magnet:')) {
          magnet = r.magnetUrl;
          const hashMatch = magnet.match(/btih:([a-zA-Z0-9]{40})/i);
          hash = hashMatch ? hashMatch[1] : null;
        }
        // Priority 2: Create magnet from infoHash
        else if (r.infoHash) {
          hash = r.infoHash;
          magnet = `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(r.title)}`;
        }
        // Priority 3: Use downloadUrl (torrent file) - RD can handle these directly
        else if (r.downloadUrl) {
          // Try to extract hash from GUID first
          const guidMatch = (r.guid || '').match(/([a-fA-F0-9]{40})/);
          hash = guidMatch ? guidMatch[1] : null;

          if (hash) {
            // We have a hash, create magnet
            magnet = `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(r.title)}`;
          } else {
            // No hash available - skip this source entirely
            // downloadUrl-only sources often redirect to magnet: which rd-client can't handle
            // These caused "Unsupported protocol magnet:" errors
            logger.debug(`Skipping downloadUrl-only source (no hash): ${r.title.substring(0, 50)}...`);
            return null;
          }
        }
        // Skip - no way to get the torrent
        else {
          logger.warn(`Skipping ${r.title} - no magnet, infoHash, or downloadUrl`);
          return null;
        }

        if (!magnet || !hash) {
          return null;
        }

        return {
          title: r.title,
          magnet: magnet,
          hash: hash,
          size: r.size,
          seeders: r.seeders,
          quality: r.title.match(/\b(2160p|4K|1080p|720p)\b/i)?.[0] || 'unknown'
        };
      } catch (err) {
        logger.warn(`Failed to process ${r.title}: ${err.message}`);
        return null;
      }
    });

    return processed.filter(r => r !== null);

  } catch (err) {
    logger.error(`Prowlarr search failed: ${err.message || err.code || 'unknown error'} (${err.code || 'no code'})`);
    return [];
  }
}

/**
 * Search Prowlarr for content with multiple search strategies
 * Returns results progressively via onResults callback for faster time-to-first-source
 *
 * @param {Object} params - Search parameters
 * @param {Function} onResults - Callback called with new results as they arrive: (torrents, isComplete) => void
 * @returns {Promise<Array>} - All torrents found (for backwards compatibility)
 */
async function searchContent({ title, year, type, season, episode, tmdbId }, onResults = null) {
  try {
    if (!PROWLARR_API_KEY) {
      throw new Error('Prowlarr API key not configured');
    }

    // Clean title: decode URL-encoded + signs and strip problematic chars
    const cleanTitle = title
      .replace(/\+/g, ' ')
      .replace(/[!?]/g, '')
      .trim();

    // Build search queries — TMDB ID search first (precise), then text fallbacks
    // Each entry: { query, extraParams } — extraParams override the default type:'search'
    const searchQueries = [];

    if (tmdbId) {
      if (type === 'tv' && season && episode) {
        searchQueries.push({
          query: cleanTitle,
          extraParams: { type: 'tvsearch', tmdbId, season, ep: episode }
        });
      } else if (type === 'movie') {
        searchQueries.push({
          query: cleanTitle,
          extraParams: { type: 'movie', tmdbId }
        });
      }
    }

    if (type === 'tv') {
      const s = String(season).padStart(2, '0');
      const e = String(episode).padStart(2, '0');

      // Try specific episode first
      searchQueries.push({ query: `${cleanTitle} S${s}E${e}` });
      if (year) searchQueries.push({ query: `${cleanTitle} ${year} S${s}E${e}` });

      // Then try season pack
      searchQueries.push({ query: `${cleanTitle} S${s}` });
      if (year) searchQueries.push({ query: `${cleanTitle} ${year} S${s}` });

      // "Season X" naming variant
      searchQueries.push({ query: `${cleanTitle} Season ${season}` });
    } else {
      // Movie searches
      if (year) searchQueries.push({ query: `${cleanTitle} ${year}` });
      searchQueries.push({ query: cleanTitle });
    }

    const seenHashes = new Set();
    let allTorrents = [];
    let completedQueries = 0;

    // Helper to dedupe and add torrents
    const addTorrents = (torrents) => {
      const newTorrents = [];
      for (const t of torrents) {
        if (!t.hash) {
          newTorrents.push(t);
        } else if (!seenHashes.has(t.hash.toLowerCase())) {
          seenHashes.add(t.hash.toLowerCase());
          newTorrents.push(t);
        }
      }
      allTorrents = allTorrents.concat(newTorrents);
      return newTorrents;
    };

    // Run all searches throttled (max 6 concurrent), emit results as they arrive
    const searchPromises = searchQueries.map(({ query, extraParams }) => {
      const label = extraParams?.tmdbId ? `${query} (tmdbId:${extraParams.tmdbId})` : query;
      logger.info(`Queuing search: ${label}`);
      return prowlarrLimiter(() => searchTorrentsWithProwlarr(query, extraParams))
        .then(results => {
          completedQueries++;
          const isComplete = completedQueries === searchQueries.length;
          if (results.length > 0) {
            logger.info(`✓ Found ${results.length} torrents for: ${label}`);
            const newTorrents = addTorrents(results);
            // Emit new results if callback provided
            if (onResults && newTorrents.length > 0) {
              onResults(newTorrents, isComplete);
            } else if (onResults && isComplete) {
              // Results existed but all were duplicates — still signal completion
              onResults([], true);
            }
          } else if (onResults && isComplete) {
            // Last query completed with no results - signal completion
            onResults([], true);
          }
          return results;
        })
        .catch(err => {
          completedQueries++;
          logger.warn(`Search failed for ${label}: ${err.message}`);
          if (onResults && completedQueries === searchQueries.length) {
            onResults([], true);
          }
          return [];
        });
    });

    await Promise.all(searchPromises);

    if (allTorrents.length === 0) {
      logger.info('No results found in Prowlarr');
      return [];
    }

    logger.info(`Total: ${allTorrents.length} unique torrents found`);

    return allTorrents;

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
  searchContent,
  searchTorrentsWithProwlarr
};
