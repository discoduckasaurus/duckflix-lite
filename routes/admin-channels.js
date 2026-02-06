const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const { db } = require('../db/init');
const { authenticateToken, requireAdmin } = require('../middleware/auth');
const logger = require('../utils/logger');
const liveTVService = require('../services/livetv-service');
const { channelActiveSource } = require('./livetv');

// Load backup-streams.json for stream source info
const BACKUP_STREAMS_PATH = path.join(__dirname, '..', 'db', 'backup-streams.json');
let backupStreams = {};
try {
  backupStreams = JSON.parse(fs.readFileSync(BACKUP_STREAMS_PATH, 'utf-8'));
} catch (err) {
  logger.warn('admin-channels: Failed to load backup-streams.json:', err.message);
}

const router = express.Router();

// All admin channel routes require authentication and admin privileges
router.use(authenticateToken);
router.use(requireAdmin);

// Multer config for logo uploads
const LOGOS_DIR = path.join(__dirname, '..', 'static', 'logos');
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    fs.mkdirSync(LOGOS_DIR, { recursive: true });
    cb(null, LOGOS_DIR);
  },
  filename: (req, file, cb) => {
    // Use channel ID as filename, keep original extension
    const ext = path.extname(file.originalname) || '.png';
    const channelId = req.params.id.replace(/[^a-zA-Z0-9_-]/g, '_');
    cb(null, `${channelId}${ext}`);
  }
});

const upload = multer({
  storage,
  limits: { fileSize: 2 * 1024 * 1024 }, // 2MB max
  fileFilter: (req, file, cb) => {
    const allowed = /\.(png|jpg|jpeg|gif|svg|webp)$/i;
    if (allowed.test(path.extname(file.originalname))) {
      cb(null, true);
    } else {
      cb(new Error('Only image files are allowed'));
    }
  }
});

/**
 * Build a lookup map of normalized display names -> logo paths from channel_metadata.
 * Used to auto-match Cabernet channels to existing logos.
 */
function buildLogoMatchMap() {
  const rows = db.prepare(`
    SELECT channel_id, custom_display_name, custom_logo_url
    FROM channel_metadata
    WHERE custom_logo_url IS NOT NULL
  `).all();

  const map = new Map();
  for (const row of rows) {
    if (row.custom_display_name) {
      map.set(normalizeName(row.custom_display_name), row.custom_logo_url);
    }
  }
  return map;
}

/**
 * Normalize a channel name for fuzzy matching.
 * Strips common suffixes, lowercases, removes non-alphanumeric.
 */
function normalizeName(name) {
  return name
    .toLowerCase()
    .replace(/\b(us|usa|eastern|western|feed|east|west|hd|sd|fhd|uhd|new york|los angeles|ny|ca|channel)\b/g, '')
    .replace(/[^a-z0-9]/g, '')
    .trim();
}

/**
 * GET /api/admin/channels
 * List all channels (M3U + special) with their admin config from channel_metadata.
 * Includes stream source info (DaddyLive mapping, backup streams).
 */
