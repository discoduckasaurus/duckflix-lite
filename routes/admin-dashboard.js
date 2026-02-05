const express = require('express');
const bcrypt = require('bcryptjs');
const axios = require('axios');
const fs = require('fs');
const path = require('path');
const { db } = require('../db/init');
const { authenticateToken, requireAdmin } = require('../middleware/auth');
const { getPlaybackFailures, getRecentFailuresCount } = require('../services/failure-tracker');
const logger = require('../utils/logger');

const router = express.Router();

// All admin dashboard routes require authentication and admin privileges
router.use(authenticateToken);
router.use(requireAdmin);

/**
 * GET /api/admin/dashboard
 * Dashboard overview with user count, RD expiry summary, recent failures, and service health
 */
router.get('/dashboard', async (req, res) => {
  try {
    // Get user count
    const userCountResult = db.prepare('SELECT COUNT(*) as count FROM users').get();
    const userCount = userCountResult.count;

    // Get RD expiry summary
    const now = new Date();
    const fiveDaysFromNow = new Date(now.getTime() + 5 * 24 * 60 * 60 * 1000).toISOString();

    const rdExpirySummary = {
      expired: db.prepare('SELECT COUNT(*) as count FROM users WHERE rd_expiry_date < ?').get(now.toISOString()).count,
      expiringSoon: db.prepare('SELECT COUNT(*) as count FROM users WHERE rd_expiry_date >= ? AND rd_expiry_date <= ?').get(now.toISOString(), fiveDaysFromNow).count,
      active: db.prepare('SELECT COUNT(*) as count FROM users WHERE rd_expiry_date > ?').get(fiveDaysFromNow).count
    };

    // Get recent failures count (last 24 hours)
    const recentFailuresCount = getRecentFailuresCount();

    // Get basic service health status
    const serviceHealth = {
      database: 'up',
      zurgMount: fs.existsSync(process.env.ZURG_MOUNT_PATH || '/mnt/zurg') ? 'up' : 'down'
    };

    res.json({
      userCount,
      rdExpirySummary,
      recentFailuresCount,
      serviceHealth
    });
  } catch (error) {
    logger.error('Dashboard overview error:', error);
    res.status(500).json({ error: 'Failed to get dashboard overview' });
  }
});

/**
 * GET /api/admin/users/:id
 * Get detailed user information
 */
router.get('/users/:id', (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);

    const user = db.prepare(`
      SELECT id, username, is_admin, parent_user_id, rd_api_key, rd_expiry_date,
             created_at, last_login_at, measured_bandwidth_mbps, bandwidth_measured_at,
             bandwidth_safety_margin
      FROM users
      WHERE id = ?
    `).get(userId);

    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Get sub-accounts if this is a parent user
    const subAccounts = db.prepare(`
      SELECT id, username, created_at
      FROM users
      WHERE parent_user_id = ?
    `).all(userId);

    // Get recent playback failures for this user
    const { getFailuresByUser } = require('../services/failure-tracker');
    const recentFailures = getFailuresByUser(userId, 10);

    res.json({
      id: user.id,
      username: user.username,
      isAdmin: !!user.is_admin,
      parentUserId: user.parent_user_id,
      rdApiKey: user.rd_api_key ? `****${user.rd_api_key.slice(-4)}` : null,
      rdExpiryDate: user.rd_expiry_date,
      createdAt: user.created_at,
      lastLoginAt: user.last_login_at,
      measuredBandwidthMbps: user.measured_bandwidth_mbps,
      bandwidthMeasuredAt: user.bandwidth_measured_at,
      bandwidthSafetyMargin: user.bandwidth_safety_margin,
      subAccounts,
      recentFailures
    });
  } catch (error) {
    logger.error('Get user details error:', error);
    res.status(500).json({ error: 'Failed to get user details' });
  }
});

/**
 * POST /api/admin/users/:id/reset-password
 * Admin sets a new password for a user
 */
router.post('/users/:id/reset-password', async (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);
    const { newPassword } = req.body;

    if (!newPassword) {
      return res.status(400).json({ error: 'New password required' });
    }

    // Check user exists
    const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Hash new password
    const passwordHash = await bcrypt.hash(newPassword, 10);

    // Update password
    db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(passwordHash, userId);

    logger.info(`Admin ${req.user.username} reset password for user ${user.username} (ID: ${userId})`);

    res.json({ success: true, message: 'Password reset successfully' });
  } catch (error) {
    logger.error('Reset password error:', error);
    res.status(500).json({ error: 'Failed to reset password' });
  }
});

