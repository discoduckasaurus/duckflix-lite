/**
 * FFprobe Service
 * Extracts subtitle stream metadata from video URLs using ffprobe
 */

const { spawn } = require('child_process');
const logger = require('../utils/logger');
const { standardizeLanguage, getLanguageCode } = require('../utils/language-standardizer');

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

const CODEC_CHECK_TIMEOUT = 8000; // 8 seconds — probing all streams takes slightly longer

// Audio codecs the ONN 4K (S905X4) can decode natively
const COMPATIBLE_AUDIO_CODECS = new Set([
  'aac', 'ac3', 'eac3', 'mp3', 'vorbis', 'opus', 'mp2', 'mp1'
]);

// Audio codecs the S905X4 cannot decode
const INCOMPATIBLE_AUDIO_CODECS = new Set([
  'dts', 'dca',           // DTS core
  'dts_hd_ma', 'dtshd',   // DTS-HD Master Audio
  'dts_hd', 'dts_hd_hra', // DTS-HD High Resolution
  'truehd'                 // Dolby TrueHD
]);

/**
 * Check if an audio codec is compatible with S905X4
 */
function isAudioCompatible(codecName, channels) {
  const codec = (codecName || '').toLowerCase();
  if (COMPATIBLE_AUDIO_CODECS.has(codec)) return true;
  if (INCOMPATIBLE_AUDIO_CODECS.has(codec)) return false;
  // flac/pcm: compatible at <=2ch only
  if (codec === 'flac' || codec.startsWith('pcm_')) {
    return (channels || 0) <= 2;
  }
  // Unknown codec — assume compatible (don't block playback)
  return true;
}

/**
 * Check video stream compatibility for S905X4 (ONN 4K)
 * Returns { compatible: boolean, reason: string|null }
 */
function checkVideoCompatibility(stream) {
  const codecName = (stream.codec_name || '').toLowerCase();
  const profile = (stream.profile || '').toLowerCase();
  const level = stream.level || 0;
  const width = stream.width || 0;
  const height = stream.height || 0;
  const pixFmt = (stream.pix_fmt || '').toLowerCase();
  const colorTransfer = (stream.color_transfer || '').toLowerCase();
  const extradataSize = stream.extradata_size || 0;

  // Parse framerate
  let fps = 0;
  if (stream.avg_frame_rate) {
    const parts = stream.avg_frame_rate.split('/');
    if (parts.length === 2 && parseInt(parts[1]) > 0) {
      fps = parseInt(parts[0]) / parseInt(parts[1]);
    }
  }

  // 1. Supported codec check
  const supportedCodecs = new Set(['h264', 'hevc', 'h265', 'vp9', 'av1', 'mpeg4']);
  if (!supportedCodecs.has(codecName)) {
    return { compatible: false, reason: `unsupported_codec_${codecName}` };
  }

  // 2. Resolution cap
  if (width > 4096 || height > 2304) {
    return { compatible: false, reason: `resolution_${width}x${height}` };
  }

  // 3. Bad chroma subsampling (applies to all codecs)
  if (/422|444|gbr|rgb/.test(pixFmt)) {
    return { compatible: false, reason: `chroma_${pixFmt}` };
  }

  // 4. 12-bit depth
  if (/p12|12le|12be/.test(pixFmt)) {
    return { compatible: false, reason: `12bit_${pixFmt}` };
  }

  // 5. H.264 specific checks
  if (codecName === 'h264') {
    if (profile.includes('high 10')) {
      return { compatible: false, reason: 'h264_high10' };
    }
    if (profile.includes('4:2:2') || profile.includes('4:4:4') || profile.includes('high 4:2:2') || profile.includes('high 4:4:4')) {
      return { compatible: false, reason: `h264_profile_${profile}` };
    }
    if (level > 51) {
      return { compatible: false, reason: `h264_level_${level}` };
    }
    if (width >= 3840 && fps > 30) {
      return { compatible: false, reason: `h264_4k_${Math.round(fps)}fps` };
    }
    // HDR10 on H.264 is not valid/supported
    if (colorTransfer === 'smpte2084') {
      return { compatible: false, reason: 'h264_hdr10' };
    }
    // No extradata = can't init decoder
    if (extradataSize === 0) {
      return { compatible: false, reason: 'h264_no_extradata' };
    }
  }

  // 6. HEVC specific checks — HEVC Main 10 is ALLOWED (S905X4 handles it fine)
  if (codecName === 'hevc' || codecName === 'h265') {
    if (profile.includes('rext') || profile.includes('scc') || profile.includes('4:2:2') || profile.includes('4:4:4')) {
      return { compatible: false, reason: `hevc_profile_${profile}` };
    }
    if (level > 153) { // Level 5.1 = 153
      return { compatible: false, reason: `hevc_level_${level}` };
    }
    if (extradataSize === 0) {
      return { compatible: false, reason: 'hevc_no_extradata' };
    }
  }

  // 7. VP9 — Profile 2 (10-bit 4:2:0) is ALLOWED on S905X4
  if (codecName === 'vp9') {
    if (profile.includes('3')) { // Profile 3 = 10-bit + 4:2:2/4:4:4
      return { compatible: false, reason: 'vp9_profile3' };
    }
  }

  // 8. Dolby Vision checks via side_data
  if (stream.side_data_list) {
    for (const sd of stream.side_data_list) {
      if (sd.side_data_type === 'DOVI configuration record' || sd.dv_profile !== undefined) {
        const dvProfile = sd.dv_profile;
        const compatId = sd.dv_bl_signal_compatibility_id;

        // DV Profile 7 with no HDR10 compat layer = won't play
        if (dvProfile === 7 && compatId === 0) {
          return { compatible: false, reason: 'dv_profile7_no_compat' };
        }
        // DV Profile 4 = no compat layer
        if (dvProfile === 4) {
          return { compatible: false, reason: 'dv_profile4' };
        }
      }
    }
  }

  return { compatible: true, reason: null };
}

