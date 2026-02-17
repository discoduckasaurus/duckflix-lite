/**
 * Subtitle Sync Service
 * Fixes subtitle timing by detecting:
 *   1. Frame rate mismatch (linear drift) — detected deterministically by comparing
 *      video duration (ffprobe) to last subtitle timestamp. No correlation needed.
 *   2. Uniform offset — detected via speech onset matching after fps correction.
 */

const { spawn } = require('child_process');
const fs = require('fs');
const logger = require('../utils/logger');

const ANALYSIS_DURATION = 180; // For offset detection: first 3 minutes
const FFMPEG_TIMEOUT = 60000;
const RESOLUTION_SEC = 0.1;
const ONSET_TOLERANCE = 0.5;   // ±0.5s match window
const MAX_CONCURRENT_SYNCS = 3;
const MIN_SUBTITLE_ENTRIES = 10;
const MAX_OFFSET_SEC = 30;
const MIN_OFFSET_SEC = 0.5;
const MIN_SCALE_DIFF = 0.005;
const MIN_Z_SCORE = 4;

// Known fps ratios: if lastSubEnd/videoDuration is near these, apply the correction
const FPS_CORRECTIONS = [
  { ratio: 23.976 / 25,   scale: 25 / 23.976,   label: '25→23.976' },  // PAL sub on NTSC video
  { ratio: 25 / 23.976,   scale: 23.976 / 25,   label: '23.976→25' },  // NTSC sub on PAL video
  { ratio: 24 / 25,       scale: 25 / 24,        label: '25→24' },      // PAL sub on film video
  { ratio: 25 / 24,       scale: 24 / 25,        label: '24→25' },      // Film sub on PAL video
];
const RATIO_TOLERANCE = 0.015; // ±1.5% tolerance for ratio matching

let activeSyncs = 0;
const syncQueue = [];

function acquireSyncSlot() {
  if (activeSyncs < MAX_CONCURRENT_SYNCS) {
    activeSyncs++;
    return Promise.resolve();
  }
  return new Promise(resolve => syncQueue.push(resolve));
}

function releaseSyncSlot() {
  activeSyncs--;
  if (syncQueue.length > 0) {
    activeSyncs++;
    syncQueue.shift()();
  }
}

// ── Video duration (ffprobe) ─────────────────────────────────────────

function getVideoDuration(videoUrl) {
  return new Promise((resolve, reject) => {
    const proc = spawn('ffprobe', [
      '-v', 'quiet',
      '-show_entries', 'format=duration',
      '-of', 'default=noprint_wrappers=1:nokey=1',
      videoUrl
    ], { stdio: ['ignore', 'pipe', 'ignore'] });

    let stdout = '';
    proc.stdout.on('data', chunk => { stdout += chunk.toString(); });

    const timeout = setTimeout(() => {
      proc.kill('SIGKILL');
      reject(new Error('ffprobe duration timed out'));
    }, 30000);

    proc.on('close', code => {
      clearTimeout(timeout);
      const duration = parseFloat(stdout.trim());
      if (isNaN(duration) || duration <= 0) {
        reject(new Error(`ffprobe returned invalid duration: ${stdout.trim()}`));
      } else {
        resolve(duration);
      }
    });

    proc.on('error', err => {
      clearTimeout(timeout);
      reject(err);
    });
  });
}

// ── Speech onset detection ───────────────────────────────────────────

function detectSpeechOnsets(videoUrl) {
  return new Promise((resolve, reject) => {
    const proc = spawn('ffmpeg', [
      '-nostdin',
      '-t', String(ANALYSIS_DURATION),
      '-i', videoUrl,
      '-vn',
      '-af', 'silencedetect=noise=-30dB:d=0.5',
      '-f', 'null',
      '/dev/null'
    ], { stdio: ['ignore', 'ignore', 'pipe'] });

    let stderr = '';
    proc.stderr.on('data', chunk => { stderr += chunk.toString(); });

    const timeout = setTimeout(() => {
      proc.kill('SIGKILL');
      reject(new Error('ffmpeg silencedetect timed out'));
    }, FFMPEG_TIMEOUT);

    proc.on('close', () => {
      clearTimeout(timeout);
      const onsets = [];
      const endRegex = /silence_end:\s*([\d.]+)/g;
      let m;
      while ((m = endRegex.exec(stderr)) !== null) {
        onsets.push(parseFloat(m[1]));
      }
      logger.info(`[SubSync] Detected ${onsets.length} speech onsets in first ${ANALYSIS_DURATION}s`);
      resolve(onsets);
    });

    proc.on('error', err => {
      clearTimeout(timeout);
      reject(err);
    });
  });
}

