const axios = require('axios');
const logger = require('../utils/logger');

const PROWLARR_BASE_URL = process.env.PROWLARR_BASE_URL || 'http://localhost:9696';
const PROWLARR_API_KEY = process.env.PROWLARR_API_KEY;

// Blocklist for problematic release groups
const BLOCKLIST_GROUPS = ['YIFY', 'YTS', 'RARBG']; // Often blocked by RD

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
      .filter(r => r.seeders > 0)
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
        // Priority 3: Use downloadUrl (torrent file) - needs special handling
        else if (r.downloadUrl) {
          // Extract hash from GUID if available
          const guidMatch = (r.guid || '').match(/([a-fA-F0-9]{40})/);
          hash = guidMatch ? guidMatch[1] : null;

          // If we have a hash, create magnet. Otherwise skip this torrent.
          if (hash) {
            magnet = `magnet:?xt=urn:btih:${hash}&dn=${encodeURIComponent(r.title)}`;
          } else {
            logger.warn(`Skipping ${r.title} - downloadUrl but no hash in GUID`);
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
 */
async function searchContent({ title, year, type, season, episode }) {
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
    } else {
      // Movie searches
      if (year) searchQueries.push(`${title} ${year}`);
      searchQueries.push(title);
    }

    let allTorrents = [];

    // Try each search query
    for (const query of searchQueries) {
      logger.info(`Searching: ${query}`);
      const results = await searchTorrentsWithProwlarr(query);
      if (results.length > 0) {
        allTorrents = [...allTorrents, ...results];
        logger.info(`✓ Found ${results.length} torrents for: ${query}`);
      }
    }

    if (allTorrents.length === 0) {
      logger.info('No results found in Prowlarr');
      return [];
    }

    // Deduplicate by hash
    const seenHashes = new Set();
    allTorrents = allTorrents.filter(t => {
      if (!t.hash) return true;
      if (seenHashes.has(t.hash.toLowerCase())) return false;
      seenHashes.add(t.hash.toLowerCase());
      return true;
    });

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
