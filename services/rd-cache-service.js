const { db } = require('../db/init');
const logger = require('../utils/logger');
const axios = require('axios');
const crypto = require('crypto');

const CACHE_TTL = 24 * 60 * 60 * 1000; // 24 hours in ms
const VERIFY_TIMEOUT = 5000; // 5 second timeout for link verification

/**
 * Hash an RD API key to a short identifier for cache isolation
 */
function hashRdKey(rdApiKey) {
  if (!rdApiKey) return '';
  return crypto.createHash('sha256').update(rdApiKey).digest('hex').substring(0, 12);
}

/**
 * Parse resolution from filename
 * @param {string} fileName
 * @returns {number|null}
 */
function parseResolutionFromFilename(fileName) {
  if (!fileName) return null;
  if (/2160p|4k|uhd/i.test(fileName)) return 2160;
  if (/1080p/i.test(fileName)) return 1080;
  if (/720p/i.test(fileName)) return 720;
  if (/480p/i.test(fileName)) return 480;
  return null;
}

class RdCacheService {
  /**
   * Get cached RD link, filtered by user's RD key and max bitrate
   * @param {Object} params
   * @param {number} params.tmdbId
   * @param {string} params.type - 'movie' or 'tv'
   * @param {number} [params.season]
   * @param {number} [params.episode]
   * @param {number} [params.maxBitrateMbps] - User's max playable bitrate
   * @param {string} [params.rdApiKey] - User's RD API key for per-user isolation
   */
  async getCachedLink({ tmdbId, type, season, episode, maxBitrateMbps, rdApiKey }) {
    const now = Date.now();
    const keyHash = hashRdKey(rdApiKey);

    let query = `
      SELECT * FROM rd_link_cache
      WHERE tmdb_id = ? AND type = ?
        AND rd_key_hash = ?
        AND expires_at > ?
    `;
    const params = [tmdbId, type, keyHash, now];

    // Add season/episode filters for TV
    if (type === 'tv') {
      query += ` AND season = ? AND episode = ?`;
      params.push(season, episode);
    }

    // Filter by bitrate if user has bandwidth measurement
    if (maxBitrateMbps && maxBitrateMbps > 0) {
      query += ` AND (estimated_bitrate_mbps IS NULL OR estimated_bitrate_mbps <= ?)`;
      params.push(maxBitrateMbps);
    }

    // Get highest resolution that fits bandwidth
    query += ` ORDER BY resolution DESC LIMIT 1`;

    const cached = db.prepare(query).get(...params);

    if (cached) {
      // Update last accessed
      db.prepare(`
        UPDATE rd_link_cache SET last_accessed_at = ? WHERE id = ?
      `).run(now, cached.id);

      logger.info(`[RD Cache] Hit for ${tmdbId} (${type}) - ${cached.resolution}p`);
      return {
        streamUrl: cached.stream_url,
        fileName: cached.file_name,
        resolution: cached.resolution,
        estimatedBitrateMbps: cached.estimated_bitrate_mbps
      };
    }

    logger.info(`[RD Cache] MISS for ${type} ${tmdbId}${type === 'tv' ? ` ${season}x${episode}` : ''}`);
    return null;
  }

  /**
   * Cache an RD link with quality tier info
   * @param {Object} params
   * @param {number} params.tmdbId
   * @param {string} params.title
   * @param {number} params.year
   * @param {string} params.type - 'movie' or 'tv'
   * @param {number} [params.season]
   * @param {number} [params.episode]
   * @param {string} params.streamUrl
   * @param {string} params.fileName
   * @param {number} [params.resolution]
   * @param {number} [params.estimatedBitrateMbps]
   * @param {number} [params.fileSizeBytes]
   * @param {string} [params.rdApiKey] - User's RD API key for per-user isolation
   */
  async cacheLink({ tmdbId, title, year, type, season, episode, streamUrl, fileName, resolution, estimatedBitrateMbps, fileSizeBytes, rdApiKey }) {
    const now = Date.now();
    const expiresAt = now + CACHE_TTL;
    const keyHash = hashRdKey(rdApiKey);

    // Parse resolution from filename if not provided
    const res = resolution || parseResolutionFromFilename(fileName);

    db.prepare(`
      INSERT INTO rd_link_cache (
        tmdb_id, title, year, type, season, episode,
        stream_url, file_name, resolution, estimated_bitrate_mbps, file_size_bytes,
        rd_key_hash, created_at, expires_at, last_accessed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(tmdb_id, type, season, episode, resolution, rd_key_hash)
      DO UPDATE SET
        stream_url = excluded.stream_url,
        file_name = excluded.file_name,
        estimated_bitrate_mbps = excluded.estimated_bitrate_mbps,
        file_size_bytes = excluded.file_size_bytes,
        expires_at = excluded.expires_at,
        last_accessed_at = excluded.last_accessed_at
    `).run(
      tmdbId, title, year, type, season || null, episode || null,
      streamUrl, fileName, res, estimatedBitrateMbps || null, fileSizeBytes || null,
      keyHash, now, expiresAt, now
    );

    logger.info(`[RD Cache] Stored ${tmdbId} (${type}) - ${res}p @ ${estimatedBitrateMbps?.toFixed(1) || '?'} Mbps`);
  }