// ── SRT parsing / formatting ──────────────────────────────────────────

function parseSrtTime(str) {
  const match = str.match(/(\d{2}):(\d{2}):(\d{2})[,.](\d{3})/);
  if (!match) return null;
  return parseInt(match[1]) * 3600000
       + parseInt(match[2]) * 60000
       + parseInt(match[3]) * 1000
       + parseInt(match[4]);
}

function formatSrtTime(ms) {
  if (ms < 0) ms = 0;
  const h = Math.floor(ms / 3600000); ms %= 3600000;
  const m = Math.floor(ms / 60000);   ms %= 60000;
  const s = Math.floor(ms / 1000);
  const r = ms % 1000;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')},${String(r).padStart(3, '0')}`;
}

function parseSrtEntries(srtContent) {
  const entries = [];
  const blocks = srtContent.trim().split(/\n\s*\n/);
  for (const block of blocks) {
    const lines = block.trim().split('\n');
    const tsLine = lines.find(l => l.includes(' --> '));
    if (!tsLine) continue;
    const [startStr, endStr] = tsLine.split(' --> ');
    const startMs = parseSrtTime(startStr.trim());
    const endMs = parseSrtTime(endStr.trim());
    if (startMs !== null && endMs !== null) {
      entries.push({ startMs, endMs });
    }
  }
  return entries;
}

// ── Onset matching (offset only) ────────────────────────────────────

function detectOffset(speechOnsets, entries, scale) {
  const maxLag = Math.floor(MAX_OFFSET_SEC / RESOLUTION_SEC);
  const tolBins = Math.floor(ONSET_TOLERANCE / RESOLUTION_SEC);

  const onsetBins = new Set();
  for (const t of speechOnsets) {
    const bin = Math.round(t / RESOLUTION_SEC);
    for (let d = -tolBins; d <= tolBins; d++) {
      onsetBins.add(bin + d);
    }
  }

  const subBins = entries.map(e => Math.round((e.startMs * scale / 1000) / RESOLUTION_SEC));

  let bestLag = 0;
  let bestCount = -1;
  const counts = [];

  for (let lag = -maxLag; lag <= maxLag; lag++) {
    let count = 0;
    for (const sb of subBins) {
      if (onsetBins.has(sb + lag)) count++;
    }
    counts.push(count);
    if (count > bestCount) {
      bestCount = count;
      bestLag = lag;
    }
  }

  const n = counts.length;
  let sum = 0, sum2 = 0;
  for (const c of counts) { sum += c; sum2 += c * c; }
  const mean = sum / n;
  const stddev = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
  const zScore = stddev > 0 ? (bestCount - mean) / stddev : 0;

  return {
    offsetSec: bestLag * RESOLUTION_SEC,
    matches: bestCount,
    total: entries.length,
    mean: mean.toFixed(1),
    zScore
  };
}

// ── SRT adjustment ───────────────────────────────────────────────────

function adjustSrtContent(srtContent, scale, offsetMs) {
  return srtContent.replace(
    /(\d{2}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}[,.]\d{3})/g,
    (_match, start, end) => {
      const newStart = formatSrtTime(Math.round(parseSrtTime(start) * scale) + offsetMs);
      const newEnd   = formatSrtTime(Math.round(parseSrtTime(end) * scale) + offsetMs);
      return `${newStart} --> ${newEnd}`;
    }
  );
}

// ── Main entry point ──────────────────────────────────────────────────

