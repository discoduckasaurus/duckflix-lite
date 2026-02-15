/**
 * DFTV Pseudo-Live TV Service
 *
 * Serves a 24/7 pseudo-live channel by calculating wall-clock position
 * within a looping schedule and generating HLS segments via ffmpeg.
 *
 * All clients see the same content at the same time (wall-clock sync).
 */

const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const { spawn } = require('child_process');
const logger = require('../utils/logger');
const { db } = require('../db/init');

const DFTV_ROOT = process.env.DFTV_ROOT || '/mnt/nas/DFTV';
const SCHEDULE_FILE = path.join(DFTV_ROOT, '.dftv-schedule.json');
const HLS_DIR = '/tmp/dftv-hls';
const HLS_MANIFEST = path.join(HLS_DIR, 'live.m3u8');

const IDLE_TIMEOUT_MS = 5 * 60 * 1000; // Stop ffmpeg after 5 min of no clients
const SEGMENT_DURATION = 6; // HLS segment length in seconds
const HLS_LIST_SIZE = 10; // Number of segments in playlist

let schedule = null;
let ffmpegProcess = null;
let ffmpegGeneration = 0; // Tracks process lifecycle to prevent stale exit handlers
let currentEpisodeIndex = -1;
let lastClientActivity = 0;
let idleTimer = null;
let transitionTimer = null;

// ---------- Schedule Loading ----------

function loadSchedule() {
  try {
    if (!fs.existsSync(SCHEDULE_FILE)) {
      logger.warn('[DFTV] Schedule file not found. Run generate-schedule.js first.');
      return null;
    }
    const data = JSON.parse(fs.readFileSync(SCHEDULE_FILE, 'utf-8'));
    if (!data.schedule || data.schedule.length === 0) {
      logger.warn('[DFTV] Schedule is empty');
      return null;
    }
    logger.info(`[DFTV] Loaded schedule: ${data.totalEntries} entries, ${(data.cycleDurationMs / 3600000).toFixed(1)}h cycle`);
    return data;
  } catch (err) {
    logger.error(`[DFTV] Failed to load schedule: ${err.message}`);
    return null;
  }
}

function reloadSchedule() {
  const newSchedule = loadSchedule();
  if (newSchedule) {
    schedule = newSchedule;
    return true;
  }
  return false;
}

// ---------- Wall-Clock Position ----------

function getCurrentPlayback() {
  if (!schedule) {
    schedule = loadSchedule();
    if (!schedule) return null;
  }

  const positionInCycle = Date.now() % schedule.cycleDurationMs;

  // Binary search for current episode
  const entries = schedule.schedule;
  let lo = 0;
  let hi = entries.length - 1;

  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (entries[mid].startOffsetMs <= positionInCycle) {
      lo = mid;
    } else {
      hi = mid - 1;
    }
  }

  const entry = entries[lo];
  const seekOffsetMs = positionInCycle - entry.startOffsetMs;
  const remainingMs = entry.durationMs - seekOffsetMs;

  return {
    entry,
    index: lo,
    seekOffsetMs,
    seekOffsetSec: seekOffsetMs / 1000,
    remainingMs,
  };
}

// ---------- ffmpeg HLS Generation ----------

function ensureHlsDir() {
  if (!fs.existsSync(HLS_DIR)) {
    fs.mkdirSync(HLS_DIR, { recursive: true });
  }
}

function cleanHlsDir() {
  try {
    if (fs.existsSync(HLS_DIR)) {
      const files = fs.readdirSync(HLS_DIR);
      for (const file of files) {
        fs.unlinkSync(path.join(HLS_DIR, file));
      }
    }
  } catch (err) {
    logger.debug(`[DFTV] HLS cleanup error: ${err.message}`);
  }
}

/**
 * Soft cleanup: read current manifest, keep referenced segments, delete only unreferenced ones.
 * Falls back to full cleanHlsDir() if no manifest exists or on error.
 */
