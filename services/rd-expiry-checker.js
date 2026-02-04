/**
 * Real-Debrid Expiry Checker Service
 * Checks RD API subscription expiry dates for users
 */

const axios = require('axios');
const { db } = require('../db/init');
const logger = require('../utils/logger');

const RD_API_BASE = 'https://api.real-debrid.com/rest/1.0';

/**
 * Get user info from Real-Debrid API
 * @param {string} apiKey - RD API key
 * @returns {Promise<{premium_until: string, type: string}>}
 */
async function getRDUserInfo(apiKey) {
  try {
    const response = await axios.get(`${RD_API_BASE}/user`, {
      headers: { 'Authorization': `Bearer ${apiKey}` },
      timeout: 10000
    });
    return response.data;
  } catch (error) {
    if (error.response?.status === 401 || error.response?.status === 403) {
      throw new Error('Invalid or expired RD API key');
    }
    throw error;
  }
}

/**
 * Check and update RD expiry for a single user
 * @param {number} userId - User ID
 * @param {string} rdApiKey - RD API key
 * @returns {Promise<{expiryDate: string, daysRemaining: number, isExpired: boolean}>}
 */
async function checkUserRDExpiry(userId, rdApiKey) {
  try {
    if (!rdApiKey) {
      throw new Error('No RD API key provided');
    }

    const userInfo = await getRDUserInfo(rdApiKey);

    // RD returns premium_until as ISO timestamp (e.g., "2024-12-31T23:59:59.000Z")
    const expiryDate = userInfo.expiration || userInfo.premium_until;

    if (!expiryDate) {
      throw new Error('No expiry date returned from RD API');
    }

    // Calculate days remaining
    const now = new Date();
    const expiry = new Date(expiryDate);
    const daysRemaining = Math.floor((expiry - now) / (1000 * 60 * 60 * 24));
    const isExpired = daysRemaining < 0;

    // Update database
    db.prepare(`
      UPDATE users
      SET rd_expiry_date = ?, updated_at = CURRENT_TIMESTAMP
      WHERE id = ?
    `).run(expiryDate, userId);

    logger.info(`[RD Expiry] User ${userId}: ${daysRemaining} days remaining`);

    return {
      expiryDate,
      daysRemaining,
      isExpired,
      accountType: userInfo.type
    };
  } catch (error) {
    logger.error(`[RD Expiry] Failed to check user ${userId}:`, error.message);

    // If RD key is invalid, mark the date as null
    if (error.message.includes('Invalid or expired')) {
      db.prepare(`
        UPDATE users
        SET rd_expiry_date = NULL, updated_at = CURRENT_TIMESTAMP
        WHERE id = ?
      `).run(userId);
    }

    throw error;
  }
}

/**
 * Check RD expiry for all users with RD API keys
 * @returns {Promise<{checked: number, errors: number}>}
 */
async function checkAllUsersRDExpiry() {
  try {
    const users = db.prepare(`
      SELECT id, username, rd_api_key
      FROM users
      WHERE rd_api_key IS NOT NULL AND rd_api_key != ''
    `).all();

    let checked = 0;
    let errors = 0;

    for (const user of users) {
      try {
        await checkUserRDExpiry(user.id, user.rd_api_key);
        checked++;
      } catch (error) {
        errors++;
        logger.error(`[RD Expiry] Failed for user ${user.username}:`, error.message);
      }
    }

    logger.info(`[RD Expiry] Checked ${checked} users, ${errors} errors`);

    return { checked, errors };
  } catch (error) {
    logger.error('[RD Expiry] Batch check failed:', error);
    throw error;
  }
}

/**
 * Validate RD API key and get expiry info
 * Used when user adds/updates their RD key
 * @param {string} apiKey - RD API key to validate
 * @returns {Promise<{valid: boolean, expiryDate: string, accountType: string}>}
 */
async function validateRDKey(apiKey) {
  try {
    const userInfo = await getRDUserInfo(apiKey);
    const expiryDate = userInfo.expiration || userInfo.premium_until;

    return {
      valid: true,
      expiryDate: expiryDate || null,
      accountType: userInfo.type || 'unknown'
    };
  } catch (error) {
    return {
      valid: false,
      error: error.message
    };
  }
}

module.exports = {
  checkUserRDExpiry,
  checkAllUsersRDExpiry,
  validateRDKey,
  getRDUserInfo
};
