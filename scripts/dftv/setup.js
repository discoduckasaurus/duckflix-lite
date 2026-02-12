#!/usr/bin/env node

/**
 * DFTV Setup — Download Orchestrator
 *
 * Downloads all episodes for DFTV pseudo-live channel shows,
 * organizes them on NAS, and combines multi-part episodes.
 *
 * Download priority (consistency first):
 *   1. Series pack — one torrent, entire show, consistent encode
 *   2. Season packs — one torrent per season, consistent within season
 *   3. Individual episodes — Zurg (instant) then Prowlarr (last resort)
 *
 * Usage:
 *   node scripts/dftv/setup.js              # Full run
 *   node scripts/dftv/setup.js --resume     # Resume interrupted run
 *   node scripts/dftv/setup.js --status     # Show progress
 *   node scripts/dftv/setup.js --show "The Office"  # Single show
 */

require('dotenv').config();

const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const axios = require('axios');
const { pipeline } = require('stream/promises');
const { execFile } = require('child_process');
const { promisify } = require('util');
const execFileAsync = promisify(execFile);

const logger = require('../../utils/logger');
const { getSearchSeason } = require('../../services/season-offset');
const { searchZurg } = require('../../services/zurg-search');
const { searchContent } = require('../../services/prowlarr-service');

const TMDB_API_KEY = process.env.TMDB_API_KEY;
const TMDB_BASE = 'https://api.themoviedb.org/3';
const RD_API_BASE = 'https://api.real-debrid.com/rest/1.0';
const RD_API_KEY = process.env.DFTV_RD_API_KEY || process.env.RD_API_KEY;

const DFTV_ROOT = process.env.DFTV_ROOT || '/mnt/nas/DFTV';
const STATE_FILE = path.join(DFTV_ROOT, '.dftv-state.json');

// Concurrency limits
const MAGNET_DELAY_MS = 1000;
const MAX_CONCURRENT_DOWNLOADS = 3;

// Show configuration
const SHOWS = [
  {
    tmdbId: 1433,
    title: 'American Dad',
    dirName: 'American Dad',
    hasSeasonOffset: false,
    preferTags: [],
    // Prowlarr search variants for series packs
    searchAliases: ['American Dad', 'American Dad!'],
  },
  {
    tmdbId: 2316,
    title: 'The Office',
    dirName: 'The Office',
    hasSeasonOffset: false,
    preferTags: ['superfan'],
    searchAliases: ['The Office US', 'The Office'],
  },
  {
    tmdbId: 8592,
    title: 'Parks and Recreation',
    dirName: 'Parks and Recreation',
    hasSeasonOffset: false,
    preferTags: [],
    searchAliases: ['Parks and Recreation', 'Parks and Rec'],
  },
  {
    tmdbId: 48891,
    title: 'Brooklyn Nine-Nine',
    dirName: 'Brooklyn Nine-Nine',
    hasSeasonOffset: false,
    preferTags: [],
    searchAliases: ['Brooklyn Nine-Nine', 'Brooklyn Nine Nine', 'Brooklyn 99'],
  },
];

// Multi-part title patterns
const MULTI_PART_PATTERNS = [
  /\(Part\s*(\d+)\)/i,
  /,?\s*Part\s*(\d+)/i,
  /\((\d+)\)\s*$/,
];

// ---------- State Management ----------

function loadState() {
  try {
    if (fs.existsSync(STATE_FILE)) {
      return JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8'));
    }
  } catch (e) {
    logger.warn(`[DFTV] Failed to load state: ${e.message}`);
  }
  return { shows: {}, version: 1 };
}

// Serialized state writes — PID-unique tmp file prevents clobbering
let saveQueued = false;
let pendingState = null;

function saveState(state) {
  pendingState = state;
  if (saveQueued) return;
  saveQueued = true;
  process.nextTick(() => {
    saveQueued = false;
    const toWrite = pendingState;
    if (!toWrite) return;
    const tmpFile = STATE_FILE + `.tmp.${process.pid}`;
    fs.writeFileSync(tmpFile, JSON.stringify(toWrite, null, 2));
    fs.renameSync(tmpFile, STATE_FILE);
  });
}

function saveStateSync(state) {
  const tmpFile = STATE_FILE + `.tmp.${process.pid}`;
  fs.writeFileSync(tmpFile, JSON.stringify(state, null, 2));
  fs.renameSync(tmpFile, STATE_FILE);
}

function getEpisodeKey(tmdbId, season, episode) {
  return `${tmdbId}-S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')}`;
}

// ---------- TMDB Catalog ----------

async function tmdbGet(endpoint, params = {}) {
  const response = await axios.get(`${TMDB_BASE}${endpoint}`, {
    params: { api_key: TMDB_API_KEY, ...params },
    timeout: 10000,
  });
  return response.data;
}

