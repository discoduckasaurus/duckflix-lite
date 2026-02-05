/**
 * Unified Source Resolver
 *
 * Queries Zurg AND Prowlarr in parallel, combines results,
 * ranks everything together, tries in order until success.
 */

const { searchZurg } = require('./zurg-search');
const { searchContent: searchProwlarr } = require('./prowlarr-service');
const { checkInstantAvailability } = require('@duckflix/rd-client');
const logger = require('../utils/logger');
const { estimateBitrateMbps, isValidSource, parseResolution } = require('../utils/bitrate');
const { getRuntime } = require('./tmdb-service');

/**
 * Search all sources in parallel and return unified ranked list
 * @param {string[]} excludedHashes - Hashes/identifiers to exclude (already tried)
 * @param {string[]} excludedFilePaths - Zurg file paths to exclude
 */
async function getAllSources({ title, year, type, season, episode, tmdbId, rdApiKey, maxBitrateMbps, excludedHashes = [], excludedFilePaths = [] }) {
  logger.info(`🔍 Searching ALL sources in parallel: ${title} (${year})`);

  if (excludedHashes.length > 0 || excludedFilePaths.length > 0) {
    logger.info(`   Excluding ${excludedHashes.length} hashes, ${excludedFilePaths.length} file paths`);
  }

  // Get runtime for bitrate calculations
  let runtimeMinutes;
  try {
    runtimeMinutes = await getRuntime(tmdbId, type, season, episode);
  } catch (e) {
    runtimeMinutes = type === 'movie' ? 120 : 45;
  }

  // Query Zurg and Prowlarr in parallel
  const [zurgResult, prowlarrTorrents] = await Promise.all([
    searchZurg({ title, year, type, season, episode, duration: runtimeMinutes }).catch(err => {
      logger.warn('Zurg search failed:', err.message);
      return { matches: [] };
    }),
    searchProwlarr({ title, year, type, season, episode }).catch(err => {
      logger.warn('Prowlarr search failed:', err.message);
      return [];
    })
  ]);

  const allSources = [];

  // Bad link tracker
  const { isBadLink } = require('./bad-link-tracker');

  // Add Zurg matches to unified list
  if (zurgResult.matches && zurgResult.matches.length > 0) {
    for (const zurgFile of zurgResult.matches) {
      // Skip excluded file paths
      if (excludedFilePaths.includes(zurgFile.filePath)) {
        logger.debug(`Excluded Zurg (already tried): ${zurgFile.fileName}`);
        continue;
      }

      // Check if flagged as bad
      const badInfo = isBadLink({ streamUrl: zurgFile.filePath });

      // Filter by bandwidth if specified
      if (maxBitrateMbps && zurgFile.mbPerMinute) {
        const zurgBitrate = zurgFile.mbPerMinute / 8; // rough conversion
        if (zurgBitrate > maxBitrateMbps) {
          logger.debug(`Filtered Zurg ${zurgFile.fileName}: ${zurgBitrate.toFixed(1)} > ${maxBitrateMbps.toFixed(1)} Mbps`);
          continue;
        }
      }

      // Filter garbage files
      const resolution = parseResolution(zurgFile.fileName);
      if (!isValidSource(zurgFile.sizeMB * 1024 * 1024, resolution, runtimeMinutes)) {
        logger.debug(`Filtered garbage Zurg: ${zurgFile.fileName}`);
        continue;
      }

      allSources.push({
        source: 'zurg',
        title: zurgFile.fileName,
        filePath: zurgFile.filePath,
        quality: zurgFile.quality || resolution + 'p',
        resolution,
        sizeMB: zurgFile.sizeMB,
        mbPerMinute: zurgFile.mbPerMinute,
        isCached: true, // Zurg files are always cached (they're in Zurg mount)
        meetsQualityThreshold: zurgFile.meetsQualityThreshold,
        isFlaggedBad: badInfo // Mark if flagged as bad
      });
    }
    logger.info(`✅ Zurg: ${allSources.length} sources added`);
  }

  // Check RD instant availability for Prowlarr torrents
  let cachedHashes = new Set();
  if (prowlarrTorrents.length > 0 && rdApiKey) {
    const hashes = prowlarrTorrents.map(t => t.hash).filter(h => h);
    if (hashes.length > 0) {
      try {
        cachedHashes = await checkInstantAvailability(hashes, rdApiKey);
        logger.info(`RD Cache: ${cachedHashes.size}/${hashes.length} Prowlarr torrents cached`);
      } catch (err) {
        logger.warn('RD availability check failed:', err.message);
      }
    }
  }

  // Add Prowlarr torrents to unified list
  for (const torrent of prowlarrTorrents) {
    // Skip excluded hashes
    if (torrent.hash && excludedHashes.includes(torrent.hash.toLowerCase())) {
      logger.debug(`Excluded Prowlarr (already tried): ${torrent.title}`);
      continue;
    }

    // Check if flagged as bad
    const badInfo = isBadLink({ hash: torrent.hash, magnetUrl: torrent.magnet });

    const isCached = torrent.hash && cachedHashes.has(torrent.hash.toLowerCase());
    const resolution = parseResolution(torrent.title);
    const sizeBytes = torrent.size || 0;

    // Filter by bandwidth if specified
    if (maxBitrateMbps && sizeBytes > 0 && runtimeMinutes) {
      const estimatedBitrate = estimateBitrateMbps(sizeBytes, runtimeMinutes);
      if (estimatedBitrate > maxBitrateMbps) {
        logger.debug(`Filtered Prowlarr ${torrent.title}: ${estimatedBitrate.toFixed(1)} > ${maxBitrateMbps.toFixed(1)} Mbps`);
        continue;
      }
    }

    // Filter garbage files
    if (!isValidSource(sizeBytes, resolution, runtimeMinutes)) {
      logger.debug(`Filtered garbage Prowlarr: ${torrent.title}`);
      continue;
    }

    // Filter by year for movies
    if (year && type === 'movie') {
      const titleYear = torrent.title.match(/\b(19|20)\d{2}\b/)?.[0];
      if (titleYear && titleYear !== String(year)) {
        logger.debug(`Filtered wrong year: ${torrent.title} (${titleYear} != ${year})`);
        continue;
      }
    }

    // Filter by episode for TV
    if (type === 'tv' && season && episode) {
      const s = String(season).padStart(2, '0');
      const e = String(episode).padStart(2, '0');
      const title = torrent.title.toLowerCase();

      // Check if this is the right episode or a valid season pack
      const episodePatterns = [
        new RegExp(`s${s}[\\._\\s]?e${e}(?:[^0-9]|$)`, 'i'),
        new RegExp(`\\b${season}x${e}\\b`, 'i'),
      ];
      const seasonPackPatterns = [
        new RegExp(`s${s}(?:[^e0-9]|$)`, 'i'),
        new RegExp(`season[\\._\\s]?${season}(?:[^0-9]|$)`, 'i')
      ];

      const matchesEpisode = episodePatterns.some(p => p.test(title));
      const isSeasonPack = seasonPackPatterns.some(p => p.test(title)) && !episodePatterns.some(p => p.test(title));

      // Check if it's a wrong specific episode
      const wrongEpisodePattern = /s(\d{2})[\._\s]?e(\d{2})/i;
      const match = title.match(wrongEpisodePattern);
      if (match) {
        const torrentSeason = parseInt(match[1], 10);
        const torrentEpisode = parseInt(match[2], 10);
        if (torrentSeason !== parseInt(season, 10) || torrentEpisode !== parseInt(episode, 10)) {
          logger.debug(`Filtered wrong episode: ${torrent.title}`);
          continue;
        }
      }

      if (!matchesEpisode && !isSeasonPack) {
        logger.debug(`Filtered non-matching: ${torrent.title}`);
        continue;
      }
    }

    allSources.push({
      source: 'prowlarr',
      title: torrent.title,
      magnet: torrent.magnet,
      hash: torrent.hash,
      quality: torrent.quality,
      resolution,
      sizeMB: sizeBytes / (1024 * 1024),
      seeders: torrent.seeders,
      isCached,
      isFlaggedBad: badInfo // Mark if flagged as bad
    });
  }

  logger.info(`✅ Prowlarr: ${prowlarrTorrents.length} torrents → ${allSources.filter(s => s.source === 'prowlarr').length} sources added after filtering`);
  logger.info(`📊 Total sources before ranking: ${allSources.length} (${allSources.filter(s => s.isCached).length} cached)`);

  // Rank all sources together
  const rankedSources = rankUnifiedSources(allSources, type);

  logger.info(`🏆 Ranked ${rankedSources.length} sources (top: ${rankedSources[0]?.title?.substring(0, 60)}...)`);

  return rankedSources;
}

