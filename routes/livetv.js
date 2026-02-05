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
    const channels = await liveTVService.getChannelsForUser(userId);

    res.json({ channels });
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
router.get('/stream/:channelId', async (req, res) => {
  try {
    const { channelId } = req.params;
    const userId = req.user.sub;

    logger.info(`Stream request: user=${userId}, channel=${channelId}`);

    // Get the stream URL for this channel
    const streamUrl = liveTVService.getStreamUrl(channelId);

    // Check if this is a request for a segment or the manifest
    // If the URL has query params like ?segment=... it's a segment request
    const isSegmentRequest = req.query.segment || req.query.url;

    if (isSegmentRequest) {
      // Proxy a specific segment or URL
      const targetUrl = req.query.url || req.query.segment;

      try {
        const response = await axios.get(targetUrl, {
          responseType: 'stream',
          timeout: 30000,
          headers: {
            'User-Agent': 'DuckFlix/1.0',
          }
        });

        // Forward headers
        res.set('Content-Type', response.headers['content-type'] || 'video/mp2t');
        if (response.headers['content-length']) {
          res.set('Content-Length', response.headers['content-length']);
        }

        // Stream the response
        response.data.pipe(res);
      } catch (error) {
        logger.error(`Failed to fetch segment ${targetUrl}:`, error.message);
        res.status(502).json({ error: 'Failed to fetch stream segment' });
      }
      return;
    }

    // This is a manifest request - fetch and rewrite it
    try {
      const response = await axios.get(streamUrl, {
        timeout: 10000,
        headers: {
          'User-Agent': 'DuckFlix/1.0',
        }
      });

      const contentType = response.headers['content-type'] || '';

      if (contentType.includes('application/vnd.apple.mpegurl') ||
          contentType.includes('application/x-mpegurl') ||
          streamUrl.endsWith('.m3u8')) {

        // This is an M3U8 manifest - rewrite URLs
        let manifest = response.data;

        // Parse base URL for relative URLs
        const baseUrl = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1);

        // Rewrite relative URLs to absolute URLs through our proxy
        manifest = manifest.split('\n').map(line => {
          const trimmed = line.trim();

          // Skip comments and empty lines
          if (trimmed.startsWith('#') || trimmed === '') {
            return line;
          }

          // This is a URL line
          let targetUrl = trimmed;

          // Make relative URLs absolute
          if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
            targetUrl = baseUrl + targetUrl;
          }

          // Rewrite to go through our proxy
          const proxyUrl = `/api/livetv/stream/${channelId}?url=${encodeURIComponent(targetUrl)}`;
          return proxyUrl;
        }).join('\n');

        res.set('Content-Type', 'application/vnd.apple.mpegurl');
        res.send(manifest);
      } else {
        // Not a manifest, redirect or proxy directly
        res.redirect(streamUrl);
      }
    } catch (error) {
      logger.error(`Failed to fetch stream ${streamUrl}:`, error.message);
      res.status(502).json({ error: 'Failed to fetch stream' });
    }
  } catch (error) {
    logger.error('Stream proxy error:', error);
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
