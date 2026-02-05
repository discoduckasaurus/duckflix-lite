/**
 * FFprobe Service
 * Extracts subtitle stream metadata from video URLs using ffprobe
 */

const { spawn } = require('child_process');
const logger = require('../utils/logger');

const FFPROBE_TIMEOUT = 15000; // 15 seconds for slow remote URLs

/**
 * Check if ffprobe is available on the system
 * @returns {Promise<boolean>}
 */
async function isFFprobeAvailable() {
  return new Promise((resolve) => {
    const proc = spawn('ffprobe', ['-version'], {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    proc.on('error', () => resolve(false));
    proc.on('close', (code) => resolve(code === 0));

    // Timeout after 5 seconds
    setTimeout(() => {
      proc.kill();
      resolve(false);
    }, 5000);
  });
}

/**
 * Get subtitle streams from a video URL
 * @param {string} videoUrl - URL to the video file
 * @returns {Promise<Array>} Array of subtitle stream objects
 */
async function getSubtitleStreams(videoUrl) {
  if (!videoUrl) {
    logger.warn('getSubtitleStreams called with empty URL');
    return [];
  }

  return new Promise((resolve) => {
    let stdout = '';
    let stderr = '';
    let timedOut = false;

    const args = [
      '-v', 'quiet',
      '-print_format', 'json',
      '-show_streams',
      '-select_streams', 's', // Only subtitle streams
      videoUrl
    ];

    const proc = spawn('ffprobe', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    proc.stdout.on('data', (data) => {
      stdout += data.toString();
    });

    proc.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    proc.on('error', (err) => {
      logger.warn(`FFprobe spawn error: ${err.message}`);
      resolve([]);
    });

    proc.on('close', (code) => {
      if (timedOut) {
        return; // Already resolved due to timeout
      }

      if (code !== 0) {
        if (stderr) {
          logger.debug(`FFprobe stderr: ${stderr.substring(0, 200)}`);
        }
        resolve([]);
        return;
      }

      try {
        const result = JSON.parse(stdout);
        const streams = result.streams || [];

        const subtitles = streams.map((stream) => ({
          index: stream.index,
          codec: stream.codec_name || 'unknown',
          language: stream.tags?.language || null,
          title: stream.tags?.title || null,
          disposition: {
            default: stream.disposition?.default === 1,
            forced: stream.disposition?.forced === 1,
            hearingImpaired: stream.disposition?.hearing_impaired === 1
          }
        }));

        logger.info(`FFprobe found ${subtitles.length} subtitle stream(s) in video`);
        resolve(subtitles);
      } catch (parseErr) {
        logger.warn(`FFprobe JSON parse error: ${parseErr.message}`);
        resolve([]);
      }
    });

    // Timeout for slow remote URLs
    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      logger.warn(`FFprobe timed out after ${FFPROBE_TIMEOUT}ms for URL`);
      resolve([]);
    }, FFPROBE_TIMEOUT);

    proc.on('close', () => {
      clearTimeout(timeout);
    });
  });
}

/**
 * Get all stream info (video, audio, subtitle) from a video URL
 * @param {string} videoUrl - URL to the video file
 * @returns {Promise<Object>} Object with video, audio, and subtitle arrays
 */
async function getAllStreams(videoUrl) {
  if (!videoUrl) {
    return { video: [], audio: [], subtitle: [] };
  }

  return new Promise((resolve) => {
    let stdout = '';
    let timedOut = false;

    const args = [
      '-v', 'quiet',
      '-print_format', 'json',
      '-show_streams',
      videoUrl
    ];

    const proc = spawn('ffprobe', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    proc.stdout.on('data', (data) => {
      stdout += data.toString();
    });

    proc.on('error', () => {
      resolve({ video: [], audio: [], subtitle: [] });
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      if (code !== 0) {
        resolve({ video: [], audio: [], subtitle: [] });
        return;
      }

      try {
        const result = JSON.parse(stdout);
        const streams = result.streams || [];

        const organized = {
          video: streams.filter(s => s.codec_type === 'video'),
          audio: streams.filter(s => s.codec_type === 'audio'),
          subtitle: streams.filter(s => s.codec_type === 'subtitle')
        };

        resolve(organized);
      } catch {
        resolve({ video: [], audio: [], subtitle: [] });
      }
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      resolve({ video: [], audio: [], subtitle: [] });
    }, FFPROBE_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

const EXTRACT_TIMEOUT = 60000; // 60 seconds for extraction

/**
 * Extract a subtitle stream from a video and save as SRT
 * @param {string} videoUrl - URL to the video file
 * @param {number} streamIndex - The stream index to extract
 * @param {string} outputPath - Where to save the extracted subtitle
 * @returns {Promise<boolean>} True if successful
 */
async function extractSubtitleStream(videoUrl, streamIndex, outputPath) {
  if (!videoUrl || streamIndex === undefined || !outputPath) {
    logger.warn('extractSubtitleStream called with missing parameters');
    return false;
  }

  return new Promise((resolve) => {
    let timedOut = false;

    const args = [
      '-y', // Overwrite output
      '-i', videoUrl,
      '-map', `0:${streamIndex}`, // Select specific stream by index
      '-c:s', 'srt', // Convert to SRT format
      outputPath
    ];

    logger.info(`Extracting subtitle stream ${streamIndex} to ${outputPath}`);

    const proc = spawn('ffmpeg', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let stderr = '';
    proc.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    proc.on('error', (err) => {
      logger.warn(`FFmpeg extract error: ${err.message}`);
      resolve(false);
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      if (code !== 0) {
        logger.warn(`FFmpeg extract failed (code ${code}): ${stderr.substring(0, 200)}`);
        resolve(false);
        return;
      }

      logger.info(`Successfully extracted subtitle to ${outputPath}`);
      resolve(true);
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      logger.warn(`FFmpeg extract timed out after ${EXTRACT_TIMEOUT}ms`);
      resolve(false);
    }, EXTRACT_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

/**
 * Incompatible codec patterns for low-end Android TV devices
 * Based on actual decoder failure reports from Nvidia/Android TV
 */
const INCOMPATIBLE_CODECS = [
  // H.264 profiles that fail on Android TV
  /^avc1\.6E/i,      // High 10 Profile (10-bit) - all levels
  /^avc1\.7A/i,      // High 4:2:2 Profile - all levels
  /^avc1\.F4/i,      // High 4:4:4 Predictive - all levels
  /^avc1\.64..33/i,  // High Profile Level 5.1
  /^avc1\.64..34/i,  // High Profile Level 5.2

  // HEVC/H.265 problematic profiles
  /^hvc1\.2/i,       // Main 10 Profile (10-bit)
  /^hev1\.2/i,       // Main 10 Profile alternate

  // VP9 problematic profiles
  /^vp09\.02/i,      // Profile 2 (10-bit)
  /^vp09\.03/i,      // Profile 3 (10-bit + 4:2:2/4:4:4)
];

const CODEC_CHECK_TIMEOUT = 6000; // 6 seconds max per source

/**
 * Check if a video stream's codec is compatible with Android TV
 * @param {string} videoUrl - URL to the video file
 * @returns {Promise<{compatible: boolean, codec: string|null, reason: string|null, probeTimeMs: number, timedOut: boolean}>}
 */
async function checkCodecCompatibility(videoUrl) {
  const startTime = Date.now();

  if (!videoUrl) {
    return { compatible: true, codec: null, reason: 'no_url', probeTimeMs: 0, timedOut: false };
  }

  return new Promise((resolve) => {
    let stdout = '';
    let timedOut = false;

    // Only probe video stream, limit to first stream for speed
    const args = [
      '-v', 'quiet',
      '-print_format', 'json',
      '-show_streams',
      '-select_streams', 'v:0', // First video stream only
      videoUrl
    ];

    const proc = spawn('ffprobe', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    proc.stdout.on('data', (data) => {
      stdout += data.toString();
    });

    proc.on('error', (err) => {
      const probeTimeMs = Date.now() - startTime;
      logger.warn(`FFprobe codec check error: ${err.message}`);
      resolve({ compatible: true, codec: null, reason: 'probe_error', probeTimeMs, timedOut: false });
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      const probeTimeMs = Date.now() - startTime;

      if (code !== 0) {
        resolve({ compatible: true, codec: null, reason: 'probe_failed', probeTimeMs, timedOut: false });
        return;
      }

      try {
        const result = JSON.parse(stdout);
        const videoStream = result.streams?.[0];

        if (!videoStream) {
          resolve({ compatible: true, codec: null, reason: 'no_video_stream', probeTimeMs, timedOut: false });
          return;
        }

        // Get codec tag string (e.g., "avc1.6E0029", "hvc1.2.4.L120")
        const codecTag = videoStream.codec_tag_string || '';
        const codecName = videoStream.codec_name || '';
        const profile = videoStream.profile || '';
        const level = videoStream.level || 0;

        // Build full codec identifier for logging
        const fullCodec = codecTag || `${codecName} (${profile}, L${level})`;

        // Check against incompatible patterns
        for (const pattern of INCOMPATIBLE_CODECS) {
          if (pattern.test(codecTag)) {
            resolve({
              compatible: false,
              codec: fullCodec,
              reason: 'exceeds_capabilities',
              probeTimeMs,
              timedOut: false
            });
            return;
          }
        }

        // Additional check: H.264 High 10 might not have proper tag
        if (codecName === 'h264' && profile?.toLowerCase().includes('high 10')) {
          resolve({
            compatible: false,
            codec: fullCodec,
            reason: 'exceeds_capabilities',
            probeTimeMs,
            timedOut: false
          });
          return;
        }

        // Additional check: HEVC Main 10
        if ((codecName === 'hevc' || codecName === 'h265') && profile?.toLowerCase().includes('main 10')) {
          resolve({
            compatible: false,
            codec: fullCodec,
            reason: 'exceeds_capabilities',
            probeTimeMs,
            timedOut: false
          });
          return;
        }

        resolve({ compatible: true, codec: fullCodec, reason: null, probeTimeMs, timedOut: false });

      } catch (parseErr) {
        const probeTimeMs = Date.now() - startTime;
        logger.warn(`FFprobe codec check parse error: ${parseErr.message}`);
        resolve({ compatible: true, codec: null, reason: 'parse_error', probeTimeMs, timedOut: false });
      }
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      const probeTimeMs = Date.now() - startTime;
      // On timeout, assume compatible (don't block playback)
      resolve({ compatible: true, codec: null, reason: 'timeout', probeTimeMs, timedOut: true });
    }, CODEC_CHECK_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

module.exports = {
  isFFprobeAvailable,
  getSubtitleStreams,
  getAllStreams,
  extractSubtitleStream,
  checkCodecCompatibility,
  FFPROBE_TIMEOUT,
  CODEC_CHECK_TIMEOUT
};
