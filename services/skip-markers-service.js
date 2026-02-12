/**
 * Skip Markers Service
 *
 * Detects intro/credits timestamps via:
 * 1. Chapter markers (from ffprobe, already probed during codec check)
 * 2. IntroDB API (crowd-sourced intro timestamps by IMDB ID)
 *
 * Chapter fuzzy matching for credits:
 * - Named: matches "credits", "end credits", "outro", "ED", "closing" (case-insensitive)
 * - Named intro: matches "intro", "opening", "OP", "title sequence", "theme"
 * - Unnamed last chapter: if < 5min AND in the last 15% of total duration → likely credits
 *
 * Post-credits scene protection:
 * - If chapters exist AFTER a detected credits chapter, credits.end = start of next chapter
 *   (not the file end). This preserves Marvel-style stingers.
 * - If credits is the last chapter, credits.end = file end
 */

const axios = require('axios');
const logger = require('../utils/logger');

const INTRODB_BASE_URL = 'https://api.introdb.app';
const INTRODB_TIMEOUT = 5000;

const CREDITS_PATTERNS = [
  /\bcredits?\b/i,
  /\bend\s*credits?\b/i,
  /\boutro\b/i,
  /\bclosing\b/i,
  /\b(?:^|\s)ED\b/,
  /\bend\b/i
];

const INTRO_PATTERNS = [
  /\bintro(?:duction)?\b/i,
  /\bopening\s*(?:credits?)?\b/i,
  /\btitle\s*sequence\b/i,
  /\btheme\b/i,
  /\b(?:^|\s)OP\b/,
  /\bopening\b/i
];

const RECAP_PATTERNS = [
  /\brecap\b/i,
  /\bpreviously\b/i,
  /\blast\s*time\b/i,
  /\bprev(?:ious)?\s*episode\b/i
];

const POST_CREDITS_PATTERNS = [
  /\bpost[- ]?credits?\b/i,
  /\bstinger\b/i,
  /\bmid[- ]?credits?\b/i,
  /\bafter[- ]?credits?\b/i,
  /\bbonus\b/i,
  /\btag\b/i,
  /\bscene\b/i
];

/**
 * Extract skip markers from chapter data + IntroDB
 * @param {Object} params
 * @param {Array} params.chapters - ffprobe chapters array
 * @param {number} params.tmdbId
 * @param {string} params.type - 'movie' or 'tv'
 * @param {number} [params.season]
 * @param {number} [params.episode]
 * @param {string} [params.imdbId] - pre-fetched IMDB ID
 * @param {number} [params.duration] - total file duration in seconds
 * @returns {Promise<Object>} { intro, recap, credits }
 */
async function getSkipMarkers({ chapters, tmdbId, type, season, episode, imdbId, duration }) {
  const result = { intro: null, recap: null, credits: null };

  // --- CHAPTER MARKER ANALYSIS ---
  if (chapters && chapters.length > 0) {
    const chapterAnalysis = analyzeChapters(chapters, duration);
    if (chapterAnalysis.intro) result.intro = chapterAnalysis.intro;
    if (chapterAnalysis.recap) result.recap = chapterAnalysis.recap;
    if (chapterAnalysis.credits) result.credits = chapterAnalysis.credits;
  }

  // --- INTRODB LOOKUP (single /segments call returns intro + recap + outro) ---
  if (imdbId) {
    const segments = await fetchIntroDBSegments(imdbId, type, season, episode);
    if (segments) {
      // Chapters take priority; IntroDB fills gaps
      if (!result.intro && segments.intro) result.intro = segments.intro;
      if (!result.recap && segments.recap) result.recap = segments.recap;
      if (!result.credits && segments.outro) result.credits = segments.outro;
    }
  }

  return result;
}

