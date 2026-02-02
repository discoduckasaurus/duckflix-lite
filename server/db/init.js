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
    return;
  }

  const passwordHash = await bcrypt.hash(adminPassword, 10);

  db.prepare(`
    INSERT INTO users (username, password_hash, is_admin)
    VALUES (?, ?, 1)
  `).run(adminUsername, passwordHash);

  logger.info(`Admin user '${adminUsername}' created successfully`);
  logger.info('Admin password is set from ADMIN_PASSWORD environment variable');
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
  cleanupExpiredSessions
};
