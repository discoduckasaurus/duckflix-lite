/**
 * Embedded Subtitle Service
 * Detects and manages embedded subtitles in video files
 */

const logger = require('../utils/logger');
const { isFFprobeAvailable, getSubtitleStreams } = require('./ffprobe-service');
const { standardizeLanguage, getLanguageCode, matchesLanguage } = require('../utils/language-standardizer');

/**
 * Detect embedded subtitles in a video stream
 * @param {string} streamUrl - URL to the video file
 * @param {string} preferredLanguage - Preferred language code (e.g., 'en')
 * @returns {Promise<Object>} Detection result
 */
async function detectEmbeddedSubtitles(streamUrl, preferredLanguage = 'en') {
  const result = {
    hasEmbedded: false,
    subtitles: [],
    shouldFallbackToApi: true,
    fallbackReason: null
  };

  // Step 1: Check if ffprobe is available
  const ffprobeAvailable = await isFFprobeAvailable();
  if (!ffprobeAvailable) {
    result.fallbackReason = 'ffprobe not available';
    logger.warn('Embedded subtitle detection skipped: ffprobe not installed');
    return result;
  }

  // Step 2: Get subtitle streams from video
  let rawStreams;
  try {
    rawStreams = await getSubtitleStreams(streamUrl);
  } catch (err) {
    result.fallbackReason = `ffprobe error: ${err.message}`;
    logger.warn(`Embedded subtitle detection failed: ${err.message}`);
    return result;
  }

  // Step 3: Check if any embedded subtitles exist
  if (!rawStreams || rawStreams.length === 0) {
    result.fallbackReason = 'no embedded subtitles found';
    return result;
  }

  result.hasEmbedded = true;

  // Step 4: Standardize language names and filter
  let hasAnyStandard = false;
  let hasPreferredLanguage = false;

  const processedSubtitles = rawStreams.map((stream) => {
    // Try to standardize from language tag first, then title
    let languageInput = stream.language || stream.title;
    const standardized = standardizeLanguage(languageInput);

    const subtitle = {
      index: stream.index,
      codec: stream.codec,
      rawLanguage: stream.language,
      rawTitle: stream.title,
      language: standardized.standardized,
      languageCode: standardized.standardized ? getLanguageCode(standardized.standardized) : null,
      isStandard: standardized.isStandard,
      disposition: stream.disposition
    };

    if (standardized.isStandard) {
      hasAnyStandard = true;

      // Check if this matches preferred language
      if (matchesLanguage(languageInput, preferredLanguage)) {
        hasPreferredLanguage = true;
      }
    }

    return subtitle;
  });

  result.subtitles = processedSubtitles;

  // Step 5: Determine if we should fallback to API

  // If ALL subtitles have non-standard naming, fallback
  if (!hasAnyStandard) {
    result.shouldFallbackToApi = true;
    result.fallbackReason = 'all embedded subtitles have non-standard naming';
    logger.info(`Embedded subs found but all have non-standard naming: ${rawStreams.map(s => s.language || s.title || 'unknown').join(', ')}`);
    return result;
  }

  // If standard naming exists but preferred language is missing, fallback
  if (!hasPreferredLanguage) {
    result.shouldFallbackToApi = true;
    result.fallbackReason = `preferred language '${preferredLanguage}' not found in embedded subtitles`;
    const availableLangs = processedSubtitles
      .filter(s => s.isStandard)
      .map(s => s.language)
      .join(', ');
    logger.info(`Embedded subs available (${availableLangs}) but '${preferredLanguage}' not found`);
    return result;
  }

  // We have embedded subtitles with standard naming and preferred language
  result.shouldFallbackToApi = false;
  result.fallbackReason = null;

  logger.info(`Found ${processedSubtitles.filter(s => s.isStandard).length} embedded subtitle(s) with standard naming`);

  return result;
}

/**
 * Get the best embedded subtitle for a preferred language
 * @param {Array} subtitles - Array of processed subtitle objects
 * @param {string} preferredLanguage - Preferred language code (e.g., 'en')
 * @returns {Object|null} Best matching subtitle or null
 */
function getBestEmbeddedSubtitle(subtitles, preferredLanguage = 'en') {
  if (!subtitles || subtitles.length === 0) {
    return null;
  }

  // Filter to standard subtitles matching preferred language
  const matching = subtitles.filter(sub =>
    sub.isStandard && sub.languageCode === preferredLanguage.toLowerCase()
  );

  if (matching.length === 0) {
    return null;
  }

  // Prioritization:
  // 1. Default disposition
  // 2. Non-forced (regular subtitles preferred over forced)
  // 3. Non-SDH (unless no other option)
  // 4. First match

  // Sort by priority
  matching.sort((a, b) => {
    // Default disposition wins
    if (a.disposition?.default && !b.disposition?.default) return -1;
    if (b.disposition?.default && !a.disposition?.default) return 1;

    // Non-forced preferred
    if (!a.disposition?.forced && b.disposition?.forced) return -1;
    if (!b.disposition?.forced && a.disposition?.forced) return 1;

    // Non-hearing-impaired preferred (but SDH is still valid)
    if (!a.disposition?.hearingImpaired && b.disposition?.hearingImpaired) return -1;
    if (!b.disposition?.hearingImpaired && a.disposition?.hearingImpaired) return 1;

    return 0;
  });

  return matching[0];
}

/**
 * Get all embedded subtitles with standard naming
 * @param {Array} subtitles - Array of processed subtitle objects
 * @returns {Array} Filtered array of standard subtitles
 */
function getStandardSubtitles(subtitles) {
  if (!subtitles || subtitles.length === 0) {
    return [];
  }

  return subtitles.filter(sub => sub.isStandard);
}

module.exports = {
  detectEmbeddedSubtitles,
  getBestEmbeddedSubtitle,
  getStandardSubtitles
};