function softCleanHlsDir() {
  try {
    if (!fs.existsSync(HLS_MANIFEST)) {
      cleanHlsDir();
      return;
    }

    const manifest = fs.readFileSync(HLS_MANIFEST, 'utf-8');
    const referencedFiles = new Set(['live.m3u8']);

    for (const line of manifest.split('\n')) {
      const trimmed = line.trim();
      if (trimmed && !trimmed.startsWith('#')) {
        // Extract just the filename (may be full path or relative)
        referencedFiles.add(path.basename(trimmed));
      }
    }

    const files = fs.readdirSync(HLS_DIR);
    let removed = 0;
    for (const file of files) {
      if (!referencedFiles.has(file)) {
        try {
          fs.unlinkSync(path.join(HLS_DIR, file));
          removed++;
        } catch (e) { /* segment may already be gone */ }
      }
    }

    if (removed > 0) {
      logger.debug(`[DFTV] Soft cleanup: removed ${removed} unreferenced files, kept ${referencedFiles.size}`);
    }
  } catch (err) {
    logger.debug(`[DFTV] Soft cleanup failed, falling back to full clean: ${err.message}`);
    cleanHlsDir();
  }
}

function stopFfmpeg() {
  if (transitionTimer) {
    clearTimeout(transitionTimer);
    transitionTimer = null;
  }

  if (ffmpegProcess) {
    logger.info('[DFTV] Stopping ffmpeg');
    const proc = ffmpegProcess;
    ffmpegProcess = null;
    ffmpegGeneration++; // Invalidate any pending exit handlers

    proc.kill('SIGTERM');
    // Force kill after 5s if process hasn't exited
    const killTimer = setTimeout(() => {
      try { proc.kill('SIGKILL'); } catch (e) {}
    }, 5000);
    proc.once('exit', () => clearTimeout(killTimer));
  }

  currentEpisodeIndex = -1;
}

