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
db.pragma('busy_timeout = 5000'); // Wait up to 5s for locks instead of failing immediately

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

  try {
    db.exec(`ALTER TABLE users ADD COLUMN enabled BOOLEAN DEFAULT 1`);
    logger.info('Added enabled column');
  } catch (e) {
    // Column already exists
  }

  try {
    db.exec(`ALTER TABLE users ADD COLUMN updated_at TEXT`);
    logger.info('Added updated_at column');
  } catch (e) {
    // Column already exists
  }

  // User sessions (IP tracking for VOD) - DEPRECATED: Use rd_sessions instead
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

  // RD sessions (RD key + IP tracking to prevent concurrent streams)
  db.exec(`
    CREATE TABLE IF NOT EXISTS rd_sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      rd_api_key TEXT NOT NULL,
      ip_address TEXT NOT NULL,
      user_id INTEGER NOT NULL,
      username TEXT NOT NULL,
      stream_started_at TEXT NOT NULL,
      last_heartbeat_at TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now')),
      UNIQUE(rd_api_key, ip_address),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
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

  // Migration: Add per-user RD cache isolation
  try {
    db.exec(`ALTER TABLE rd_link_cache ADD COLUMN rd_key_hash TEXT NOT NULL DEFAULT ''`);
    logger.info('Added rd_key_hash column to rd_link_cache for per-user isolation');
  } catch (e) {}

  // Drop old index and create new one with rd_key_hash (UNIQUE for upsert, per-user)
  db.exec(`DROP INDEX IF EXISTS idx_rd_cache_lookup`);
  db.exec(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_rd_cache_lookup
    ON rd_link_cache(tmdb_id, type, season, episode, resolution, rd_key_hash)
  `);

  // Playback failures tracking
  db.exec(`
    CREATE TABLE IF NOT EXISTS playback_failures (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      username TEXT NOT NULL,
      tmdb_id INTEGER NOT NULL,
      title TEXT NOT NULL,
      season INTEGER,
      episode INTEGER,
      error TEXT NOT NULL,
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_failures_user
    ON playback_failures(user_id)
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_failures_created
    ON playback_failures(created_at DESC)
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

  // Live TV: User channel settings (per-user preferences)
  db.exec(`
    CREATE TABLE IF NOT EXISTS user_channel_settings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      channel_id TEXT NOT NULL,
      is_enabled BOOLEAN DEFAULT 1,
      is_favorite BOOLEAN DEFAULT 0,
      sort_order INTEGER DEFAULT 0,
      created_at TEXT DEFAULT (datetime('now')),
      updated_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
      UNIQUE(user_id, channel_id)
    )
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_user_channel_settings
    ON user_channel_settings(user_id, channel_id)
  `);

  // Live TV: Special channels (admin-defined additional channels not in M3U)
  db.exec(`
    CREATE TABLE IF NOT EXISTS special_channels (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      display_name TEXT NOT NULL,
      group_name TEXT DEFAULT 'Live',
      stream_url TEXT NOT NULL,
      logo_url TEXT,
      sort_order INTEGER DEFAULT 999,
      channel_number INTEGER,
      is_active BOOLEAN DEFAULT 1,
      created_at TEXT DEFAULT (datetime('now')),
      updated_at TEXT DEFAULT (datetime('now'))
    )
  `);

  // Live TV: Channel metadata (additional info like custom logos, EPG overrides)
  db.exec(`
    CREATE TABLE IF NOT EXISTS channel_metadata (
      channel_id TEXT PRIMARY KEY,
      custom_logo_url TEXT,
      custom_display_name TEXT,
      epg_override_id TEXT,
      notes TEXT,
      updated_at TEXT DEFAULT (datetime('now'))
    )
  `);

  // Migration: Add admin-level is_enabled and sort_order to channel_metadata
  try {
    db.exec(`ALTER TABLE channel_metadata ADD COLUMN is_enabled BOOLEAN DEFAULT 1`);
    logger.info('Added is_enabled column to channel_metadata');
  } catch (e) {}

  try {
    db.exec(`ALTER TABLE channel_metadata ADD COLUMN sort_order INTEGER DEFAULT 999`);
    logger.info('Added sort_order column to channel_metadata');
  } catch (e) {}

  // Pre-seed channel_metadata from channel-config.json (one-time migration)
  try {
    const channelConfigPath = path.join(__dirname, 'channel-config.json');
    if (fs.existsSync(channelConfigPath)) {
      const existingCount = db.prepare('SELECT COUNT(*) as count FROM channel_metadata').get();
      if (existingCount.count === 0) {
        const channelConfig = JSON.parse(fs.readFileSync(channelConfigPath, 'utf-8'));
        const stmt = db.prepare(`
          INSERT OR IGNORE INTO channel_metadata (channel_id, custom_display_name, custom_logo_url, is_enabled, sort_order, updated_at)
          VALUES (?, ?, ?, ?, ?, datetime('now'))
        `);
        let order = 0;
        for (const [id, cfg] of Object.entries(channelConfig)) {
          const logoUrl = cfg.logoFile ? `/static/logos/${cfg.logoFile}` : null;
          const enabled = cfg.enabled !== false ? 1 : 0;
          stmt.run(id, cfg.displayName || null, logoUrl, enabled, order++);
        }
        logger.info(`Pre-seeded channel_metadata with ${Object.keys(channelConfig).length} channels from channel-config.json`);
      }
    }
  } catch (e) {
    logger.warn('Failed to pre-seed channel_metadata:', e.message);
  }

  // TMDB Provider metadata (admin-managed display name, enabled, sort order)
  db.exec(`
    CREATE TABLE IF NOT EXISTS provider_metadata (
      provider_id INTEGER PRIMARY KEY,
      custom_display_name TEXT,
      custom_logo_url TEXT,
      is_enabled BOOLEAN DEFAULT 1,
      sort_order INTEGER DEFAULT 999,
      notes TEXT,
      updated_at TEXT DEFAULT (datetime('now'))
    )
  `);

  // Live TV: DVR recordings (FUTURE - Phase 5)
  db.exec(`
    CREATE TABLE IF NOT EXISTS dvr_recordings (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      user_id INTEGER NOT NULL,
      channel_id TEXT NOT NULL,
      channel_name TEXT NOT NULL,
      program_title TEXT NOT NULL,
      program_description TEXT,
      start_time INTEGER NOT NULL,
      end_time INTEGER NOT NULL,
      status TEXT DEFAULT 'scheduled',
      recording_path TEXT,
      file_size_bytes INTEGER,
      created_at TEXT DEFAULT (datetime('now')),
      FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
    )
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_dvr_user_time
    ON dvr_recordings(user_id, start_time)
  `);

  // Subtitles cache (permanent storage with 100GB limit)
  db.exec(`
    CREATE TABLE IF NOT EXISTS subtitles (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      tmdb_id INTEGER NOT NULL,
      title TEXT NOT NULL,
      year TEXT,
      type TEXT NOT NULL,
      season INTEGER,
      episode INTEGER,
      language TEXT NOT NULL,
      language_code TEXT NOT NULL,
      format TEXT NOT NULL,
      file_path TEXT NOT NULL,
      file_size_bytes INTEGER NOT NULL,
      opensubtitles_file_id TEXT,
      downloaded_at TEXT DEFAULT (datetime('now')),
      last_accessed_at TEXT DEFAULT (datetime('now'))
    )
  `);

  db.exec(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_subtitles_lookup
    ON subtitles(tmdb_id, type, season, episode, language_code)
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_subtitles_downloaded
    ON subtitles(downloaded_at)
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_subtitles_accessed
    ON subtitles(last_accessed_at)
  `);

  // Subtitle quota tracking (daily OpenSubtitles API usage)
  db.exec(`
    CREATE TABLE IF NOT EXISTS subtitle_quota_tracking (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      download_date TEXT NOT NULL,
      download_count INTEGER DEFAULT 0,
      created_at TEXT DEFAULT (datetime('now')),
      updated_at TEXT DEFAULT (datetime('now')),
      UNIQUE(download_date)
    )
  `);

  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_quota_date
    ON subtitle_quota_tracking(download_date)
  `);

  // Scheduled loading phrases (date/holiday-specific phrase sets)
  db.exec(`
    CREATE TABLE IF NOT EXISTS loading_phrase_schedules (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      schedule_type TEXT NOT NULL,
      scheduled_date TEXT,
      holiday TEXT,
      repeat_type TEXT DEFAULT 'none',
      phrases_a TEXT NOT NULL,
      phrases_b TEXT NOT NULL,
      target_user_ids TEXT,
      is_enabled BOOLEAN DEFAULT 1,
      created_at TEXT DEFAULT (datetime('now')),
      updated_at TEXT DEFAULT (datetime('now'))
    )
  `);

  // Migration: Add ordered_pairs to loading_phrase_schedules
  try {
    db.exec(`ALTER TABLE loading_phrase_schedules ADD COLUMN ordered_pairs BOOLEAN DEFAULT 0`);
    logger.info('Added ordered_pairs column to loading_phrase_schedules');
  } catch (e) {}

  // DFTV pseudo-live channel
  db.prepare(`
    INSERT OR IGNORE INTO special_channels
      (id, name, display_name, group_name, stream_url, logo_url, sort_order, is_active)
    VALUES ('dftv-mixed', 'DFTV', 'DFTV', 'DuckFlix', 'dftv://mixed', NULL, 1, 1)
  `).run();

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

  const existingAdmin = db.prepare('SELECT id FROM users WHERE LOWER(username) = LOWER(?)').get(adminUsername);

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

  const existingUser = db.prepare('SELECT id FROM users WHERE LOWER(username) = LOWER(?)').get(testUsername);

  if (existingUser) {
    // Update existing user's RD API key
    db.prepare(`
      UPDATE users
      SET rd_api_key = ?
      WHERE LOWER(username) = LOWER(?)
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
