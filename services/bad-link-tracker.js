/**
 * Bad Link Tracker
 *
 * Tracks user-reported bad streams/sources
 * Flags last-try for 48h (matches RD cache duration)
 */

const logger = require('../utils/logger');

const BAD_LINK_TTL = 48 * 60 * 60 * 1000; // 48 hours (matches RD cache)

// In-memory storage: Map<hash|streamUrl, { reportedAt, reportedBy[], reason }>
const badLinks = new Map();

/**
 * Report a bad link/source
 */
function reportBadLink({ hash, streamUrl, magnetUrl, source, reportedBy, reason }) {
  const key = hash || streamUrl || magnetUrl;

  if (!key) {
    logger.warn('Cannot report bad link: no identifier provided');
    return false;
  }

  const existing = badLinks.get(key);

  if (existing) {
    // Add to reporters list if not already there
    if (!existing.reportedBy.includes(reportedBy)) {
      existing.reportedBy.push(reportedBy);
      existing.reportCount = existing.reportedBy.length;
      logger.info(`🚩 Bad link updated (${existing.reportCount} reports): ${key.substring(0, 60)}`);
    }
  } else {
    // New bad link report
    badLinks.set(key, {
      reportedAt: Date.now(),
      expiresAt: Date.now() + BAD_LINK_TTL,
      reportedBy: [reportedBy],
      reportCount: 1,
      reason: reason || 'User reported as bad',
      source,
      hash,
      streamUrl,
      magnetUrl
    });

    logger.info(`🚩 New bad link reported by ${reportedBy}: ${key.substring(0, 60)} - ${reason}`);
  }

  return true;
}

/**
 * Check if a source is flagged as bad
 */
function isBadLink({ hash, streamUrl, magnetUrl }) {
  const key = hash || streamUrl || magnetUrl;

  if (!key) return false;

  const entry = badLinks.get(key);

  if (!entry) return false;

  // Check if expired
  if (Date.now() >= entry.expiresAt) {
    badLinks.delete(key);
    return false;
  }

  return true;
}

/**
 * Get bad link info
 */
function getBadLinkInfo({ hash, streamUrl, magnetUrl }) {
  const key = hash || streamUrl || magnetUrl;

  if (!key) return null;

  const entry = badLinks.get(key);

  if (!entry) return null;

  // Check if expired
  if (Date.now() >= entry.expiresAt) {
    badLinks.delete(key);
    return null;
  }

  return {
    reportCount: entry.reportCount,
    reportedAt: entry.reportedAt,
    expiresAt: entry.expiresAt,
    reason: entry.reason,
    timeRemaining: Math.max(0, entry.expiresAt - Date.now())
  };
}

/**
 * Get all bad links (for debugging/admin)
 */
function getAllBadLinks() {
  const now = Date.now();
  const active = [];

  for (const [key, entry] of badLinks.entries()) {
    if (now < entry.expiresAt) {
      active.push({
        key: key.substring(0, 80),
        reportCount: entry.reportCount,
        reportedAt: entry.reportedAt,
        expiresAt: entry.expiresAt,
        reason: entry.reason,
        source: entry.source
      });
    } else {
      badLinks.delete(key);
    }
  }

  return active;
}

/**
 * Clear all bad link flags (admin function)
 */
function clearAllBadLinks() {
  const count = badLinks.size;
  badLinks.clear();
  logger.info(`🧹 Cleared all ${count} bad link flags`);
  return count;
}

/**
 * Periodic cleanup of expired entries
 */
function cleanup() {
  const now = Date.now();
  let cleaned = 0;

  for (const [key, entry] of badLinks.entries()) {
    if (now >= entry.expiresAt) {
      badLinks.delete(key);
      cleaned++;
    }
  }

  if (cleaned > 0) {
    logger.info(`🧹 Cleaned up ${cleaned} expired bad link flags`);
  }
}

// Run cleanup every hour
setInterval(cleanup, 60 * 60 * 1000);

module.exports = {
  reportBadLink,
  isBadLink,
  getBadLinkInfo,
  getAllBadLinks,
  clearAllBadLinks,
  BAD_LINK_TTL
};
