/**
 * Content Resolver - Zurg/Prowlarr decision logic with RD instant availability
 *
 * Flow:
 * 1. Check Zurg with quality gating (>= 7 MB/min)
 * 2. If quality match: Use RD unrestrict link
 * 3. If no quality match: Search Prowlarr
 * 4. Check RD instant availability
 * 5. If cached: Stream immediately
 * 6. If not cached: Add to RD, track download progress
 */

const { searchZurg } = require('./zurg-search');
const { searchContent } = require('./prowlarr-service');
const { checkInstantAvailability } = require('@duckflix/rd-client');
const logger = require('../utils/logger');
const { getRuntime } = require('./tmdb-service');
const { mbPerMinToMbps } = require('../utils/bitrate');

// Quality thresholds (MB per minute)
const MIN_MB_PER_MINUTE = 7;

/**
 * Resolve content source (Zurg or Prowlarr)
 *
 * @param {string} rdApiKey - User's Real-Debrid API key
 * @returns {Object} {
 *   source: 'zurg' | 'prowlarr',
 *   magnetUrl: string (if prowlarr),
 *   zurgPath: string (if zurg),
 *   quality: object,
 *   isCached: boolean (if prowlarr)
 * }
 */
async function resolveContent({ title, year, type, season, episode, rdApiKey, tmdbId, maxBitrateMbps }) {

  // Step 1: Check Zurg with quality gating
  logger.info(`Resolving content: ${title} (${year || 'no year'})`);

  // Get runtime for bitrate calculations
  let runtimeMinutes;
  try {
    runtimeMinutes = await getRuntime(tmdbId, type, season, episode);
  } catch (e) {
    runtimeMinutes = type === 'movie' ? 120 : 45; // Defaults
  }

  const zurgResult = await searchZurg({
    title,
    year,
    type, // Pass 'tv' or 'movie' directly
    season,
    episode
  });

  // Filter Zurg results by bandwidth
  if (zurgResult.match && maxBitrateMbps) {
    const zurgBitrate = mbPerMinToMbps(zurgResult.match.mbPerMinute);
    if (zurgBitrate > maxBitrateMbps) {
      logger.info(`Zurg match ${zurgResult.match.quality} exceeds bandwidth (${zurgBitrate.toFixed(1)} > ${maxBitrateMbps.toFixed(1)} Mbps), checking alternatives`);
      // Move match to fallback if no fallback exists
      if (!zurgResult.fallback) {
        zurgResult.fallback = zurgResult.match;
      }
      zurgResult.match = null;
    }
  }

  // If Zurg has quality match, use it
  if (zurgResult.match) {
    logger.info(`✅ Zurg quality match: ${zurgResult.match.mbPerMinute} MB/min >= ${MIN_MB_PER_MINUTE}`);
    return {
      source: 'zurg',
      zurgPath: zurgResult.match.filePath,
      quality: {
        resolution: zurgResult.match.resolution || zurgResult.match.quality,
        sizeMB: zurgResult.match.sizeMB,
        mbPerMin: zurgResult.match.mbPerMinute
      },
      needsDownload: false
    };
  }

  // Store Zurg fallback (low quality) for comparison
  const zurgFallback = zurgResult.fallback;
  if (zurgFallback) {
    logger.info(`📋 Zurg fallback available: ${zurgFallback.mbPerMinuteute} MB/min (below threshold)`);
  }

  // Step 2: Search Prowlarr
  logger.info('Searching Prowlarr for alternatives...');

  let torrents;
  try {
    torrents = await searchContent({
      title,
      year,
      type,
      season,
      episode
    });
  } catch (err) {
    logger.warn('Prowlarr search failed:', err.message);

    // If Prowlarr fails and we have Zurg fallback, use it
    if (zurgFallback) {
      logger.info('📋 Using Zurg fallback (Prowlarr failed)');
      return {
        source: 'zurg',
        zurgPath: zurgFallback.filePath,
        quality: {
          resolution: zurgFallback.resolution,
          sizeMB: zurgFallback.sizeMB,
          mbPerMin: zurgFallback.mbPerMinute
        },
        needsDownload: false
      };
    }

    throw new Error('Content not available (Prowlarr search failed, no Zurg fallback)');
  }

  if (!torrents || torrents.length === 0) {
    // No Prowlarr results, use Zurg fallback if available
    if (zurgFallback) {
      logger.info('📋 Using Zurg fallback (Prowlarr found nothing)');
      return {
        source: 'zurg',
        zurgPath: zurgFallback.filePath,
        quality: {
          resolution: zurgFallback.resolution,
          sizeMB: zurgFallback.sizeMB,
          mbPerMin: zurgFallback.mbPerMinute
        },
        needsDownload: false
      };
    }

    throw new Error('Content not available (no sources found)');
  }

  // Step 3: Filter by year to avoid wrong versions (e.g. remakes)
  let filteredTorrents = torrents;
  if (year && type === 'movie') {
    // Filter torrents that match the expected year
    filteredTorrents = torrents.filter(t => {
      // Check if title contains the year
      const titleYear = t.title.match(/\b(19|20)\d{2}\b/)?.[0];
      if (titleYear && titleYear === year) {
        return true;
      }
      // If no year in title, keep it (might be correct)
      if (!titleYear) {
        return true;
      }
      return false;
    });

    if (filteredTorrents.length > 0) {
      logger.info(`Filtered ${torrents.length} torrents to ${filteredTorrents.length} matching year ${year}`);
    } else {
      // If all torrents were filtered out, keep original list
      logger.warn(`Year filter removed all torrents, using unfiltered list`);
      filteredTorrents = torrents;
    }
  }

  // Step 4: Check RD instant availability and rank torrents
  const { checkRDInstantAvailability, rankTorrents } = require('./torrent-ranker');

  logger.info(`Checking RD instant availability for ${filteredTorrents.length} torrents...`);
  const hashes = filteredTorrents.map(t => t.hash).filter(h => h);
  const cachedHashes = await checkRDInstantAvailability(hashes, rdApiKey);

  // Rank torrents (prioritize cached, then quality)
  const downloadType = type === 'tv' ? 'episode' : 'movie';
  const rankedTorrents = rankTorrents(
    filteredTorrents,  // torrents
    type,              // type ('tv' or 'movie')
    downloadType,      // downloadType ('episode' or 'movie')
    1,                 // episodeCount (single episode)
    cachedHashes,      // cachedHashes (Set from RD)
    season,            // season
    episode,           // episode
    [],                // excludedHashes
    // NEW: Pass bitrate constraints
    maxBitrateMbps ? { maxBitrateMbps, runtimeMinutes } : null
  );

  if (rankedTorrents.length === 0) {
    // Use Zurg fallback if available
    if (zurgFallback) {
      logger.info('📋 Using Zurg fallback (no suitable torrents)');
      return {
        source: 'zurg',
        zurgPath: zurgFallback.filePath,
        quality: {
          resolution: zurgFallback.resolution,
          sizeMB: zurgFallback.sizeMB,
          mbPerMin: zurgFallback.mbPerMinute
        },
        needsDownload: false
      };
    }

    throw new Error('No suitable torrents found');
  }

  const bestTorrent = rankedTorrents[0];
  const isCached = bestTorrent.isCached;

  // Step 4: Compare Prowlarr quality with Zurg fallback
  const prowlarrMbPerMin = bestTorrent.size ?
    (bestTorrent.size / (1024 * 1024 * 1024)) / (type === 'tv' ? 0.75 : 1.67) * 60 :
    0;

  if (zurgFallback && !isCached) {
    // If Prowlarr is not cached and not significantly better than Zurg, use Zurg
    if (prowlarrMbPerMin < zurgFallback.mbPerMinute * 1.5) {
      logger.info(`📋 Using Zurg fallback (Prowlarr ~${Math.round(prowlarrMbPerMin)} MB/min not much better than Zurg ${zurgFallback.mbPerMinute} MB/min, not cached)`);
      return {
        source: 'zurg',
        zurgPath: zurgFallback.filePath,
        quality: {
          resolution: zurgFallback.resolution,
          sizeMB: zurgFallback.sizeMB,
          mbPerMin: zurgFallback.mbPerMinute
        },
        needsDownload: false
      };
    }
  }

  // Step 5: Use Prowlarr result
  logger.info(`Using Prowlarr: ${bestTorrent.title} (${bestTorrent.seeders} seeders, ${bestTorrent.resolution}p, ${isCached ? 'CACHED' : 'NOT CACHED'})`);

  return {
    source: 'prowlarr',
    magnetUrl: bestTorrent.magnet,
    torrentHash: bestTorrent.hash,
    quality: {
      title: bestTorrent.title,
      size: bestTorrent.size,
      seeders: bestTorrent.seeders,
      resolution: bestTorrent.resolution
    },
    isCached,
    needsDownload: !isCached,
    estimatedBitrateMbps: bestTorrent.estimatedBitrateMbps
  };
}

module.exports = {
  resolveContent,
  MIN_MB_PER_MINUTE
};
