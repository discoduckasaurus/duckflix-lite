const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/init');
const { authenticateToken, requireAdmin } = require('../middleware/auth');
const logger = require('../utils/logger');
const { validateRDKey, checkUserRDExpiry } = require('../services/rd-expiry-checker');
const opensubtitlesService = require('../services/opensubtitles-service');
const fs = require('fs');
const path = require('path');

const router = express.Router();

// All admin routes require authentication and admin privileges
router.use(authenticateToken);
router.use(requireAdmin);

/**
 * GET /api/admin/users
 * Get all users
 */
router.get('/users', (req, res) => {
  try {
    const users = db.prepare(`
      SELECT id, username, is_admin, parent_user_id, rd_api_key, rd_expiry_date, enabled, created_at, last_login_at
      FROM users
      ORDER BY created_at DESC
    `).all();

    res.json({
      users: users.map(u => ({
        id: u.id,
        username: u.username,
        isAdmin: !!u.is_admin,
        parentUserId: u.parent_user_id,
        // SECURITY: Mask RD API keys (only show last 4 chars)
        rdApiKey: u.rd_api_key ? `****${u.rd_api_key.slice(-4)}` : null,
        rdExpiryDate: u.rd_expiry_date,
        enabled: !!u.enabled,
        createdAt: u.created_at,
        lastLoginAt: u.last_login_at
      }))
    });
  } catch (error) {
    logger.error('Get users error:', error);
    res.status(500).json({ error: 'Failed to get users' });
  }
});

/**
 * POST /api/admin/users
 * Create new user
 */
router.post('/users', async (req, res) => {
  try {
    const { username, password, isAdmin, parentUserId, rdApiKey, rdExpiryDate } = req.body;

    if (!username || !password) {
      return res.status(400).json({ error: 'Username and password required' });
    }

    // Check for case-insensitive duplicate username
    const existingUser = db.prepare('SELECT id FROM users WHERE LOWER(username) = LOWER(?)').get(username);
    if (existingUser) {
      return res.status(409).json({ error: 'Username already exists' });
    }

    // Validate parent user exists if provided
    let parent = null;
    if (parentUserId) {
      parent = db.prepare('SELECT id, enabled, rd_api_key, rd_expiry_date FROM users WHERE id = ?').get(parentUserId);
      if (!parent) {
        return res.status(400).json({ error: 'Parent user not found' });
      }
    }

    // Validate and check RD API key if provided
    let validatedRdKey = rdApiKey;
    let validatedExpiry = rdExpiryDate;
    let enabled = 0; // Disabled by default until RD key is validated

    // Sub-accounts inherit parent's status
    if (parent) {
      enabled = parent.enabled; // Inherit parent's enabled status
      logger.info(`[User Create] Sub-account ${username} inherits parent's enabled status: ${enabled}`);
    } else if (rdApiKey) {
      // Standalone account with RD key
      try {
        const rdValidation = await validateRDKey(rdApiKey);
        if (rdValidation.valid) {
          validatedExpiry = rdValidation.expiryDate;
          enabled = 1; // Enable user if RD key is valid
          logger.info(`[User Create] Valid RD key for ${username}, expiry: ${validatedExpiry}`);
        } else {
          return res.status(400).json({ error: `Invalid RD API key: ${rdValidation.error}` });
        }
      } catch (error) {
        return res.status(400).json({ error: `Failed to validate RD API key: ${error.message}` });
      }
    } else {
      // Standalone account without RD key - stays disabled
      logger.info(`[User Create] Standalone account ${username} created without RD key, disabled`);
    }

    // Hash password
    const passwordHash = await bcrypt.hash(password, 10);

    // Create user
    const result = db.prepare(`
      INSERT INTO users (username, password_hash, is_admin, parent_user_id, rd_api_key, rd_expiry_date, enabled)
      VALUES (?, ?, ?, ?, ?, ?, ?)
    `).run(username, passwordHash, isAdmin ? 1 : 0, parentUserId || null, validatedRdKey || null, validatedExpiry || null, enabled);

    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(result.lastInsertRowid);

    logger.info(`User created: ${username} (ID: ${user.id}, enabled: ${enabled})`);

    res.status(201).json({
      user: {
        id: user.id,
        username: user.username,
        isAdmin: !!user.is_admin,
        parentUserId: user.parent_user_id,
        rdApiKey: user.rd_api_key,
        rdExpiryDate: user.rd_expiry_date,
        enabled: !!user.enabled
      }
    });
  } catch (error) {
    if (error.code === 'SQLITE_CONSTRAINT_UNIQUE') {
      return res.status(409).json({ error: 'Username already exists' });
    }
    logger.error('Create user error:', error);
    res.status(500).json({ error: 'Failed to create user' });
  }
});