function startFfmpeg(filePath, seekOffsetSec, durationMs) {
  ensureHlsDir();

  // Validate remaining duration
  const remainingMs = durationMs - (seekOffsetSec * 1000);
  if (remainingMs <= 0) {
    logger.warn(`[DFTV] Skipping expired entry (remaining: ${remainingMs}ms), transitioning`);
    transitionToNext();
    return;
  }

  // Soft-clean HLS dir: remove unreferenced segments, keep ones clients may still be fetching
  softCleanHlsDir();

  const generation = ++ffmpegGeneration;

  const args = [];

  // Input seeking (before -i for fast keyframe seek)
  if (seekOffsetSec > 1) {
    args.push('-ss', String(seekOffsetSec));
  }

  args.push(
    '-re',                     // Read input at native frame rate (pseudo-live pacing)
    '-i', filePath,
    '-c:v', 'copy',          // No video re-encode
    '-c:a', 'aac',           // Transcode audio for HLS compatibility
    '-b:a', '192k',
    '-ac', '2',              // Stereo
    '-sn',                     // Strip subtitle streams (prevent .vtt file pollution)
    '-f', 'hls',
    '-hls_time', String(SEGMENT_DURATION),
    '-hls_list_size', String(HLS_LIST_SIZE),
    '-hls_flags', 'delete_segments+omit_endlist',
    '-hls_segment_filename', path.join(HLS_DIR, 'seg_%05d.ts'),
    HLS_MANIFEST,
  );

  logger.info(`[DFTV] Starting ffmpeg: ${path.basename(filePath)} (seek: ${seekOffsetSec.toFixed(1)}s)`);

  const proc = spawn('ffmpeg', args, {
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  proc.stderr.on('data', (data) => {
    const line = data.toString().trim();
    if (line.includes('Error') || line.includes('error')) {
      logger.warn(`[DFTV] ffmpeg: ${line.substring(0, 200)}`);
    }
  });

  proc.on('exit', (code, signal) => {
    // Only handle if this is still the current generation (not replaced by a newer process)
    if (generation === ffmpegGeneration) {
      logger.info(`[DFTV] ffmpeg exited (code: ${code}, signal: ${signal})`);
      ffmpegProcess = null;

      // If not killed intentionally, transition to next episode
      if (signal !== 'SIGTERM' && signal !== 'SIGKILL') {
        transitionToNext();
      }
    }
  });

  proc.on('error', (err) => {
    logger.error(`[DFTV] ffmpeg spawn error: ${err.message}`);
    if (generation === ffmpegGeneration) {
      ffmpegProcess = null;
    }
  });

  ffmpegProcess = proc;

  // Backup transition timer in case ffmpeg hangs
  if (transitionTimer) clearTimeout(transitionTimer);
  const safetyMs = remainingMs + 5000;
  if (safetyMs > 0 && safetyMs < 24 * 3600 * 1000) { // Sanity check: max 24h
    transitionTimer = setTimeout(() => {
      if (generation === ffmpegGeneration && ffmpegProcess === proc) {
        logger.warn('[DFTV] Safety timer: killing hung ffmpeg');
        stopFfmpeg();
        transitionToNext();
      }
    }, safetyMs);
  }
}

function transitionToNext() {
  const playback = getCurrentPlayback();
  if (!playback) {
    logger.warn('[DFTV] No playback data for transition');
    return;
  }

  // Only start if there are active clients
  if (Date.now() - lastClientActivity > IDLE_TIMEOUT_MS) {
    logger.info('[DFTV] No active clients, not starting next episode');
    cleanHlsDir();
    return;
  }

  // If we're still on the same episode (ffmpeg finished before wall-clock advanced),
  // wait until the remaining time elapses then try again
  if (playback.index === currentEpisodeIndex && playback.remainingMs > 2000) {
    logger.info(`[DFTV] Still on same episode (${playback.remainingMs}ms remaining), waiting`);
    if (transitionTimer) clearTimeout(transitionTimer);
    transitionTimer = setTimeout(() => {
      transitionToNext();
    }, playback.remainingMs + 500);
    return;
  }

  currentEpisodeIndex = playback.index;
  startFfmpeg(playback.entry.filePath, playback.seekOffsetSec, playback.entry.durationMs);
}

// ---------- Public API ----------

function ensureRunning() {
  const playback = getCurrentPlayback();
  if (!playback) return false;

  lastClientActivity = Date.now();

  // Reset idle timer
  if (idleTimer) clearTimeout(idleTimer);
  idleTimer = setTimeout(() => {
    if (Date.now() - lastClientActivity >= IDLE_TIMEOUT_MS) {
      logger.info('[DFTV] Idle timeout — stopping ffmpeg');
      stopFfmpeg();
      cleanHlsDir();
    }
  }, IDLE_TIMEOUT_MS + 1000);

  // Check if we need to start or switch episodes
  if (!ffmpegProcess || currentEpisodeIndex !== playback.index) {
    if (ffmpegProcess) {
      logger.info(`[DFTV] Episode changed (${currentEpisodeIndex} → ${playback.index}), restarting ffmpeg`);
      stopFfmpeg();
    }
    currentEpisodeIndex = playback.index;
    startFfmpeg(playback.entry.filePath, playback.seekOffsetSec, playback.entry.durationMs);
  }

  return true;
}

async function getManifest() {
  if (!ensureRunning()) {
    return null;
  }

  // If manifest already exists, return immediately
  if (fs.existsSync(HLS_MANIFEST)) {
    return fs.readFileSync(HLS_MANIFEST, 'utf-8');
  }

  // Wait for ffmpeg to generate manifest using fs.watch (non-blocking)
  try {
    await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        watcher.close();
        reject(new Error('timeout'));
      }, 8000);

      const watcher = fs.watch(HLS_DIR, (eventType, filename) => {
        if (filename === 'live.m3u8') {
          clearTimeout(timeout);
          watcher.close();
          resolve();
        }
      });

      watcher.on('error', (err) => {
        clearTimeout(timeout);
        reject(err);
      });

      // Re-check after setting up watcher (race condition: file may have appeared between existsSync and watch)
      if (fs.existsSync(HLS_MANIFEST)) {
        clearTimeout(timeout);
        watcher.close();
        resolve();
      }
    });
  } catch (err) {
    if (err.message !== 'timeout') {
      logger.debug(`[DFTV] fs.watch error: ${err.message}`);
    }
  }

  if (!fs.existsSync(HLS_MANIFEST)) {
    logger.warn('[DFTV] Manifest not ready after wait');
    return null;
  }

  return fs.readFileSync(HLS_MANIFEST, 'utf-8');
}

