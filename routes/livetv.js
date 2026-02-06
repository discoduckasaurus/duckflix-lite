const express = require('express');
const axios = require('axios');
const { authenticateToken } = require('../middleware/auth');
const liveTVService = require('../services/livetv-service');
const logger = require('../utils/logger');

const router = express.Router();

/**
 * GET /api/logo-proxy
 * Proxy external channel logo images (handles CORS/SSL issues)
 * Query param: url (encoded logo URL)
 *
 * This endpoint does NOT require authentication since logos are public content
 * and need to be accessible for UI rendering before user logs in (e.g., login screen backgrounds)
 */
router.get('/logo-proxy', async (req, res) => {
  try {
    const { url } = req.query;

    if (!url) {
      return res.status(400).json({ error: 'Missing url parameter' });
    }

    logger.debug(`Logo proxy request: ${url}`);

    const response = await axios.get(url, {
      responseType: 'arraybuffer',
      timeout: 10000,
      headers: {
        'User-Agent': 'DuckFlix/1.0',
      }
    });

    // Forward the image with proper content type
    const contentType = response.headers['content-type'] || 'image/png';
    res.set('Content-Type', contentType);
    res.set('Cache-Control', 'public, max-age=86400'); // Cache for 24 hours
    res.send(response.data);
  } catch (error) {
    logger.error('Logo proxy error:', error.message);
    // Return a 1x1 transparent PNG as fallback
    const transparentPng = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
      'base64'
    );
    res.set('Content-Type', 'image/png');
    res.send(transparentPng);
  }
});

// All other Live TV routes require authentication
router.use(authenticateToken);

/**
 * GET /api/livetv/channels
 * Get all enabled channels with EPG data for the authenticated user
 */
router.get('/channels', async (req, res) => {
  try {
    const userId = req.user.sub;
    const channelsRaw = await liveTVService.getChannelsForUser(userId);

    // Transform to snake_case for APK compatibility
    const now = Math.floor(Date.now() / 1000);
    const channels = channelsRaw.map(ch => ({
      id: ch.id,
      name: ch.name,
      display_name: ch.displayName || ch.name,
      channel_number: ch.channelNumber,
      logo: ch.logo,
      group: ch.group,
      is_favorite: ch.isFavorite,
      current_program: ch.currentProgram ? {
        title: ch.currentProgram.title,
        start: Math.floor(ch.currentProgram.start / 1000), // Convert to Unix seconds
        stop: Math.floor(ch.currentProgram.stop / 1000),
        description: ch.currentProgram.description,
        category: ch.currentProgram.category
      } : null,
      upcoming_programs: (ch.upcomingPrograms || []).map(p => ({
        title: p.title,
        start: Math.floor(p.start / 1000),
        stop: Math.floor(p.stop / 1000),
        description: p.description,
        category: p.category
      }))
    }));

    // Calculate EPG time window
    const epgStart = now;
    const epgEnd = now + (6 * 60 * 60); // 6 hours from now

    res.json({
      channels,
      epg_start: epgStart,
      epg_end: epgEnd
    });
  } catch (error) {
    logger.error('Get channels error:', error);
    res.status(500).json({ error: 'Failed to load channels' });
  }
});

/**
 * GET /api/livetv/stream/:channelId
 * Proxy/pass through the HLS stream for a channel
 *
 * This endpoint acts as a transparent proxy for the HLS stream.
 * For M3U8 manifests, it rewrites segment URLs to point back through this proxy.
 * For TS segments and other content, it passes through as-is.
 */
// Track which stream source is currently working per channel (auto-failover state)
const channelActiveSource = new Map(); // channelId -> { urlIndex, baseUrl, failCount }
const SOURCE_FAIL_THRESHOLD = 3; // consecutive segment failures before switching

/**
 * Rewrite an HLS manifest: make relative URLs absolute and route through our proxy
 */