/**
 * PUT /api/admin/users/:id
 * Update user
 */
router.put('/users/:id', async (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);
    const { username, password, rdApiKey, parentUserId, isAdmin } = req.body;

    // Check user exists
    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Validate parent user if provided
    let parent = null;
    if (parentUserId !== undefined) {
      if (parentUserId) {
        parent = db.prepare('SELECT id, enabled, rd_api_key FROM users WHERE id = ?').get(parentUserId);
        if (!parent) {
          return res.status(400).json({ error: 'Parent user not found' });
        }
      }
    }

    // Build update query dynamically
    const updates = [];
    const values = [];

    if (username !== undefined) {
      // Check for case-insensitive duplicate username (excluding current user)
      const existingUser = db.prepare('SELECT id FROM users WHERE LOWER(username) = LOWER(?) AND id != ?').get(username, userId);
      if (existingUser) {
        return res.status(409).json({ error: 'Username already exists' });
      }
      updates.push('username = ?');
      values.push(username);
    }

    if (password) {
      const passwordHash = await bcrypt.hash(password, 10);
      updates.push('password_hash = ?');
      values.push(passwordHash);
    }

    if (isAdmin !== undefined) {
      updates.push('is_admin = ?');
      values.push(isAdmin ? 1 : 0);
    }

    if (parentUserId !== undefined) {
      updates.push('parent_user_id = ?');
      values.push(parentUserId || null);
    }

    // Handle RD API key updates
    let validatedRdKey = rdApiKey;
    let validatedExpiry = null;
    let shouldUpdateEnabled = false;
    let newEnabledStatus = user.enabled;

    if (rdApiKey !== undefined && rdApiKey) {
      // Validate RD key if provided
      try {
        const rdValidation = await validateRDKey(rdApiKey);
        if (rdValidation.valid) {
          validatedExpiry = rdValidation.expiryDate;
          updates.push('rd_api_key = ?');
          values.push(rdApiKey);
          updates.push('rd_expiry_date = ?');
          values.push(validatedExpiry);

          // Enable if RD key is valid and no parent
          if (!parent && !parentUserId) {
            shouldUpdateEnabled = true;
            newEnabledStatus = 1;
          }
        } else {
          return res.status(400).json({ error: `Invalid RD API key: ${rdValidation.error}` });
        }
      } catch (error) {
        return res.status(400).json({ error: `Failed to validate RD API key: ${error.message}` });
      }
    } else if (rdApiKey === null || rdApiKey === '') {
      // Removing RD key
      updates.push('rd_api_key = ?');
      values.push(null);
      updates.push('rd_expiry_date = ?');
      values.push(null);
    }

    // Update enabled status based on parent change
    if (parentUserId !== undefined) {
      shouldUpdateEnabled = true;
      if (parent) {
        // Converting to sub-user - inherit parent's enabled status
        newEnabledStatus = parent.enabled;
        logger.info(`[User Update] Converting ${user.username} to sub-user, inheriting enabled=${newEnabledStatus}`);
      } else {
        // Converting to standalone - enabled only if has valid RD key
        newEnabledStatus = user.rd_api_key || validatedRdKey ? 1 : 0;
        logger.info(`[User Update] Converting ${user.username} to standalone, enabled=${newEnabledStatus}`);
      }
    }

    if (shouldUpdateEnabled) {
      updates.push('enabled = ?');
      values.push(newEnabledStatus);
    }

    if (updates.length === 0) {
      return res.status(400).json({ error: 'No fields to update' });
    }

    values.push(userId);

    db.prepare(`
      UPDATE users SET ${updates.join(', ')}, updated_at = datetime('now')
      WHERE id = ?
    `).run(...values);

    const updatedUser = db.prepare('SELECT * FROM users WHERE id = ?').get(userId);

    logger.info(`User updated: ${updatedUser.username} (ID: ${userId}, enabled: ${updatedUser.enabled})`);

    res.json({
      user: {
        id: updatedUser.id,
        username: updatedUser.username,
        isAdmin: !!updatedUser.is_admin,
        parentUserId: updatedUser.parent_user_id,
        rdApiKey: updatedUser.rd_api_key,
        rdExpiryDate: updatedUser.rd_expiry_date,
        enabled: !!updatedUser.enabled
      }
    });
  } catch (error) {
    if (error.code === 'SQLITE_CONSTRAINT_UNIQUE') {
      return res.status(409).json({ error: 'Username already exists' });
    }
    logger.error('Update user error:', error);
    res.status(500).json({ error: 'Failed to update user' });
  }
});

