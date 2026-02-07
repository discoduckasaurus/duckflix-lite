const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');
const axios = require('axios');
const { db } = require('../db/init');
const { authenticateToken, requireAdmin } = require('../middleware/auth');
const logger = require('../utils/logger');

const router = express.Router();

// All admin provider routes require authentication and admin privileges
router.use(authenticateToken);
router.use(requireAdmin);

// Multer config for logo uploads
const LOGOS_DIR = path.join(__dirname, '..', 'static', 'logos', 'providers');
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    fs.mkdirSync(LOGOS_DIR, { recursive: true });
    cb(null, LOGOS_DIR);
  },
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname) || '.png';
    const providerId = String(req.params.id).replace(/[^a-zA-Z0-9_-]/g, '_');
    cb(null, `provider_${providerId}${ext}`);
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

const TMDB_TIMEOUT = 10000;

/**
 * Fetch providers from TMDB API for a given region.
 * Returns array of { id, name, logo, priority }.
 */
async function fetchTmdbProviders(region = 'US') {
  const tmdbApiKey = process.env.TMDB_API_KEY;
  if (!tmdbApiKey) throw new Error('TMDB API key not configured');

  // Fetch both movie and TV providers to get a comprehensive list
  const [movieRes, tvRes] = await Promise.all([
    axios.get('https://api.themoviedb.org/3/watch/providers/movie', {
      params: { api_key: tmdbApiKey, watch_region: region },
      timeout: TMDB_TIMEOUT
    }),
    axios.get('https://api.themoviedb.org/3/watch/providers/tv', {
      params: { api_key: tmdbApiKey, watch_region: region },
      timeout: TMDB_TIMEOUT
    })
  ]);

  // Merge and deduplicate by provider_id
  const providerMap = new Map();
  const allResults = [...(movieRes.data.results || []), ...(tvRes.data.results || [])];
  for (const p of allResults) {
    if (!providerMap.has(p.provider_id)) {
      providerMap.set(p.provider_id, {
        id: p.provider_id,
        name: p.provider_name,
        logo: p.logo_path ? `https://image.tmdb.org/t/p/w92${p.logo_path}` : null,
        priority: p.display_priority
      });
    }
  }

  return Array.from(providerMap.values()).sort((a, b) => a.priority - b.priority);
}

/**
 * GET /api/admin/providers
 * List all TMDB providers merged with admin metadata.
 * Auto-inserts new providers as disabled.
 */
router.get('/providers', async (req, res) => {
  try {
    const region = req.query.region || 'US';

    // Fetch current providers from TMDB
    const tmdbProviders = await fetchTmdbProviders(region);

    // Get all provider_metadata
    const metadata = db.prepare(`
      SELECT provider_id, custom_display_name, custom_logo_url, is_enabled, sort_order, notes
      FROM provider_metadata
    `).all();
    const metaMap = new Map(metadata.map(m => [m.provider_id, m]));

    // Track new providers (in TMDB but not in metadata)
    const newProviderIds = [];
    const insertNew = db.prepare(`
      INSERT OR IGNORE INTO provider_metadata (provider_id, is_enabled, sort_order, updated_at)
      VALUES (?, 0, 999, datetime('now'))
    `);

    const insertTransaction = db.transaction((providers) => {
      for (const p of providers) {
        if (!metaMap.has(p.id)) {
          insertNew.run(p.id);
          newProviderIds.push(p.id);
        }
      }
    });
    insertTransaction(tmdbProviders);

    // Re-fetch metadata if we inserted new ones
    let finalMetaMap = metaMap;
    if (newProviderIds.length > 0) {
      const updatedMetadata = db.prepare(`
        SELECT provider_id, custom_display_name, custom_logo_url, is_enabled, sort_order, notes
        FROM provider_metadata
      `).all();
      finalMetaMap = new Map(updatedMetadata.map(m => [m.provider_id, m]));
    }

    // Build combined provider list
    const providers = tmdbProviders.map(p => {
      const meta = finalMetaMap.get(p.id);
      return {
        id: p.id,
        name: p.name,
        displayName: meta?.custom_display_name || p.name,
        logo: meta?.custom_logo_url || p.logo,
        tmdbLogo: p.logo, // Always keep original TMDB logo reference
        isEnabled: meta ? !!meta.is_enabled : false, // New = disabled
        sortOrder: meta?.sort_order ?? 999,
        hasMetadata: !!meta && (meta.custom_display_name || meta.custom_logo_url || meta.sort_order !== 999),
        isNew: newProviderIds.includes(p.id),
        priority: p.priority
      };
    });

    // Sort by sort_order, then name
    providers.sort((a, b) => {
      if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder;
      return a.displayName.localeCompare(b.displayName);
    });

    res.json({
      providers,
      total: providers.length,
      newCount: newProviderIds.length,
      newProviderIds
    });
  } catch (error) {
    logger.error('Get admin providers error:', error);
    res.status(500).json({ error: 'Failed to load providers' });
  }
});