async function syncSubtitle(videoUrl, srtPath) {
  if (!videoUrl || !srtPath) {
    return { synced: false, reason: 'missing params' };
  }
  if (videoUrl.startsWith('/') || videoUrl.includes('localhost')) {
    return { synced: false, reason: 'local URL' };
  }

  await acquireSyncSlot();
  try {
    // 1. Parse SRT
    const srtContent = fs.readFileSync(srtPath, 'utf-8');
    const entries = parseSrtEntries(srtContent);
    if (entries.length < MIN_SUBTITLE_ENTRIES) {
      logger.info(`[SubSync] Only ${entries.length} subtitle entries, skipping`);
      return { synced: false, reason: `too few entries (${entries.length})` };
    }

    const lastSubEndSec = entries[entries.length - 1].endMs / 1000;

    // 2. Get video duration from ffprobe
    logger.info(`[SubSync] Getting video duration...`);
    const videoDuration = await getVideoDuration(videoUrl);
    logger.info(`[SubSync] Video duration: ${videoDuration.toFixed(1)}s, last subtitle: ${lastSubEndSec.toFixed(1)}s`);

    // 3. FPS detection: compare ratio to known fps pairs
    const ratio = lastSubEndSec / videoDuration;
    let scale = 1.0;
    let scaleLabel = 'none';

    logger.info(`[SubSync] Duration ratio: ${ratio.toFixed(5)} (sub/video)`);

    for (const correction of FPS_CORRECTIONS) {
      if (Math.abs(ratio - correction.ratio) < RATIO_TOLERANCE) {
        scale = correction.scale;
        scaleLabel = correction.label;
        logger.info(`[SubSync] FPS mismatch detected: ${scaleLabel} (ratio ${ratio.toFixed(5)} ≈ ${correction.ratio.toFixed(5)}, scale=${scale.toFixed(5)})`);
        break;
      }
    }

    if (scaleLabel === 'none') {
      logger.info(`[SubSync] No FPS mismatch detected (ratio ${ratio.toFixed(5)} doesn't match known fps pairs)`);
    }

    // 4. Offset detection via speech onset matching
    let offsetMs = 0;
    let offsetDetected = false;
    try {
      logger.info(`[SubSync] Analyzing audio for offset detection (first ${ANALYSIS_DURATION}s)...`);
      const speechOnsets = await detectSpeechOnsets(videoUrl);

      if (speechOnsets.length >= 5) {
        const usableEntries = entries.filter(e => e.startMs / 1000 < ANALYSIS_DURATION + MAX_OFFSET_SEC);

        if (usableEntries.length >= MIN_SUBTITLE_ENTRIES) {
          const result = detectOffset(speechOnsets, usableEntries, scale);
          logger.info(`[SubSync] Offset detection: ${result.offsetSec > 0 ? '+' : ''}${result.offsetSec.toFixed(1)}s, matches=${result.matches}/${result.total} (mean=${result.mean}), z=${result.zScore.toFixed(1)}`);

          if (result.zScore >= MIN_Z_SCORE && Math.abs(result.offsetSec) >= MIN_OFFSET_SEC) {
            offsetMs = Math.round(result.offsetSec * 1000);
            offsetDetected = true;
          }
        }
      }
    } catch (err) {
      logger.warn(`[SubSync] Offset detection failed: ${err.message} (continuing with fps correction only)`);
    }

    // 5. Decide what to apply
    const needsScale = Math.abs(scale - 1.0) > MIN_SCALE_DIFF;

    if (!needsScale && !offsetDetected) {
      logger.info(`[SubSync] No adjustment needed`);
      return { synced: false, reason: 'no adjustment needed' };
    }

    // 6. Apply
    const adjusted = adjustSrtContent(srtContent, scale, offsetMs);
    fs.writeFileSync(srtPath, adjusted, 'utf-8');

    const parts = [];
    if (needsScale) parts.push(`scale=${scale.toFixed(5)} (${scaleLabel})`);
    if (offsetDetected) parts.push(`offset=${offsetMs > 0 ? '+' : ''}${(offsetMs / 1000).toFixed(2)}s`);
    logger.info(`[SubSync] Adjusted subtitle: ${parts.join(', ')} — saved to ${srtPath}`);

    return { synced: true, offsetMs, scale };
  } catch (err) {
    logger.warn(`[SubSync] Failed: ${err.message}`);
    return { synced: false, reason: err.message };
  } finally {
    releaseSyncSlot();
  }
}

module.exports = { syncSubtitle };
