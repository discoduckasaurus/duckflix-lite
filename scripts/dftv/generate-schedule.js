#!/usr/bin/env node

/**
 * DFTV Schedule Generator
 *
 * Scans downloaded episodes, creates shuffled blocks, and generates
 * a schedule + EPG data for the DFTV pseudo-live channel.
 *
 * Usage:
 *   node scripts/dftv/generate-schedule.js
 *   node scripts/dftv/generate-schedule.js --dry-run
 */

require('dotenv').config();

const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const { execFile } = require('child_process');
const { promisify } = require('util');
const execFileAsync = promisify(execFile);

const logger = require('../../utils/logger');

const DFTV_ROOT = process.env.DFTV_ROOT || '/mnt/nas/DFTV';
const STATE_FILE = path.join(DFTV_ROOT, '.dftv-state.json');
const SCHEDULE_FILE = path.join(DFTV_ROOT, '.dftv-schedule.json');

// Fixed seed for reproducible shuffle — change to regenerate
const SHUFFLE_SEED = 20260212;

// Shows in the rotation
const SHOWS = ['American Dad', 'The Office', 'Parks and Recreation', 'Brooklyn Nine-Nine'];

// ---------- Seeded PRNG (Mulberry32) ----------

function mulberry32(seed) {
  let s = seed | 0;
  return function () {
    s = (s + 0x6D2B79F5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------- Inventory Building ----------

async function getFileDuration(filePath) {
  try {
    // Use -show_streams to get video stream duration specifically,
    // because format-level duration can be corrupted by subtitle streams
    const { stdout } = await execFileAsync('ffprobe', [
      '-v', 'quiet',
      '-print_format', 'json',
      '-show_streams',
      '-show_format',
      filePath,
    ], { timeout: 30000 });

    const data = JSON.parse(stdout);

    // Prefer video stream duration (most reliable — avoids corrupt subtitle durations)
    let durationSec = 0;
    if (data.streams) {
      const videoStream = data.streams.find(s => s.codec_type === 'video');
      if (videoStream) {
        if (videoStream.duration) {
          durationSec = parseFloat(videoStream.duration);
        } else if (videoStream.tags?.DURATION) {
          // MKV stores duration in tags as HH:MM:SS.mmm
          const parts = videoStream.tags.DURATION.split(':');
          if (parts.length === 3) {
            durationSec = parseFloat(parts[0]) * 3600 + parseFloat(parts[1]) * 60 + parseFloat(parts[2]);
          }
        }
      }
    }

    // Fall back to format duration if video stream has none
    if (!durationSec && data.format?.duration) {
      durationSec = parseFloat(data.format.duration);
    }

    // Sanity cap: no TV episode should exceed 2 hours (7200s)
    // Catches corrupt container metadata
    if (durationSec > 7200) {
      logger.warn(`[DFTV] Suspicious duration ${(durationSec / 3600).toFixed(1)}h for ${path.basename(filePath)}, capping at 22 min`);
      durationSec = 22 * 60; // Default to 22 minutes
    }

    return Math.round(durationSec * 1000); // Return milliseconds
  } catch (err) {
    logger.warn(`[DFTV] ffprobe failed for ${path.basename(filePath)}: ${err.message}`);
    return 0;
  }
}

async function scanInventory() {
  logger.info('[DFTV] Scanning episode inventory...');

  // Load state for TMDB metadata
  let state = {};
  try {
    if (fs.existsSync(STATE_FILE)) {
      state = JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
    }
  } catch (e) {
    logger.warn(`[DFTV] Could not load state file: ${e.message}`);
  }

  // Build TMDB ID lookup map
  const tmdbIdByShow = {};
  for (const [tmdbId, showData] of Object.entries(state.shows || {})) {
    tmdbIdByShow[showData.title] = parseInt(tmdbId);
  }

  const inventory = [];

  for (const showName of SHOWS) {
    const showDir = path.join(DFTV_ROOT, showName);
    if (!fs.existsSync(showDir)) {
      logger.warn(`[DFTV] Show directory not found: ${showDir}`);
      continue;
    }

    const seasonDirs = (await fsp.readdir(showDir, { withFileTypes: true }))
      .filter(d => d.isDirectory() && d.name.startsWith('Season'))
      .sort((a, b) => {
        const numA = parseInt(a.name.replace(/\D/g, ''));
        const numB = parseInt(b.name.replace(/\D/g, ''));
        return numA - numB;
      });

    for (const seasonDir of seasonDirs) {
      const seasonNum = parseInt(seasonDir.name.replace(/\D/g, ''));
      const seasonPath = path.join(showDir, seasonDir.name);
      const files = (await fsp.readdir(seasonPath))
        .filter(f => {
          const ext = path.extname(f).toLowerCase();
          return ['.mkv', '.mp4', '.avi', '.ts', '.m2ts'].includes(ext);
        })
        .sort();

      for (const file of files) {
        // Parse episode number(s) from filename
        const epMatch = file.match(/S(\d{2})E(\d{2})(?:-E(\d{2}))?/i);
        if (!epMatch) continue;

        const season = parseInt(epMatch[1]);
        const episodeStart = parseInt(epMatch[2]);
        const episodeEnd = epMatch[3] ? parseInt(epMatch[3]) : episodeStart;
        const episodeCount = episodeEnd - episodeStart + 1;

        // Look up metadata from state
        const tmdbId = tmdbIdByShow[showName];
        const key = `${tmdbId}-S${String(season).padStart(2, '0')}E${String(episodeStart).padStart(2, '0')}`;
        const epState = state.shows?.[tmdbId]?.episodes?.[key] || {};

        inventory.push({
          show: showName,
          tmdbId: tmdbId || 0,
          season,
          episode: episodeStart,
          episodeEnd,
          episodeCount,
          title: epState.title || file.replace(/\.[^.]+$/, ''),
          overview: epState.overview || '',
          stillPath: epState.stillPath || null,
          filePath: path.join(seasonPath, file),
          durationMs: 0, // Will be filled by ffprobe
          isSuperfan: file.toLowerCase().includes('superfan'),
        });
      }
    }
  }

  // Get durations via ffprobe (parallel, max 10 concurrent)
  logger.info(`[DFTV] Probing durations for ${inventory.length} files...`);
  const PROBE_CONCURRENCY = 10;
  for (let i = 0; i < inventory.length; i += PROBE_CONCURRENCY) {
    const batch = inventory.slice(i, i + PROBE_CONCURRENCY);
    const durations = await Promise.all(batch.map(ep => getFileDuration(ep.filePath)));
    for (let j = 0; j < batch.length; j++) {
      batch[j].durationMs = durations[j] || (batch[j].episodeCount * 22 * 60 * 1000); // Fallback: 22 min per ep
    }
    if (i % 50 === 0 && i > 0) {
      logger.info(`[DFTV] Probed ${i}/${inventory.length} files...`);
    }
  }

  // Group by show
  const byShow = {};
  for (const ep of inventory) {
    if (!byShow[ep.show]) byShow[ep.show] = [];
    byShow[ep.show].push(ep);
  }

  // Sort each show's episodes by season then episode
  for (const show of Object.values(byShow)) {
    show.sort((a, b) => a.season - b.season || a.episode - b.episode);
  }

  for (const [show, eps] of Object.entries(byShow)) {
    const totalHours = eps.reduce((sum, e) => sum + e.durationMs, 0) / 3600000;
    logger.info(`[DFTV] ${show}: ${eps.length} files, ${totalHours.toFixed(1)} hours`);
  }

  return byShow;
}

// ---------- Multi-Part Detection ----------

// Detect multi-part episode titles (Part 1, Part 2, (1), (2), etc.)
const MULTI_PART_PATTERNS = [
  /\(Part\s*(\d+)\)/i,
  /,?\s*Part\s*(\d+)/i,
  /\((\d+)\)\s*$/,
];

function getPartNumber(title) {
  for (const pattern of MULTI_PART_PATTERNS) {
    const match = title.match(pattern);
    if (match) return parseInt(match[1]);
  }
  return 0;
}

// ---------- Block Creation ----------

function createBlocks(byShow) {
  logger.info('[DFTV] Creating episode blocks...');
  const allBlocks = [];

  for (const [showName, episodes] of Object.entries(byShow)) {
    let blockId = 0;
    let epIndex = 0;
    let targetSize = 3; // Alternate 3, 4, 3, 4...

    while (epIndex < episodes.length) {
      const block = {
        show: showName,
        tmdbId: episodes[0].tmdbId,
        episodes: [],
        totalEpisodeCount: 0,
        durationMs: 0,
        blockId: `${showName}-${blockId}`,
      };

      let count = 0;
      while (count < targetSize && epIndex < episodes.length) {
        const ep = episodes[epIndex];
        block.episodes.push(ep);
        block.durationMs += ep.durationMs;
        count++;
        block.totalEpisodeCount++;
        epIndex++;

        // If this episode is Part N (N >= 1), pull in subsequent parts
        // so multi-part episodes stay together in the same block
        const partNum = getPartNumber(ep.title);
        if (partNum >= 1) {
          while (epIndex < episodes.length) {
            const nextPart = getPartNumber(episodes[epIndex].title);
            if (nextPart > partNum && nextPart <= partNum + 3 &&
                episodes[epIndex].season === ep.season) {
              block.episodes.push(episodes[epIndex]);
              block.durationMs += episodes[epIndex].durationMs;
              count++;
              block.totalEpisodeCount++;
              epIndex++;
            } else {
              break;
            }
          }
        }
      }

      allBlocks.push(block);
      blockId++;
      targetSize = targetSize === 3 ? 4 : 3; // Alternate
    }
  }

  logger.info(`[DFTV] Created ${allBlocks.length} blocks total`);
  return allBlocks;
}

// ---------- Constrained Shuffle ----------

function constrainedShuffle(blocks, rng) {
  // Step 1: Round-robin interleave by show
  const byShow = {};
  for (const block of blocks) {
    if (!byShow[block.show]) byShow[block.show] = [];
    byShow[block.show].push(block);
  }

  const showQueues = Object.values(byShow);
  const interleaved = [];
  let queueIdx = 0;

  while (showQueues.some(q => q.length > 0)) {
    const queue = showQueues[queueIdx % showQueues.length];
    if (queue.length > 0) {
      interleaved.push(queue.shift());
    }
    queueIdx++;
  }

  // Step 2: Fisher-Yates shuffle with constraint check
  const result = [...interleaved];
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [result[i], result[j]] = [result[j], result[i]];
  }

  // Step 3: Fix constraint violations (no back-to-back same show)
  for (let i = 1; i < result.length; i++) {
    if (result[i].show === result[i - 1].show) {
      // Find nearest valid swap ahead
      let swapped = false;
      for (let j = i + 1; j < result.length; j++) {
        if (result[j].show !== result[i - 1].show &&
            (i + 1 >= result.length || result[j].show !== result[i + 1]?.show)) {
          [result[i], result[j]] = [result[j], result[i]];
          swapped = true;
          break;
        }
      }
      if (!swapped) {
        // Try swapping backwards as last resort
        for (let j = 0; j < i - 1; j++) {
          if (result[j].show !== result[i].show &&
              (j === 0 || result[j - 1]?.show !== result[i].show) &&
              (result[j + 1]?.show !== result[i].show)) {
            [result[i], result[j]] = [result[j], result[i]];
            break;
          }
        }
      }
    }
  }

  // Step 4: Fix wrap-around (last block !== first block)
  if (result.length > 2 && result[result.length - 1].show === result[0].show) {
    for (let j = result.length - 2; j > 0; j--) {
      if (result[j].show !== result[0].show && result[j].show !== result[result.length - 2].show) {
        [result[result.length - 1], result[j]] = [result[j], result[result.length - 1]];
        break;
      }
    }
  }

  return result;
}

// ---------- Schedule Generation ----------

function generateSchedule(orderedBlocks) {
  logger.info('[DFTV] Generating schedule...');

  const schedule = [];
  let offsetMs = 0;
  let schedIndex = 0;

  for (const block of orderedBlocks) {
    for (const ep of block.episodes) {
      schedule.push({
        index: schedIndex++,
        startOffsetMs: offsetMs,
        durationMs: ep.durationMs,
        show: ep.show,
        tmdbId: ep.tmdbId,
        season: ep.season,
        episode: ep.episode,
        episodeEnd: ep.episodeEnd,
        title: ep.title,
        overview: ep.overview,
        stillPath: ep.stillPath,
        filePath: ep.filePath,
        episodeCount: ep.episodeCount,
        isSuperfan: ep.isSuperfan,
        blockId: block.blockId,
      });
      offsetMs += ep.durationMs;
    }
  }

  const cycleDurationMs = offsetMs;

  logger.info(`[DFTV] Schedule: ${schedule.length} entries, cycle duration: ${(cycleDurationMs / 3600000).toFixed(1)} hours`);

  return {
    version: 1,
    generatedAt: new Date().toISOString(),
    cycleDurationMs,
    totalEntries: schedule.length,
    schedule,
  };
}

// ---------- Validation ----------

function validateSchedule(scheduleData) {
  const { schedule } = scheduleData;
  let violations = 0;

  for (let i = 1; i < schedule.length; i++) {
    // Check block boundaries — same blockId means same block, different blockId = potential violation
    if (schedule[i].blockId !== schedule[i - 1].blockId) {
      // This is a block transition — check no back-to-back same show
      if (schedule[i].show === schedule[i - 1].show) {
        violations++;
        logger.warn(`[DFTV] Constraint violation at index ${i}: ${schedule[i].show} back-to-back`);
      }
    }
  }

  // Check wrap-around
  if (schedule.length > 1) {
    const last = schedule[schedule.length - 1];
    const first = schedule[0];
    if (last.blockId !== first.blockId && last.show === first.show) {
      violations++;
      logger.warn(`[DFTV] Wrap-around violation: ${last.show}`);
    }
  }

  if (violations === 0) {
    logger.info('[DFTV] Schedule validation passed: no constraint violations');
  } else {
    logger.warn(`[DFTV] Schedule has ${violations} constraint violations`);
  }

  return violations;
}

// ---------- Main ----------

async function main() {
  const args = process.argv.slice(2);
  const isDryRun = args.includes('--dry-run');

  // 1. Scan inventory
  const byShow = await scanInventory();

  const totalEpisodes = Object.values(byShow).reduce((sum, eps) => sum + eps.length, 0);
  if (totalEpisodes === 0) {
    logger.error('[DFTV] No episodes found! Run setup.js first.');
    process.exit(1);
  }

  // 2. Create blocks
  const blocks = createBlocks(byShow);

  // 3. Shuffle with constraints
  const rng = mulberry32(SHUFFLE_SEED);
  const shuffled = constrainedShuffle(blocks, rng);

  // 4. Generate schedule
  const scheduleData = generateSchedule(shuffled);

  // 5. Validate
  const violations = validateSchedule(scheduleData);

  // 6. Summary
  const showStats = {};
  for (const entry of scheduleData.schedule) {
    if (!showStats[entry.show]) showStats[entry.show] = { count: 0, durationMs: 0 };
    showStats[entry.show].count++;
    showStats[entry.show].durationMs += entry.durationMs;
  }

  console.log('\n' + '='.repeat(60));
  console.log('DFTV Schedule Summary');
  console.log('='.repeat(60));
  console.log(`Total entries: ${scheduleData.totalEntries}`);
  console.log(`Cycle duration: ${(scheduleData.cycleDurationMs / 3600000).toFixed(1)} hours`);
  console.log(`Constraint violations: ${violations}`);
  console.log('\nPer show:');
  for (const [show, stats] of Object.entries(showStats)) {
    console.log(`  ${show}: ${stats.count} episodes, ${(stats.durationMs / 3600000).toFixed(1)} hours`);
  }

  // 7. Print first 20 entries as preview
  console.log('\nFirst 20 entries:');
  for (let i = 0; i < Math.min(20, scheduleData.schedule.length); i++) {
    const e = scheduleData.schedule[i];
    const dur = (e.durationMs / 60000).toFixed(0);
    console.log(`  ${String(i).padStart(3)}: [${e.show.padEnd(25)}] S${String(e.season).padStart(2, '0')}E${String(e.episode).padStart(2, '0')} "${e.title}" (${dur} min)`);
  }
  console.log('='.repeat(60));

  if (isDryRun) {
    logger.info('[DFTV] Dry run — schedule not saved');
    return;
  }

  // 8. Save schedule
  const tmpFile = SCHEDULE_FILE + '.tmp';
  await fsp.writeFile(tmpFile, JSON.stringify(scheduleData, null, 2));
  await fsp.rename(tmpFile, SCHEDULE_FILE);
  logger.info(`[DFTV] Schedule saved to ${SCHEDULE_FILE}`);
}

main().catch(err => {
  logger.error('[DFTV] Fatal error:', err);
  process.exit(1);
});