/**
 * Full stream analysis: video compatibility + audio compatibility + best audio track selection
 * Single ffprobe call for both video and audio streams.
 *
 * @param {string} videoUrl - URL to the video file
 * @returns {Promise<Object>} Analysis result
 */
async function analyzeStreamCompatibility(videoUrl) {
  const startTime = Date.now();

  if (!videoUrl) {
    return {
      videoCompatible: true, videoReason: null,
      audioCompatible: true, audioNeedsProcessing: false,
      bestCompatibleAudioIndex: null, defaultAudioCodec: null,
      audioStreams: [], probeTimeMs: 0, timedOut: false
    };
  }

  return new Promise((resolve) => {
    let stdout = '';
    let timedOut = false;

    const args = [
      '-v', 'error',
      '-print_format', 'json',
      '-show_chapters',
      '-show_entries', 'format=duration',
      '-show_entries', 'stream=index,codec_type,codec_name,profile,level,width,height,pix_fmt,avg_frame_rate,bit_rate,bits_per_raw_sample,channels,sample_rate,extradata_size,color_transfer,codec_tag_string',
      '-show_entries', 'stream_side_data_list',
      '-show_entries', 'stream_disposition=default,forced,hearing_impaired',
      '-show_entries', 'stream_tags=language,title',
      videoUrl
    ];

    const proc = spawn('ffprobe', args, {
      stdio: ['ignore', 'pipe', 'pipe']
    });

    proc.stdout.on('data', (data) => {
      stdout += data.toString();
    });

    const makeDefaultResult = (overrides = {}) => ({
      videoCompatible: true, videoReason: null,
      audioCompatible: true, audioNeedsProcessing: false,
      bestCompatibleAudioIndex: null, defaultAudioCodec: null,
      audioStreams: [], subtitleStreams: [], subtitleCleanupNeeded: false,
      cleanSubtitleArgs: null, hasEnglishSubtitle: false,
      chapters: [], duration: null,
      probeTimeMs: Date.now() - startTime, timedOut: false,
      ...overrides
    });

    proc.on('error', (err) => {
      logger.warn(`FFprobe stream analysis error: ${err.message}`);
      resolve(makeDefaultResult({ reason: 'probe_error' }));
    });

    proc.on('close', (code) => {
      if (timedOut) return;

      const probeTimeMs = Date.now() - startTime;

      if (code !== 0) {
        resolve(makeDefaultResult({ probeTimeMs }));
        return;
      }

      try {
        const result = JSON.parse(stdout);
        const streams = result.streams || [];

        // Find first video stream
        const videoStream = streams.find(s => s.codec_type === 'video');
        if (!videoStream) {
          resolve(makeDefaultResult({ probeTimeMs }));
          return;
        }

        // Video compatibility check
        const videoCheck = checkVideoCompatibility(videoStream);
        const videoCodecTag = videoStream.codec_tag_string || '';
        const rawCodecName = (videoStream.codec_name || '').toLowerCase();
        const videoProfile = videoStream.profile || '';
        const videoLevel = videoStream.level || 0;
        const fullVideoCodec = videoCodecTag || `${rawCodecName} (${videoProfile}, L${videoLevel})`;

        // Audio analysis
        const audioStreams = streams
          .filter(s => s.codec_type === 'audio')
          .map(s => ({
            index: s.index,
            codec: (s.codec_name || 'unknown').toLowerCase(),
            channels: s.channels || 0,
            language: s.tags?.language || null,
            title: s.tags?.title || null,
            isDefault: s.disposition?.default === 1,
            compatible: isAudioCompatible(s.codec_name, s.channels)
          }));

        const defaultAudio = audioStreams.find(a => a.isDefault) || audioStreams[0] || null;
        const defaultAudioCodec = defaultAudio?.codec || null;
        const audioCompatible = !defaultAudio || defaultAudio.compatible;

        // Find best compatible audio track (when default is incompatible)
        let bestCompatibleAudioIndex = null;
        let audioNeedsProcessing = false;

        if (!audioCompatible && audioStreams.length > 0) {
          audioNeedsProcessing = true;

          // Priority: (1) default+compatible, (2) english+compatible, (3) any compatible
          const compatTracks = audioStreams.filter(a => a.compatible);

          if (compatTracks.length > 0) {
            // Prefer default track if it's compatible (shouldn't reach here, but safety)
            const defaultCompat = compatTracks.find(a => a.isDefault);
            if (defaultCompat) {
              bestCompatibleAudioIndex = defaultCompat.index;
            } else {
              // Prefer English
              const engCompat = compatTracks.find(a =>
                a.language && (a.language === 'eng' || a.language === 'en')
              );
              bestCompatibleAudioIndex = engCompat ? engCompat.index : compatTracks[0].index;
            }
          }
          // If no compatible tracks found, bestCompatibleAudioIndex stays null (needs transcode)
        }

        // Subtitle analysis
        const subtitleStreams = streams
          .filter(s => s.codec_type === 'subtitle')
          .map(s => {
            const lang = s.tags?.language || null;
            const title = s.tags?.title || null;
            // Try language tag first, fall back to title for standardization
            let stdResult = standardizeLanguage(lang);
            if (!stdResult.isStandard && title) {
              stdResult = standardizeLanguage(title);
            }
            return {
              index: s.index,
              codec: (s.codec_name || 'unknown').toLowerCase(),
              language: lang,
              title: title,
              standardizedLanguage: stdResult.standardized,
              languageCode: stdResult.isStandard ? getLanguageCode(stdResult.standardized) : null,
              isRecognized: stdResult.isStandard,
              isForced: s.disposition?.forced === 1,
              isDefault: s.disposition?.default === 1,
              isSDH: s.disposition?.hearing_impaired === 1
            };
          });

        // Filter subtitle tracks: keep recognized, remove forced-only, deduplicate per language
        const seenLanguages = new Map();
        for (const sub of subtitleStreams) {
          if (!sub.isRecognized) continue;
          if (sub.isForced && !sub.isDefault) continue;

          const langKey = sub.languageCode || sub.standardizedLanguage;
          const existing = seenLanguages.get(langKey);

          if (!existing) {
            seenLanguages.set(langKey, sub);
          } else {
            // Prefer: default > non-forced > non-SDH
            const score = (s) => (s.isDefault ? 4 : 0) + (!s.isForced ? 2 : 0) + (!s.isSDH ? 1 : 0);
            if (score(sub) > score(existing)) {
              seenLanguages.set(langKey, sub);
            }
          }
        }
        const cleanSubs = [...seenLanguages.values()];

        // Determine if cleanup is needed
        const subtitleCleanupNeeded = subtitleStreams.length > 0 && (
          subtitleStreams.length !== cleanSubs.length ||
          subtitleStreams.some(s => !s.isRecognized)
        );

        // Build ffmpeg args for selective subtitle mapping
        let cleanSubtitleArgs = null;
        if (subtitleCleanupNeeded) {
          if (cleanSubs.length > 0) {
            const mapArgs = cleanSubs.flatMap(s => ['-map', `0:${s.index}`]);
            const metadataArgs = cleanSubs.flatMap((s, i) => [
              `-metadata:s:s:${i}`, `language=${s.languageCode || 'und'}`,
              `-metadata:s:s:${i}`, `title=${s.standardizedLanguage || 'Unknown'}`
            ]);
            // Set first English track as default, clear forced on all
            const dispositionArgs = cleanSubs.flatMap((s, i) => {
              const isFirstEng = s.languageCode === 'en' && !cleanSubs.slice(0, i).some(p => p.languageCode === 'en');
              return [`-disposition:s:${i}`, isFirstEng ? 'default' : '0'];
            });
            cleanSubtitleArgs = { mapArgs, metadataArgs: [...metadataArgs, ...dispositionArgs] };
          } else {
            // All subs were bad — map none
            cleanSubtitleArgs = { mapArgs: [], metadataArgs: [] };
          }
        }

        const hasEnglishSubtitle = cleanSubs.some(s => s.languageCode === 'en');

        if (subtitleStreams.length > 0) {
          logger.info(`[SubAnalysis] ${subtitleStreams.length} sub tracks found, ${cleanSubs.length} kept after cleanup${subtitleCleanupNeeded ? ' (cleanup needed)' : ''}, hasEnglish=${hasEnglishSubtitle}`);
          if (subtitleCleanupNeeded) {
            const removed = subtitleStreams.filter(s => !cleanSubs.includes(s));
            for (const r of removed) {
              logger.debug(`[SubAnalysis] Removing: idx=${r.index} lang=${r.language} title="${r.title}" forced=${r.isForced} recognized=${r.isRecognized}`);
            }
          }
        }

        resolve({
          videoCompatible: videoCheck.compatible,
          videoReason: videoCheck.reason,
          videoCodec: fullVideoCodec,
          videoCodecName: rawCodecName,
          audioCompatible,
          audioNeedsProcessing,
          bestCompatibleAudioIndex,
          defaultAudioCodec,
          audioStreams,
          subtitleStreams,
          subtitleCleanupNeeded,
          cleanSubtitleArgs,
          hasEnglishSubtitle,
          chapters: result.chapters || [],
          duration: parseFloat(result.format?.duration) || null,
          probeTimeMs,
          timedOut: false
        });

      } catch (parseErr) {
        logger.warn(`FFprobe stream analysis parse error: ${parseErr.message}`);
        resolve(makeDefaultResult({ probeTimeMs }));
      }
    });

    const timeout = setTimeout(() => {
      timedOut = true;
      proc.kill('SIGKILL');
      const probeTimeMs = Date.now() - startTime;
      // On timeout, assume compatible (don't block playback)
      resolve(makeDefaultResult({ probeTimeMs, timedOut: true }));
    }, CODEC_CHECK_TIMEOUT);

    proc.on('close', () => clearTimeout(timeout));
  });
}

/**
 * Legacy wrapper — calls analyzeStreamCompatibility() and returns the old shape.
 * Keeps backward compatibility with any callers using the old API.
 */
async function checkCodecCompatibility(videoUrl) {
  const analysis = await analyzeStreamCompatibility(videoUrl);
  return {
    compatible: analysis.videoCompatible,
    codec: analysis.videoCodec || null,
    reason: analysis.videoReason,
    probeTimeMs: analysis.probeTimeMs,
    timedOut: analysis.timedOut
  };
}

module.exports = {
  isFFprobeAvailable,
  getSubtitleStreams,
  getAllStreams,
  extractSubtitleStream,
  checkCodecCompatibility,
  analyzeStreamCompatibility,
  FFPROBE_TIMEOUT,
  CODEC_CHECK_TIMEOUT
};