/**
 * DELETE /api/admin/users/:id
 * Delete user (and cascading delete sub-accounts)
 */
router.delete('/users/:id', (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);

    // Prevent deleting yourself
    if (userId === req.user.sub) {
      return res.status(400).json({ error: 'Cannot delete your own account' });
    }

    const result = db.prepare('DELETE FROM users WHERE id = ?').run(userId);

    if (result.changes === 0) {
      return res.status(404).json({ error: 'User not found' });
    }

    logger.info(`User deleted: ID ${userId}`);

    res.json({ success: true });
  } catch (error) {
    logger.error('Delete user error:', error);
    res.status(500).json({ error: 'Failed to delete user' });
  }
});

/**
 * POST /api/admin/users/:id/rd-key
 * Set RD API key for user
 */
router.post('/users/:id/rd-key', (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);
    const { rdApiKey, rdExpiryDate } = req.body;

    if (!rdApiKey) {
      return res.status(400).json({ error: 'RD API key required' });
    }

    const result = db.prepare(`
      UPDATE users
      SET rd_api_key = ?, rd_expiry_date = ?
      WHERE id = ?
    `).run(rdApiKey, rdExpiryDate || null, userId);

    if (result.changes === 0) {
      return res.status(404).json({ error: 'User not found' });
    }

    logger.info(`RD API key updated for user ID ${userId}`);

    res.json({ success: true });
  } catch (error) {
    logger.error('Set RD key error:', error);
    res.status(500).json({ error: 'Failed to set RD key' });
  }
});

/**
 * GET /api/admin/rd-expiry-alerts
 * Get users with expiring or expired RD keys
 */
router.get('/rd-expiry-alerts', (req, res) => {
  try {
    const now = new Date();
    const fiveDays = new Date(now.getTime() + 5 * 24 * 60 * 60 * 1000).toISOString();
    const oneDay = new Date(now.getTime() + 1 * 24 * 60 * 60 * 1000).toISOString();

    const users = db.prepare(`
      SELECT id, username, rd_expiry_date
      FROM users
      WHERE rd_expiry_date IS NOT NULL
      ORDER BY rd_expiry_date ASC
    `).all();

    const alerts = users.map(u => {
      const expiryDate = new Date(u.rd_expiry_date);
      const daysUntilExpiry = Math.ceil((expiryDate - now) / (24 * 60 * 60 * 1000));

      let alertType = null;
      if (daysUntilExpiry < 0) {
        alertType = 'expired';
      } else if (daysUntilExpiry <= 1) {
        alertType = '1_day';
      } else if (daysUntilExpiry <= 5) {
        alertType = '5_days';
      }

      return {
        userId: u.id,
        username: u.username,
        rdExpiryDate: u.rd_expiry_date,
        daysUntilExpiry,
        alertType
      };
    }).filter(a => a.alertType !== null);

    res.json({ alerts });
  } catch (error) {
    logger.error('Get RD expiry alerts error:', error);
    res.status(500).json({ error: 'Failed to get alerts' });
  }
});

/**
 * GET /api/admin/opensubtitles/status
 * Get OpenSubtitles account status and credentials
 */
