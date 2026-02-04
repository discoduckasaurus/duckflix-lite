/**
 * Real-Debrid Session Tracker
 * Prevents concurrent streaming from same RD key on different IPs
 */

const { db } = require('../db/init');
const logger = require('../utils/logger');

// Initialize RD sessions table
function initRdSessionsTable() {
  db.exec(`
    CREATE TABLE IF NOT EXISTS rd_sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      rd_api_key TEXT NOT NULL,
      ip_address TEXT NOT NULL,
      user_id INTEGER NOT NULL,
      username TEXT NOT NULL,
      stream_started_at TEXT NOT NULL,
      last_heartbeat_at TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now')),
      UNIQUE(rd_api_key, ip_address),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);
  logger.info('RD sessions table initialized');
}

/**
 * Check if RD key is being used from a different IP
 * @param {string} rdApiKey - RD API key to check
 * @param {string} currentIp - Current IP address
 * @param {number} userId - User ID attempting to stream
 * @returns {Promise<{allowed: boolean, activeSession?: object}>}
 */
function checkRdSession(rdApiKey, currentIp, userId) {
  try {
    const sessionTimeoutMs = 5000; // 5 seconds after last heartbeat
    const timeoutThreshold = new Date(Date.now() - sessionTimeoutMs).toISOString();

    // Check for active sessions with this RD key on different IPs
    const activeSessions = db.prepare(`
      SELECT rd_api_key, ip_address, username, stream_started_at, last_heartbeat_at
      FROM rd_sessions
      WHERE rd_api_key = ?
        AND ip_address != ?
        AND last_heartbeat_at > ?
    `).all(rdApiKey, currentIp, timeoutThreshold);

    if (activeSessions.length > 0) {
      const activeSession = activeSessions[0];
      logger.warn(`[RD Session] Concurrent stream blocked - RD key in use on IP ${activeSession.ip_address} by ${activeSession.username}`);

      return {
        allowed: false,
        activeSession: {
          username: activeSession.username,
          ipAddress: activeSession.ip_address,
          startedAt: activeSession.stream_started_at
        }
      };
    }

    return { allowed: true };
  } catch (error) {
    logger.error('[RD Session] Check failed:', error);
    // Allow on error to prevent blocking legitimate requests
    return { allowed: true };
  }
}

/**
 * Start a new RD session (when playback begins)
 * @param {string} rdApiKey - RD API key
 * @param {string} ipAddress - IP address
 * @param {number} userId - User ID
 * @param {string} username - Username
 */
function startRdSession(rdApiKey, ipAddress, userId, username) {
  try {
    const now = new Date().toISOString();

    db.prepare(`
      INSERT INTO rd_sessions (rd_api_key, ip_address, user_id, username, stream_started_at, last_heartbeat_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(rd_api_key, ip_address)
      DO UPDATE SET
        stream_started_at = ?,
        last_heartbeat_at = ?,
        username = ?
    `).run(rdApiKey, ipAddress, userId, username, now, now, now, now, username);

    logger.info(`[RD Session] Started for ${username} on ${ipAddress} with RD ${rdApiKey.slice(-6)}`);
  } catch (error) {
    logger.error('[RD Session] Failed to start session:', error);
  }
}

/**
 * Update heartbeat for active RD session
 * @param {string} rdApiKey - RD API key
 * @param {string} ipAddress - IP address
 */
function updateRdHeartbeat(rdApiKey, ipAddress) {
  try {
    const result = db.prepare(`
      UPDATE rd_sessions
      SET last_heartbeat_at = datetime('now')
      WHERE rd_api_key = ? AND ip_address = ?
    `).run(rdApiKey, ipAddress);

    if (result.changes > 0) {
      logger.debug(`[RD Session] Heartbeat updated for ${rdApiKey.slice(-6)} on ${ipAddress}`);
    }
  } catch (error) {
    logger.error('[RD Session] Failed to update heartbeat:', error);
  }
}

/**
 * End RD session (when stream stops)
 * @param {string} rdApiKey - RD API key
 * @param {string} ipAddress - IP address
 */
function endRdSession(rdApiKey, ipAddress) {
  try {
    const result = db.prepare(`
      DELETE FROM rd_sessions
      WHERE rd_api_key = ? AND ip_address = ?
    `).run(rdApiKey, ipAddress);

    if (result.changes > 0) {
      logger.info(`[RD Session] Ended for ${rdApiKey.slice(-6)} on ${ipAddress}`);
    }
  } catch (error) {
    logger.error('[RD Session] Failed to end session:', error);
  }
}

/**
 * Cleanup expired RD sessions (older than 30 seconds)
 * Run periodically to remove stale sessions
 */
function cleanupExpiredRdSessions() {
  try {
    const expiryThreshold = new Date(Date.now() - 30000).toISOString(); // 30s timeout

    const result = db.prepare(`
      DELETE FROM rd_sessions
      WHERE last_heartbeat_at < ?
    `).run(expiryThreshold);

    if (result.changes > 0) {
      logger.info(`[RD Session] Cleaned up ${result.changes} expired sessions`);
    }
  } catch (error) {
    logger.error('[RD Session] Cleanup failed:', error);
  }
}

/**
 * Get all active RD sessions
 * @returns {Array} Active sessions
 */
function getActiveRdSessions() {
  try {
    const timeoutThreshold = new Date(Date.now() - 30000).toISOString();

    return db.prepare(`
      SELECT rd_api_key, ip_address, username, stream_started_at, last_heartbeat_at
      FROM rd_sessions
      WHERE last_heartbeat_at > ?
      ORDER BY last_heartbeat_at DESC
    `).all(timeoutThreshold);
  } catch (error) {
    logger.error('[RD Session] Failed to get active sessions:', error);
    return [];
  }
}

module.exports = {
  initRdSessionsTable,
  checkRdSession,
  startRdSession,
  updateRdHeartbeat,
  endRdSession,
  cleanupExpiredRdSessions,
  getActiveRdSessions
};