/**
 * PUT /api/admin/providers/batch
 * Batch update multiple providers (for bulk enable/disable, reorder).
 * Body: { providers: [{ id, isEnabled, sortOrder, displayName }] }
 * NOTE: This MUST be defined before /providers/:id to avoid route conflict.
 */
router.put('/providers/batch', (req, res) => {
  try {
    const { providers } = req.body;
    if (!Array.isArray(providers)) {
      return res.status(400).json({ error: 'providers must be an array' });
    }

    const upsert = db.prepare(`
      INSERT INTO provider_metadata (provider_id, custom_display_name, is_enabled, sort_order, updated_at)
      VALUES (?, ?, ?, ?, datetime('now'))
      ON CONFLICT(provider_id) DO UPDATE SET
        custom_display_name = COALESCE(excluded.custom_display_name, provider_metadata.custom_display_name),
        is_enabled = excluded.is_enabled,
        sort_order = excluded.sort_order,
        updated_at = datetime('now')
    `);

    const transaction = db.transaction((items) => {
      for (const p of items) {
        upsert.run(
          p.id,
          p.displayName || null,
          p.isEnabled !== undefined ? (p.isEnabled ? 1 : 0) : 1,
          p.sortOrder ?? 999
        );
      }
    });

    transaction(providers);

    logger.info(`[Admin] Batch updated ${providers.length} providers`);
    res.json({ success: true, count: providers.length });
  } catch (error) {
    logger.error('Batch update providers error:', error);
    res.status(500).json({ error: 'Failed to batch update providers' });
  }
});

/**
 * PUT /api/admin/providers/:id
 * Update a single provider's admin config.
 */
router.put('/providers/:id', (req, res) => {
  try {
    const providerId = parseInt(req.params.id, 10);
    const { displayName, isEnabled, sortOrder } = req.body;

    const existing = db.prepare('SELECT * FROM provider_metadata WHERE provider_id = ?').get(providerId);

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
        values.push(providerId);
        db.prepare(`UPDATE provider_metadata SET ${updates.join(', ')} WHERE provider_id = ?`).run(...values);
      }
    } else {
      db.prepare(`
        INSERT INTO provider_metadata (provider_id, custom_display_name, is_enabled, sort_order, updated_at)
        VALUES (?, ?, ?, ?, datetime('now'))
      `).run(providerId, displayName || null, isEnabled !== undefined ? (isEnabled ? 1 : 0) : 1, sortOrder ?? 999);
    }

    logger.info(`[Admin] Provider ${providerId} updated: displayName=${displayName}, enabled=${isEnabled}, order=${sortOrder}`);
    res.json({ success: true, providerId });
  } catch (error) {
    logger.error('Update provider error:', error);
    res.status(500).json({ error: 'Failed to update provider' });
  }
});

/**
 * POST /api/admin/providers/:id/logo
 * Upload a custom logo for a provider.
 */
router.post('/providers/:id/logo', upload.single('logo'), (req, res) => {
  try {
    const providerId = parseInt(req.params.id, 10);

    if (!req.file) {
      return res.status(400).json({ error: 'No file uploaded' });
    }

    const logoUrl = `/static/logos/providers/${req.file.filename}`;

    db.prepare(`
      INSERT INTO provider_metadata (provider_id, custom_logo_url, updated_at)
      VALUES (?, ?, datetime('now'))
      ON CONFLICT(provider_id) DO UPDATE SET
        custom_logo_url = excluded.custom_logo_url,
        updated_at = datetime('now')
    `).run(providerId, logoUrl);

    logger.info(`[Admin] Logo uploaded for provider ${providerId}: ${logoUrl}`);
    res.json({ success: true, logoUrl });
  } catch (error) {
    logger.error('Upload provider logo error:', error);
    res.status(500).json({ error: 'Failed to upload logo' });
  }
});

module.exports = router;
