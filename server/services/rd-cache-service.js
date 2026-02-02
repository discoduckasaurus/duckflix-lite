const { db } = require('../db/init');
const logger = require('../utils/logger');

const CACHE_TTL = 48 * 60 * 60 * 1000; // 48 hours in ms

class RdCacheService {
  /**
   * Get cached RD link if available and not expired
   */
  async getCachedLink({ tmdbId, type, season, episode }) {
    const now = Date.now();

    const query = `
      SELECT stream_url, file_name, expires_at
      FROM rd_link_cache
      WHERE tmdb_id = ?
        AND type = ?
        AND (season IS ? OR season = ?)
        AND (episode IS ? OR episode = ?)
        AND expires_at > ?
      ORDER BY created_at DESC
      LIMIT 1
    `;

    const result = db.prepare(query).get(
      tmdbId,
      type,
      season,
      season,
      episode,
      episode,
      now
    );

    if (result) {
      // Update last accessed timestamp
      db.prepare(
        'UPDATE rd_link_cache SET last_accessed_at = ? WHERE stream_url = ?'
      ).run(now, result.stream_url);

      logger.info(`[RD Cache] HIT for ${type} ${tmdbId} ${season}x${episode}`);
      return {
        streamUrl: result.stream_url,
        fileName: result.file_name
      };
    }

    logger.info(`[RD Cache] MISS for ${type} ${tmdbId} ${season}x${episode}`);
    return null;
  }

  /**
   * Cache a new RD link
   */
  async cacheLink({ tmdbId, title, year, type, season, episode, streamUrl, fileName }) {
    const now = Date.now();
    const expiresAt = now + CACHE_TTL;

    const query = `
      INSERT INTO rd_link_cache
      (tmdb_id, title, year, type, season, episode, stream_url, file_name, created_at, expires_at, last_accessed_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `;

    db.prepare(query).run(
      tmdbId,
      title,
      year,
      type,
      season,
      episode,
      streamUrl,
      fileName,
      now,
      expiresAt,
      now
    );

    logger.info(`[RD Cache] CACHED for ${type} ${tmdbId} ${season}x${episode} (expires in 48h)`);
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