/**
 * Unified ranking algorithm that treats cached sources equally
 */
function rankUnifiedSources(sources, type) {
  return sources
    .map((source) => {
      let score = 0;
      const title = (source.title || '').toLowerCase();
      const titleUpper = title.toUpperCase();

      // FLAGGED BAD - massive penalty (try last)
      if (source.isFlaggedBad) {
        score -= 100000; // Ensure these are tried last
      }

      // CACHED BONUS - same for Zurg and Prowlarr cached
      // Both are instant streaming from RD
      if (source.isCached) {
        score += 5000;
      }

      // Slight Zurg preference for reliability (only if not already massively boosted by cache)
      if (source.source === 'zurg') {
        score += 50; // Small boost
      }

      // Resolution scoring
      score += source.resolution || 0;

      // Seeders (only for Prowlarr)
      if (source.seeders) {
        score += Math.min(source.seeders, 500);
      }

      // Quality threshold for Zurg (7+ MB/min gets boost)
      if (source.meetsQualityThreshold) {
        score += 300;
      }

      // Subtitles - huge boost but no penalty
      const hasSubs = /\b(SUBS?|SUBTITLES?|SUBBED|MULTISUBS?|MULTI[\.\-_\s]?SUBS?)\b/.test(titleUpper);
      const hasEngSubs = /\b(ENG[\.\-_\s]?SUBS?|ENGLISH[\.\-_\s]?SUBS?|ENGSUB)\b/.test(titleUpper);
      if (hasSubs || hasEngSubs) {
        score += 500;
      }
      if (hasEngSubs) {
        score += 50;
      }

      // English audio
      const hasEnglish = /\b(ENG|ENGLISH|DUAL[\s\._-]?AUDIO|MULTI[\s\._-]?AUDIO)\b/.test(titleUpper);
      if (hasEnglish) {
        score += 50;
      }

      // Foreign language penalty (if no English indicator)
      const foreignLanguages = /\b(ITA|ITALIAN|FRE|FRENCH|GER|GERMAN|SPA|SPANISH|JPN|JAPANESE|KOR|KOREAN|RUS|RUSSIAN|CHI|CHINESE)\b/.test(titleUpper);
      if (foreignLanguages && !hasEnglish) {
        score -= 30;
      }

      // Size scoring (prefer files close to ideal size)
      const sizeGB = source.sizeMB / 1024;
      let idealSize = 0;
      if (type === 'tv') {
        if (source.resolution === 2160) idealSize = 3;
        else if (source.resolution === 1080) idealSize = 1.5;
        else if (source.resolution === 720) idealSize = 0.8;
        else idealSize = 0.5;
      } else {
        if (source.resolution === 2160) idealSize = 20;
        else if (source.resolution === 1080) idealSize = 6;
        else if (source.resolution === 720) idealSize = 2.5;
        else idealSize = 1.5;
      }

      if (idealSize > 0 && sizeGB > 0) {
        const sizeDiff = Math.abs(sizeGB - idealSize);
        score -= sizeDiff * 50;
      }

      return {
        ...source,
        score
      };
    })
    .sort((a, b) => b.score - a.score);
}

module.exports = {
  getAllSources
};
