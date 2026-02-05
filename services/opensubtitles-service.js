const axios = require('axios');
const fs = require('fs');
const path = require('path');
const logger = require('../utils/logger');
const { db } = require('../db/init');
const { standardizeLanguage } = require('../utils/language-standardizer');

const OPENSUBTITLES_API_KEY = process.env.OPENSUBTITLES_API_KEY;
const OPENSUBTITLES_USERNAME = process.env.OPENSUBTITLES_USERNAME;
const OPENSUBTITLES_PASSWORD = process.env.OPENSUBTITLES_PASSWORD;
const OPENSUBTITLES_BASE_URL = 'https://api.opensubtitles.com/api/v1';
const SUBTITLES_DIR = path.join(__dirname, '..', 'subtitles');
const MAX_STORAGE_BYTES = 100 * 1024 * 1024 * 1024; // 100GB
const DAILY_DOWNLOAD_LIMIT = 1000; // VIP limit

// Session token cache (in-memory)
let sessionToken = null;
let sessionExpiry = null;
let userInfo = null;

// Ensure subtitles directory exists
if (!fs.existsSync(SUBTITLES_DIR)) {
  fs.mkdirSync(SUBTITLES_DIR, { recursive: true });
  logger.info(`Created subtitles directory: ${SUBTITLES_DIR}`);
}

/**
 * Login to OpenSubtitles and get session token
 * @param {string} username - Optional username (defaults to env)
 * @param {string} password - Optional password (defaults to env)
 * @returns {Promise<Object>} { token, user }
 */
async function login(username = OPENSUBTITLES_USERNAME, password = OPENSUBTITLES_PASSWORD) {
  if (!OPENSUBTITLES_API_KEY) {
    throw new Error('OPENSUBTITLES_API_KEY not configured');
  }

  if (!username || !password) {
    throw new Error('OpenSubtitles username and password not configured');
  }

  try {
    logger.info(`Logging in to OpenSubtitles as: ${username}`);

    const response = await axios.post(
      `${OPENSUBTITLES_BASE_URL}/login`,
      { username, password },
      {
        headers: {
          'Api-Key': OPENSUBTITLES_API_KEY,
          'Content-Type': 'application/json',
          'User-Agent': 'DuckFlixLite v1.0',
          'Accept': 'application/json'
        }
      }
    );

    if (response.data && response.data.token) {
      sessionToken = response.data.token;
      sessionExpiry = Date.now() + (24 * 60 * 60 * 1000); // 24 hours
      userInfo = response.data.user;

      logger.info(`OpenSubtitles login successful! VIP status: ${userInfo?.vip || false}`);
      logger.info(`Allowed downloads: ${userInfo?.allowed_downloads || 'N/A'}`);

      return {
        token: sessionToken,
        user: userInfo,
        expiresIn: '24h'
      };
    }

    throw new Error('No token returned from login');
  } catch (error) {
    logger.error('OpenSubtitles login failed:', error.response?.data || error.message);
    throw error;
  }
}

/**
 * Ensure we have a valid session token (login if needed)
 */
async function ensureAuthenticated() {
  // Check if we have a valid token
  if (sessionToken && sessionExpiry && Date.now() < sessionExpiry) {
    return sessionToken;
  }

  // Token expired or doesn't exist - login
  logger.info('Session token expired or missing, logging in...');
  await login();

  return sessionToken;
}

/**
 * Get axios instance with OpenSubtitles authentication
 */
async function getAuthenticatedClient() {
  const token = await ensureAuthenticated();

  return axios.create({
    baseURL: OPENSUBTITLES_BASE_URL,
    headers: {
      'Api-Key': OPENSUBTITLES_API_KEY,
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      'User-Agent': 'DuckFlixLite v1.0',
      'Accept': 'application/json'
    }
  });
}

/**
 * Get current user info
 */
async function getUserInfo() {
  try {
    const client = await getAuthenticatedClient();
    const response = await client.get('/infos/user');

    if (response.data && response.data.data) {
      userInfo = response.data.data;
      return userInfo;
    }

    return null;
  } catch (error) {
    logger.error('Failed to get user info:', error.response?.data || error.message);
    return null;
  }
}

/**
 * Force logout and clear session
 */