function analyzeChapters(chapters, totalDuration) {
  const result = { intro: null, recap: null, credits: null };

  const sorted = chapters
    .map(ch => ({
      title: ch.tags?.title || '',
      start: parseFloat(ch.start_time),
      end: parseFloat(ch.end_time)
    }))
    .sort((a, b) => a.start - b.start);

  if (sorted.length === 0) return result;

  const fileDuration = totalDuration || sorted[sorted.length - 1].end;

  // --- INTRO DETECTION ---
  // Only look in first 25% of file or first 10 minutes
  const introSearchLimit = Math.min(fileDuration * 0.25, 600);

  for (let i = 0; i < sorted.length; i++) {
    const ch = sorted[i];
    if (ch.start > introSearchLimit) break;

    const chDuration = ch.end - ch.start;
    // Skip very short chapters (< 5s = likely studio logo)
    // and very long ones (> 3min = probably actual content)
    if (chDuration < 5 || chDuration > 180) continue;

    if (INTRO_PATTERNS.some(p => p.test(ch.title))) {
      result.intro = {
        start: ch.start,
        end: ch.end,
        source: 'chapters',
        label: ch.title
      };
      break;
    }
  }

  // --- RECAP DETECTION ---
  // Recaps appear before intros or early in the episode (first 15%)
  const recapSearchLimit = Math.min(fileDuration * 0.15, 300);
  for (let i = 0; i < sorted.length; i++) {
    const ch = sorted[i];
    if (ch.start > recapSearchLimit) break;
    if (RECAP_PATTERNS.some(p => p.test(ch.title))) {
      result.recap = {
        start: ch.start,
        end: ch.end,
        source: 'chapters',
        label: ch.title
      };
      break;
    }
  }

  // --- CREDITS DETECTION ---
  // Search from the end backward
  for (let i = sorted.length - 1; i >= 0; i--) {
    const ch = sorted[i];
    const chDuration = ch.end - ch.start;

    // Named credits match
    if (CREDITS_PATTERNS.some(p => p.test(ch.title))) {
      let creditsEnd = ch.end;
      const hasPostCredits = i < sorted.length - 1;

      if (hasPostCredits) {
        const nextCh = sorted[i + 1];
        const nextIsPostCredits = POST_CREDITS_PATTERNS.some(p => p.test(nextCh.title));
        const nextIsShort = (nextCh.end - nextCh.start) < 600; // < 10 min

        if (nextIsPostCredits || nextIsShort) {
          // Post-credits scene detected — cap credits skip at start of post-credits
          creditsEnd = nextCh.start;
          logger.info(`[SkipMarkers] Post-credits scene detected: "${nextCh.title}" at ${nextCh.start}s`);
        }
      }

      result.credits = {
        start: ch.start,
        end: creditsEnd,
        source: 'chapters',
        label: ch.title,
        hasPostCredits: hasPostCredits && creditsEnd !== ch.end
      };
      break;
    }

    // Unnamed heuristic: last chapter, short (< 5min), in the last 15% of file
    if (i === sorted.length - 1 && !ch.title.match(/\w{3,}/) && chDuration < 300) {
      const positionRatio = ch.start / fileDuration;
      if (positionRatio > 0.85) {
        result.credits = {
          start: ch.start,
          end: ch.end,
          source: 'chapters-heuristic',
          label: 'Detected credits (unnamed chapter)'
        };
        break;
      }
    }
  }

  return result;
}

/**
 * Fetch all segments from IntroDB in a single call.
 * GET /segments?imdb_id=ttXXX&season=N&episode=N
 */
async function fetchIntroDBSegments(imdbId, type, season, episode) {
  try {
    const s = type === 'movie' ? 0 : season;
    const e = type === 'movie' ? 0 : episode;
    if (type === 'tv' && (!s || !e)) return null;

    const url = `${INTRODB_BASE_URL}/segments?imdb_id=${imdbId}&season=${s}&episode=${e}`;
    const res = await axios.get(url, { timeout: INTRODB_TIMEOUT });
    const data = res.data;
    if (!data) return null;

    const result = { intro: null, recap: null, outro: null };

    if (data.intro && data.intro.start_sec != null) {
      result.intro = {
        start: data.intro.start_sec,
        end: data.intro.end_sec,
        source: 'introdb',
        confidence: data.intro.confidence || null
      };
    }
    if (data.recap && data.recap.start_sec != null) {
      result.recap = {
        start: data.recap.start_sec,
        end: data.recap.end_sec,
        source: 'introdb'
      };
    }
    if (data.outro && data.outro.start_sec != null) {
      result.outro = {
        start: data.outro.start_sec,
        end: data.outro.end_sec,
        source: 'introdb'
      };
    }

    return result;
  } catch (e) {
    if (e.response?.status !== 404) {
      logger.warn(`[SkipMarkers] IntroDB segments lookup failed: ${e.message}`);
    }
    return null;
  }
}

module.exports = { getSkipMarkers, analyzeChapters };
