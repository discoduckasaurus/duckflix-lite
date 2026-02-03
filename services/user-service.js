const { db } = require('../db/init');
const logger = require('../utils/logger');

/**
 * Get user's RD API key, with parent inheritance for sub-accounts
 * @param {number} userId - User ID
 * @returns {string|null} RD API key or null if not found
 */
function getUserRdApiKey(userId) {
  try {
    const user = db.prepare('SELECT rd_api_key, parent_user_id FROM users WHERE id = ?').get(userId);

    if (!user) {
      logger.warn(`User ${userId} not found`);
      return null;
    }

    // If user has their own RD API key, use it
    if (user.rd_api_key) {
      return user.rd_api_key;
    }

    // If user is a sub-account, inherit parent's RD API key
    if (user.parent_user_id) {
      const parent = db.prepare('SELECT rd_api_key FROM users WHERE id = ?').get(user.parent_user_id);
      if (parent && parent.rd_api_key) {
        logger.info(`User ${userId} inheriting RD API key from parent ${user.parent_user_id}`);
        return parent.rd_api_key;
      }
    }

    logger.warn(`No RD API key found for user ${userId}`);
    return null;
  } catch (error) {
    logger.error(`Error getting RD API key for user ${userId}:`, error);
    return null;
  }
}

/**
 * Get effective user ID for IP session checking (parent for sub-accounts)
 * Sub-accounts share the parent's IP session to allow family/household use
 * @param {number} userId - User ID
 * @returns {number} Effective user ID (parent ID if sub-account, otherwise own ID)
 */
function getEffectiveUserId(userId) {
  try {
    const user = db.prepare('SELECT parent_user_id FROM users WHERE id = ?').get(userId);

    if (!user) {
      return userId;
    }

    // If sub-account, return parent's ID for session checking
    if (user.parent_user_id) {
      logger.info(`User ${userId} is sub-account, using parent ${user.parent_user_id} for session check`);
      return user.parent_user_id;
    }

    return userId;
  } catch (error) {
    logger.error(`Error getting effective user ID for ${userId}:`, error);
    return userId;
  }
}

/**
 * Get user's bandwidth info for source filtering
 * @param {number} userId
 * @returns {{ maxBitrateMbps: number, hasMeasurement: boolean }}
 */
function getUserBandwidthInfo(userId) {
  try {
    const user = db.prepare(`
      SELECT measured_bandwidth_mbps, bandwidth_safety_margin
      FROM users WHERE id = ?
    `).get(userId);

    if (!user || !user.measured_bandwidth_mbps) {
      return {
        maxBitrateMbps: 10 / 1.3,
        hasMeasurement: false
      };
    }

    const margin = user.bandwidth_safety_margin || 1.3;
    return {
      maxBitrateMbps: user.measured_bandwidth_mbps / margin,
      hasMeasurement: true
    };
  } catch (error) {
    logger.error(`Error getting bandwidth info for user ${userId}:`, error);
    return {
      maxBitrateMbps: 10 / 1.3,
      hasMeasurement: false
    };
  }
}

module.exports = {
  getUserRdApiKey,
  getEffectiveUserId,
  getUserBandwidthInfo
};