async function logout() {
  try {
    if (sessionToken) {
      const client = await getAuthenticatedClient();
      await client.delete('/logout');
    }
  } catch (err) {
    logger.warn('Logout failed (non-critical):', err.message);
  } finally {
    sessionToken = null;
    sessionExpiry = null;
    userInfo = null;
    logger.info('OpenSubtitles session cleared');
  }
}

/**
 * Get current UTC date (YYYY-MM-DD)
 */
function getCurrentDate() {
  const now = new Date();
  return now.toISOString().split('T')[0];
}

/**
 * Get daily download count and check quota
 * @returns {Object} { count: number, remaining: number, exceeded: boolean }
 */
function checkDailyQuota() {
  const today = getCurrentDate();

  const row = db.prepare(`
    SELECT download_count
    FROM subtitle_quota_tracking
    WHERE download_date = ?
  `).get(today);

  const count = row?.download_count || 0;
  const remaining = DAILY_DOWNLOAD_LIMIT - count;
  const exceeded = count >= DAILY_DOWNLOAD_LIMIT;

  return { count, remaining, exceeded, limit: DAILY_DOWNLOAD_LIMIT };
}

/**
 * Increment daily download count
 */
function incrementQuotaCount() {
  const today = getCurrentDate();

  db.prepare(`
    INSERT INTO subtitle_quota_tracking (download_date, download_count, updated_at)
    VALUES (?, 1, datetime('now'))
    ON CONFLICT(download_date)
    DO UPDATE SET
      download_count = download_count + 1,
      updated_at = datetime('now')
  `).run(today);

  logger.info(`OpenSubtitles quota: ${checkDailyQuota().count}/${DAILY_DOWNLOAD_LIMIT} used today`);
}

/**
 * Get total storage used by subtitles
 */
function getTotalStorageUsed() {
  const result = db.prepare(`
    SELECT COALESCE(SUM(file_size_bytes), 0) as total
    FROM subtitles
  `).get();

  return result.total || 0;
}

/**
 * Delete oldest subtitles to free up space
 * @param {number} bytesNeeded - Bytes to free
 */
function freeUpStorage(bytesNeeded) {
  logger.info(`Freeing up ${(bytesNeeded / 1024 / 1024).toFixed(2)} MB of subtitle storage...`);

  // Get oldest subtitles first (by last_accessed_at)
  const oldestSubs = db.prepare(`
    SELECT id, file_path, file_size_bytes
    FROM subtitles
    ORDER BY last_accessed_at ASC
  `).all();

  let freedBytes = 0;
  let deletedCount = 0;

  for (const sub of oldestSubs) {
    if (freedBytes >= bytesNeeded) {
      break;
    }

    // Delete file from disk
    try {
      if (fs.existsSync(sub.file_path)) {
        fs.unlinkSync(sub.file_path);
      }
    } catch (err) {
      logger.error(`Failed to delete subtitle file: ${sub.file_path}`, err);
    }

    // Delete from database
    db.prepare('DELETE FROM subtitles WHERE id = ?').run(sub.id);

    freedBytes += sub.file_size_bytes;
    deletedCount++;
  }

  logger.info(`Deleted ${deletedCount} old subtitles, freed ${(freedBytes / 1024 / 1024).toFixed(2)} MB`);
}

/**
 * Check if subtitle exists in cache
 * @param {number} tmdbId
 * @param {string} type - 'movie' or 'tv'
 * @param {number} season
 * @param {number} episode
 * @param {string} languageCode - 'en', 'es', etc.
 * @returns {Object|null} Cached subtitle record
 */
function getCachedSubtitle(tmdbId, type, season, episode, languageCode) {
  const row = db.prepare(`
    SELECT * FROM subtitles
    WHERE tmdb_id = ?
      AND type = ?
      AND COALESCE(season, 0) = COALESCE(?, 0)
      AND COALESCE(episode, 0) = COALESCE(?, 0)
      AND language_code = ?
  `).get(tmdbId, type, season || null, episode || null, languageCode);

  if (row) {
    // Update last accessed timestamp
    db.prepare(`
      UPDATE subtitles
      SET last_accessed_at = datetime('now')
      WHERE id = ?
    `).run(row.id);

    logger.info(`Subtitle cache hit: ${tmdbId} ${type} S${season}E${episode} (${languageCode})`);
  }

  return row;
}