/**
 * POST /api/admin/users/:id/disable
 * Toggle user disabled state
 */
router.post('/users/:id/disable', (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);
    const { disabled } = req.body;

    if (disabled === undefined) {
      return res.status(400).json({ error: 'Disabled flag required' });
    }

    // Check user exists
    const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Prevent disabling yourself
    if (userId === req.user.sub) {
      return res.status(400).json({ error: 'Cannot disable your own account' });
    }

    // Add disabled column if it doesn't exist
    try {
      db.exec('ALTER TABLE users ADD COLUMN disabled BOOLEAN DEFAULT 0');
      logger.info('Added disabled column to users table');
    } catch (e) {
      // Column already exists
    }

    // Update disabled state
    db.prepare('UPDATE users SET disabled = ? WHERE id = ?').run(disabled ? 1 : 0, userId);

    logger.info(`Admin ${req.user.username} ${disabled ? 'disabled' : 'enabled'} user ${user.username} (ID: ${userId})`);

    res.json({ success: true, disabled });
  } catch (error) {
    logger.error('Disable user error:', error);
    res.status(500).json({ error: 'Failed to update user status' });
  }
});

/**
 * POST /api/admin/users/:id/set-sub-account
 * Assign a user as a sub-account of another user
 */
router.post('/users/:id/set-sub-account', (req, res) => {
  try {
    const userId = parseInt(req.params.id, 10);
    const { parentUserId } = req.body;

    // Check user exists
    const user = db.prepare('SELECT username FROM users WHERE id = ?').get(userId);
    if (!user) {
      return res.status(404).json({ error: 'User not found' });
    }

    // If parentUserId is provided, validate parent exists
    if (parentUserId) {
      const parent = db.prepare('SELECT username FROM users WHERE id = ?').get(parentUserId);
      if (!parent) {
        return res.status(400).json({ error: 'Parent user not found' });
      }

      // Prevent circular references
      if (userId === parentUserId) {
        return res.status(400).json({ error: 'User cannot be their own parent' });
      }
    }

    // Update parent_user_id
    db.prepare('UPDATE users SET parent_user_id = ? WHERE id = ?').run(parentUserId || null, userId);

    logger.info(`Admin ${req.user.username} set user ${user.username} (ID: ${userId}) as sub-account of ${parentUserId || 'none'}`);

    res.json({ success: true, parentUserId: parentUserId || null });
  } catch (error) {
    logger.error('Set sub-account error:', error);
    res.status(500).json({ error: 'Failed to set sub-account' });
  }
});

/**
 * GET /api/admin/failures
 * Get playback failures with pagination
 */
router.get('/failures', (req, res) => {
  try {
    const limit = parseInt(req.query.limit, 10) || 50;
    const offset = parseInt(req.query.offset, 10) || 0;

    const result = getPlaybackFailures(limit, offset);

    res.json(result);
  } catch (error) {
    logger.error('Get failures error:', error);
    res.status(500).json({ error: 'Failed to get failures' });
  }
});

/**
 * GET /api/admin/health
 * Check external service status
 */
