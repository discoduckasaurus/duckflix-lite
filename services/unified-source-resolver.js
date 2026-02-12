/**
 * Unified Source Resolver
 *
 * Queries Zurg AND Prowlarr in parallel, combines results,
 * ranks everything together, tries in order until success.
 *
 * Supports streaming mode: returns sources progressively as they arrive
 * to reduce time-to-first-playback.
 */

const { searchZurg, normalizeTitle, titleMatches } = require('./zurg-search');
const { searchContent: searchProwlarr } = require('./prowlarr-service');
const logger = require('../utils/logger');
const { estimateBitrateMbps, isValidSource, parseResolution } = require('../utils/bitrate');
const { getRuntime } = require('./tmdb-service');

// Minimum resolution to start trying before all searches complete
const MIN_EARLY_RESOLUTION = 720;

/**
 * Search all sources in parallel and return unified ranked list
 * @param {string[]} excludedHashes - Hashes/identifiers to exclude (already tried)
 * @param {string[]} excludedFilePaths - Zurg file paths to exclude
 * @param {Function} onSourcesReady - Optional callback for streaming mode: (sources, isComplete) => void
 */
async function getAllSources({ title, year, type, season, episode, tmdbId, rdApiKey, maxBitrateMbps, excludedHashes = [], excludedFilePaths = [], platform }, onSourcesReady = null) {
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

  const allSources = [];
  let prowlarrComplete = false;

  // Helper to process and filter Prowlarr torrents
  const processProwlarrTorrents = (torrents) => {
    const { isBadLink } = require('./bad-link-tracker');
    const sources = [];
    const normalizedExpected = normalizeTitle(title);
    const expectedWords = normalizedExpected.split(' ').filter(w => w.length > 2);
    const shouldCheckTitle = expectedWords.length >= 2;

    for (const torrent of torrents) {
      // Skip excluded hashes
      if (torrent.hash && excludedHashes.includes(torrent.hash.toLowerCase())) {
        continue;
      }

      // Check if flagged as bad
      const badInfo = isBadLink({ hash: torrent.hash, magnetUrl: torrent.magnet });

      const resolution = parseResolution(torrent.title);
      const sizeBytes = torrent.size || 0;

      // Check bandwidth — mark over-bandwidth sources as fallback instead of dropping
      let overBandwidth = false;
      let sourceBitrateMbps = null;
      if (sizeBytes > 0 && runtimeMinutes) {
        sourceBitrateMbps = estimateBitrateMbps(sizeBytes, runtimeMinutes);
        if (maxBitrateMbps && sourceBitrateMbps > maxBitrateMbps) {
          overBandwidth = true;
        }
      }

      // Filter garbage files
      if (!isValidSource(sizeBytes, resolution, runtimeMinutes)) {
        continue;
      }

      // Filter by year for movies
      if (year && type === 'movie') {
        const titleYear = torrent.title.match(/\b(19|20)\d{2}\b/)?.[0];
        if (titleYear && titleYear !== String(year)) {
          continue;
        }
      }

      // For movies: reject torrents that look like TV episodes (S##E##)
      if (type === 'movie') {
        const tvEpisodePattern = /s\d{1,2}[\._\s]?e\d{1,2}/i;
        if (tvEpisodePattern.test(torrent.title)) {
          continue;
        }
      }

      // Title matching: reject torrents that don't match the requested title
      // (same 70% word-match logic used for Zurg results)
      if (shouldCheckTitle && !titleMatches(torrent.title, title)) {
        continue;
      }

      // Filter by episode for TV
      if (type === 'tv' && season && episode) {
        const s = String(season).padStart(2, '0');
        const e = String(episode).padStart(2, '0');
        const titleLower = torrent.title.toLowerCase();

        const episodePatterns = [
          new RegExp(`s${s}[\\._\\s]?e${e}(?:[^0-9]|$)`, 'i'),
          new RegExp(`\\b${season}x${e}\\b`, 'i'),
        ];
        const seasonPackPatterns = [
          new RegExp(`s${s}(?:[^e0-9]|$)`, 'i'),
          new RegExp(`season[\\._\\s]?${season}(?:[^0-9]|$)`, 'i')
        ];

        const matchesEpisode = episodePatterns.some(p => p.test(titleLower));
        const isSeasonPack = seasonPackPatterns.some(p => p.test(titleLower)) && !episodePatterns.some(p => p.test(titleLower));

        // Check if it's a wrong specific episode
        const wrongEpisodePattern = /s(\d{2})[\._\s]?e(\d{2})/i;
        const match = titleLower.match(wrongEpisodePattern);
        if (match) {
          const torrentSeason = parseInt(match[1], 10);
          const torrentEpisode = parseInt(match[2], 10);
          if (torrentSeason !== parseInt(season, 10) || torrentEpisode !== parseInt(episode, 10)) {
            continue;
          }
        }

        if (!matchesEpisode && !isSeasonPack) {
          continue;
        }
      }

      sources.push({
        source: 'prowlarr',
        title: torrent.title,
        magnet: torrent.magnet,
        hash: torrent.hash,
        quality: torrent.quality,
        resolution,
        sizeMB: sizeBytes / (1024 * 1024),
        seeders: torrent.seeders,
        isCached: false,
        isFlaggedBad: badInfo,
        overBandwidth,
        estimatedBitrateMbps: sourceBitrateMbps
      });
    }

    return sources;
  };

  // Helper to emit sources (for streaming mode)
  const emitSources = (isComplete) => {
    if (!onSourcesReady) return;

    const ranked = rankUnifiedSources(allSources, type, platform);
    logger.info(`📤 Emitting ${ranked.length} sources (complete: ${isComplete})`);
    onSourcesReady(ranked, isComplete);
  };

  // Start Zurg search (must complete - it's fast and gives cached results)
  const zurgPromise = searchZurg({ title, year, type, season, episode, duration: runtimeMinutes }).catch(err => {
    logger.warn('Zurg search failed:', err.message);
    return { matches: [] };
  });

  // Start Prowlarr search with streaming callback
  const prowlarrPromise = new Promise((resolve) => {
    searchProwlarr({ title, year, type, season, episode, tmdbId }, (newTorrents, isComplete) => {
      // Process new torrents
      const newSources = processProwlarrTorrents(newTorrents);
      if (newSources.length > 0) {
        allSources.push(...newSources);
        logger.info(`   +${newSources.length} Prowlarr sources (total: ${allSources.length})`);
      }

      if (isComplete) {
        prowlarrComplete = true;
        emitSources(true);
        resolve();
      } else if (onSourcesReady && newSources.length > 0) {
        // Emit immediately as sources arrive — vod.js re-sorts the queue on each batch
        emitSources(false);
      }
    }).catch(err => {
      logger.warn('Prowlarr search failed:', err.message);
      prowlarrComplete = true;
      resolve();
    });
  });

  // Wait for Zurg first (fast, ~1s)
  const zurgResult = await zurgPromise;

  // Bad link tracker
  const { isBadLink } = require('./bad-link-tracker');

  // Add Zurg matches to unified list (these go first - they're cached)
  if (zurgResult.matches && zurgResult.matches.length > 0) {
    for (const zurgFile of zurgResult.matches) {
      // Skip excluded file paths
      if (excludedFilePaths.includes(zurgFile.filePath)) {
        logger.debug(`Excluded Zurg (already tried): ${zurgFile.fileName}`);
        continue;
      }

      // Check if flagged as bad
      const badInfo = isBadLink({ streamUrl: zurgFile.filePath });

      // Check bandwidth — mark over-bandwidth sources as fallback instead of dropping
      let overBandwidth = false;
      const sourceBitrateMbps = zurgFile.mbPerMinute ? (zurgFile.mbPerMinute * 8) / 60 : null;
      if (maxBitrateMbps && sourceBitrateMbps && sourceBitrateMbps > maxBitrateMbps) {
        overBandwidth = true;
        logger.info(`Over-bandwidth Zurg: ${zurgFile.fileName} (${sourceBitrateMbps.toFixed(1)} > ${maxBitrateMbps.toFixed(1)} Mbps, will try as fallback)`);
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
        isFlaggedBad: badInfo,
        overBandwidth,
        estimatedBitrateMbps: sourceBitrateMbps
      });
    }
    logger.info(`✅ Zurg: ${allSources.filter(s => s.source === 'zurg').length} sources added`);
  }

  // In streaming mode with Zurg results, emit them immediately (they're instant)
  if (onSourcesReady && allSources.length > 0) {
    logger.info(`⚡ Immediate emit: ${allSources.length} Zurg sources`);
    emitSources(false);
  }

  // Wait for Prowlarr to complete (or timeout if in streaming mode)
  if (!onSourcesReady) {
    // Non-streaming mode: wait for everything
    await prowlarrPromise;
  } else {
    // Streaming mode: don't block - prowlarrPromise will emit via callback
    // Just wait a bit to give it a chance to get some results
    await Promise.race([
      prowlarrPromise,
      new Promise(resolve => setTimeout(resolve, 45000)) // Max 45s wait (matches Prowlarr timeout)
    ]);
  }

  const zurgCount = allSources.filter(s => s.source === 'zurg').length;
  const prowlarrCount = allSources.filter(s => s.source === 'prowlarr').length;
  const overBwCount = allSources.filter(s => s.overBandwidth).length;
  logger.info(`📊 Total sources: ${allSources.length} (${zurgCount} Zurg cached, ${prowlarrCount} Prowlarr uncached${overBwCount > 0 ? `, ${overBwCount} over-bandwidth fallback` : ''})`);

  // Rank all sources together
  const rankedSources = rankUnifiedSources(allSources, type, platform);

  logger.info(`🏆 Ranked ${rankedSources.length} sources (top: ${rankedSources[0]?.title?.substring(0, 60)}...)`);

  return rankedSources;
}

