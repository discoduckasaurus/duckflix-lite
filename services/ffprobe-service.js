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

module.exports = {
  isFFprobeAvailable,
  getSubtitleStreams,
  getAllStreams,
  extractSubtitleStream,
  FFPROBE_TIMEOUT
};
