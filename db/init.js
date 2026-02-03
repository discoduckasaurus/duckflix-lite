const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');
const bcrypt = require('bcryptjs');
const logger = require('../utils/logger');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, 'duckflix_lite.db');

// Ensure db directory exists
const dbDir = path.dirname(DB_PATH);
if (!fs.existsSync(dbDir)) {
  fs.mkdirSync(dbDir, { recursive: true });
  logger.info(`Created database directory: ${dbDir}`);
}

// Initialize database connection
const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL'); // Write-Ahead Logging for better concurrency

logger.info(`Database initialized at: ${DB_PATH}`);

/**
 * Initialize database schema
 */
function initDatabase() {
  logger.info('Creating database tables...');

  // Users table
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      is_admin BOOLEAN DEFAULT 0,
      parent_user_id INTEGER,
      rd_api_key TEXT,
      rd_expiry_date TEXT,
      created_at TEXT DEFAULT (datetime('now')),
      last_login_at TEXT,
      FOREIGN KEY(parent_user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // Migration: Add bandwidth columns to users
  try {
    db.exec(`ALTER TABLE users ADD COLUMN measured_bandwidth_mbps REAL`);
    logger.info('Added measured_bandwidth_mbps column');
  } catch (e) {
    // Column already exists
  }

  try {
    db.exec(`ALTER TABLE users ADD COLUMN bandwidth_measured_at TEXT`);
    logger.info('Added bandwidth_measured_at column');
  } catch (e) {
    // Column already exists
  }

  try {
    db.exec(`ALTER TABLE users ADD COLUMN bandwidth_safety_margin REAL DEFAULT 1.3`);
    logger.info('Added bandwidth_safety_margin column');
  } catch (e) {
    // Column already exists
  }

  // User sessions (IP tracking for VOD)
  db.exec(`
    CREATE TABLE IF NOT EXISTS user_sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      ip_address TEXT NOT NULL,
      last_vod_playback_at TEXT,
      last_heartbeat_at TEXT,
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE(user_id, ip_address)
    )
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_sessions_user_ip
    ON user_sessions(user_id, ip_address)
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_sessions_heartbeat
    ON user_sessions(last_heartbeat_at)
  `);

  // EPG cache
  db.exec(`
    CREATE TABLE IF NOT EXISTS epg_cache (
      channel_id TEXT PRIMARY KEY,
      epg_data TEXT NOT NULL,
      updated_at TEXT DEFAULT (datetime('now'))
    )
  `);

  // M3U sources
  db.exec(`
    CREATE TABLE IF NOT EXISTS m3u_sources (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      url TEXT NOT NULL,
      priority INTEGER DEFAULT 0,
      last_fetched_at TEXT,
      is_active BOOLEAN DEFAULT 1
    )
  `);

  // RD expiry alerts log
  db.exec(`
    CREATE TABLE IF NOT EXISTS rd_alerts (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      alert_type TEXT NOT NULL,
      sent_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  // RD link cache (48-hour TTL for cached stream URLs)
  db.exec(`
    CREATE TABLE IF NOT EXISTS rd_link_cache (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      tmdb_id INTEGER NOT NULL,
      title TEXT NOT NULL,
      year TEXT,
      type TEXT NOT NULL,
      season INTEGER,
      episode INTEGER,
      stream_url TEXT NOT NULL,
      file_name TEXT,
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      last_accessed_at INTEGER NOT NULL
    )
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_rd_cache_lookup
    ON rd_link_cache(tmdb_id, type, season, episode)
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_rd_cache_expiry
    ON rd_link_cache(expires_at)
  `);

  // Migration: Add quality columns to rd_link_cache
  try {
    db.exec(`ALTER TABLE rd_link_cache ADD COLUMN resolution INTEGER`);
    logger.info('Added resolution column to rd_link_cache');
  } catch (e) {}

  try {
    db.exec(`ALTER TABLE rd_link_cache ADD COLUMN estimated_bitrate_mbps REAL`);
    logger.info('Added estimated_bitrate_mbps column to rd_link_cache');
  } catch (e) {}

  try {
    db.exec(`ALTER TABLE rd_link_cache ADD COLUMN file_size_bytes INTEGER`);
    logger.info('Added file_size_bytes column to rd_link_cache');
  } catch (e) {}

  // Drop old index and create new one with resolution (UNIQUE for upsert support)
  db.exec(`DROP INDEX IF EXISTS idx_rd_cache_lookup`);
  db.exec(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_rd_cache_lookup
    ON rd_link_cache(tmdb_id, type, season, episode, resolution)
  `);

  // App settings table (admin-configurable)
  db.exec(`
    CREATE TABLE IF NOT EXISTS app_settings (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL,
      updated_at TEXT DEFAULT (datetime('now'))
    )
  `);

  // Insert default stutter thresholds if not exist
  const defaultSettings = [
    ['stutter_count_threshold', '3'],
    ['stutter_duration_seconds', '3'],
    ['stutter_window_minutes', '5'],
    ['single_stutter_max_seconds', '10']
  ];

  const insertSetting = db.prepare(`
    INSERT OR IGNORE INTO app_settings (key, value) VALUES (?, ?)
  `);

  for (const [key, value] of defaultSettings) {
    insertSetting.run(key, value);
  }

  logger.info('Database tables created successfully');
}

/**
 * Create admin user if not exists
 */
async function createAdminUser() {
  const adminUsername = process.env.ADMIN_USERNAME || 'admin';
  const adminPassword = process.env.ADMIN_PASSWORD;

  // CRITICAL: Require admin password to be set
  if (!adminPassword) {
    logger.error('CRITICAL: ADMIN_PASSWORD environment variable is not set!');
    logger.error('Set a strong password in your .env file before starting the server.');
    process.exit(1);
  }

  const existingAdmin = db.prepare('SELECT id FROM users WHERE username = ?').get(adminUsername);

  if (existingAdmin) {
    logger.info(`Admin user '${adminUsername}' already exists`);
    // NOTE: Admin must set their own RD API key via admin panel
    // NEVER use ENV RD_API_KEY - each user must have unique key to avoid bans
    return;
  }

  const passwordHash = await bcrypt.hash(adminPassword, 10);

  // Create admin WITHOUT RD API key - they'll add it via admin panel
  db.prepare(`
    INSERT INTO users (username, password_hash, is_admin, rd_api_key)
    VALUES (?, ?, 1, NULL)
  `).run(adminUsername, passwordHash);

  logger.info(`Admin user '${adminUsername}' created successfully`);
  logger.info('Admin password is set from ADMIN_PASSWORD environment variable');
  logger.warn('⚠️  Admin has NO RD API key - add via admin panel to enable VOD');
}

/**
 * Create test user "Tawnia" for remote testing
 */
async function createTestUser() {
  const testUsername = 'Tawnia';
  const testPassword = 'jjjjjj';
  const testRdApiKey = 'IOGOUVDH4JSBH57UJAFDP3O375DCPSKP7ERWPURNCP3CCNUSFPKQ';

  const existingUser = db.prepare('SELECT id FROM users WHERE username = ?').get(testUsername);

  if (existingUser) {
    // Update existing user's RD API key
    db.prepare(`
      UPDATE users
      SET rd_api_key = ?
      WHERE username = ?
    `).run(testRdApiKey, testUsername);
    logger.info(`Test user '${testUsername}' already exists, RD API key updated`);
    return;
  }

  const passwordHash = await bcrypt.hash(testPassword, 10);

  db.prepare(`
    INSERT INTO users (username, password_hash, is_admin, rd_api_key)
    VALUES (?, ?, 0, ?)
  `).run(testUsername, passwordHash, testRdApiKey);

  logger.info(`Test user '${testUsername}' created with password: ${testPassword}`);
}

/**
 * Clean up expired sessions (heartbeat older than timeout)
 */
function cleanupExpiredSessions() {
  const timeout = parseInt(process.env.VOD_SESSION_TIMEOUT_MS || '120000', 10);
  const cutoffTime = new Date(Date.now() - timeout).toISOString();

  const result = db.prepare(`
    DELETE FROM user_sessions
    WHERE last_heartbeat_at < ?
  `).run(cutoffTime);

  if (result.changes > 0) {
    logger.info(`Cleaned up ${result.changes} expired sessions`);
  }

  return result.changes;
}

// Run cleanup every 5 minutes
setInterval(cleanupExpiredSessions, 5 * 60 * 1000);

module.exports = {
  db,
  initDatabase,
  createAdminUser,
  createTestUser,
  cleanupExpiredSessions
};