/**
 * Unified ranking algorithm that treats cached sources equally
 * Note: Codec compatibility is validated via ffprobe AFTER stream URL is resolved
 */
function rankUnifiedSources(sources, type, platform) {
  return sources
    .map((source) => {
      let score = 0;
      const title = (source.title || '').toLowerCase();
      const titleUpper = title.toUpperCase();

      // Web client container preference: MP4 plays natively in browsers, MKV does not
      if (platform === 'web') {
        if (title.endsWith('.mp4')) {
          score += 2000;
        } else if (title.endsWith('.mkv')) {
          score -= 1000;
        }
      }

      // FLAGGED BAD - massive penalty (try last)
      if (source.isFlaggedBad) {
        score -= 100000; // Ensure these are tried last
      }

      // OVER BANDWIDTH - heavy penalty so these are tried after all in-bandwidth sources
      // Among over-bandwidth, prefer sources closest to the bandwidth limit (lowest bitrate first)
      if (source.overBandwidth) {
        score -= 50000;
        if (source.estimatedBitrateMbps) {
          score -= source.estimatedBitrateMbps * 100; // Lower bitrate = smaller penalty = tried first
        }
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

      // Seeders (only for Prowlarr) - CRITICAL for uncached sources
      if (source.seeders) {
        // Strong seeder bonus for uncached sources (up to +2000)
        // This ensures high-seeder torrents are strongly preferred over low-seeder ones
        if (!source.isCached) {
          score += Math.min(source.seeders * 10, 2000);
        } else {
          // Cached sources still get a small seeder bonus for ranking among cached
          score += Math.min(source.seeders, 500);
        }
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