function rewriteManifest(manifest, baseUrl, channelId) {
  return manifest.split('\n').map(line => {
    const trimmed = line.trim();

    // Skip comments and empty lines
    if (trimmed.startsWith('#') || trimmed === '') {
      return line;
    }

    // This is a URL line (segment or sub-playlist)
    let segUrl = trimmed;

    // Make relative URLs absolute
    if (!segUrl.startsWith('http://') && !segUrl.startsWith('https://')) {
      segUrl = baseUrl + segUrl;
    }

    // Rewrite to go through our proxy
    return `/api/livetv/stream/${channelId}?url=${encodeURIComponent(segUrl)}`;
  }).join('\n');
}

/**
 * Fetch a manifest URL, follow redirects, and rewrite it.
 * Handles both master playlists (with sub-playlist refs) and media playlists (with segments).
 * For master playlists: resolves sub-playlist inline so the player gets a media playlist directly.
 */
async function fetchAndRewriteManifest(url, channelId) {
  const response = await axios.get(url, {
    timeout: 10000,
    headers: { 'User-Agent': 'DuckFlix/1.0' }
  });

  const manifest = typeof response.data === 'string' ? response.data : String(response.data);
  const finalUrl = response.request?.res?.responseUrl || url;
  const baseUrl = finalUrl.substring(0, finalUrl.lastIndexOf('/') + 1);

  if (finalUrl !== url) {
    logger.info(`[LiveTV] Redirect: ${url.substring(0, 40)}... -> ${finalUrl.substring(0, 60)}...`);
  }

  // Detect master playlist (has #EXT-X-STREAM-INF)
  if (manifest.includes('#EXT-X-STREAM-INF')) {
    // Master playlist — resolve the first variant's sub-playlist directly
    const lines = manifest.split('\n');
    for (const line of lines) {
      const trimmed = line.trim();
      if (trimmed && !trimmed.startsWith('#')) {
        // First non-comment, non-empty line after #EXT-X-STREAM-INF = sub-playlist URL
        let subUrl = trimmed;
        if (!subUrl.startsWith('http://') && !subUrl.startsWith('https://')) {
          subUrl = baseUrl + subUrl;
        }
        logger.info(`[LiveTV] Master playlist -> resolving sub-playlist: ${subUrl.substring(0, 80)}`);
        return fetchAndRewriteManifest(subUrl, channelId);
      }
    }
  }

  // Media playlist — rewrite segment URLs through our proxy
  return rewriteManifest(manifest, baseUrl, channelId);
}

router.get('/stream/:channelId', async (req, res) => {
  try {
    const { channelId } = req.params;
    const userId = req.user.sub;

    // Check if this is a request for a segment/sub-manifest or the top-level manifest
    const isSegmentRequest = req.query.segment || req.query.url;

    if (isSegmentRequest) {
      const targetUrl = req.query.url || req.query.segment;
      const isSubManifest = targetUrl.endsWith('.m3u8');

      try {
        if (isSubManifest) {
          // Sub-playlist — fetch as text, rewrite URLs, serve as manifest
          const rewritten = await fetchAndRewriteManifest(targetUrl, channelId);
          res.set('Content-Type', 'application/vnd.apple.mpegurl');
          return res.send(rewritten);
        }

        // Regular segment — pipe through as stream
        const response = await axios.get(targetUrl, {
          responseType: 'stream',
          timeout: 30000,
          headers: { 'User-Agent': 'DuckFlix/1.0' }
        });

        res.set('Content-Type', response.headers['content-type'] || 'video/mp2t');
        if (response.headers['content-length']) {
          res.set('Content-Length', response.headers['content-length']);
        }

        response.data.pipe(res);

        // Reset fail count on successful segment
        const active = channelActiveSource.get(channelId);
        if (active) active.failCount = 0;
      } catch (error) {
        // Track consecutive segment failures for auto-failover
        const active = channelActiveSource.get(channelId);
        if (active) {
          active.failCount = (active.failCount || 0) + 1;
          if (active.failCount >= SOURCE_FAIL_THRESHOLD) {
            const streamUrls = liveTVService.getStreamUrls(channelId);
            const nextIndex = (active.urlIndex + 1) % streamUrls.length;
            if (nextIndex !== active.urlIndex) {
              logger.warn(`[LiveTV] ${SOURCE_FAIL_THRESHOLD} consecutive segment failures on ${channelId}, switching from source ${active.urlIndex} to ${nextIndex}`);
              channelActiveSource.set(channelId, { urlIndex: nextIndex, failCount: 0 });
            }
          }
        }

        logger.error(`[LiveTV] Failed to fetch ${isSubManifest ? 'sub-manifest' : 'segment'}: ${error.message}`);
        res.status(502).json({ error: 'Failed to fetch stream segment' });
      }
      return;
    }

    // Manifest request — try stream URLs in priority order with fallback
    const streamUrls = liveTVService.getStreamUrls(channelId);

    // Start from the active source if we have one (auto-failover state)
    const active = channelActiveSource.get(channelId);
    const startIndex = active?.urlIndex || 0;

    for (let attempt = 0; attempt < streamUrls.length; attempt++) {
      const i = (startIndex + attempt) % streamUrls.length;
      const streamUrl = streamUrls[i];

      try {
        const rewritten = await fetchAndRewriteManifest(streamUrl, channelId);

        // Remember which source worked
        channelActiveSource.set(channelId, { urlIndex: i, failCount: 0 });

        res.set('Content-Type', 'application/vnd.apple.mpegurl');
        return res.send(rewritten);
      } catch (error) {
        logger.warn(`[LiveTV] Stream ${i + 1}/${streamUrls.length} failed for ${channelId}: ${error.message}`);
        // Try next URL
      }
    }

    // All URLs failed
    logger.error(`[LiveTV] All ${streamUrls.length} stream URLs failed for ${channelId}`);
    res.status(502).json({ error: 'All stream sources failed' });
  } catch (error) {
    logger.error('[LiveTV] Stream proxy error:', error);
    res.status(500).json({ error: 'Stream proxy failed' });
  }
});


