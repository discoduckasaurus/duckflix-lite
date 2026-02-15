const express = require('express');
const axios = require('axios');
const fs = require('fs');
const { pipeline } = require('stream');
const { authenticateToken } = require('../middleware/auth');
const liveTVService = require('../services/livetv-service');
const { getNTVStreamUrl, NTV_DOMAINS } = require('../services/ntv-service');
const dftvService = require('../services/dftv-service');
const logger = require('../utils/logger');

const router = express.Router();

// NTV resolved URL cache: ntvChannelId -> { url, resolvedAt }
const ntvUrlCache = new Map();
const NTV_URL_CACHE_TTL = 5 * 60 * 1000; // 5 minutes

/**
 * Get a human-readable label for a stream URL (used for logging).
 */
function getSourceLabel(url) {
  if (url.startsWith('ntv://') || NTV_DOMAINS.some(d => url.includes(d))) return 'NTV';
  if (url.includes('tvpass.org')) return 'TVPass';
  return 'Backup';
}

/**
 * Check if a URL needs cdn-live.ru specific headers.
 * Matches explicit NTV_DOMAINS list + any *.cdn-live.* or *.cdn-google.* subdomains
 * to handle future CDN edge domain rotations automatically.
 */
function isCdnLiveUrl(url) {
  return NTV_DOMAINS.some(d => url.includes(d)) || /cdn-live\.|cdn-google\./.test(url);
}

/**
 * Build headers for a given stream URL.
 */
function getStreamHeaders(url) {
  const headers = { 'User-Agent': 'DuckFlix/1.0' };
  if (isCdnLiveUrl(url)) {
    headers['Origin'] = 'https://cdn-live.tv';
    headers['Referer'] = 'https://cdn-live.tv/';
  }
  return headers;
}

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
      sort_order: ch.sortOrder,
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
const channelActiveSource = new Map(); // channelId -> { urlIndex, failCount, failedAt }
const SOURCE_FAIL_THRESHOLD = 3; // consecutive segment failures before switching
const FAILOVER_RETRY_MS = 5 * 60 * 1000; // retry primary source after 5 minutes

// Track segment activity per channel to detect "manifest-only loop" (client stuck in error state)
const channelSegmentActivity = new Map(); // channelId -> { lastSegmentAt, manifestsSinceSegment }
const MANIFEST_ONLY_THRESHOLD = 5; // consecutive manifests without segments before failover
const MIN_SEGMENT_STALENESS_MS = 20 * 1000; // segments must be stale for this long
const MANIFEST_FAILOVER_COOLDOWN_MS = 3 * 60 * 1000; // 3 min cooldown after manifest-only failover

// Circuit breaker: skip sources that consistently fail manifest fetches.
// Prevents failover oscillation loops where a dead source is retried every 60s,
// leaking memory via accumulated axios error objects.
const sourceFailures = new Map(); // "channelId:sourceLabel" -> { count, lastFailAt }
const CIRCUIT_OPEN_THRESHOLD = 3; // consecutive failures before opening circuit
const CIRCUIT_OPEN_DURATION_MS = 10 * 60 * 1000; // 10 min cooldown when circuit is open

/**
 * Check if an error is a connection-level failure (upstream down, not a transient glitch).
 * These should NOT be retried — retrying doubles the leaked connections for no benefit.
 */
function isConnectionError(err) {
  const code = err.code || '';
  const msg = err.message || '';
  return code === 'ECONNREFUSED' || code === 'ECONNRESET' || code === 'ETIMEDOUT' ||
         code === 'ENOTFOUND' || msg.includes('socket hang up');
}

/**
 * Rewrite an HLS manifest: make relative URLs absolute and route through our proxy
 */