async function buildCatalog(show) {
  logger.info(`[DFTV] Building catalog for ${show.title} (TMDB ${show.tmdbId})...`);

  const tvData = await tmdbGet(`/tv/${show.tmdbId}`);
  const seasons = tvData.seasons.filter(s => s.season_number > 0);

  const catalog = [];

  for (const season of seasons) {
    const seasonData = await tmdbGet(`/tv/${show.tmdbId}/season/${season.season_number}`);

    for (const ep of seasonData.episodes) {
      catalog.push({
        tmdbId: show.tmdbId,
        show: show.title,
        dirName: show.dirName,
        season: ep.season_number,
        episode: ep.episode_number,
        title: ep.name || `Episode ${ep.episode_number}`,
        overview: ep.overview || '',
        runtime: ep.runtime || 22,
        stillPath: ep.still_path ? `https://image.tmdb.org/t/p/w780${ep.still_path}` : null,
        airDate: ep.air_date,
      });
    }

    await sleep(250);
  }

  detectMultiParts(catalog);

  logger.info(`[DFTV] ${show.title}: ${catalog.length} episodes across ${seasons.length} seasons`);
  return catalog;
}

function detectMultiParts(catalog) {
  for (let i = 0; i < catalog.length; i++) {
    const ep = catalog[i];
    let partNum = null;

    for (const pattern of MULTI_PART_PATTERNS) {
      const match = ep.title.match(pattern);
      if (match) {
        partNum = parseInt(match[1]);
        break;
      }
    }

    if (partNum === 1) {
      const parts = [i];
      for (let j = i + 1; j < catalog.length && j < i + 4; j++) {
        const next = catalog[j];
        if (next.season !== ep.season) break;

        let nextPart = null;
        for (const pattern of MULTI_PART_PATTERNS) {
          const match = next.title.match(pattern);
          if (match) {
            nextPart = parseInt(match[1]);
            break;
          }
        }

        if (nextPart === parts.length + 1) {
          parts.push(j);
        } else {
          break;
        }
      }

      if (parts.length > 1) {
        ep.multiPart = {
          role: 'primary',
          partCount: parts.length,
          partIndices: parts,
          episodes: parts.map(idx => catalog[idx].episode),
        };

        for (let k = 1; k < parts.length; k++) {
          catalog[parts[k]].multiPart = {
            role: 'secondary',
            primaryEpisode: ep.episode,
          };
        }

        logger.info(`[DFTV] Multi-part: ${ep.show} S${ep.season} E${ep.multiPart.episodes.join('-')} "${ep.title}"`);
      }
    }
  }
}

// ---------- File Organization ----------