/**
 * PATCH /api/livetv/channels/:id/toggle
 * Enable/disable a channel for the user
 */
router.patch('/channels/:id/toggle', (req, res) => {
  try {
    const { id } = req.params;
    const userId = req.user.sub;

    const newState = liveTVService.toggleChannel(userId, id);

    res.json({
      success: true,
      channelId: id,
      isEnabled: newState
    });
  } catch (error) {
    logger.error('Toggle channel error:', error);
    res.status(500).json({ error: 'Failed to toggle channel' });
  }
});

/**
 * POST /api/livetv/channels/:id/favorite
 * Toggle favorite status for a channel
 */
router.post('/channels/:id/favorite', (req, res) => {
  try {
    const { id } = req.params;
    const userId = req.user.sub;

    const newState = liveTVService.toggleFavorite(userId, id);

    res.json({
      success: true,
      channelId: id,
      isFavorite: newState
    });
  } catch (error) {
    logger.error('Toggle favorite error:', error);
    res.status(500).json({ error: 'Failed to toggle favorite' });
  }
});

/**
 * POST /api/livetv/channels/reorder
 * Update channel priority order
 * Body: { channelIds: ["espn", "cnn", "hbo-eastern-feed", ...] }
 */
router.post('/channels/reorder', (req, res) => {
  try {
    const { channelIds } = req.body;
    const userId = req.user.sub;

    if (!Array.isArray(channelIds)) {
      return res.status(400).json({ error: 'channelIds must be an array' });
    }

    liveTVService.reorderChannels(userId, channelIds);

    res.json({
      success: true,
      count: channelIds.length
    });
  } catch (error) {
    logger.error('Reorder channels error:', error);
    res.status(500).json({ error: 'Failed to reorder channels' });
  }
});

/**
 * POST /api/dvr/recordings/schedule (FUTURE - Phase 5)
 * Schedule a DVR recording
 *
 * Body:
 * {
 *   channelId: "espn",
 *   channelName: "ESPN",
 *   startTime: 1706749200000,
 *   endTime: 1706752800000,
 *   title: "NFL Live",
 *   description: "...",
 *   epg: { title, description, category, icon }
 * }
 */
router.post('/dvr/recordings/schedule', (req, res) => {
  // FUTURE implementation
  res.status(501).json({
    error: 'DVR functionality not yet implemented',
    message: 'This feature will be available in Phase 5'
  });
});

module.exports = router;