function rewriteManifest(manifest, baseUrl, channelId) {
  return manifest.split('\n').map(line => {
    const trimmed = line.trim();

    // Empty lines pass through
    if (trimmed === '') {
      return line;
    }

    // Handle #EXT-X-KEY lines — rewrite the URI value to go through our proxy
    if (trimmed.startsWith('#EXT-X-KEY') && trimmed.includes('URI="')) {
      let rewritten = line.replace(/URI="([^"]+)"/, (match, uri) => {
        let keyUrl = uri;
        if (!keyUrl.startsWith('http://') && !keyUrl.startsWith('https://')) {
          keyUrl = baseUrl + keyUrl;
        }
        return `URI="/api/livetv/stream/${channelId}?url=${encodeURIComponent(keyUrl)}"`;
      });
      // Strip KEYFORMAT="identity" — it's the default and not valid in VERSION < 5.
      // Some players (ExoPlayer) may reject it in a v3 manifest.
      rewritten = rewritten.replace(/,KEYFORMAT="identity"/i, '');
      return rewritten;
    }

    // Skip other comment lines
    if (trimmed.startsWith('#')) {
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
    headers: getStreamHeaders(url)
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

    // DFTV pseudo-live channel handling
    if (channelId === 'dftv-mixed') {
      // Segment request: serve .ts file from HLS dir
      const dftvSeg = req.query.url || req.query.segment;
      if (dftvSeg && dftvSeg.startsWith('dftv-seg:')) {
        const segName = dftvSeg.substring(9); // Strip "dftv-seg:" prefix
        const segPath = dftvService.getSegmentPath(segName);
        if (!segPath) {
          return res.status(404).json({ error: 'Segment not found' });
        }

        // Track segment activity for compatibility with failover system
        const activity = channelSegmentActivity.get(channelId) || {};
        activity.lastSegmentAt = Date.now();
        activity.manifestsSinceSegment = 0;
        channelSegmentActivity.set(channelId, activity);

        res.set('Content-Type', 'video/mp2t');
        const segStream = fs.createReadStream(segPath);
        segStream.on('error', (err) => {
          logger.debug(`[DFTV] Segment read error: ${err.message}`);
          if (!res.headersSent) res.status(404).json({ error: 'Segment read failed' });
          else res.destroy();
        });
        return segStream.pipe(res);
      }

      // Manifest request
      try {
        const manifest = await dftvService.getManifest();
        if (!manifest) {
          return res.status(503).json({ error: 'DFTV not ready — schedule may not be generated' });
        }

        // Rewrite segment URLs to route through our proxy
        const rewritten = manifest.split('\n').map(line => {
          const trimmed = line.trim();
          if (trimmed && !trimmed.startsWith('#')) {
            // This is a segment filename — rewrite to proxy URL
            const segName = trimmed.split('/').pop();
            return `/api/livetv/stream/dftv-mixed?url=${encodeURIComponent('dftv-seg:' + segName)}`;
          }
          return line;
        }).join('\n');

        res.set('Content-Type', 'application/vnd.apple.mpegurl');
        res.set('Cache-Control', 'no-cache, no-store');
        return res.send(rewritten);
      } catch (err) {
        logger.error(`[DFTV] Manifest error: ${err.message}`);
        return res.status(502).json({ error: 'DFTV stream failed' });
      }
    }

    // Check if this is a request for a segment/sub-manifest or the top-level manifest
    const isSegmentRequest = req.query.segment || req.query.url;

    if (isSegmentRequest) {
      const targetUrl = req.query.url || req.query.segment;
      const isSubManifest = targetUrl.endsWith('.m3u8');
      const isKeyRequest = targetUrl.includes('/key/');
      logger.info(`[LiveTV] ${channelId} ${isKeyRequest ? 'KEY' : isSubManifest ? 'SUB-MANIFEST' : 'SEGMENT'}: ${targetUrl.substring(0, 80)}`);

      try {
        if (isSubManifest) {
          // Sub-playlist — fetch as text, rewrite URLs, serve as manifest
          const rewritten = await fetchAndRewriteManifest(targetUrl, channelId);
          res.set('Content-Type', 'application/vnd.apple.mpegurl');
          res.set('Cache-Control', 'no-cache, no-store');
          return res.send(rewritten);
        }

        // Regular segment — pipe through as stream
        const response = await axios.get(targetUrl, {
          responseType: 'stream',
          timeout: 30000,
          headers: getStreamHeaders(targetUrl)
        });

        // Force binary content type — upstream CDNs may disguise segments as .css/.html/.js
        res.set('Content-Type', 'application/octet-stream');
        if (response.headers['content-length']) {
          res.set('Content-Length', response.headers['content-length']);
        }

        // Destroy upstream connection if client disconnects mid-stream
        res.on('close', () => response.data.destroy());

        // pipeline handles errors, cleanup, and backpressure automatically
        pipeline(response.data, res, (err) => {
          if (err && err.code !== 'ERR_STREAM_PREMATURE_CLOSE') {
            logger.debug(`[LiveTV] Segment pipeline error for ${channelId}: ${err.message}`);
          }
        });

        // Track segment activity (for manifest-only loop detection)
        const activity = channelSegmentActivity.get(channelId) || {};
        activity.lastSegmentAt = Date.now();
        activity.manifestsSinceSegment = 0;
        channelSegmentActivity.set(channelId, activity);

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
              const curLabel = getSourceLabel(streamUrls[active.urlIndex] || '');
              const nextLabel = getSourceLabel(streamUrls[nextIndex] || '');
              logger.warn(`[LiveTV] ${channelId} segment failover: ${curLabel} → ${nextLabel} (${SOURCE_FAIL_THRESHOLD} consecutive failures)`);
              channelActiveSource.set(channelId, { urlIndex: nextIndex, failCount: 0, failedAt: Date.now() });
            }
          }
        }

        logger.debug(`[LiveTV] Segment fetch failed for ${channelId}: ${error.message}`);
        res.status(502).json({ error: 'Failed to fetch stream segment' });
      }
      return;
    }

    // Manifest request — try stream URLs in priority order with fallback
    const streamUrls = liveTVService.getStreamUrls(channelId);

    // Start from the active source if we have one (auto-failover state)
    let active = channelActiveSource.get(channelId);

    // Re-navigate reset: if user re-enters a channel after >60s of inactivity,
    // reset to primary source. This fixes channels permanently stuck on fallback.
    const prevActivity = channelSegmentActivity.get(channelId);
    const lastActivity = prevActivity?.lastSegmentAt || prevActivity?.firstManifestAt || 0;
    const isReNavigate = lastActivity > 0 && (Date.now() - lastActivity) > 60000;

    if (isReNavigate && active && active.urlIndex > 0) {
      logger.info(`[LiveTV] Re-navigate reset for ${channelId} (inactive ${Math.round((Date.now() - lastActivity) / 1000)}s)`);
      channelActiveSource.delete(channelId);
      channelSegmentActivity.delete(channelId);
      active = null;
    }

    // Auto-recover: if failover is older than FAILOVER_RETRY_MS AND client is actively playing
    // (has segment activity), retry from primary. Don't recover if client is stuck in error state.
    if (active && active.urlIndex > 0 && active.failedAt && (Date.now() - active.failedAt > FAILOVER_RETRY_MS)) {
      const recoveryActivity = channelSegmentActivity.get(channelId);
      const hasRecentSegments = recoveryActivity?.lastSegmentAt && (Date.now() - recoveryActivity.lastSegmentAt < 30000);
      if (hasRecentSegments) {
        logger.info(`[LiveTV] Auto-retrying primary source for ${channelId} (failover was ${Math.round((Date.now() - active.failedAt) / 1000)}s ago)`);
        channelActiveSource.delete(channelId);
        channelSegmentActivity.delete(channelId);
        active = null;
      }
    }

    // Detect "manifest-only loop": client is polling manifests but not fetching segments.
    // This happens when ExoPlayer enters an error state — manifests succeed (200) but
    // the player never requests segments, so segment-level failover never triggers.
    const activity = channelSegmentActivity.get(channelId) || { lastSegmentAt: 0, manifestsSinceSegment: 0, firstManifestAt: 0 };
    if (!activity.firstManifestAt) activity.firstManifestAt = Date.now();
    activity.manifestsSinceSegment = (activity.manifestsSinceSegment || 0) + 1;
    channelSegmentActivity.set(channelId, activity);

    // Failover if: enough manifests without segments AND enough time has passed
    // (either since last segment, or since first manifest if segments never arrived)
    // Cooldown prevents rapid cycling when the CLIENT is broken (not the source)
    const segmentStaleSince = activity.lastSegmentAt || activity.firstManifestAt;
    const cooldownActive = activity.lastFailoverAt && (Date.now() - activity.lastFailoverAt) < MANIFEST_FAILOVER_COOLDOWN_MS;
    if (active && streamUrls.length > 1 && !cooldownActive &&
        activity.manifestsSinceSegment >= MANIFEST_ONLY_THRESHOLD &&
        (Date.now() - segmentStaleSince) > MIN_SEGMENT_STALENESS_MS) {
      // Client has been requesting manifests but no segments — source is broken from client perspective
      const nextIndex = (active.urlIndex + 1) % streamUrls.length;
      if (nextIndex !== active.urlIndex) {
        const curLabel = getSourceLabel(streamUrls[active.urlIndex] || '');
        const nextLabel = getSourceLabel(streamUrls[nextIndex] || '');
        logger.warn(`[LiveTV] ${channelId} manifest-only failover: ${curLabel} → ${nextLabel} (${activity.manifestsSinceSegment} manifests, no segments for ${Math.round((Date.now() - segmentStaleSince) / 1000)}s)`);
        channelActiveSource.set(channelId, { urlIndex: nextIndex, failCount: 0, failedAt: Date.now() });
        activity.manifestsSinceSegment = 0;
        activity.lastFailoverAt = Date.now();
        active = channelActiveSource.get(channelId);
      }
    }

    const startIndex = active?.urlIndex || 0;
    const previousIndex = active?.urlIndex ?? -1;

    for (let attempt = 0; attempt < streamUrls.length; attempt++) {
      const i = (startIndex + attempt) % streamUrls.length;
      let streamUrl = streamUrls[i];
      const sourceLabel = getSourceLabel(streamUrl);
      const circuitKey = `${channelId}:${sourceLabel}`;

      // Circuit breaker: skip sources that keep failing (prevents oscillation loops)
      const circuitState = sourceFailures.get(circuitKey);
      if (circuitState && circuitState.count >= CIRCUIT_OPEN_THRESHOLD &&
          (Date.now() - circuitState.lastFailAt) < CIRCUIT_OPEN_DURATION_MS) {
        logger.debug(`[LiveTV] Skipping ${sourceLabel} for ${channelId} (circuit open, ${circuitState.count} failures)`);
        continue;
      }

      try {
        // Resolve NTV marker URLs on-demand (tokens are ephemeral, cached for 5 min)
        if (streamUrl.startsWith('ntv://')) {
          const ntvChannelId = streamUrl.substring(6);
          const cachedNtv = ntvUrlCache.get(ntvChannelId);
          if (cachedNtv && (Date.now() - cachedNtv.resolvedAt) < NTV_URL_CACHE_TTL) {
            streamUrl = cachedNtv.url;
          } else {
            const ntvMap = liveTVService.getNTVStreamMap ? liveTVService.getNTVStreamMap() : {};
            let ntvChannel = null;
            for (const [, data] of Object.entries(ntvMap)) {
              if (data.channel_id === ntvChannelId) {
                ntvChannel = data;
                break;
              }
            }
            if (!ntvChannel) {
              throw new Error(`NTV channel ${ntvChannelId} not found in stream map`);
            }
            streamUrl = await getNTVStreamUrl(ntvChannel);
            ntvUrlCache.set(ntvChannelId, { url: streamUrl, resolvedAt: Date.now() });
          }
        }

        let rewritten;
        try {
          rewritten = await fetchAndRewriteManifest(streamUrl, channelId);
        } catch (firstErr) {
          // Don't retry connection-level errors — upstream is down, not a transient glitch
          if (isConnectionError(firstErr)) throw firstErr;
          // Retry once — CDN intermittent failures are common; avoids encrypted→unencrypted transition
          logger.debug(`[LiveTV] Retrying manifest for ${channelId} ${sourceLabel}: ${firstErr.message}`);
          rewritten = await fetchAndRewriteManifest(streamUrl, channelId);
        }

        // Source succeeded — reset circuit breaker
        sourceFailures.delete(circuitKey);

        // Log source switches (different source than last time, or first time)
        if (previousIndex !== i) {
          if (previousIndex === -1) {
            logger.info(`[LiveTV] ${channelId} → ${sourceLabel} (initial)`);
          } else {
            const prevLabel = getSourceLabel(streamUrls[previousIndex] || '');
            logger.warn(`[LiveTV] ${channelId} source switch: ${prevLabel} → ${sourceLabel}`);
          }
        }

        // Remember which source worked (set failedAt when on non-primary so auto-recovery can retry primary later)
        channelActiveSource.set(channelId, { urlIndex: i, failCount: 0, failedAt: i > 0 ? Date.now() : undefined });

        res.set('Content-Type', 'application/vnd.apple.mpegurl');
        res.set('Cache-Control', 'no-cache, no-store');
        return res.send(rewritten);
      } catch (error) {
        // Clear NTV cache on failure so next attempt re-resolves
        if (streamUrls[i].startsWith('ntv://')) {
          ntvUrlCache.delete(streamUrls[i].substring(6));
        }

        // Record failure in circuit breaker
        const cs = sourceFailures.get(circuitKey) || { count: 0, lastFailAt: 0 };
        cs.count++;
        cs.lastFailAt = Date.now();
        sourceFailures.set(circuitKey, cs);

        logger.warn(`[LiveTV] Stream ${i + 1}/${streamUrls.length} failed for ${channelId} (${sourceLabel}, circuit ${cs.count}/${CIRCUIT_OPEN_THRESHOLD}): ${error.message}`);
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
 * POST /api/livetv/stream/:channelId/error
 * Client-side error reporting — APK calls this when ExoPlayer encounters a stream error.
 * Triggers immediate failover to the next source for this channel.
 */
router.post('/stream/:channelId/error', (req, res) => {
  try {
    const { channelId } = req.params;
    const active = channelActiveSource.get(channelId);
    const streamUrls = liveTVService.getStreamUrls(channelId);

    if (!active || streamUrls.length <= 1) {
      return res.json({ success: true, action: 'none', reason: 'no alternative sources' });
    }

    const nextIndex = (active.urlIndex + 1) % streamUrls.length;
    if (nextIndex === active.urlIndex) {
      return res.json({ success: true, action: 'none', reason: 'already on last source' });
    }

    const curLabel = getSourceLabel(streamUrls[active.urlIndex] || '');
    const nextLabel = getSourceLabel(streamUrls[nextIndex] || '');

    logger.warn(`[LiveTV] ${channelId} client-reported error failover: ${curLabel} → ${nextLabel}`);
    channelActiveSource.set(channelId, { urlIndex: nextIndex, failCount: 0, failedAt: Date.now() });

    // Reset segment activity so the new source gets a fair chance
    channelSegmentActivity.delete(channelId);

    res.json({ success: true, action: 'failover', from: curLabel, to: nextLabel });
  } catch (error) {
    logger.error('[LiveTV] Client error report failed:', error);
    res.status(500).json({ error: 'Failed to process error report' });
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

// Prune stale entries from Live TV state Maps every 10 minutes
const PRUNE_INTERVAL_MS = 10 * 60 * 1000;
const STALE_THRESHOLD_MS = 30 * 60 * 1000; // 30 minutes

setInterval(() => {
  const now = Date.now();
  let pruned = 0;

  for (const [key, val] of channelSegmentActivity) {
    const lastActive = val.lastSegmentAt || val.firstManifestAt || 0;
    if (lastActive > 0 && (now - lastActive) > STALE_THRESHOLD_MS) {
      channelSegmentActivity.delete(key);
      channelActiveSource.delete(key);
      pruned++;
    }
  }

  for (const [key, val] of sourceFailures) {
    if ((now - val.lastFailAt) > STALE_THRESHOLD_MS) {
      sourceFailures.delete(key);
      pruned++;
    }
  }

  // Also prune NTV URL cache (entries older than TTL)
  for (const [key, val] of ntvUrlCache) {
    if ((now - val.resolvedAt) > NTV_URL_CACHE_TTL) {
      ntvUrlCache.delete(key);
    }
  }

  if (pruned > 0) {
    logger.debug(`[LiveTV] Pruned ${pruned} stale state entries`);
  }
}, PRUNE_INTERVAL_MS);

module.exports = router;
module.exports.channelActiveSource = channelActiveSource;
module.exports.channelSegmentActivity = channelSegmentActivity;
module.exports.sourceFailures = sourceFailures;
