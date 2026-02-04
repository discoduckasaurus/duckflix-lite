/**
 * User service stub for duckflix_lite
 * In duckflix_lite, we use a single RD API key from .env rather than per-user keys
 */

/**
 * Get user's RD API key (returns default from .env since we don't have per-user keys)
 */
function getUserRdApiKey(userId) {
  return process.env.RD_API_KEY || null;
}

/**
 * Get effective user ID (returns the same userId)
 */
function getEffectiveUserId(userId) {
  return userId;
}

module.exports = {
  getUserRdApiKey,
  getEffectiveUserId
};