router.get('/opensubtitles/status', async (req, res) => {
  try {
    // Get current credentials from env
    const username = process.env.OPENSUBTITLES_USERNAME;
    const hasPassword = !!process.env.OPENSUBTITLES_PASSWORD;
    const hasApiKey = !!process.env.OPENSUBTITLES_API_KEY;

    // Get user info from OpenSubtitles
    let userInfo = null;
    let error = null;

    try {
      userInfo = await opensubtitlesService.getUserInfo();
    } catch (err) {
      error = err.message;
      logger.warn('Failed to get OpenSubtitles user info:', err.message);
    }

    // Get quota and storage stats
    const quota = opensubtitlesService.checkDailyQuota();
    const storage = opensubtitlesService.getStorageStats();

    res.json({
      credentials: {
        username: username || null,
        hasPassword,
        hasApiKey,
        configured: !!(username && hasPassword && hasApiKey)
      },
      account: userInfo ? {
        userId: userInfo.user_id,
        username: userInfo.username || username,
        level: userInfo.level,
        vip: userInfo.vip || false,
        allowedDownloads: userInfo.allowed_downloads,
        allowedTranslations: userInfo.allowed_translations,
        extInstalled: userInfo.ext_installed || false
      } : null,
      quota: {
        used: quota.count,
        limit: quota.limit,
        remaining: quota.remaining,
        exceeded: quota.exceeded
      },
      storage: {
        used: storage.totalMB + ' MB',
        usedGB: storage.totalGB + ' GB',
        maxGB: storage.maxGB + ' GB',
        usedPercent: storage.usedPercent + '%',
        subtitleCount: storage.subtitleCount
      },
      error
    });
  } catch (error) {
    logger.error('Get OpenSubtitles status error:', error);
    res.status(500).json({ error: 'Failed to get OpenSubtitles status' });
  }
});

/**
 * POST /api/admin/opensubtitles/credentials
 * Update OpenSubtitles credentials
 */
router.post('/opensubtitles/credentials', async (req, res) => {
  try {
    const { username, password, apiKey } = req.body;

    if (!username || !password) {
      return res.status(400).json({ error: 'Username and password are required' });
    }

    // Update .env file
    const envPath = path.join(__dirname, '..', '.env');
    let envContent = fs.readFileSync(envPath, 'utf8');

    // Update or add credentials
    const updates = {
      OPENSUBTITLES_USERNAME: username,
      OPENSUBTITLES_PASSWORD: password
    };

    if (apiKey) {
      updates.OPENSUBTITLES_API_KEY = apiKey;
    }

    for (const [key, value] of Object.entries(updates)) {
      const regex = new RegExp(`^${key}=.*$`, 'm');
      if (regex.test(envContent)) {
        envContent = envContent.replace(regex, `${key}=${value}`);
      } else {
        envContent += `\n${key}=${value}`;
      }
    }

    fs.writeFileSync(envPath, envContent);

    // Update process.env
    process.env.OPENSUBTITLES_USERNAME = username;
    process.env.OPENSUBTITLES_PASSWORD = password;
    if (apiKey) {
      process.env.OPENSUBTITLES_API_KEY = apiKey;
    }

    // Test login with new credentials
    try {
      const result = await opensubtitlesService.login(username, password);
      logger.info('OpenSubtitles credentials updated and verified');

      res.json({
        success: true,
        message: 'Credentials updated successfully',
        account: result.user
      });
    } catch (loginError) {
      logger.error('Login failed with new credentials:', loginError.message);
      res.status(401).json({
        error: 'Credentials updated but login failed',
        message: loginError.message
      });
    }
  } catch (error) {
    logger.error('Update OpenSubtitles credentials error:', error);
    res.status(500).json({ error: 'Failed to update credentials' });
  }
});

/**
 * POST /api/admin/opensubtitles/test-login
 * Test OpenSubtitles login
 */
router.post('/opensubtitles/test-login', async (req, res) => {
  try {
    const result = await opensubtitlesService.login();

    res.json({
      success: true,
      message: 'Login successful',
      account: result.user,
      tokenExpiry: result.expiresIn
    });
  } catch (error) {
    logger.error('Test login error:', error);
    res.status(401).json({
      error: 'Login failed',
      message: error.response?.data?.message || error.message
    });
  }
});

/**
 * DELETE /api/admin/opensubtitles/logout
 * Logout from OpenSubtitles (clear session)
 */
router.delete('/opensubtitles/logout', async (req, res) => {
  try {
    await opensubtitlesService.logout();

    res.json({
      success: true,
      message: 'Logged out successfully'
    });
  } catch (error) {
    logger.error('Logout error:', error);
    res.status(500).json({ error: 'Logout failed' });
  }
});

module.exports = router;
