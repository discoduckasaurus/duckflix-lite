/**
 * Zurg Client Module
 *
 * Searches the Zurg mount for matching content with quality gating.
 * Prefers higher quality files when multiple matches exist.
 * Returns both quality matches AND garbage fallback for comparison with Prowlarr.
 */

const fs = require('fs').promises;
const fsSync = require('fs');
const path = require('path');

// Quality thresholds (MB per minute of content)
const QUALITY_THRESHOLDS = {
  // 7 MB/min = accepts decent 720p (e.g., 154MB for 22min episode)
  // Rejects true garbage like 120MB/45min = 2.7 MB/min
  MIN_MB_PER_MINUTE: 7,

  // Estimated episode durations when TMDB data unavailable
  EPISODE_DURATION: {
    drama: 45,
    sitcom: 22,
    anime: 24,
    default: 42
  },

  MOVIE_DURATION: 100
};

// Resolution parsing from filename
const RESOLUTION_PATTERNS = [
  { pattern: /2160p|4k|uhd/i, resolution: 2160, label: '4K' },
  { pattern: /1080p|1080i|fullhd|fhd/i, resolution: 1080, label: '1080p' },
  { pattern: /720p|hd/i, resolution: 720, label: '720p' },
  { pattern: /480p|sd/i, resolution: 480, label: '480p' },
  { pattern: /360p/i, resolution: 360, label: '360p' }
];

const VIDEO_EXTENSIONS = ['.mkv', '.mp4', '.avi', '.mov', '.webm', '.m4v'];

/**
 * Generate title variations for better matching
 * Handles: apostrophes, US/UK suffixes, years, common abbreviations
 *
 * @param {string} title - The title to generate variations for
 * @param {string|number} [year] - Optional year to include in variations
 * @returns {string[]} Array of title variations
 */