function getSegmentPath(segmentName) {
  lastClientActivity = Date.now();

  // Sanitize: only allow segment filenames generated by ffmpeg (seg_XXXXX.ts, live.m3u8)
  if (!/^[a-zA-Z0-9_.-]+$/.test(segmentName)) {
    logger.warn(`[DFTV] Invalid segment name rejected: ${segmentName}`);
    return null;
  }

  const segPath = path.join(HLS_DIR, segmentName);

  // Belt-and-suspenders: verify resolved path is within HLS_DIR
  if (!path.resolve(segPath).startsWith(path.resolve(HLS_DIR) + path.sep)) {
    logger.warn(`[DFTV] Path traversal blocked: ${segmentName}`);
    return null;
  }

  if (fs.existsSync(segPath)) {
    return segPath;
  }
  return null;
}

function getCurrentProgram() {
  const playback = getCurrentPlayback();
  if (!playback) return null;

  return {
    title: `${playback.entry.show} - ${playback.entry.title}`,
    description: playback.entry.overview,
    show: playback.entry.show,
    season: playback.entry.season,
    episode: playback.entry.episode,
    remainingMs: playback.remainingMs,
  };
}

// ---------- EPG Generation ----------

function generateEPG(days = 7) {
  if (!schedule) {
    schedule = loadSchedule();
    if (!schedule) return [];
  }

  const now = Date.now();
  const startTime = now - (now % schedule.cycleDurationMs) - schedule.cycleDurationMs; // Start from previous cycle
  const endTime = now + (days * 24 * 3600 * 1000);
  const programs = [];

  let currentTime = startTime;

  while (currentTime < endTime) {
    for (const entry of schedule.schedule) {
      const programStart = currentTime + entry.startOffsetMs;
      const programEnd = programStart + entry.durationMs;

      // Only include programs within our time window
      if (programEnd < now - (24 * 3600 * 1000)) {
        continue; // Skip programs more than 1 day in the past
      }
      if (programStart > endTime) {
        break;
      }

      const epLabel = entry.episodeEnd > entry.episode
        ? `S${String(entry.season).padStart(2, '0')}E${String(entry.episode).padStart(2, '0')}-E${String(entry.episodeEnd).padStart(2, '0')}`
        : `S${String(entry.season).padStart(2, '0')}E${String(entry.episode).padStart(2, '0')}`;

      programs.push({
        start: programStart,
        stop: programEnd,
        title: `${entry.show} - ${entry.title}`,
        desc: entry.overview || `${entry.show} ${epLabel}`,
        category: entry.show,
        icon: entry.stillPath || null,
      });
    }

    currentTime += schedule.cycleDurationMs;
  }

  return programs;
}

function refreshEPG() {
  try {
    const programs = generateEPG(7);
    if (programs.length === 0) {
      logger.warn('[DFTV] No EPG data generated');
      return false;
    }

    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('dftv-mixed', JSON.stringify(programs));

    logger.info(`[DFTV] EPG refreshed: ${programs.length} programs`);
    return true;
  } catch (err) {
    logger.error(`[DFTV] EPG refresh failed: ${err.message}`);
    return false;
  }
}

// ---------- Module Exports ----------

module.exports = {
  loadSchedule,
  reloadSchedule,
  getCurrentPlayback,
  getManifest,
  getSegmentPath,
  getCurrentProgram,
  generateEPG,
  refreshEPG,
  ensureRunning,
  stopFfmpeg,
  get isRunning() { return !!ffmpegProcess; },
};
