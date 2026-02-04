const axios = require('axios');
const logger = require('../utils/logger');
const { checkInstantAvailability } = require('@duckflix/rd-client');
const { estimateBitrateMbps, isValidSource, parseResolution } = require('../utils/bitrate');

/**
 * Check RD instant availability for multiple hashes
 * @param {string[]} hashes - Torrent hashes to check
 * @param {string} rdApiKey - User's Real-Debrid API key
 */
async function checkRDInstantAvailability(hashes, rdApiKey) {
  if (!rdApiKey || hashes.length === 0) return new Set();

  try {
    const cachedHashes = await checkInstantAvailability(hashes, rdApiKey);
    logger.info(`RD Cache: ${cachedHashes.size}/${hashes.length} torrents cached`);
    return cachedHashes;
  } catch (err) {
    logger.warn('RD instant availability check failed:', err.message);
    return new Set();
  }
}

/**
 * COPIED FROM MAIN PROJECT - Rank torrents with episode filtering
 */
const rankTorrents = (torrents, type, downloadType, episodeCount = 1, cachedHashes = new Set(), season = null, episode = null, excludedHashes = [], bitrateConstraints = null) => {
  const s = season ? String(season).padStart(2, '0') : null;
  const e = episode ? String(episode).padStart(2, '0') : null;

  return torrents
    .map((t) => {
      const title = (t.title || '').toLowerCase();
      const sizeGB = t.size / (1024 * 1024 * 1024);

      // Parse resolution from title
      let resolution = parseResolution(t.title);

      const isCached = t.hash && cachedHashes.has(t.hash.toLowerCase());

      // === BITRATE FILTERING ===
      if (bitrateConstraints && bitrateConstraints.maxBitrateMbps && bitrateConstraints.runtimeMinutes) {
        const { maxBitrateMbps, runtimeMinutes } = bitrateConstraints;

        // Calculate estimated bitrate
        const estimatedBitrate = estimateBitrateMbps(t.size, runtimeMinutes);

        // Filter out if exceeds user's bandwidth
        if (estimatedBitrate > maxBitrateMbps) {
          logger.debug(`[Ranker] Filtered ${t.title}: ${estimatedBitrate.toFixed(1)} Mbps > ${maxBitrateMbps.toFixed(1)} Mbps max`);
          return null;
        }

        // Filter out garbage/fake files
        if (!isValidSource(t.size, resolution, runtimeMinutes)) {
          logger.debug(`[Ranker] Filtered garbage: ${t.title} (${resolution}p but only ${sizeGB.toFixed(2)} GB)`);
          return null;
        }

        // Store bitrate for later use
        t.estimatedBitrateMbps = estimatedBitrate;
      }

      // For TV episodes with season/episode specified, filter by episode
      if (type === 'tv' && season && episode) {
        // Episode patterns that match our specific episode
        const episodePatterns = [
          new RegExp(`s${s}[\\._\\s]?e${e}(?:[^0-9]|$)`, 'i'),
          new RegExp(`\\b${season}x${e}\\b`, 'i'),
        ];

        // Season pack patterns (these are OK - we'll select the right file later)
        const seasonPackPatterns = [
          new RegExp(`s${s}(?:[^e0-9]|$)`, 'i'),                   // S03 (season pack, no episode)
          new RegExp(`season[\\._\\s]?${season}(?:[^0-9]|$)`, 'i') // Season 3
        ];

        // Check for series/multi-season packs (these contain multiple seasons including ours)
        const isSeriesOrMultiSeasonPack = (title) => {
          const multiSeasonPatterns = [
            /s\d{2}[\._\-\s]?s\d{2}/i,              // S01-S10, S01.S10
            /season[\s\._]?\d+[\s\._]?-[\s\._]?season[\s\._]?\d+/i, // Season 1 - Season 10
            /seasons?[\s\._]?\d+[\s\._]?-[\s\._]?\d+/i,             // Seasons 1-10
            /complete/i,                             // Complete series
            /collection/i                            // Collection
          ];

          if (!multiSeasonPatterns.some(p => p.test(title))) {
            return false;
          }

          // Extract season range if present
          const rangeMatch = title.match(/s(\d{2})[\._\-\s]?s(\d{2})/i);
          if (rangeMatch) {
            const startSeason = parseInt(rangeMatch[1], 10);
            const endSeason = parseInt(rangeMatch[2], 10);
            const targetSeason = parseInt(season, 10);
            if (targetSeason < startSeason || targetSeason > endSeason) {
              return false; // Our season is not in this pack's range
            }
          }

          // Check if this is genuinely a pack covering multiple seasons
          const hasMultipleSeasons = title.match(/s\d{2}/gi);
          if (hasMultipleSeasons && hasMultipleSeasons.length > 1) {
            // Check if our season is mentioned
            const ourSeasonPattern = new RegExp(`s${s}(?:[^0-9]|$)`, 'i');
            if (!ourSeasonPattern.test(title)) {
              return false; // It's a multi-season pack but doesn't include our season
            }
          }

          return true;
        };

        // Must match either specific episode OR be a season pack for the right season
        const matchesEpisode = episodePatterns.some(p => p.test(title));
        const isSeasonPack = seasonPackPatterns.some(p => p.test(title)) && !episodePatterns.some(p => p.test(title));
        const isMultiPack = isSeriesOrMultiSeasonPack(title);

        // Reject if it's a specific episode but for the WRONG episode
        const wrongEpisodePattern = /s(\d{2})[\._\s]?e(\d{2})/i;
        const match = title.match(wrongEpisodePattern);
        if (match) {
          const torrentSeason = parseInt(match[1], 10);
          const torrentEpisode = parseInt(match[2], 10);
          if (torrentSeason !== parseInt(season, 10) || torrentEpisode !== parseInt(episode, 10)) {
            return null; // Wrong episode - filter it out
          }
        }

        // Must match episode, be a season pack, or be a multi-season pack
        const result = matchesEpisode || isSeasonPack || isMultiPack;
        if (!result) {
          return null; // Doesn't match our episode criteria
        }
      }

      // Calculate score
      let score = 0;

      // Massive bonus for cached torrents
      if (isCached) score += 5000;

      // Resolution score
      score += resolution;

      // Seeder score (capped at 500)
      score += Math.min(t.seeders || 0, 500);

      // === AUDIO TRACK SCORING ===
      // Prefer English audio, deprioritize foreign-only releases
      const titleUpper = title.toUpperCase();

      // English audio indicators
      const hasEnglish = /\b(ENG|ENGLISH|DUAL[\s\._-]?AUDIO|MULTI[\s\._-]?AUDIO)\b/.test(titleUpper);
      if (hasEnglish) {
        score += 50;
      }

      // Subtitle indicators (embedded subs are valuable)
      const hasSubs = /\b(SUBS|SUBTITLES|SUBBED)\b/.test(titleUpper);
      if (hasSubs) {
        score += 20;
      }

      // Foreign language markers (penalize if no English indicator)
      const foreignLanguages = /\b(ITA|ITALIAN|FRE|FRENCH|GER|GERMAN|SPA|SPANISH|JPN|JAPANESE|KOR|KOREAN|RUS|RUSSIAN|CHI|CHINESE)\b/.test(titleUpper);
      if (foreignLanguages && !hasEnglish) {
        score -= 30;
      }

      // Size scoring
      let idealSize = 0;
      if (type === 'tv') {
        if (resolution === 2160) idealSize = 3;
        else if (resolution === 1080) idealSize = 1.5;
        else if (resolution === 720) idealSize = 0.8;
        else idealSize = 0.5;
      } else {
        if (resolution === 2160) idealSize = 20;
        else if (resolution === 1080) idealSize = 6;
        else if (resolution === 720) idealSize = 2.5;
        else idealSize = 1.5;
      }

      if (idealSize > 0) {
        const sizeDiff = Math.abs(sizeGB - idealSize);
        score -= sizeDiff * 50;
      }

      return {
        ...t,
        resolution,
        score,
        isCached,
        estimatedBitrateMbps: t.estimatedBitrateMbps
      };
    })
    .filter(t => t !== null)
    .sort((a, b) => b.score - a.score);
};

module.exports = {
  checkRDInstantAvailability,
  rankTorrents
};