function sanitizeFilename(name) {
  return name.replace(/[?:*"<>|]/g, '').replace(/\s+/g, ' ').trim();
}

function getEpisodeDir(show, season) {
  return path.join(DFTV_ROOT, show.dirName, `Season ${String(season).padStart(2, '0')}`);
}

function getEpisodeFilename(show, season, episode, title, ext, isSuperfan = false) {
  const s = String(season).padStart(2, '0');
  const e = String(episode).padStart(2, '0');
  const suffix = isSuperfan ? ' (Superfan)' : '';
  return sanitizeFilename(`${show.dirName} - S${s}E${e} - ${title}${suffix}.${ext}`);
}

function getCombinedFilename(show, season, episodes, title, ext) {
  const s = String(season).padStart(2, '0');
  const eRange = `E${String(episodes[0]).padStart(2, '0')}-E${String(episodes[episodes.length - 1]).padStart(2, '0')}`;
  const cleanTitle = title.replace(/\s*\(Part\s*\d+\)/i, '').replace(/,?\s*Part\s*\d+/i, '').trim();
  return sanitizeFilename(`${show.dirName} - S${s}${eRange} - ${cleanTitle}.${ext}`);
}

function parseEpisodeFromFilename(filename) {
  const patterns = [
    /[Ss](\d{1,2})[Ee](\d{1,3})/,
    /(\d{1,2})x(\d{2,3})/,
    /[Ss](\d{1,2})\.?[Ee](\d{1,3})/,
  ];

  for (const pattern of patterns) {
    const match = filename.match(pattern);
    if (match) {
      return { season: parseInt(match[1]), episode: parseInt(match[2]) };
    }
  }
  return null;
}

function isVideoFile(filename) {
  const ext = path.extname(filename).toLowerCase();
  return ['.mkv', '.mp4', '.avi', '.ts', '.m2ts'].includes(ext);
}

async function findExistingFile(show, season, episode) {
  const dir = getEpisodeDir(show, season);
  if (!fs.existsSync(dir)) return null;

  const s = String(season).padStart(2, '0');
  const e = String(episode).padStart(2, '0');
  const pattern = new RegExp(`S${s}E${e}`, 'i');

  try {
    const files = await fsp.readdir(dir);
    for (const file of files) {
      if (pattern.test(file) && isVideoFile(file)) {
        return path.join(dir, file);
      }
    }
  } catch (err) {}
  return null;
}

// ---------- Source Scoring ----------

/**
 * Score a torrent result. Higher = better.
 * Designed so series packs always beat season packs, which always beat individual episodes.
 */
function scoreSource(torrent, show, packType = null) {
  let score = 0;
  const title = (torrent.title || '').toLowerCase();

  // --- Tier 1: Pack type (dominant factor) ---
  if (packType === 'series') score += 100000;
  else if (packType === 'season') score += 50000;

  // --- Tier 2: Preferred tags (e.g. Superfan for The Office) ---
  for (const tag of show.preferTags) {
    if (title.includes(tag)) score += 10000;
  }

  // --- Tier 3: Resolution ---
  if (title.includes('2160p') || title.includes('4k')) score += 2160;
  else if (title.includes('1080p')) score += 1080;
  else if (title.includes('720p')) score += 720;

  // --- Tier 4: Encode quality ---
  if (title.includes('remux') || title.includes('blu-ray') || title.includes('bluray')) score += 400;
  if (title.includes('x265') || title.includes('hevc') || title.includes('h.265')) score += 200;
  if (title.includes('web-dl') || title.includes('webdl')) score += 100;

  // --- Tier 5: Seeders (capped) ---
  score += Math.min((torrent.seeders || 0) * 2, 500);

  return score;
}

// ---------- Prowlarr Search (Pack-Aware) ----------

/**
 * Search Prowlarr for series packs for an entire show.
 * Returns scored + deduped results tagged with packType='series'.
 */
async function searchSeriesPacks(show) {
  logger.info(`[DFTV] Searching for series packs: ${show.title}`);
  const allResults = [];
  const seenHashes = new Set();

  for (const alias of show.searchAliases) {
    for (const suffix of ['Complete', 'Complete Series']) {
      try {
        const results = await searchContent({
          title: `${alias} ${suffix}`,
          type: 'tv',
          season: 1,
          episode: 1,
        });
        for (const r of results) {
          const hash = (r.hash || '').toLowerCase();
          if (hash && seenHashes.has(hash)) continue;
          if (hash) seenHashes.add(hash);

          // Minimal size floor — just exclude tiny/fake results
          const sizeMB = r.size / (1024 * 1024);
          if (sizeMB < 100) continue;

          allResults.push({
            ...r,
            packType: 'series',
            score: scoreSource(r, show, 'series'),
          });
        }
      } catch (err) {
        logger.debug(`[DFTV] Series pack search "${alias} ${suffix}" failed: ${err.message}`);
      }
    }
  }

  allResults.sort((a, b) => b.score - a.score);
  logger.info(`[DFTV] Found ${allResults.length} series pack candidates for ${show.title}`);
  if (allResults.length > 0) {
    const best = allResults[0];
    const sizeGB = (best.size / (1024 * 1024 * 1024)).toFixed(1);
    logger.info(`[DFTV] Best series pack: "${best.title.substring(0, 80)}" (${sizeGB}GB, score: ${best.score}, seeders: ${best.seeders})`);
  }
  return allResults;
}

/**
 * Search Prowlarr for season packs for a specific season.
 * Returns scored + deduped results tagged with packType='season'.
 */
async function searchSeasonPacks(show, season) {
  const searchSeason = show.hasSeasonOffset ? getSearchSeason(show.tmdbId, season) : season;
  const sNum = String(searchSeason).padStart(2, '0');

  logger.info(`[DFTV] Searching for season packs: ${show.title} S${sNum}`);
  const allResults = [];
  const seenHashes = new Set();

  for (const alias of show.searchAliases) {
    // "Title S01" format (most common for season packs)
    for (const query of [`${alias} S${sNum}`, `${alias} Season ${searchSeason}`]) {
      try {
        const results = await searchContent({
          title: query,
          type: 'tv',
          season: searchSeason,
          episode: 1,
        });
        for (const r of results) {
          const hash = (r.hash || '').toLowerCase();
          if (hash && seenHashes.has(hash)) continue;
          if (hash) seenHashes.add(hash);

          const titleLower = (r.title || '').toLowerCase();

          // Must look like a season pack, not an individual episode
          const hasSeasonMarker = titleLower.includes(`s${sNum}`) ||
            titleLower.includes(`season ${searchSeason}`) ||
            titleLower.includes(`season.${searchSeason}`);
          const hasEpisodeMarker = /s\d{1,2}e\d{1,2}/i.test(r.title);

          // Minimal size floor — just exclude tiny/fake results
          const sizeMB = r.size / (1024 * 1024);

          // Accept if it has a season marker but NO specific episode marker
          if (hasSeasonMarker && !hasEpisodeMarker && sizeMB > 100) {
            allResults.push({
              ...r,
              packType: 'season',
              targetSeason: season,
              searchSeason,
              score: scoreSource(r, show, 'season'),
            });
          }
        }
      } catch (err) {
        logger.debug(`[DFTV] Season pack search "${query}" failed: ${err.message}`);
      }
    }
  }

  allResults.sort((a, b) => b.score - a.score);
  logger.info(`[DFTV] Found ${allResults.length} season pack candidates for ${show.title} S${sNum}`);
  return allResults;
}

// ---------- RD Download Pipeline ----------

async function rdRequest(method, endpoint, data = null, retries = 2) {
  const config = {
    method,
    url: `${RD_API_BASE}${endpoint}`,
    headers: { Authorization: `Bearer ${RD_API_KEY}` },
    timeout: 30000,
  };
  if (data) {
    config.data = new URLSearchParams(data).toString();
    config.headers['Content-Type'] = 'application/x-www-form-urlencoded';
  }
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      return await axios(config);
    } catch (err) {
      const status = err.response?.status;
      if (status === 429) {
        const delay = Math.pow(2, attempt + 1) * 1000;
        logger.warn(`[DFTV] RD rate limited, retrying in ${delay}ms`);
        await sleep(delay);
        continue;
      }
      if (attempt < retries && (status >= 500 || !err.response)) {
        await sleep(1000 * (attempt + 1));
        continue;
      }
      throw err;
    }
  }
}

