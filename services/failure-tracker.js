const { db } = require('../db/init');
const logger = require('../utils/logger');

/**
 * Log a playback failure
 */
function logPlaybackFailure(userId, username, tmdbId, title, season, episode, error) {
  try {
    const stmt = db.prepare(`
      INSERT INTO playback_failures (user_id, username, tmdb_id, title, season, episode, error)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `);

    stmt.run(userId, username, tmdbId, title, season, episode, error);

    logger.info(`[Failure Tracker] Logged failure for user ${username}: ${title} S${season || 'N/A'}E${episode || 'N/A'}`);
  } catch (err) {
    logger.error('[Failure Tracker] Failed to log playback failure:', err);
  }
}

/**
 * Get playback failures with pagination
 */
function getPlaybackFailures(limit = 50, offset = 0) {
  try {
    const countStmt = db.prepare('SELECT COUNT(*) as total FROM playback_failures');
    const { total } = countStmt.get();

    const stmt = db.prepare(`
      SELECT
        id,
        user_id as userId,
        username,
        tmdb_id as tmdbId,
        title,
        season,
        episode,
        error,
        created_at as timestamp
      FROM playback_failures
      ORDER BY created_at DESC
      LIMIT ? OFFSET ?
    `);

    const failures = stmt.all(limit, offset);

    return {
      failures,
      total
    };
  } catch (err) {
    logger.error('[Failure Tracker] Failed to get playback failures:', err);
    throw err;
  }
}

/**
 * Get recent failures count (last 24 hours)
 */
function getRecentFailuresCount() {
  try {
    const stmt = db.prepare(`
      SELECT COUNT(*) as count
      FROM playback_failures
      WHERE created_at > datetime('now', '-24 hours')
    `);

    const { count } = stmt.get();
    return count;
  } catch (err) {
    logger.error('[Failure Tracker] Failed to get recent failures count:', err);
    return 0;
  }
}

/**
 * Get failures by user ID
 */
function getFailuresByUser(userId, limit = 50) {
  try {
    const stmt = db.prepare(`
      SELECT
        id,
        user_id as userId,
        username,
        tmdb_id as tmdbId,
        title,
        season,
        episode,
        error,
        created_at as timestamp
      FROM playback_failures
      WHERE user_id = ?
      ORDER BY created_at DESC
      LIMIT ?
    `);

    return stmt.all(userId, limit);
  } catch (err) {
    logger.error('[Failure Tracker] Failed to get failures by user:', err);
    throw err;
  }
}

module.exports = {
  logPlaybackFailure,
  getPlaybackFailures,
  getRecentFailuresCount,
  getFailuresByUser
};