/**
 * Search for subtitles on OpenSubtitles.com
 * @param {number} tmdbId
 * @param {string} type - 'movie' or 'tv'
 * @param {number} season
 * @param {number} episode
 * @param {string} languageCode - 'en', 'es', etc.
 * @returns {Promise<Array>} Array of subtitle results
 */
async function searchSubtitles(tmdbId, type, season, episode, languageCode = 'en') {
  try {
    const client = await getAuthenticatedClient();
    const params = {
      languages: languageCode,
      order_by: 'download_count' // Most popular first
    };

    if (type === 'movie') {
      params.tmdb_id = tmdbId;
      params.type = 'movie';
    } else {
      params.parent_tmdb_id = tmdbId;
      params.type = 'episode';
      if (season) params.season_number = season;
      if (episode) params.episode_number = episode;
    }

    logger.info(`Searching OpenSubtitles: TMDB ${tmdbId} ${type} S${season}E${episode} (${languageCode})`);

    const response = await client.get('/subtitles', { params });

    if (response.data && response.data.data) {
      logger.info(`Found ${response.data.data.length} subtitle results`);
      return response.data.data;
    }

    return [];
  } catch (error) {
    logger.error('OpenSubtitles search error:', error.response?.data || error.message);
    throw error;
  }
}

/**
 * Download subtitle file from OpenSubtitles with retry logic
 * @param {number} fileId - OpenSubtitles file ID
 * @param {number} retryCount - Current retry attempt
 * @returns {Promise<Object>} { link: string, file_name: string }
 */
async function getDownloadLink(fileId, retryCount = 0) {
  const maxRetries = 3;
  const retryDelay = Math.pow(2, retryCount) * 1000; // 1s, 2s, 4s

  try {
    // IMPORTANT: Download endpoint uses ONLY Api-Key, not Bearer token
    const response = await axios.post(
      `${OPENSUBTITLES_BASE_URL}/download`,
      { file_id: fileId },
      {
        headers: {
          'Api-Key': OPENSUBTITLES_API_KEY,
          'Content-Type': 'application/json',
          'User-Agent': 'DuckFlixLite v1.0',
          'Accept': 'application/json' // Override axios default - OpenSubtitles 503s on "application/json, text/plain, */*"
        },
        timeout: 10000 // 10 second timeout
      }
    );

    if (response.data && response.data.link) {
      logger.info(`Download link obtained: ${response.data.requests} requests, ${response.data.remaining} remaining`);
      return {
        link: response.data.link,
        file_name: response.data.file_name || `subtitle_${fileId}.srt`,
        remaining: response.data.remaining
      };
    }

    throw new Error('No download link returned from OpenSubtitles');
  } catch (error) {
    const status = error.response?.status;
    const isRetryable = status === 503 || status === 502 || error.code === 'ECONNRESET';

    if (isRetryable && retryCount < maxRetries) {
      logger.warn(`OpenSubtitles ${status || error.code} error, retrying in ${retryDelay}ms (attempt ${retryCount + 1}/${maxRetries})`);

      await new Promise(resolve => setTimeout(resolve, retryDelay));
      return getDownloadLink(fileId, retryCount + 1);
    }

    logger.error('OpenSubtitles download link error:', {
      status: error.response?.status,
      statusText: error.response?.statusText,
      message: error.message,
      retries: retryCount
    });
    throw error;
  }
}

/**
 * Download subtitle file content
 * @param {string} downloadUrl
 * @returns {Promise<Buffer>} File content
 */
async function downloadSubtitleFile(downloadUrl) {
  try {
    const response = await axios.get(downloadUrl, {
      responseType: 'arraybuffer'
    });

    return Buffer.from(response.data);
  } catch (error) {
    logger.error('Subtitle file download error:', error.message);
    throw error;
  }
}

/**
 * Save subtitle to disk and database
 * @param {Object} params
 * @returns {Object} Saved subtitle record
 */
