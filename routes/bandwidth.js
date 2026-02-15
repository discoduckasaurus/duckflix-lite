const express = require('express');
const crypto = require('crypto');
const { authenticateToken } = require('../middleware/auth');
const { db } = require('../db/init');
const logger = require('../utils/logger');

const router = express.Router();

// Legacy test payload (25MB) for backward compat
const LEGACY_PAYLOAD_SIZE = 25 * 1024 * 1024;
const legacyPayload = crypto.randomBytes(LEGACY_PAYLOAD_SIZE);

// Streaming test: reusable 64KB chunk — small enough to flush through nginx
const STREAM_CHUNK_SIZE = 64 * 1024;
const streamChunk = crypto.randomBytes(STREAM_CHUNK_SIZE);

// Default/max streaming duration
const DEFAULT_STREAM_DURATION_S = 5;
const MAX_STREAM_DURATION_S = 10;

/**
 * GET /api/bandwidth/test
 * Legacy: returns fixed test payload for client to measure download speed
 */
router.get('/test', authenticateToken, (req, res) => {
  logger.info(`Bandwidth test (legacy) requested by user ${req.user.username}`);

  res.set({
    'Content-Type': 'application/octet-stream',
    'Content-Length': LEGACY_PAYLOAD_SIZE,
    'Cache-Control': 'no-store',
    'X-Test-Size-Bytes': LEGACY_PAYLOAD_SIZE
  });

  res.send(legacyPayload);
});

/**
 * GET /api/bandwidth/test-stream?duration=5
 * Streaming bandwidth test — sends random data for `duration` seconds.
 * Works accurately at ANY connection speed (1 Mbps or 1 Gbps).
 *
 * Client should:
 *   1. Record timestamp of first byte received
 *   2. Accumulate total bytes received
 *   3. On stream end, compute: totalBytes * 8 / elapsedSeconds / 1_000_000 = Mbps
 *   4. POST result to /api/bandwidth/report
 *
 * Response headers:
 *   X-Test-Duration-Seconds: actual duration the server will stream
 */
router.get('/test-stream', authenticateToken, (req, res) => {
  const durationS = Math.min(
    Math.max(parseInt(req.query.duration) || DEFAULT_STREAM_DURATION_S, 1),
    MAX_STREAM_DURATION_S
  );

  logger.info(`Bandwidth test (stream, ${durationS}s) requested by user ${req.user.username}`);

  res.set({
    'Content-Type': 'application/octet-stream',
    'Cache-Control': 'no-store',
    'Transfer-Encoding': 'chunked',
    'X-Accel-Buffering': 'no', // Tell nginx not to buffer this response
    'X-Test-Duration-Seconds': durationS
  });
  res.flushHeaders();

  const startTime = Date.now();
  const endTime = startTime + (durationS * 1000);
  let totalBytes = 0;
  let stopped = false;

  function stop(reason) {
    if (stopped) return;
    stopped = true;
    if (!res.writableEnded) res.end();
    logger.info(`Bandwidth test ${reason}: ${Math.round(totalBytes / (1024 * 1024))}MB in ${((Date.now() - startTime) / 1000).toFixed(1)}s to ${req.user.username}`);
  }

  // Server-side timeout — if drain never fires, don't hang forever
  const safetyTimeout = setTimeout(() => stop('timed out'), (durationS + 5) * 1000);

  function writeChunks() {
    while (!stopped && Date.now() < endTime) {
      const ok = res.write(streamChunk);
      totalBytes += STREAM_CHUNK_SIZE;
      if (!ok) {
        res.once('drain', writeChunks);
        return;
      }
    }
    if (!stopped) {
      clearTimeout(safetyTimeout);
      stop('completed');
    }
  }

  req.on('close', () => {
    clearTimeout(safetyTimeout);
    stop('client disconnected');
  });

  writeChunks();
});

/**
 * POST /api/bandwidth/report
 * Client reports measured bandwidth
 */
router.post('/report', authenticateToken, (req, res) => {
  try {
    const { measuredMbps, durationMs, trigger } = req.body;
    const userId = req.user.sub;

    if (typeof measuredMbps !== 'number' || !Number.isFinite(measuredMbps) || measuredMbps <= 0) {
      return res.status(400).json({ error: 'Invalid measuredMbps value' });
    }

    // Cap at reasonable maximum (1 Gbps)
    const cappedMbps = Math.min(measuredMbps, 1000);

    // Flag unreliable measurements (test completed too fast for accurate reading)
    const isReliable = !durationMs || durationMs >= 2000;

    db.prepare(`
      UPDATE users
      SET measured_bandwidth_mbps = ?,
          bandwidth_measured_at = datetime('now')
      WHERE id = ?
    `).run(cappedMbps, userId);

    logger.info(`Bandwidth reported for user ${req.user.username}: ${cappedMbps.toFixed(1)} Mbps (trigger: ${trigger || 'unknown'}, duration: ${durationMs || '?'}ms, reliable: ${isReliable})`);

    res.json({
      success: true,
      recorded: cappedMbps,
      reliable: isReliable,
      maxBitrate: cappedMbps / 1.3
    });
  } catch (error) {
    logger.error('Bandwidth report error:', error);
    res.status(500).json({ error: 'Failed to record bandwidth' });
  }
});

/**
 * GET /api/bandwidth/status
 * Get current user's bandwidth info
 */
router.get('/status', authenticateToken, (req, res) => {
  try {
    const userId = req.user.sub;

    const user = db.prepare(`
      SELECT measured_bandwidth_mbps, bandwidth_measured_at, bandwidth_safety_margin
      FROM users WHERE id = ?
    `).get(userId);

    if (!user || !user.measured_bandwidth_mbps) {
      return res.json({
        hasMeasurement: false,
        needsTest: true
      });
    }

    const safetyMargin = user.bandwidth_safety_margin || 1.3;
    const maxBitrate = user.measured_bandwidth_mbps / safetyMargin;

    // Check staleness (>1 hour)
    const measuredAt = user.bandwidth_measured_at ? new Date(user.bandwidth_measured_at + 'Z').getTime() : 0;
    const ageMs = Date.now() - measuredAt;
    const isStale = ageMs > 60 * 60 * 1000;

    res.json({
      hasMeasurement: true,
      measuredMbps: user.measured_bandwidth_mbps,
      measuredAt: user.bandwidth_measured_at,
      safetyMargin: safetyMargin,
      maxBitrateMbps: Math.round(maxBitrate * 10) / 10,
      isStale,
      suggestRetest: isStale
    });
  } catch (error) {
    logger.error('Bandwidth status error:', error);
    res.status(500).json({ error: 'Failed to get bandwidth status' });
  }
});

module.exports = router;