router.get('/channels', (req, res) => {
  try {
    // Get M3U channels from cache
    const m3uData = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'
    `).get();

    let m3uChannels = [];
    if (m3uData) {
      m3uChannels = JSON.parse(m3uData.epg_data);
    }

    // Get DaddyLive stream map (tvpassId -> daddylive URL)
    let dlStreamMap = {};
    try {
      const dlRow = db.prepare(`
        SELECT epg_data FROM epg_cache WHERE channel_id = 'daddylive_stream_map'
      `).get();
      if (dlRow) dlStreamMap = JSON.parse(dlRow.epg_data);
    } catch (err) {
      logger.debug('Failed to load daddylive_stream_map:', err.message);
    }

    // Get special channels
    const specialChannels = db.prepare(`
      SELECT id, name, display_name, group_name, stream_url, logo_url, sort_order, is_active
      FROM special_channels
    `).all();

    // Get all channel_metadata
    const metadata = db.prepare(`
      SELECT channel_id, custom_display_name, custom_logo_url, is_enabled, sort_order, notes
      FROM channel_metadata
    `).all();
    const metaMap = new Map(metadata.map(m => [m.channel_id, m]));

    // Build logo match map for auto-matching Cabernet channels
    const logoMatchMap = buildLogoMatchMap();

    // Combine all channels
    const channels = [];

    for (const ch of m3uChannels) {
      const meta = metaMap.get(ch.id);
      let autoMatchedLogo = null;

      // For channels without metadata, try auto-matching by name
      if (!meta && ch.id.startsWith('cab-')) {
        const normalized = normalizeName(ch.displayName || ch.name);
        autoMatchedLogo = logoMatchMap.get(normalized) || null;
      }

      const hasDaddyLive = !!dlStreamMap[ch.id];
      const hasBackupStreams = !!backupStreams[ch.id];
      let streamSourceCount = 1; // original M3U URL
      if (hasDaddyLive) streamSourceCount++;
      if (hasBackupStreams && backupStreams[ch.id].primary) streamSourceCount++;
      if (hasBackupStreams && backupStreams[ch.id].backups?.length) {
        streamSourceCount += backupStreams[ch.id].backups.length;
      }

      // Determine active stream source
      let activeSourceLabel = null;
      let activeSourceIndex = 0;
      try {
        const streamUrls = liveTVService.getStreamUrls(ch.id);
        const activeState = channelActiveSource.get(ch.id);
        activeSourceIndex = activeState?.urlIndex || 0;
        const dlUrl = dlStreamMap[ch.id];
        // Label based on which URL is at the active index
        if (streamUrls.length > 0) {
          const activeUrl = streamUrls[activeSourceIndex] || streamUrls[0];
          if (dlUrl && activeUrl === dlUrl) {
            activeSourceLabel = 'daddylive';
          } else if (activeUrl.includes('tvpass.org')) {
            activeSourceLabel = 'tvpass';
          } else if (activeUrl.includes('localhost:9191') || activeUrl.includes('dlhd')) {
            activeSourceLabel = 'daddylive';
          } else {
            activeSourceLabel = 'backup';
          }
        }
      } catch (e) { /* channel may not have URLs */ }

      channels.push({
        id: ch.id,
        name: ch.name,
        displayName: meta?.custom_display_name || ch.displayName || ch.name,
        group: ch.group || 'Live',
        logo: meta?.custom_logo_url || autoMatchedLogo || null,
        isEnabled: meta ? !!meta.is_enabled : true,
        sortOrder: meta?.sort_order ?? 999,
        source: ch.source || (ch.id.startsWith('cab-') ? 'cabernet' : 'tvpass'),
        hasMetadata: !!meta,
        hasDaddyLive,
        hasBackupStreams,
        streamSourceCount,
        activeSource: activeSourceLabel,
        activeSourceIndex
      });
    }

    for (const ch of specialChannels) {
      const meta = metaMap.get(ch.id);
      const hasBackup = !!backupStreams[ch.id];
      channels.push({
        id: ch.id,
        name: ch.name,
        displayName: meta?.custom_display_name || ch.display_name,
        group: ch.group_name || 'Special',
        logo: meta?.custom_logo_url || ch.logo_url,
        isEnabled: meta ? !!meta.is_enabled : !!ch.is_active,
        sortOrder: meta?.sort_order ?? ch.sort_order,
        source: 'special',
        hasMetadata: !!meta,
        hasDaddyLive: false,
        hasBackupStreams: hasBackup,
        streamSourceCount: 1 + (hasBackup ? (backupStreams[ch.id].backups?.length || 0) + (backupStreams[ch.id].primary ? 1 : 0) : 0)
      });
    }

    // Sort by sort_order, then name
    channels.sort((a, b) => {
      if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
      return a.displayName.localeCompare(b.displayName);
    });

    res.json({ channels, total: channels.length });
  } catch (error) {
    logger.error('Get admin channels error:', error);
    res.status(500).json({ error: 'Failed to load channels' });
  }
});

/**
 * PUT /api/admin/channels/batch
 * Batch update multiple channels (for bulk enable/disable, reorder).
 * Body: { channels: [{ id, isEnabled, sortOrder, displayName }] }
 * NOTE: This MUST be defined before /channels/:id to avoid route conflict.
 */
router.put('/channels/batch', (req, res) => {
  try {
    const { channels } = req.body;
    if (!Array.isArray(channels)) {
      return res.status(400).json({ error: 'channels must be an array' });
    }

    const upsert = db.prepare(`
      INSERT INTO channel_metadata (channel_id, custom_display_name, is_enabled, sort_order, updated_at)
      VALUES (?, ?, ?, ?, datetime('now'))
      ON CONFLICT(channel_id) DO UPDATE SET
        custom_display_name = COALESCE(excluded.custom_display_name, channel_metadata.custom_display_name),
        is_enabled = excluded.is_enabled,
        sort_order = excluded.sort_order,
        updated_at = datetime('now')
    `);

    const transaction = db.transaction((items) => {
      for (const ch of items) {
        upsert.run(
          ch.id,
          ch.displayName || null,
          ch.isEnabled !== undefined ? (ch.isEnabled ? 1 : 0) : 1,
          ch.sortOrder ?? 999
        );
      }
    });

    transaction(channels);

    logger.info(`[Admin] Batch updated ${channels.length} channels`);
    res.json({ success: true, count: channels.length });
  } catch (error) {
    logger.error('Batch update channels error:', error);
    res.status(500).json({ error: 'Failed to batch update channels' });
  }
});

/**
 * POST /api/admin/channels/reset-sources
 * Reset active stream source for all channels (or specific ones) back to primary (DaddyLive).
 * Clears the failover state so channels retry DaddyLive on next request.
 */
router.post('/channels/reset-sources', (req, res) => {
  try {
    const { channelIds } = req.body || {};
    let resetCount = 0;

    if (channelIds && Array.isArray(channelIds)) {
      for (const id of channelIds) {
        if (channelActiveSource.has(id)) {
          channelActiveSource.delete(id);
          resetCount++;
        }
      }
    } else {
      resetCount = channelActiveSource.size;
      channelActiveSource.clear();
    }

    logger.info(`[Admin] Reset stream sources for ${resetCount} channels`);
    res.json({ success: true, resetCount });
  } catch (error) {
    logger.error('Reset sources error:', error);
    res.status(500).json({ error: 'Failed to reset sources' });
  }
});

/**
 * POST /api/admin/channels/seed-cabernet-logos
 * Auto-match Cabernet channels to existing logos by display name.
 * Only sets logos for channels that don't already have one in channel_metadata.
 * NOTE: This MUST be defined before /channels/:id to avoid route conflict.
 */
router.post('/channels/seed-cabernet-logos', (req, res) => {
  try {
    const logoMatchMap = buildLogoMatchMap();

    // Get M3U channels
    const m3uData = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'
    `).get();

    if (!m3uData) {
      return res.json({ success: true, matched: 0 });
    }

    const channels = JSON.parse(m3uData.epg_data);
    const cabChannels = channels.filter(ch => ch.id.startsWith('cab-'));

    const upsert = db.prepare(`
      INSERT INTO channel_metadata (channel_id, custom_display_name, custom_logo_url, updated_at)
      VALUES (?, ?, ?, datetime('now'))
      ON CONFLICT(channel_id) DO UPDATE SET
        custom_logo_url = COALESCE(channel_metadata.custom_logo_url, excluded.custom_logo_url),
        custom_display_name = COALESCE(channel_metadata.custom_display_name, excluded.custom_display_name),
        updated_at = datetime('now')
    `);

    let matched = 0;
    const transaction = db.transaction(() => {
      for (const ch of cabChannels) {
        const normalized = normalizeName(ch.displayName || ch.name);
        const matchedLogo = logoMatchMap.get(normalized);
        if (matchedLogo) {
          upsert.run(ch.id, ch.displayName || ch.name, matchedLogo);
          matched++;
        }
      }
    });

    transaction();
    logger.info(`[Admin] Auto-matched ${matched}/${cabChannels.length} Cabernet channels to existing logos`);
    res.json({ success: true, matched, total: cabChannels.length });
  } catch (error) {
    logger.error('Seed cabernet logos error:', error);
    res.status(500).json({ error: 'Failed to seed logos' });
  }
});

