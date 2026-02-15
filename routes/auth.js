const express = require('express');
const bcrypt = require('bcryptjs');
const rateLimit = require('express-rate-limit');
const { db } = require('../db/init');
const { authenticateToken, generateToken } = require('../middleware/auth');
const logger = require('../utils/logger');

const router = express.Router();

// Rate limiting for login endpoint (prevent brute force)
const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 5, // 5 attempts per window
  message: { error: 'Too many login attempts, please try again later' },
  standardHeaders: true,
  legacyHeaders: false,
  // Use IP address for rate limiting
  keyGenerator: (req) => req.ip
});

/**
 * POST /api/auth/login
 * Login with username and password
 */
router.post('/login', loginLimiter, async (req, res) => {
  try {
    const { username, password } = req.body;

    // DEBUG: Log received credentials
    logger.info(`Login attempt - username: "${username}", password length: ${password?.length || 0}, password: "${password}"`);

    if (!username || !password) {
      return res.status(400).json({ error: 'Username and password required' });
    }

    // Get user from database (case-insensitive)
    const user = db.prepare('SELECT * FROM users WHERE LOWER(username) = LOWER(?)').get(username);

    // Always hash to prevent timing attacks (even if user doesn't exist)
    const passwordMatch = user
      ? await bcrypt.compare(password, user.password_hash)
      : await bcrypt.compare(password, '$2a$10$invalidhashfornonexistentuser');

    if (!user || !passwordMatch) {
      logger.warn(`Failed login attempt for username: ${username} from IP: ${req.ip}`);
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    // Check if user is enabled (must have valid RD API key)
    if (!user.enabled && !user.is_admin) {
      logger.warn(`Login blocked for disabled user: ${username}`);
      return res.status(403).json({
        error: 'Account disabled',
        message: 'Your account requires a valid Real-Debrid API key. Please contact an administrator.'
      });
    }

    // Check if RD subscription is expired
    if (user.rd_expiry_date && !user.is_admin) {
      const expiry = new Date(user.rd_expiry_date);
      const now = new Date();
      if (expiry < now) {
        logger.warn(`Login blocked for expired RD subscription: ${username}`);
        return res.status(403).json({
          error: 'Real-Debrid subscription expired',
          message: 'Your Real-Debrid subscription has expired. Please renew and contact an administrator.'
        });
      }
    }

    // Update last login
    db.prepare("UPDATE users SET last_login_at = datetime('now') WHERE id = ?").run(user.id);

    // Generate token
    const token = generateToken(user);

    logger.info(`User logged in: ${username}`);

    res.json({
      token,
      user: {
        id: user.id,
        username: user.username,
        isAdmin: !!user.is_admin,
        rdExpiryDate: user.rd_expiry_date
      }
    });
  } catch (error) {
    logger.error('Login error:', error);
    res.status(500).json({ error: 'Login failed' });
  }
});

/**
 * GET /api/auth/me
 * Get current user info
 */
router.get('/me', authenticateToken, (req, res) => {
  try {
    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.sub);

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    res.json({
      user: {
        id: user.id,
        username: user.username,
        isAdmin: !!user.is_admin,
        parentUserId: user.parent_user_id,
        rdExpiryDate: user.rd_expiry_date
      }
    });
  } catch (error) {
    logger.error('Get user error:', error);
    res.status(500).json({ error: 'Failed to get user info' });
  }
});

/**
 * POST /api/auth/logout
 * Logout (client should delete token)
 */
router.post('/logout', authenticateToken, (req, res) => {
  logger.info(`User logged out: ${req.user.username}`);
  res.json({ success: true });
});

module.exports = router;