async function addMagnetToRD(magnet) {
  const response = await rdRequest('POST', '/torrents/addMagnet', { magnet });
  return response.data.id;
}

async function waitForFiles(torrentId, maxWaitMs = 60000) {
  const start = Date.now();
  while (Date.now() - start < maxWaitMs) {
    const response = await rdRequest('GET', `/torrents/info/${torrentId}`);
    const info = response.data;

    if (info.files && info.files.length > 0) {
      return info;
    }

    if (info.status === 'magnet_error' || info.status === 'error' || info.status === 'dead') {
      throw new Error(`Torrent error: ${info.status}`);
    }

    await sleep(2000);
  }
  throw new Error('Timeout waiting for torrent files');
}

async function selectVideoFiles(torrentId, info) {
  const videoFiles = info.files.filter(f => isVideoFile(f.path));
  if (videoFiles.length === 0) throw new Error('No video files in torrent');

  const fileIds = videoFiles.map(f => f.id).join(',');
  await rdRequest('POST', `/torrents/selectFiles/${torrentId}`, { files: fileIds });
  return videoFiles;
}

async function waitForDownload(torrentId, maxWaitMs = 1800000) {
  const start = Date.now();
  let lastLog = 0;
  while (Date.now() - start < maxWaitMs) {
    const response = await rdRequest('GET', `/torrents/info/${torrentId}`);
    const info = response.data;

    if (info.status === 'downloaded') {
      return info;
    }

    if (info.status === 'magnet_error' || info.status === 'error' || info.status === 'dead') {
      throw new Error(`Download error: ${info.status}`);
    }

    const progress = info.progress || 0;
    const now = Date.now();
    if (progress > 0 && now - lastLog > 10000) {
      logger.info(`[DFTV] Torrent ${torrentId}: ${progress}% (${info.status})`);
      lastLog = now;
    }

    await sleep(5000);
  }
  throw new Error('Download timeout (30 min)');
}

async function unrestrictAndDownload(rdLink, destPath) {
  const response = await rdRequest('POST', '/unrestrict/link', { link: rdLink });
  const downloadUrl = response.data.download;

  await fsp.mkdir(path.dirname(destPath), { recursive: true });

  const tmpPath = destPath + '.downloading';
  const downloadResponse = await axios.get(downloadUrl, {
    responseType: 'stream',
    timeout: 0,
  });

  const writer = fs.createWriteStream(tmpPath);
  await pipeline(downloadResponse.data, writer);
  await fsp.rename(tmpPath, destPath);

  const stat = await fsp.stat(destPath);
  logger.info(`[DFTV] Downloaded: ${path.basename(destPath)} (${(stat.size / 1024 / 1024).toFixed(1)} MB)`);
}

function createSemaphore(max) {
  let active = 0;
  const queue = [];

  return {
    async acquire() {
      if (active < max) { active++; return; }
      await new Promise(resolve => queue.push(resolve));
      active++;
    },
    release() {
      active--;
      if (queue.length > 0) queue.shift()();
    },
  };
}

// ---------- Pack Download Flow ----------

/**
 * Download a torrent pack (series or season) and organize all files.
 * Returns a Set of episode keys that were successfully downloaded.
 */