/**
 * PUT /api/admin/channels/:id
 * Update a channel's admin config (display name, enabled, sort_order).
 */
router.put('/channels/:id', (req, res) => {
  try {
    const channelId = req.params.id;
    const { displayName, isEnabled, sortOrder } = req.body;

    // Upsert into channel_metadata
    const existing = db.prepare('SELECT * FROM channel_metadata WHERE channel_id = ?').get(channelId);

    if (existing) {
      const updates = [];
      const values = [];

      if (displayName !== undefined) {
        updates.push('custom_display_name = ?');
        values.push(displayName || null);
      }
      if (isEnabled !== undefined) {
        updates.push('is_enabled = ?');
        values.push(isEnabled ? 1 : 0);
      }
      if (sortOrder !== undefined) {
        updates.push('sort_order = ?');
        values.push(sortOrder);
      }

      if (updates.length > 0) {
        updates.push("updated_at = datetime('now')");
        values.push(channelId);
        db.prepare(`UPDATE channel_metadata SET ${updates.join(', ')} WHERE channel_id = ?`).run(...values);
      }
    } else {
      db.prepare(`
        INSERT INTO channel_metadata (channel_id, custom_display_name, is_enabled, sort_order, updated_at)
        VALUES (?, ?, ?, ?, datetime('now'))
      `).run(channelId, displayName || null, isEnabled !== undefined ? (isEnabled ? 1 : 0) : 1, sortOrder ?? 999);
    }

    logger.info(`[Admin] Channel ${channelId} updated: displayName=${displayName}, enabled=${isEnabled}, order=${sortOrder}`);
    res.json({ success: true, channelId });
  } catch (error) {
    logger.error('Update channel error:', error);
    res.status(500).json({ error: 'Failed to update channel' });
  }
});

/**
 * POST /api/admin/channels/:id/logo
 * Upload a custom logo for a channel.
 */
router.post('/channels/:id/logo', upload.single('logo'), (req, res) => {
  try {
    const channelId = req.params.id;

    if (!req.file) {
      return res.status(400).json({ error: 'No file uploaded' });
    }

    const logoUrl = `/static/logos/${req.file.filename}`;

    // Upsert channel_metadata with new logo
    db.prepare(`
      INSERT INTO channel_metadata (channel_id, custom_logo_url, updated_at)
      VALUES (?, ?, datetime('now'))
      ON CONFLICT(channel_id) DO UPDATE SET
        custom_logo_url = excluded.custom_logo_url,
        updated_at = datetime('now')
    `).run(channelId, logoUrl);

    logger.info(`[Admin] Logo uploaded for channel ${channelId}: ${logoUrl}`);
    res.json({ success: true, logoUrl });
  } catch (error) {
    logger.error('Upload logo error:', error);
    res.status(500).json({ error: 'Failed to upload logo' });
  }
});

module.exports = router;
