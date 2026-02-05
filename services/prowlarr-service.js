const axios = require('axios');
const crypto = require('crypto');
const logger = require('../utils/logger');

const PROWLARR_BASE_URL = process.env.PROWLARR_BASE_URL || 'http://localhost:9696';
const PROWLARR_API_KEY = process.env.PROWLARR_API_KEY;


// Blocklist for problematic release groups
const BLOCKLIST_GROUPS = ['YIFY', 'YTS', 'RARBG']; // Often blocked by RD

// Minimum seeders to consider a torrent viable
const MIN_SEEDERS = 5;

/**
 * Search Prowlarr with multiple queries and return ranked results
 */
async function searchTorrentsWithProwlarr(searchQuery) {
  if (!PROWLARR_API_KEY) {
    logger.warn('Prowlarr not configured');
    return [];
  }

  try {
    const response = await axios.get(`${PROWLARR_BASE_URL}/api/v1/search`, {
      params: {
        query: searchQuery,
        type: 'search'
      },
      headers: {
        'X-Api-Key': PROWLARR_API_KEY
      },
      timeout: 30000
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
    logger.error('Prowlarr search failed:', err.message);
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
async function searchContent({ title, year, type, season, episode }, onResults = null) {
  try {
    if (!PROWLARR_API_KEY) {
      throw new Error('Prowlarr API key not configured');
    }

    // Build multiple search queries (from main server logic)
    const searchQueries = [];

    if (type === 'tv') {
      const s = String(season).padStart(2, '0');
      const e = String(episode).padStart(2, '0');

      // Try specific episode first
      searchQueries.push(`${title} S${s}E${e}`);
      if (year) searchQueries.push(`${title} ${year} S${s}E${e}`);

      // Then try season pack
      searchQueries.push(`${title} S${s}`);
      if (year) searchQueries.push(`${title} ${year} S${s}`);

      // "Season X" naming variant
      searchQueries.push(`${title} Season ${season}`);
    } else {
      // Movie searches
      if (year) searchQueries.push(`${title} ${year}`);
      searchQueries.push(title);
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

    // Run all searches in parallel, but emit results as they arrive
    const searchPromises = searchQueries.map(query => {
      logger.info(`Searching: ${query}`);
      return searchTorrentsWithProwlarr(query)
        .then(results => {
          completedQueries++;
          const isComplete = completedQueries === searchQueries.length;
          if (results.length > 0) {
            logger.info(`✓ Found ${results.length} torrents for: ${query}`);
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
          logger.warn(`Search failed for ${query}: ${err.message}`);
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