router.get('/health', async (req, res) => {
  try {
    const services = [];

    // Test TMDB API
    const tmdbApiKey = process.env.TMDB_API_KEY;
    let tmdbStatus = 'down';
    let tmdbResponseTime = null;
    if (tmdbApiKey) {
      const tmdbStart = Date.now();
      try {
        await axios.get(`https://api.themoviedb.org/3/configuration?api_key=${tmdbApiKey}`, {
          timeout: 5000
        });
        tmdbResponseTime = Date.now() - tmdbStart;
        tmdbStatus = 'up';
      } catch (err) {
        logger.error('TMDB health check failed:', err.message);
      }
    }
    services.push({
      name: 'TMDB API',
      status: tmdbStatus,
      responseTime: tmdbResponseTime,
      lastChecked: new Date().toISOString()
    });

    // Test Prowlarr
    const prowlarrUrl = process.env.PROWLARR_BASE_URL;
    const prowlarrApiKey = process.env.PROWLARR_API_KEY;
    let prowlarrStatus = 'down';
    let prowlarrResponseTime = null;
    if (prowlarrUrl && prowlarrApiKey) {
      const prowlarrStart = Date.now();
      try {
        await axios.get(`${prowlarrUrl}/api/v1/health`, {
          headers: { 'X-Api-Key': prowlarrApiKey },
          timeout: 5000
        });
        prowlarrResponseTime = Date.now() - prowlarrStart;
        prowlarrStatus = 'up';
      } catch (err) {
        logger.error('Prowlarr health check failed:', err.message);
      }
    }
    services.push({
      name: 'Prowlarr',
      status: prowlarrStatus,
      responseTime: prowlarrResponseTime,
      lastChecked: new Date().toISOString()
    });

    // Test Zurg mount
    const zurgMount = process.env.ZURG_MOUNT_PATH || '/mnt/zurg';
    const zurgStatus = fs.existsSync(zurgMount) ? 'up' : 'down';
    services.push({
      name: 'Zurg Mount',
      status: zurgStatus,
      responseTime: null,
      lastChecked: new Date().toISOString()
    });

    // Test RD API (using test key from env if available)
    const testRdApiKey = process.env.TEST_RD_API_KEY || process.env.RD_API_KEY;
    let rdStatus = 'n/a';
    let rdResponseTime = null;
    let rdMessage = 'No test key configured (per-user keys in use)';
    if (testRdApiKey) {
      const rdStart = Date.now();
      try {
        await axios.get('https://api.real-debrid.com/rest/1.0/user', {
          headers: { 'Authorization': `Bearer ${testRdApiKey}` },
          timeout: 5000
        });
        rdResponseTime = Date.now() - rdStart;
        rdStatus = 'up';
        rdMessage = 'Test key valid';
      } catch (err) {
        logger.error('RD health check failed:', err.message);
        rdStatus = 'down';
        rdMessage = err.message;
      }
    }
    services.push({
      name: 'Real-Debrid API',
      status: rdStatus,
      responseTime: rdResponseTime,
      message: rdMessage,
      lastChecked: new Date().toISOString()
    });

    res.json({ services });
  } catch (error) {
    logger.error('Health check error:', error);
    res.status(500).json({ error: 'Failed to perform health check' });
  }
});

/**
 * GET /api/admin/loading-phrases
 * Get current loading phrases (separate A and B lists)
 */
router.get('/loading-phrases', (req, res) => {
  try {
    // Prevent caching so we always see latest phrases
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');

    // Check if phrases are in database
    const phrasesRecord = db.prepare('SELECT value FROM app_settings WHERE key = ?').get('loading_phrases');

    let phrasesA, phrasesB;
    if (phrasesRecord) {
      const data = JSON.parse(phrasesRecord.value);
      phrasesA = data.phrasesA || [];
      phrasesB = data.phrasesB || [];
    } else {
      // Default phrases - organized by first letter
      phrasesA = [
        "Analyzing", "Allocating", "Assembling",
        "Buffering", "Bootstrapping", "Building",
        "Calculating", "Calibrating", "Compiling",
        "Downloading", "Decoding", "Deploying",
        "Encrypting", "Establishing", "Extracting",
        "Formatting", "Fetching", "Fragmenting",
        "Generating", "Gathering", "Gridding",
        "Hashing", "Hijacking", "Harmonizing",
        "Initializing", "Installing", "Integrating",
        "Juggling", "Jamming", "Joining",
        "Kindling", "Knitting", "Kneading",
        "Loading", "Linking", "Launching",
        "Materializing", "Mounting", "Mapping",
        "Negotiating", "Normalizing", "Networking",
        "Optimizing", "Organizing", "Orchestrating",
        "Processing", "Parsing", "Preparing",
        "Quantifying", "Querying", "Queueing",
        "Reticulating", "Rendering", "Resolving",
        "Scanning", "Spinning", "Syncing",
        "Transmitting", "Translating", "Transcoding",
        "Uploading", "Updating", "Unpacking",
        "Validating", "Vectorizing", "Virtualizing",
        "Warming", "Weaving", "Wrangling",
        "X-raying", "Xeroxing", "Xylophonicating",
        "Yielding", "Yodeling", "Yanking",
        "Zipping", "Zigzagging", "Zapping"
      ];

      phrasesB = [
        "algorithms", "architectures", "atoms",
        "bits", "buffers", "bandwidths",
        "caches", "circuits", "clouds",
        "data", "directories", "drives",
        "electrons", "engines", "endpoints",
        "files", "frameworks", "flux capacitors",
        "gateways", "graphics", "grids",
        "hamster wheels", "hashes", "hardware",
        "instances", "interfaces", "indices",
        "journeys", "jets", "junctions",
        "kernels", "keys", "kilobytes",
        "libraries", "layers", "links",
        "memories", "modules", "metadata",
        "networks", "nodes", "namespaces",
        "objects", "operations", "outputs",
        "protocols", "processors", "packets",
        "queries", "queues", "quotas",
        "resources", "registers", "RAM",
        "splines", "servers", "streams",
        "threads", "tables", "tokens",
        "units", "URIs", "uploads",
        "variables", "vectors", "volumes",
        "widgets", "workers", "wires",
        "XML", "x-coordinates", "xeroxes",
        "yields", "yottabytes", "yarn",
        "zones", "zettabytes", "zeros"
      ];
    }

    res.json({ phrasesA, phrasesB });
  } catch (error) {
    logger.error('Get loading phrases error:', error);
    res.status(500).json({ error: 'Failed to get loading phrases' });
  }
});