function generateTitleVariations(title, year) {
  const variations = new Set();
  const base = title.trim();

  variations.add(base);

  // Add/remove apostrophes: "RuPaul's" <-> "RuPauls" <-> "RuPaul"
  variations.add(base.replace(/'/g, ''));
  variations.add(base.replace(/(\w)'s\b/gi, '$1s'));
  variations.add(base.replace(/(\w)s\b/gi, "$1's"));

  // US/UK variations for shows
  if (!base.toLowerCase().includes(' us') && !base.toLowerCase().includes('(us)')) {
    variations.add(`${base} US`);
    variations.add(`${base} (US)`);
  }
  // Remove US/UK suffix
  variations.add(base.replace(/\s*\(?US\)?$/i, '').trim());
  variations.add(base.replace(/\s*\(?UK\)?$/i, '').trim());

  // Year variations
  if (year) {
    variations.add(`${base} ${year}`);
    variations.add(`${base} (${year})`);
  }

  // Common show name variations
  const lowerBase = base.toLowerCase();

  // The Office variations
  if (lowerBase.includes('office') && !lowerBase.includes('us')) {
    variations.add('The Office US');
    variations.add('The Office (US)');
    variations.add('The Office (US) (2005)');
  }

  // Drag Race variations
  if (lowerBase.includes('drag race') || lowerBase.includes('rupaul')) {
    variations.add("RuPaul's Drag Race");
    variations.add('RuPauls Drag Race');
    variations.add('RuPaul Drag Race');
    if (lowerBase.includes('all stars') || lowerBase.includes('all-stars')) {
      variations.add("RuPaul's Drag Race All Stars");
      variations.add('RuPauls Drag Race All Stars');
    }
  }

  // BSG variations
  if (lowerBase.includes('battlestar') || lowerBase.includes('galactica')) {
    variations.add('Battlestar Galactica');
    variations.add('Battlestar Galactica 2004');
    variations.add('Battlestar Galactica (2004)');
    variations.add('BSG');
  }

  return Array.from(variations);
}

/**
 * Parse resolution information from filename
 *
 * @param {string} filename - The filename to parse
 * @returns {{resolution: number, label: string}} Resolution info
 */
function parseResolution(filename) {
  for (const { pattern, resolution, label } of RESOLUTION_PATTERNS) {
    if (pattern.test(filename)) {
      return { resolution, label };
    }
  }
  return { resolution: 0, label: 'Unknown' };
}

/**
 * Check if file is a video file based on extension
 *
 * @param {string} filename - The filename to check
 * @returns {boolean} True if video file
 */
function isVideoFile(filename) {
  const ext = path.extname(filename).toLowerCase();
  return VIDEO_EXTENSIONS.includes(ext);
}

/**
 * Normalize title for matching (convert separators to spaces, lowercase)
 *
 * @param {string} title - The title to normalize
 * @returns {string} Normalized title
 */
function normalizeTitle(title) {
  return title
    .toLowerCase()
    .replace(/[._\-]+/g, ' ')
    .replace(/[']/g, '')  // Remove apostrophes
    .replace(/[^a-z0-9\s]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Check if filename matches TV episode pattern
 *
 * @param {string} filename - The filename to check
 * @param {number} season - Season number
 * @param {number} episode - Episode number
 * @returns {boolean} True if matches episode pattern
 */
function matchesTVEpisode(filename, season, episode) {
  const s = String(season).padStart(2, '0');
  const e = String(episode).padStart(2, '0');
  const lower = filename.toLowerCase();

  const patterns = [
    // Standard format: S01E01
    new RegExp(`s${s}[._\\s]?e${e}(?:[^0-9]|$)`, 'i'),
    // Alternate format: 1x01
    new RegExp(`\\b${season}x${String(episode).padStart(2, '0')}\\b`, 'i'),
    // Season pack check for multi-episode files
    // Match: S01E01-E03 or S01E01-03
    new RegExp(`s${s}e(\\d{2})-e?(\\d{2})`, 'i'),
  ];

  // Check standard patterns first
  if (patterns[0].test(lower) || patterns[1].test(lower)) {
    return true;
  }

  // Check multi-episode pattern (S01E01-E03)
  const multiMatch = lower.match(patterns[2]);
  if (multiMatch) {
    const epStart = parseInt(multiMatch[1], 10);
    const epEnd = parseInt(multiMatch[2], 10);
    return episode >= epStart && episode <= epEnd;
  }

  // Season pack check: "season 1" or "s01"
  // Only if filename contains season number AND specific episode
  const seasonPattern = new RegExp(`(season[._\\s]?${season}|s${s})`, 'i');
  const episodePattern = new RegExp(`e${e}(?:[^0-9]|$)`, 'i');

  return seasonPattern.test(lower) && episodePattern.test(lower);
}

/**
 * Check if directory/file matches show title (with variations)
 *
 * @param {string} pathPart - The path part to check
 * @param {string[]} titleVariations - Array of title variations to match against
 * @returns {boolean} True if matches any variation
 */
function matchesShowTitle(pathPart, titleVariations) {
  const normalPath = normalizeTitle(pathPart);

  for (const title of titleVariations) {
    const normalTitle = normalizeTitle(title);

    // Direct contains check
    if (normalPath.includes(normalTitle) || normalTitle.includes(normalPath)) {
      return true;
    }

    // Word-based matching (60% of words must match)
    const pathWords = normalPath.split(' ').filter(w => w.length > 2);
    const titleWords = normalTitle.split(' ').filter(w => w.length > 2);

    if (titleWords.length > 0) {
      const matchCount = titleWords.filter(w => pathWords.includes(w)).length;
      if (matchCount >= Math.ceil(titleWords.length * 0.6)) {
        return true;
      }
    }
  }

  return false;
}

/**
 * Calculate quality score based on file size and duration
 *
 * @param {number} fileSizeBytes - File size in bytes
 * @param {number} durationMinutes - Duration in minutes
 * @returns {{sizeMB: number, mbPerMinute: number, meetsThreshold: boolean}} Quality score
 */
function calculateQualityScore(fileSizeBytes, durationMinutes) {
  const sizeMB = fileSizeBytes / (1024 * 1024);
  const mbPerMinute = sizeMB / durationMinutes;

  return {
    sizeMB: Math.round(sizeMB),
    mbPerMinute: Math.round(mbPerMinute * 10) / 10,
    meetsThreshold: mbPerMinute >= QUALITY_THRESHOLDS.MIN_MB_PER_MINUTE
  };
}

/**
 * Estimate episode duration based on show title
 *
 * @param {string} [showTitle] - The show title
 * @returns {number} Estimated duration in minutes
 */
function estimateEpisodeDuration(showTitle) {
  const lower = showTitle?.toLowerCase() || '';

  const sitcomPatterns = [
    /american dad/i, /family guy/i, /simpsons/i, /futurama/i,
    /bob.?s burgers/i, /archer/i, /rick and morty/i, /south park/i,
    /the office/i, /parks and rec/i, /friends/i, /seinfeld/i,
    /brooklyn nine/i, /community/i, /30 rock/i, /scrubs/i,
    /rupaul/i, /drag race/i  // Drag Race is ~60-90 min but varies
  ];

  // Drag Race episodes are long
  if (/rupaul|drag race/i.test(lower)) {
    return 60;
  }

  if (sitcomPatterns.some(p => p.test(lower))) {
    return QUALITY_THRESHOLDS.EPISODE_DURATION.sitcom;
  }

  if (/anime|\[.*\]/.test(lower)) {
    return QUALITY_THRESHOLDS.EPISODE_DURATION.anime;
  }

  return QUALITY_THRESHOLDS.EPISODE_DURATION.drama;
}

/**
 * Recursively search directory for video files
 *
 * @param {string} dir - Directory to search
 * @param {number} [maxDepth=4] - Maximum recursion depth
 * @param {number} [currentDepth=0] - Current recursion depth
 * @returns {Promise<Array>} Array of file objects
 */
async function searchDirectory(dir, maxDepth = 4, currentDepth = 0) {
  if (currentDepth >= maxDepth) return [];

  try {
    const entries = await fs.readdir(dir, { withFileTypes: true });
    const results = [];

    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);

      if (entry.isDirectory()) {
        const subResults = await searchDirectory(fullPath, maxDepth, currentDepth + 1);
        results.push(...subResults);
      } else if (entry.isFile() && isVideoFile(entry.name)) {
        try {
          const stats = await fs.stat(fullPath);
          results.push({
            path: fullPath,
            name: entry.name,
            size: stats.size,
            directory: path.basename(dir)
          });
        } catch (err) {
          // Skip files we can't stat
        }
      }
    }

    return results;
  } catch (err) {
    if (err.code === 'ENOENT' || err.code === 'EACCES') {
      return [];
    }
    throw err;
  }
}

/**
 * Find TV episode in Zurg mount
 *
 * @param {string} zurgMount - Path to Zurg mount
 * @param {string} showTitle - Show title
 * @param {number} season - Season number
 * @param {number} episode - Episode number
 * @param {string|number} [year] - Optional year
 * @returns {Promise<Array>} Array of matches
 */
async function findTVEpisode(zurgMount, showTitle, season, episode, year) {
  const searchDirs = [
    path.join(zurgMount, 'shows'),
    path.join(zurgMount, '__all__')
  ];

  const matches = [];
  const estimatedDuration = estimateEpisodeDuration(showTitle);
  const titleVariations = generateTitleVariations(showTitle, year);

  for (const searchDir of searchDirs) {
    try {
      await fs.access(searchDir);
    } catch {
      continue;
    }

    const files = await searchDirectory(searchDir);

    for (const file of files) {
      // Check if filename or directory matches ANY title variation
      const matchesShow = matchesShowTitle(file.name, titleVariations) ||
                          matchesShowTitle(file.directory, titleVariations);

      const matchesEp = matchesTVEpisode(file.name, season, episode);

      if (matchesShow && matchesEp) {
        const { resolution, label } = parseResolution(file.name);
        const quality = calculateQualityScore(file.size, estimatedDuration);

        matches.push({
          filePath: file.path,
          fileName: file.name,
          fileSize: file.size,
          sizeMB: quality.sizeMB,
          mbPerMinute: quality.mbPerMinute,
          meetsQualityThreshold: quality.meetsThreshold,
          resolution,
          quality: label,
          estimatedDuration,
          source: 'zurg'
        });
      }
    }
  }

  // Sort by quality score (mbPerMinute) descending
  matches.sort((a, b) => b.mbPerMinute - a.mbPerMinute);

  return matches;
}

/**
 * Find movie in Zurg mount
 *
 * @param {string} zurgMount - Path to Zurg mount
 * @param {string} movieTitle - Movie title
 * @param {string|number} [year] - Optional year
 * @returns {Promise<Array>} Array of matches
 */
async function findMovie(zurgMount, movieTitle, year) {
  const searchDirs = [
    path.join(zurgMount, 'movies'),
    path.join(zurgMount, '__all__')
  ];

  const matches = [];
  const estimatedDuration = QUALITY_THRESHOLDS.MOVIE_DURATION;
  const titleVariations = generateTitleVariations(movieTitle, year);

  for (const searchDir of searchDirs) {
    try {
      await fs.access(searchDir);
    } catch {
      continue;
    }

    const files = await searchDirectory(searchDir);

    for (const file of files) {
      const matchesTitle = matchesShowTitle(file.name, titleVariations) ||
                           matchesShowTitle(file.directory, titleVariations);

      const yearMatch = year ?
        (file.name.includes(year) || file.directory.includes(year)) :
        true;

      if (matchesTitle && yearMatch) {
        const { resolution, label } = parseResolution(file.name);
        const quality = calculateQualityScore(file.size, estimatedDuration);

        matches.push({
          filePath: file.path,
          fileName: file.name,
          fileSize: file.size,
          sizeMB: quality.sizeMB,
          mbPerMinute: quality.mbPerMinute,
          meetsQualityThreshold: quality.meetsThreshold,
          resolution,
          quality: label,
          estimatedDuration,
          source: 'zurg'
        });
      }
    }
  }

  matches.sort((a, b) => b.mbPerMinute - a.mbPerMinute);

  return matches;
}

/**
 * Main function: Find content in Zurg mount with quality gating
 *
 * @param {Object} options - Search options
 * @param {string} options.title - Content title
 * @param {string} options.type - Content type ('tv' or 'movie')
 * @param {string|number} [options.year] - Year
 * @param {number} [options.season] - Season number (for TV)
 * @param {number} [options.episode] - Episode number (for TV)
 * @param {string} [options.tmdbId] - TMDB ID
 * @param {number} [options.episodeRuntime] - Actual episode runtime from TMDB
 * @returns {Promise<{match: Object|null, fallback: Object|null}>} Best match and fallback
 */
async function findInZurgMount(options) {
  const { title, type, year, season, episode, tmdbId, episodeRuntime } = options;

  const zurgMount = process.env.ZURG_MOUNT || '/mnt/zurg';
  const zurgEnabled = process.env.ZURG_ENABLED === 'true';

  if (!zurgEnabled) {
    console.log('⏭️  ZURG: Disabled, skipping lookup');
    return { match: null, fallback: null };
  }

  try {
    await fs.access(zurgMount);
  } catch (err) {
    console.warn(`⚠️  ZURG: Mount not accessible at ${zurgMount}`);
    return { match: null, fallback: null };
  }

  const searchDesc = type === 'tv' ? `${title} S${season}E${episode}` : `${title} (${year})`;
  console.log(`🔍 ZURG: Searching for ${searchDesc}`);

  let matches;

  if (type === 'tv') {
    matches = await findTVEpisode(zurgMount, title, season, episode, year);

    // Recalculate with TMDB runtime if provided
    if (episodeRuntime && matches.length > 0) {
      for (const match of matches) {
        const quality = calculateQualityScore(match.fileSize, episodeRuntime);
        match.mbPerMinute = quality.mbPerMinute;
        match.meetsQualityThreshold = quality.meetsThreshold;
        match.estimatedDuration = episodeRuntime;
      }
      matches.sort((a, b) => b.mbPerMinute - a.mbPerMinute);
    }
  } else {
    matches = await findMovie(zurgMount, title, year);
  }

  if (matches.length === 0) {
    console.log(`❌ ZURG: No matches found`);
    return { match: null, fallback: null };
  }

  // Log all matches
  console.log(`📋 ZURG: Found ${matches.length} potential match(es):`);
  for (const m of matches.slice(0, 8)) {
    const status = m.meetsQualityThreshold ? '✅' : '❌';
    console.log(`   ${status} ${m.sizeMB}MB (${m.mbPerMinute} MB/min) - ${m.quality} - ${m.fileName.substring(0, 55)}...`);
  }
  if (matches.length > 8) {
    console.log(`   ... and ${matches.length - 8} more`);
  }

  // Find best quality match
  const qualityMatch = matches.find(m => m.meetsQualityThreshold);

  // Best garbage match (for fallback comparison)
  const fallbackMatch = !qualityMatch ? matches[0] : null;

  if (qualityMatch) {
    console.log(`✅ ZURG: Using ${qualityMatch.quality} at ${qualityMatch.mbPerMinute} MB/min`);
    return { match: qualityMatch, fallback: null };
  }

  // No quality match - return fallback for comparison
  console.log(`⚠️  ZURG: Best is only ${fallbackMatch.mbPerMinute} MB/min (need ${QUALITY_THRESHOLDS.MIN_MB_PER_MINUTE}+)`);
  return { match: null, fallback: fallbackMatch };
}

/**
 * Validate that a Zurg path exists and is a valid file
 *
 * @param {string} filePath - Path to validate
 * @returns {Promise<boolean>} True if valid file
 */
async function validateZurgPath(filePath) {
  try {
    const stats = await fs.stat(filePath);
    return stats.isFile() && stats.size > 0;
  } catch {
    return false;
  }
}

module.exports = {
  findInZurgMount,
  validateZurgPath,
  calculateQualityScore,
  parseResolution,
  generateTitleVariations,
  QUALITY_THRESHOLDS
};
