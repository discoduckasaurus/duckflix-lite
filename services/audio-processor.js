/**
 * Audio Processor Service
 * Remux or transcode incompatible audio (DTS/TrueHD) for ONN 4K / S905X4 playback
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const logger = require('../utils/logger');

const REMUX_TIMEOUT = 300000;     // 5 minutes for remux (copy streams)
const TRANSCODE_TIMEOUT = 600000; // 10 minutes for audio transcode

/**
 * Remux video with a specific compatible audio track, discarding incompatible ones.
 * Video + subtitles are copied untouched.
 *
 * @param {string} inputUrl - HTTP URL or local file path
 * @param {string} outputPath - Local file path for output
 * @param {number} audioStreamIndex - Absolute stream index of the compatible audio track
 * @param {Object} [options] - Additional options
 * @param {string} [options.videoCodecName] - Raw video codec name (e.g. "hevc") for MP4 tag fixup
 * @returns {Promise<{success: boolean, outputPath: string, durationMs: number}>}
 */
async function remuxWithCompatibleAudio(inputUrl, outputPath, audioStreamIndex, options = {}) {
  const startTime = Date.now();
  logger.info(`[AudioProcessor] Remuxing with audio stream ${audioStreamIndex} -> ${path.basename(outputPath)}`);

  return new Promise((resolve) => {
    let timedOut = false;

    const args = [
      '-y',
      '-i', inputUrl,
      '-map', '0:v',
      '-map', `0:${audioStreamIndex}`,
    ];
    // Subtitle mapping: use explicit args if provided, otherwise copy all subs
    if (options.subtitleArgs && options.subtitleArgs.mapArgs.length > 0) {
      args.push(...options.subtitleArgs.mapArgs);
    } else if (!options.subtitleArgs) {
      args.push('-map', '0:s?');
    }
    // else: subtitleArgs with empty mapArgs = no subs mapped (all removed)
    args.push('-c', 'copy');
    if (options.subtitleArgs?.metadataArgs?.length > 0) {
      args.push(...options.subtitleArgs.metadataArgs);
    }
    // Safari requires hvc1 tag for HEVC in MP4 (hev1 = black screen with audio)
    if (outputPath.endsWith('.mp4') && (options.videoCodecName === 'hevc' || options.videoCodecName === 'h265')) {
      args.push('-tag:v', 'hvc1');
    }
    args.push(outputPath);

    const proc = spawn('ffmpeg', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let stderr = '';
    proc.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    proc.on('error', (err) => {
      const durationMs = Date.now() - startTime;
      logger.error(`[AudioProcessor] Remux spawn error: ${err.message}`);
      resolve({ success: false, outputPath, durationMs });
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      const durationMs = Date.now() - startTime;

      if (code !== 0) {
        logger.error(`[AudioProcessor] Remux failed (code ${code}): ${stderr.substring(0, 300)}`);
        // Clean up partial output
        fs.unlink(outputPath, () => {});
        resolve({ success: false, outputPath, durationMs });
        return;
      }

      logger.info(`[AudioProcessor] Remux complete in ${(durationMs / 1000).toFixed(1)}s -> ${path.basename(outputPath)}`);
      resolve({ success: true, outputPath, durationMs });
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      const durationMs = Date.now() - startTime;
      logger.error(`[AudioProcessor] Remux timed out after ${REMUX_TIMEOUT / 1000}s`);
      fs.unlink(outputPath, () => {});
      resolve({ success: false, outputPath, durationMs });
    }, REMUX_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

/**
 * Transcode the first audio stream to EAC3 640kbps.
 * Used when no compatible audio track exists (DTS-only files).
 * Video + subtitles are copied untouched.
 *
 * @param {string} inputUrl - HTTP URL or local file path
 * @param {string} outputPath - Local file path for output
 * @param {Object} [options] - Additional options
 * @param {string} [options.videoCodecName] - Raw video codec name (e.g. "hevc") for MP4 tag fixup
 * @returns {Promise<{success: boolean, outputPath: string, durationMs: number}>}
 */
async function transcodeAudioToEac3(inputUrl, outputPath, options = {}) {
  const startTime = Date.now();
  logger.info(`[AudioProcessor] Transcoding audio to EAC3 -> ${path.basename(outputPath)}`);

  return new Promise((resolve) => {
    let timedOut = false;

    const args = [
      '-y',
      '-i', inputUrl,
      '-map', '0:v',
      '-map', '0:a:0',
    ];
    // Subtitle mapping: use explicit args if provided, otherwise copy all subs
    if (options.subtitleArgs && options.subtitleArgs.mapArgs.length > 0) {
      args.push(...options.subtitleArgs.mapArgs);
    } else if (!options.subtitleArgs) {
      args.push('-map', '0:s?');
    }
    args.push(
      '-c:v', 'copy',
      '-c:s', 'copy',
      '-c:a', 'eac3',
      '-b:a', '640k',
    );
    if (options.subtitleArgs?.metadataArgs?.length > 0) {
      args.push(...options.subtitleArgs.metadataArgs);
    }
    // Safari requires hvc1 tag for HEVC in MP4 (hev1 = black screen with audio)
    if (outputPath.endsWith('.mp4') && (options.videoCodecName === 'hevc' || options.videoCodecName === 'h265')) {
      args.push('-tag:v', 'hvc1');
    }
    args.push(outputPath);

    const proc = spawn('ffmpeg', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let stderr = '';
    proc.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    proc.on('error', (err) => {
      const durationMs = Date.now() - startTime;
      logger.error(`[AudioProcessor] Transcode spawn error: ${err.message}`);
      resolve({ success: false, outputPath, durationMs });
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      const durationMs = Date.now() - startTime;

      if (code !== 0) {
        logger.error(`[AudioProcessor] Transcode failed (code ${code}): ${stderr.substring(0, 300)}`);
        fs.unlink(outputPath, () => {});
        resolve({ success: false, outputPath, durationMs });
        return;
      }

      logger.info(`[AudioProcessor] Transcode complete in ${(durationMs / 1000).toFixed(1)}s -> ${path.basename(outputPath)}`);
      resolve({ success: true, outputPath, durationMs });
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      const durationMs = Date.now() - startTime;
      logger.error(`[AudioProcessor] Transcode timed out after ${TRANSCODE_TIMEOUT / 1000}s`);
      fs.unlink(outputPath, () => {});
      resolve({ success: false, outputPath, durationMs });
    }, TRANSCODE_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

/**
 * Remux video with subtitle cleanup only (audio is fine as-is).
 * All video + audio streams are copied; only subtitles are selectively mapped.
 *
 * @param {string} inputUrl - HTTP URL or local file path
 * @param {string} outputPath - Local file path for output
 * @param {Object} subtitleArgs - { mapArgs: [...], metadataArgs: [...] }
 * @param {Object} [options] - Additional options
 * @param {string} [options.videoCodecName] - Raw video codec name for MP4 tag fixup
 * @returns {Promise<{success: boolean, outputPath: string, durationMs: number}>}
 */
async function remuxSubtitlesOnly(inputUrl, outputPath, subtitleArgs, options = {}) {
  const startTime = Date.now();
  logger.info(`[AudioProcessor] Subtitle-only remux -> ${path.basename(outputPath)}`);

  return new Promise((resolve) => {
    let timedOut = false;

    const args = [
      '-y',
      '-i', inputUrl,
      '-map', '0:v',
      '-map', '0:a',
    ];
    if (subtitleArgs.mapArgs.length > 0) {
      args.push(...subtitleArgs.mapArgs);
    }
    // else: no subs mapped (all removed)
    args.push('-c', 'copy');
    if (subtitleArgs.metadataArgs?.length > 0) {
      args.push(...subtitleArgs.metadataArgs);
    }
    if (outputPath.endsWith('.mp4') && (options.videoCodecName === 'hevc' || options.videoCodecName === 'h265')) {
      args.push('-tag:v', 'hvc1');
    }
    args.push(outputPath);

    const proc = spawn('ffmpeg', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let stderr = '';
    proc.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    proc.on('error', (err) => {
      const durationMs = Date.now() - startTime;
      logger.error(`[AudioProcessor] Subtitle remux spawn error: ${err.message}`);
      resolve({ success: false, outputPath, durationMs });
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      const durationMs = Date.now() - startTime;

      if (code !== 0) {
        logger.error(`[AudioProcessor] Subtitle remux failed (code ${code}): ${stderr.substring(0, 300)}`);
        fs.unlink(outputPath, () => {});
        resolve({ success: false, outputPath, durationMs });
        return;
      }

      logger.info(`[AudioProcessor] Subtitle remux complete in ${(durationMs / 1000).toFixed(1)}s -> ${path.basename(outputPath)}`);
      resolve({ success: true, outputPath, durationMs });
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      const durationMs = Date.now() - startTime;
      logger.error(`[AudioProcessor] Subtitle remux timed out after ${REMUX_TIMEOUT / 1000}s`);
      fs.unlink(outputPath, () => {});
      resolve({ success: false, outputPath, durationMs });
    }, REMUX_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

/**
 * Delete files older than maxAgeDays in a directory
 * @param {string} directory - Directory to clean
 * @param {number} maxAgeDays - Max age in days
 */
function cleanupOldFiles(directory, maxAgeDays) {
  try {
    if (!fs.existsSync(directory)) return;

    const maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000;
    const now = Date.now();
    const files = fs.readdirSync(directory);
    let cleaned = 0;

    for (const file of files) {
      const filePath = path.join(directory, file);
      try {
        const stat = fs.statSync(filePath);
        if (stat.isFile() && (now - stat.mtimeMs) > maxAgeMs) {
          fs.unlinkSync(filePath);
          cleaned++;
        }
      } catch (err) {
        logger.warn(`[AudioProcessor] Failed to clean up ${file}: ${err.message}`);
      }
    }

    if (cleaned > 0) {
      logger.info(`[AudioProcessor] Cleaned up ${cleaned} old transcoded file(s) from ${directory}`);
    }
  } catch (err) {
    logger.error(`[AudioProcessor] Cleanup error: ${err.message}`);
  }
}

module.exports = {
  remuxWithCompatibleAudio,
  transcodeAudioToEac3,
  remuxSubtitlesOnly,
  cleanupOldFiles
};