  /**
   * Get cached RD link at a lower quality than the current stream.
   * Used by the fallback endpoint to find a lower-bitrate alternative.
   */
  async getCachedLinkLowerQuality({ tmdbId, type, season, episode, maxResolution, rdApiKey }) {
    const now = Date.now();
    const keyHash = hashRdKey(rdApiKey);

    let query = `
      SELECT * FROM rd_link_cache
      WHERE tmdb_id = ? AND type = ?
        AND rd_key_hash = ?
        AND expires_at > ?
        AND resolution IS NOT NULL
        AND resolution < ?
    `;
    const params = [tmdbId, type, keyHash, now, maxResolution];

    if (type === 'tv') {
      query += ` AND season = ? AND episode = ?`;
      params.push(season, episode);
    }

    // Best quality under the cap
    query += ` ORDER BY resolution DESC LIMIT 1`;

    const cached = db.prepare(query).get(...params);

    if (cached) {
      db.prepare(`UPDATE rd_link_cache SET last_accessed_at = ? WHERE id = ?`).run(now, cached.id);
      logger.info(`[RD Cache] Lower-quality hit for ${tmdbId} (${type}) - ${cached.resolution}p (max was ${maxResolution}p)`);
      return {
        streamUrl: cached.stream_url,
        fileName: cached.file_name,
        resolution: cached.resolution,
        estimatedBitrateMbps: cached.estimated_bitrate_mbps
      };
    }

    return null;
  }

  /**
   * Cleanup expired links (run periodically)
   */
  async cleanupExpired() {
    const now = Date.now();
    const result = db.prepare('DELETE FROM rd_link_cache WHERE expires_at < ?').run(now);

    if (result.changes > 0) {
      logger.info(`[RD Cache] Cleaned up ${result.changes} expired links`);
    }

    return result.changes;
  }

  /**
   * Verify if an RD link is still valid/accessible
   * Uses HEAD request to check without downloading content
   * @param {string} streamUrl - The RD download URL to verify
   * @returns {Promise<boolean>} - True if link is still valid
   */
  async verifyLink(streamUrl) {
    if (!streamUrl) return false;

    try {
      const response = await axios.head(streamUrl, {
        timeout: VERIFY_TIMEOUT,
        maxRedirects: 5,
        validateStatus: (status) => status < 400 // Accept any 2xx or 3xx
      });

      // RD links return 200 or redirect (302/307) if valid
      const isValid = response.status >= 200 && response.status < 400;

      if (isValid) {
        logger.info(`[RD Cache] Link verified OK (${response.status})`);
      } else {
        logger.info(`[RD Cache] Link verification failed (${response.status})`);
      }

      return isValid;
    } catch (error) {
      // Link is dead, expired, or network error
      logger.info(`[RD Cache] Link verification failed: ${error.message}`);
      return false;
    }
  }

  /**
   * Invalidate/delete a cached link (e.g., when verification fails)
   * @param {number} cacheId - The cache entry ID
   */
  async invalidateLink(cacheId) {
    try {
      db.prepare('DELETE FROM rd_link_cache WHERE id = ?').run(cacheId);
      logger.info(`[RD Cache] Invalidated cache entry ${cacheId}`);
    } catch (error) {
      logger.warn(`[RD Cache] Failed to invalidate cache entry: ${error.message}`);
    }
  }
}

module.exports = new RdCacheService();