async function downloadPack(pack, show, catalog, showState, state) {
  logger.info(`[DFTV] Downloading pack: "${pack.title.substring(0, 80)}" (${pack.packType}, score: ${pack.score})`);

  const torrentId = await addMagnetToRD(pack.magnet);
  await sleep(MAGNET_DELAY_MS);

  const info = await waitForFiles(torrentId);
  const videoFiles = await selectVideoFiles(torrentId, info);

  logger.info(`[DFTV] Pack has ${videoFiles.length} video files, waiting for RD to cache...`);
  const downloadedInfo = await waitForDownload(torrentId);
  const links = downloadedInfo.links || [];

  logger.info(`[DFTV] Pack ready, ${links.length} download links available`);

  // Map torrent files to their download links by index
  // RD returns links in the same order as selected files
  const fileToLink = new Map();
  const selectedVideoFiles = info.files
    .filter(f => isVideoFile(f.path))
    .sort((a, b) => a.id - b.id); // RD orders by file ID

  for (let i = 0; i < Math.min(selectedVideoFiles.length, links.length); i++) {
    fileToLink.set(selectedVideoFiles[i].path, links[i]);
  }

  // Parse each file, match to catalog episodes
  const downloadSem = createSemaphore(MAX_CONCURRENT_DOWNLOADS);
  const downloadTasks = [];
  const handledKeys = new Set();

  for (const [filePath, rdLink] of fileToLink.entries()) {
    const filename = path.basename(filePath);
    const parsed = parseEpisodeFromFilename(filename);
    if (!parsed) {
      logger.debug(`[DFTV] Could not parse episode from: ${filename}`);
      continue;
    }

    // Reverse season offset: file uses TVDB numbering
    // Multiple TMDB seasons can map to same TVDB season (e.g. TMDB S11 and S13 both → TVDB S11)
    // Find the one where the episode actually exists in the catalog
    let tmdbSeason = parsed.season;
    let epData = null;
    if (show.hasSeasonOffset) {
      const allSeasons = [...new Set(catalog.map(e => e.season))].sort((a, b) => a - b);
      for (const s of allSeasons) {
        if (getSearchSeason(show.tmdbId, s) === parsed.season) {
          const match = catalog.find(e => e.season === s && e.episode === parsed.episode);
          if (match) {
            tmdbSeason = s;
            epData = match;
            break;
          }
        }
      }
    } else {
      epData = catalog.find(e => e.season === tmdbSeason && e.episode === parsed.episode);
    }

    if (!epData) {
      logger.debug(`[DFTV] No catalog match for S${tmdbSeason}E${parsed.episode} from "${filename}"`);
      continue;
    }

    const key = getEpisodeKey(show.tmdbId, tmdbSeason, parsed.episode);

    // Skip already downloaded
    if (showState.episodes[key]?.status === 'downloaded') {
      const existing = await findExistingFile(show, tmdbSeason, parsed.episode);
      if (existing) {
        handledKeys.add(key);
        continue;
      }
    }

    const ext = path.extname(filename).substring(1) || 'mkv';
    const isSuperfan = show.preferTags.includes('superfan') && /superfan/i.test(filename);
    const destFilename = getEpisodeFilename(show, tmdbSeason, parsed.episode, epData.title, ext, isSuperfan);
    const destPath = path.join(getEpisodeDir(show, tmdbSeason), destFilename);

    downloadTasks.push({ rdLink, destPath, key, epData, tmdbSeason });
  }

  logger.info(`[DFTV] Pack matched ${downloadTasks.length} episodes to download (${handledKeys.size} already had)`);

  // Download with concurrency limit
  const results = await Promise.allSettled(
    downloadTasks.map(async (task) => {
      await downloadSem.acquire();
      try {
        showState.episodes[task.key] = { status: 'downloading', title: task.epData.title, source: pack.packType };
        saveState(state);

        await unrestrictAndDownload(task.rdLink, task.destPath);

        showState.episodes[task.key] = {
          status: 'downloaded',
          title: task.epData.title,
          filePath: task.destPath,
          runtime: task.epData.runtime,
          overview: task.epData.overview,
          stillPath: task.epData.stillPath,
          source: pack.packType,
        };
        saveState(state);
        handledKeys.add(task.key);
      } finally {
        downloadSem.release();
      }
    })
  );

  const succeeded = results.filter(r => r.status === 'fulfilled').length;
  const failed = results.filter(r => r.status === 'rejected').length;
  logger.info(`[DFTV] Pack result: ${succeeded} downloaded, ${failed} failed, ${handledKeys.size} total handled`);

  return handledKeys;
}

// ---------- Multi-Part Combining ----------

