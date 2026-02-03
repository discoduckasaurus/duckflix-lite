const { db } = require('../db/init');
const logger = require('../utils/logger');

const CACHE_TTL = 48 * 60 * 60 * 1000; // 48 hours in ms

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
   * Get cached RD link, filtered by user's max bitrate
   * @param {Object} params
   * @param {number} params.tmdbId
   * @param {string} params.type - 'movie' or 'tv'
   * @param {number} [params.season]
   * @param {number} [params.episode]
   * @param {number} [params.maxBitrateMbps] - User's max playable bitrate
   */
  async getCachedLink({ tmdbId, type, season, episode, maxBitrateMbps }) {
    const now = Date.now();

    let query = `
      SELECT * FROM rd_link_cache
      WHERE tmdb_id = ? AND type = ?
        AND expires_at > ?
    `;
    const params = [tmdbId, type, now];

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
   */
  async cacheLink({ tmdbId, title, year, type, season, episode, streamUrl, fileName, resolution, estimatedBitrateMbps, fileSizeBytes }) {
    const now = Date.now();
    const expiresAt = now + CACHE_TTL;

    // Parse resolution from filename if not provided
    const res = resolution || parseResolutionFromFilename(fileName);

    db.prepare(`
      INSERT INTO rd_link_cache (
        tmdb_id, title, year, type, season, episode,
        stream_url, file_name, resolution, estimated_bitrate_mbps, file_size_bytes,
        created_at, expires_at, last_accessed_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(tmdb_id, type, season, episode, resolution)
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
      now, expiresAt, now
    );

    logger.info(`[RD Cache] Stored ${tmdbId} (${type}) - ${res}p @ ${estimatedBitrateMbps?.toFixed(1) || '?'} Mbps`);
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
}

module.exports = new RdCacheService();
