const express = require('express');
const bcrypt = require('bcryptjs');
const { db } = require('../db/init');
const { authenticateToken, requireAdmin } = require('../middleware/auth');
const logger = require('../utils/logger');

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
      SELECT id, username, is_admin, parent_user_id, rd_api_key, rd_expiry_date, created_at, last_login_at
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

    // Validate parent user exists if provided
    if (parentUserId) {
      const parent = db.prepare('SELECT id FROM users WHERE id = ?').get(parentUserId);
      if (!parent) {
        return res.status(400).json({ error: 'Parent user not found' });
      }
    }

    // Hash password
    const passwordHash = await bcrypt.hash(password, 10);

    // Create user
    const result = db.prepare(`
      INSERT INTO users (username, password_hash, is_admin, parent_user_id, rd_api_key, rd_expiry_date)
      VALUES (?, ?, ?, ?, ?, ?)
    `).run(username, passwordHash, isAdmin ? 1 : 0, parentUserId || null, rdApiKey || null, rdExpiryDate || null);

    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(result.lastInsertRowid);

    logger.info(`User created: ${username} (ID: ${user.id})`);

    res.status(201).json({
      user: {
        id: user.id,
        username: user.username,
        isAdmin: !!user.is_admin,
        parentUserId: user.parent_user_id,
        rdApiKey: user.rd_api_key,
        rdExpiryDate: user.rd_expiry_date
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
    const { username, password, rdApiKey, rdExpiryDate } = req.body;

    // Check user exists
    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Build update query dynamically
    const updates = [];
    const values = [];

    if (username) {
      updates.push('username = ?');
      values.push(username);
    }

    if (password) {
      const passwordHash = await bcrypt.hash(password, 10);
      updates.push('password_hash = ?');
      values.push(passwordHash);
    }

    if (rdApiKey !== undefined) {
      updates.push('rd_api_key = ?');
      values.push(rdApiKey);
    }

    if (rdExpiryDate !== undefined) {
      updates.push('rd_expiry_date = ?');
      values.push(rdExpiryDate);
    }

    if (updates.length === 0) {
      return res.status(400).json({ error: 'No fields to update' });
    }

    values.push(userId);

    db.prepare(`
      UPDATE users SET ${updates.join(', ')}
      WHERE id = ?
    `).run(...values);

    const updatedUser = db.prepare('SELECT * FROM users WHERE id = ?').get(userId);

    logger.info(`User updated: ${updatedUser.username} (ID: ${userId})`);

    res.json({
      user: {
        id: updatedUser.id,
        username: updatedUser.username,
        isAdmin: !!updatedUser.is_admin,
        parentUserId: updatedUser.parent_user_id,
        rdApiKey: updatedUser.rd_api_key,
        rdExpiryDate: updatedUser.rd_expiry_date
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

module.exports = router;