async function combineMultiParts(show, season, episodes, catalog) {
  const dir = getEpisodeDir(show, season);
  const primaryEp = catalog.find(e => e.season === season && e.episode === episodes[0]);
  if (!primaryEp) return;

  const partFiles = [];
  for (const epNum of episodes) {
    const existing = await findExistingFile(show, season, epNum);
    if (!existing) {
      logger.warn(`[DFTV] Cannot combine: missing S${season}E${epNum} for ${show.title}`);
      return;
    }
    partFiles.push(existing);
  }

  const ext = path.extname(partFiles[0]).substring(1);
  const combinedName = getCombinedFilename(show, season, episodes, primaryEp.title, ext);
  const combinedPath = path.join(dir, combinedName);

  if (fs.existsSync(combinedPath)) {
    logger.info(`[DFTV] Combined file already exists: ${combinedName}`);
    return combinedPath;
  }

  const concatFile = path.join(dir, '.concat_list.txt');
  const concatContent = partFiles.map(f => `file '${f.replace(/'/g, "'\\''")}'`).join('\n');
  await fsp.writeFile(concatFile, concatContent);

  logger.info(`[DFTV] Combining ${partFiles.length} parts: ${combinedName}`);
  try {
    await execFileAsync('ffmpeg', [
      '-f', 'concat', '-safe', '0',
      '-i', concatFile,
      '-c', 'copy',
      combinedPath,
    ], { timeout: 300000 });

    const originalsDir = path.join(dir, '.originals');
    await fsp.mkdir(originalsDir, { recursive: true });
    for (const f of partFiles) {
      await fsp.rename(f, path.join(originalsDir, path.basename(f)));
    }

    await fsp.unlink(concatFile).catch(() => {});
    logger.info(`[DFTV] Combined successfully: ${combinedName}`);
    return combinedPath;
  } catch (err) {
    logger.error(`[DFTV] Combine failed: ${err.message}`);
    await fsp.unlink(combinedPath).catch(() => {});
    await fsp.unlink(concatFile).catch(() => {});
    return null;
  }
}

// ---------- Main Orchestration ----------