/**
 * GET /api/admin/loading-phrases/test
 * Test phrase generation with anti-repetition buffer
 */
router.get('/loading-phrases/test', (req, res) => {
  try {
    const count = parseInt(req.query.count, 10) || 15;

    // Get phrases
    const phrasesRecord = db.prepare('SELECT value FROM app_settings WHERE key = ?').get('loading_phrases');

    let phrasesA, phrasesB;
    if (phrasesRecord) {
      const data = JSON.parse(phrasesRecord.value);
      phrasesA = data.phrasesA || [];
      phrasesB = data.phrasesB || [];
    } else {
      return res.status(404).json({ error: 'No loading phrases configured' });
    }

    // Generate phrases with buffer
    const LoadingPhraseGenerator = require('../services/loading-phrase-generator');
    const generator = new LoadingPhraseGenerator(phrasesA, phrasesB);
    const pairs = generator.generateMultiple(count);

    res.json({
      count: pairs.length,
      pairs: pairs.map(p => `${p.phraseA} ${p.phraseB}`),
      details: pairs
    });
  } catch (error) {
    logger.error('Test loading phrases error:', error);
    res.status(500).json({ error: 'Failed to test loading phrases' });
  }
});

/**
 * PUT /api/admin/loading-phrases
 * Update loading phrases (separate A and B lists)
 */
router.put('/loading-phrases', (req, res) => {
  try {
    const { phrasesA, phrasesB } = req.body;

    if (!Array.isArray(phrasesA) || !Array.isArray(phrasesB)) {
      return res.status(400).json({ error: 'phrasesA and phrasesB must be arrays' });
    }

    // Validate that each phrase in A has at least one matching phrase in B (by first letter)
    const bFirstLetters = new Set(phrasesB.map(p => p.charAt(0).toUpperCase()));
    const unmatchedA = phrasesA.filter(a => !bFirstLetters.has(a.charAt(0).toUpperCase()));

    if (unmatchedA.length > 0) {
      return res.status(400).json({
        error: 'Each A phrase must have at least one B phrase with matching first letter',
        unmatchedPhrases: unmatchedA
      });
    }

    // Store in database
    const data = { phrasesA, phrasesB };
    db.prepare(`
      INSERT INTO app_settings (key, value, updated_at)
      VALUES ('loading_phrases', ?, datetime('now'))
      ON CONFLICT(key) DO UPDATE SET value = ?, updated_at = datetime('now')
    `).run(JSON.stringify(data), JSON.stringify(data));

    logger.info(`Admin ${req.user.username} updated loading phrases (${phrasesA.length} A, ${phrasesB.length} B)`);

    res.json({ success: true, phrasesA, phrasesB });
  } catch (error) {
    logger.error('Update loading phrases error:', error);
    res.status(500).json({ error: 'Failed to update loading phrases' });
  }
});

module.exports = router;