function saveSubtitle({
  tmdbId,
  title,
  year,
  type,
  season,
  episode,
  language,
  languageCode,
  format,
  fileContent,
  opensubtitlesFileId
}) {
  // Generate filename
  const fileName = type === 'movie'
    ? `${tmdbId}_movie_${languageCode}.${format}`
    : `${tmdbId}_tv_s${season}_e${episode}_${languageCode}.${format}`;

  const filePath = path.join(SUBTITLES_DIR, fileName);
  const fileSize = Buffer.byteLength(fileContent);

  // Check if we need to free up space
  const currentUsage = getTotalStorageUsed();
  const neededSpace = currentUsage + fileSize - MAX_STORAGE_BYTES;

  if (neededSpace > 0) {
    freeUpStorage(neededSpace);
  }

  // Write file to disk
  fs.writeFileSync(filePath, fileContent);
  logger.info(`Saved subtitle file: ${fileName} (${(fileSize / 1024).toFixed(2)} KB)`);

  // Save to database (UPSERT)
  const result = db.prepare(`
    INSERT INTO subtitles (
      tmdb_id, title, year, type, season, episode,
      language, language_code, format, file_path, file_size_bytes,
      opensubtitles_file_id, downloaded_at, last_accessed_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, datetime('now'), datetime('now'))
    ON CONFLICT(tmdb_id, type, season, episode, language_code)
    DO UPDATE SET
      file_path = excluded.file_path,
      file_size_bytes = excluded.file_size_bytes,
      opensubtitles_file_id = excluded.opensubtitles_file_id,
      downloaded_at = datetime('now'),
      last_accessed_at = datetime('now')
  `).run(
    tmdbId,
    title,
    year,
    type,
    season || null,
    episode || null,
    language,
    languageCode,
    format,
    filePath,
    fileSize,
    opensubtitlesFileId
  );

  return {
    id: result.lastInsertRowid,
    filePath,
    fileSize,
    fileName
  };
}

/**
 * Get or download subtitle
 * @param {Object} params
 * @returns {Promise<Object>} Subtitle record with file info
 */
async function getSubtitle({ tmdbId, title, year, type, season, episode, languageCode = 'en' }) {
  // Check cache first
  const cached = getCachedSubtitle(tmdbId, type, season, episode, languageCode);
  if (cached && fs.existsSync(cached.file_path)) {
    // Standardize language name from cache (older entries may have non-standard names)
    const langResult = standardizeLanguage(cached.language);
    return {
      id: cached.id,
      fileName: path.basename(cached.file_path),
      filePath: cached.file_path,
      fileSize: cached.file_size_bytes,
      language: langResult.standardized || cached.language,
      languageCode: cached.language_code,
      format: cached.format,
      cached: true
    };
  }

  // Check daily quota
  const quota = checkDailyQuota();
  if (quota.exceeded) {
    throw new Error(`Daily subtitle download quota exceeded (${quota.limit}/day). Please try again tomorrow.`);
  }

  // Search OpenSubtitles
  const results = await searchSubtitles(tmdbId, type, season, episode, languageCode);

  if (results.length === 0) {
    throw new Error(`No subtitles found for TMDB ${tmdbId} (${languageCode})`);
  }

  // Get best result (first one, already sorted by popularity)
  const bestResult = results[0];
  const fileId = bestResult.attributes.files[0].file_id;

  // Get download link
  const downloadInfo = await getDownloadLink(fileId);

  // Download file content
  const fileContent = await downloadSubtitleFile(downloadInfo.link);

  // Increment quota
  incrementQuotaCount();

  // Standardize language name before saving
  const rawLanguage = bestResult.attributes.language || 'English';
  const langResult = standardizeLanguage(rawLanguage);
  const standardizedLanguage = langResult.standardized || rawLanguage;

  // Save to disk and database
  const saved = saveSubtitle({
    tmdbId,
    title,
    year,
    type,
    season,
    episode,
    language: standardizedLanguage,
    languageCode: languageCode,
    format: 'srt',
    fileContent,
    opensubtitlesFileId: fileId
  });

  logger.info(`Downloaded new subtitle: ${saved.fileName} (${standardizedLanguage})`);

  return {
    id: saved.id,
    fileName: saved.fileName,
    filePath: saved.filePath,
    fileSize: saved.fileSize,
    language: standardizedLanguage,
    languageCode: languageCode,
    format: 'srt',
    cached: false
  };
}

/**
 * Get subtitle file path by ID
 * @param {number} id
 * @returns {string|null}
 */