async function processShow(show, state) {
  logger.info(`\n${'='.repeat(60)}\n[DFTV] Processing: ${show.title}\n${'='.repeat(60)}`);

  // 1. Build catalog from TMDB
  const catalog = await buildCatalog(show);
  if (catalog.length === 0) {
    logger.warn(`[DFTV] No episodes found for ${show.title}`);
    return;
  }

  if (!state.shows[show.tmdbId]) state.shows[show.tmdbId] = { episodes: {} };
  const showState = state.shows[show.tmdbId];
  showState.title = show.title;
  showState.totalEpisodes = catalog.length;
  saveStateSync(state);

  const seasons = [...new Set(catalog.map(e => e.season))].sort((a, b) => a - b);

  // 2. Check which episodes we already have
  const neededKeys = new Set();
  for (const ep of catalog) {
    const key = getEpisodeKey(show.tmdbId, ep.season, ep.episode);
    if (showState.episodes[key]?.status === 'downloaded') {
      const existing = await findExistingFile(show, ep.season, ep.episode);
      if (existing) continue;
      logger.warn(`[DFTV] ${key}: marked downloaded but file missing`);
    }
    neededKeys.add(key);
  }

  if (neededKeys.size === 0) {
    logger.info(`[DFTV] ${show.title}: all ${catalog.length} episodes present!`);
    return;
  }

  logger.info(`[DFTV] ${show.title}: need ${neededKeys.size}/${catalog.length} episodes`);

  // ===== TIER 1: Series Pack =====
  if (neededKeys.size > catalog.length * 0.3) {
    // Only try series packs if we need more than 30% of the show
    const seriesPacks = await searchSeriesPacks(show);

    for (const pack of seriesPacks.slice(0, 3)) {
      // Try top 3 series packs
      try {
        const handled = await downloadPack(pack, show, catalog, showState, state);

        // Remove handled keys from needed
        for (const key of handled) neededKeys.delete(key);

        if (neededKeys.size === 0) {
          logger.info(`[DFTV] ${show.title}: series pack covered everything!`);
          break;
        }

        logger.info(`[DFTV] ${show.title}: ${neededKeys.size} episodes still needed after series pack`);
        break; // One successful pack is enough, fall through to season packs for remainder
      } catch (err) {
        logger.warn(`[DFTV] Series pack failed: ${err.message}`);
      }
    }
  }

  // ===== TIER 2: Season Packs =====
  for (const season of seasons) {
    const seasonEps = catalog.filter(e => e.season === season);
    const seasonNeeded = seasonEps.filter(e => neededKeys.has(getEpisodeKey(show.tmdbId, e.season, e.episode)));

    if (seasonNeeded.length === 0) continue;
    if (seasonNeeded.length < seasonEps.length * 0.3) {
      // Less than 30% of season needed — skip to individual, a pack isn't worth it
      logger.info(`[DFTV] ${show.title} S${season}: only ${seasonNeeded.length}/${seasonEps.length} needed, skipping to individual`);
      continue;
    }

    logger.info(`[DFTV] ${show.title} S${season}: ${seasonNeeded.length}/${seasonEps.length} needed, trying season pack`);

    const seasonPacks = await searchSeasonPacks(show, season);

    for (const pack of seasonPacks.slice(0, 3)) {
      try {
        const handled = await downloadPack(pack, show, catalog, showState, state);
        for (const key of handled) neededKeys.delete(key);

        const remaining = seasonEps.filter(e => neededKeys.has(getEpisodeKey(show.tmdbId, e.season, e.episode)));
        if (remaining.length === 0) {
          logger.info(`[DFTV] ${show.title} S${season}: season pack covered everything!`);
        } else {
          logger.info(`[DFTV] ${show.title} S${season}: ${remaining.length} still needed after season pack`);
        }
        break; // Move on to next season or individual fill
      } catch (err) {
        logger.warn(`[DFTV] Season pack failed for S${season}: ${err.message}`);
      }
    }
  }

  // ===== TIER 3: Individual Episodes (Zurg → Prowlarr) =====
  const remainingEps = catalog.filter(e => neededKeys.has(getEpisodeKey(show.tmdbId, e.season, e.episode)));

  if (remainingEps.length > 0) {
    logger.info(`[DFTV] ${show.title}: ${remainingEps.length} episodes need individual download`);

    for (const ep of remainingEps) {
      const key = getEpisodeKey(show.tmdbId, ep.season, ep.episode);
      const searchSeason = show.hasSeasonOffset ? getSearchSeason(show.tmdbId, ep.season) : ep.season;

      // Check disk again (pack might have placed it)
      const existing = await findExistingFile(show, ep.season, ep.episode);
      if (existing) {
        showState.episodes[key] = {
          status: 'downloaded', title: ep.title, filePath: existing,
          runtime: ep.runtime, overview: ep.overview, stillPath: ep.stillPath, source: 'disk',
        };
        saveState(state);
        neededKeys.delete(key);
        continue;
      }

      // Try Zurg (fast, free)
      try {
        showState.episodes[key] = { status: 'searching', title: ep.title, source: 'zurg' };
        saveState(state);

        const result = await searchZurg({
          title: show.title,
          year: ep.airDate ? ep.airDate.substring(0, 4) : '',
          type: 'episode',
          season: searchSeason,
          episode: ep.episode,
          duration: ep.runtime,
        });

        const zurgMatch = result.match || result.fallback;
        if (zurgMatch?.filePath) {
          const ext = path.extname(zurgMatch.filePath).substring(1) || 'mkv';
          const isSuperfan = show.preferTags.includes('superfan') && /superfan/i.test(zurgMatch.filePath);
          const filename = getEpisodeFilename(show, ep.season, ep.episode, ep.title, ext, isSuperfan);
          const destPath = path.join(getEpisodeDir(show, ep.season), filename);

          showState.episodes[key] = { status: 'downloading', title: ep.title, source: 'zurg' };
          saveState(state);

          await fsp.mkdir(path.dirname(destPath), { recursive: true });
          await fsp.copyFile(zurgMatch.filePath, destPath);

          showState.episodes[key] = {
            status: 'downloaded', title: ep.title, filePath: destPath,
            runtime: ep.runtime, overview: ep.overview, stillPath: ep.stillPath, source: 'zurg',
          };
          saveState(state);
          neededKeys.delete(key);
          logger.info(`[DFTV] Copied from Zurg: ${path.basename(destPath)}`);
          continue;
        }
      } catch (err) {
        logger.debug(`[DFTV] Zurg failed for ${key}: ${err.message}`);
      }

      // Prowlarr individual search + RD download
      try {
        showState.episodes[key] = { status: 'searching', title: ep.title, source: 'prowlarr' };
        saveState(state);

        const results = await searchContent({
          title: show.title,
          type: 'tv',
          season: searchSeason,
          episode: ep.episode,
          tmdbId: show.tmdbId,
        });

        if (results.length === 0) {
          logger.warn(`[DFTV] No sources found for ${key}`);
          showState.episodes[key] = { status: 'not_found', title: ep.title };
          saveState(state);
          continue;
        }

        // Score and pick best individual result
        const scored = results.map(r => ({ ...r, score: scoreSource(r, show) }));
        scored.sort((a, b) => b.score - a.score);
        const best = scored[0];

        showState.episodes[key] = { status: 'downloading', title: ep.title, source: 'prowlarr' };
        saveState(state);

        const torrentId = await addMagnetToRD(best.magnet);
        await sleep(MAGNET_DELAY_MS);

        const info = await waitForFiles(torrentId);
        await selectVideoFiles(torrentId, info);
        const downloaded = await waitForDownload(torrentId);

        if (downloaded.links && downloaded.links.length > 0) {
          // Find the link that matches this episode
          let bestLink = downloaded.links[0];
          for (const link of downloaded.links) {
            const parsed = parseEpisodeFromFilename(link);
            if (parsed && parsed.episode === ep.episode) {
              bestLink = link;
              break;
            }
          }

          const ext = path.extname(bestLink).substring(1) || 'mkv';
          const isSuperfan = show.preferTags.includes('superfan') && /superfan/i.test(best.title);
          const filename = getEpisodeFilename(show, ep.season, ep.episode, ep.title, ext, isSuperfan);
          const destPath = path.join(getEpisodeDir(show, ep.season), filename);

          await unrestrictAndDownload(bestLink, destPath);

          showState.episodes[key] = {
            status: 'downloaded', title: ep.title, filePath: destPath,
            runtime: ep.runtime, overview: ep.overview, stillPath: ep.stillPath, source: 'prowlarr',
          };
          saveState(state);
          neededKeys.delete(key);
        }
      } catch (err) {
        logger.error(`[DFTV] Individual download failed for ${key}: ${err.message}`);
        showState.episodes[key] = { status: 'error', title: ep.title, error: err.message };
        saveState(state);
      }

      await sleep(MAGNET_DELAY_MS);
    }
  }

  // Multi-part episodes (Part 1/2) are kept as separate files.
  // The schedule generator handles keeping them together in blocks.

  logger.info(`[DFTV] ${show.title}: ${neededKeys.size} episodes still missing after all tiers`);
}

