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

/**
 * GET /api/auth/check-vod-session
 * Check if user can start VOD playback from this IP
 */
router.get('/check-vod-session', authenticateToken, (req, res) => {
  try {
    const userId = req.user.sub;
    // SECURITY: Only trust server-detected IP, never client-provided
    const clientIp = req.ip;

    // Check for active sessions from different IPs
    const timeout = parseInt(process.env.VOD_SESSION_TIMEOUT_MS || '120000', 10);
    const cutoffTime = new Date(Date.now() - timeout).toISOString();

    const activeSessions = db.prepare(`
      SELECT ip_address FROM user_sessions
      WHERE user_id = ? AND last_heartbeat_at > ?
    `).all(userId, cutoffTime);

    // Check if there's an active session from a different IP
    const differentIpSession = activeSessions.find(s => s.ip_address !== clientIp);

    if (differentIpSession) {
      return res.json({
        allowed: false,
        reason: `Already playing from IP ${differentIpSession.ip_address}. Wait 2 minutes or end that session.`
      });
    }

    // Allow playback and record/update session
    db.prepare(`
      INSERT INTO user_sessions (user_id, ip_address, last_vod_playback_at, last_heartbeat_at)
      VALUES (?, ?, datetime('now'), datetime('now'))
      ON CONFLICT(user_id, ip_address) DO UPDATE SET
        last_vod_playback_at = datetime('now'),
        last_heartbeat_at = datetime('now')
    `).run(userId, clientIp);

    res.json({
      allowed: true,
      currentIp: clientIp
    });
  } catch (error) {
    logger.error('Check VOD session error:', error);
    res.status(500).json({ error: 'Failed to check session' });
  }
});

/**
 * POST /api/auth/vod-heartbeat
 * Update heartbeat for active VOD session
 */
router.post('/vod-heartbeat', authenticateToken, (req, res) => {
  try {
    const userId = req.user.sub;
    // SECURITY: Only trust server-detected IP
    const clientIp = req.ip;

    const result = db.prepare(`
      UPDATE user_sessions
      SET last_heartbeat_at = datetime('now')
      WHERE user_id = ? AND ip_address = ?
    `).run(userId, clientIp);

    if (result.changes === 0) {
      logger.warn(`Heartbeat for non-existent session: user ${userId}, IP ${clientIp}`);
    }

    res.json({ success: true });
  } catch (error) {
    logger.error('VOD heartbeat error:', error);
    res.status(500).json({ error: 'Failed to update heartbeat' });
  }
});

/**
 * POST /api/auth/vod-session-end
 * End VOD session (user stopped playback)
 */
router.post('/vod-session-end', authenticateToken, (req, res) => {
  try {
    const userId = req.user.sub;
    // SECURITY: Only trust server-detected IP
    const clientIp = req.ip;

    db.prepare(`
      DELETE FROM user_sessions
      WHERE user_id = ? AND ip_address = ?
    `).run(userId, clientIp);

    logger.info(`VOD session ended: user ${userId}, IP ${clientIp}`);

    res.json({ success: true });
  } catch (error) {
    logger.error('VOD session end error:', error);
    res.status(500).json({ error: 'Failed to end session' });
  }
});

module.exports = router;