function getSubtitleFilePath(id) {
  const row = db.prepare('SELECT file_path FROM subtitles WHERE id = ?').get(id);

  if (row) {
    // Update last accessed
    db.prepare(`
      UPDATE subtitles
      SET last_accessed_at = datetime('now')
      WHERE id = ?
    `).run(id);
  }

  return row?.file_path || null;
}

/**
 * Get storage statistics
 */
function getStorageStats() {
  const total = getTotalStorageUsed();
  const count = db.prepare('SELECT COUNT(*) as count FROM subtitles').get().count;

  return {
    totalBytes: total,
    totalMB: (total / 1024 / 1024).toFixed(2),
    totalGB: (total / 1024 / 1024 / 1024).toFixed(2),
    maxGB: (MAX_STORAGE_BYTES / 1024 / 1024 / 1024).toFixed(2),
    usedPercent: ((total / MAX_STORAGE_BYTES) * 100).toFixed(2),
    subtitleCount: count
  };
}

/**
 * Cache an extracted embedded subtitle
 * @param {Object} params
 * @returns {Object|null} Saved subtitle record or null on failure
 */
function cacheExtractedSubtitle({
  tmdbId,
  title,
  year,
  type,
  season,
  episode,
  language,
  languageCode,
  extractedFilePath
}) {
  try {
    // Read the extracted file
    if (!fs.existsSync(extractedFilePath)) {
      logger.warn(`Extracted subtitle file not found: ${extractedFilePath}`);
      return null;
    }

    const fileContent = fs.readFileSync(extractedFilePath);
    const fileSize = fileContent.length;

    if (fileSize === 0) {
      logger.warn('Extracted subtitle file is empty, skipping cache');
      fs.unlinkSync(extractedFilePath); // Clean up empty file
      return null;
    }

    // Standardize language name
    const langResult = standardizeLanguage(language);
    const standardizedLanguage = langResult.standardized || language;

    // Generate final filename
    const fileName = type === 'movie'
      ? `${tmdbId}_movie_${languageCode}.srt`
      : `${tmdbId}_tv_s${season}_e${episode}_${languageCode}.srt`;

    const finalPath = path.join(SUBTITLES_DIR, fileName);

    // Move/rename to final location
    fs.renameSync(extractedFilePath, finalPath);

    // Check storage limits
    const currentUsage = getTotalStorageUsed();
    const neededSpace = currentUsage + fileSize - MAX_STORAGE_BYTES;
    if (neededSpace > 0) {
      freeUpStorage(neededSpace);
    }

    // Save to database (source: embedded)
    const result = db.prepare(`
      INSERT INTO subtitles (
        tmdb_id, title, year, type, season, episode,
        language, language_code, format, file_path, file_size_bytes,
        opensubtitles_file_id, downloaded_at, last_accessed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'srt', ?, ?, NULL, datetime('now'), datetime('now'))
      ON CONFLICT(tmdb_id, type, season, episode, language_code)
      DO UPDATE SET
        file_path = excluded.file_path,
        file_size_bytes = excluded.file_size_bytes,
        downloaded_at = datetime('now'),
        last_accessed_at = datetime('now')
    `).run(
      tmdbId,
      title,
      year,
      type,
      season || null,
      episode || null,
      standardizedLanguage,
      languageCode,
      finalPath,
      fileSize
    );

    logger.info(`Cached extracted subtitle: ${fileName} (${(fileSize / 1024).toFixed(2)} KB) - source: embedded`);

    return {
      id: result.lastInsertRowid,
      filePath: finalPath,
      fileSize,
      fileName,
      language: standardizedLanguage,
      languageCode
    };
  } catch (err) {
    logger.error(`Failed to cache extracted subtitle: ${err.message}`);
    // Clean up temp file if it exists
    try {
      if (fs.existsSync(extractedFilePath)) {
        fs.unlinkSync(extractedFilePath);
      }
    } catch {}
    return null;
  }
}

module.exports = {
  login,
  logout,
  getUserInfo,
  ensureAuthenticated,
  getSubtitle,
  getCachedSubtitle,
  cacheExtractedSubtitle,
  searchSubtitles,
  checkDailyQuota,
  getSubtitleFilePath,
  getStorageStats,
  SUBTITLES_DIR
};