// ---------- Status / CLI ----------

function printStatus(state) {
  console.log('\n' + '='.repeat(60));
  console.log('DFTV Download Status');
  console.log('='.repeat(60));

  for (const show of SHOWS) {
    const showState = state.shows[show.tmdbId];
    if (!showState) {
      console.log(`\n${show.title}: Not started`);
      continue;
    }

    const episodes = Object.entries(showState.episodes || {});
    const downloaded = episodes.filter(([, e]) => e.status === 'downloaded').length;
    const errors = episodes.filter(([, e]) => e.status === 'error').length;
    const notFound = episodes.filter(([, e]) => e.status === 'not_found').length;
    const total = showState.totalEpisodes || episodes.length;

    // Count sources
    const sources = {};
    for (const [, e] of episodes) {
      if (e.status === 'downloaded' && e.source) {
        sources[e.source] = (sources[e.source] || 0) + 1;
      }
    }
    const sourceStr = Object.entries(sources).map(([k, v]) => `${k}:${v}`).join(', ');

    console.log(`\n${show.title}: ${downloaded}/${total} downloaded${sourceStr ? ` (${sourceStr})` : ''}`);
    if (errors > 0) console.log(`  Errors: ${errors}`);
    if (notFound > 0) console.log(`  Not found: ${notFound}`);

    for (const [key, ep] of episodes) {
      if (ep.status === 'error') {
        console.log(`  ${key}: ${ep.error}`);
      }
    }
  }
  console.log('\n' + '='.repeat(60));
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function main() {
  const args = process.argv.slice(2);
  const isResume = args.includes('--resume');
  const isStatus = args.includes('--status');
  const showIndex = args.indexOf('--show');
  const targetShow = showIndex !== -1 ? args[showIndex + 1] : null;

  await fsp.mkdir(DFTV_ROOT, { recursive: true });

  let state;
  if (isResume || isStatus) {
    state = loadState();
  } else if (targetShow) {
    state = loadState();
  } else {
    state = { shows: {}, version: 1 };
  }

  if (isStatus) {
    printStatus(state);
    return;
  }

  if (!TMDB_API_KEY) {
    console.error('ERROR: TMDB_API_KEY not set');
    process.exit(1);
  }
  if (!RD_API_KEY) {
    console.error('ERROR: DFTV_RD_API_KEY or RD_API_KEY not set');
    process.exit(1);
  }

  logger.info('[DFTV] Starting download orchestrator...');
  logger.info(`[DFTV] Root: ${DFTV_ROOT}`);
  logger.info(`[DFTV] Priority: series packs → season packs → individual (Zurg → Prowlarr)`);

  const showsToProcess = targetShow
    ? SHOWS.filter(s => s.title.toLowerCase().includes(targetShow.toLowerCase()))
    : SHOWS;

  if (showsToProcess.length === 0) {
    console.error(`No show matching "${targetShow}"`);
    process.exit(1);
  }

  for (const show of showsToProcess) {
    try {
      await processShow(show, state);
    } catch (err) {
      logger.error(`[DFTV] Fatal error processing ${show.title}: ${err.message}`);
      logger.error(err.stack);
    }
  }

  printStatus(state);
  logger.info('[DFTV] Setup complete!');
}

main().catch(err => {
  logger.error('[DFTV] Fatal error:', err);
  process.exit(1);
});
