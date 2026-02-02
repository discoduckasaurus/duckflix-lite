/**
 * Real-Debrid Client Module
 *
 * API client for Real-Debrid service.
 * Handles torrent caching, unrestricting, and streaming.
 */

const axios = require('axios');

const RD_API_BASE = 'https://api.real-debrid.com/rest/1.0';

// Video file extensions
const VIDEO_EXTENSIONS = ['.mp4', '.mkv', '.avi', '.mov', '.webm', '.wmv', '.m4v', '.ts'];

/**
 * Check Real-Debrid instant availability for multiple hashes
 *
 * @param {string[]} hashes - Array of torrent info hashes
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<Set<string>>} Set of instantly available hashes (lowercase)
 */
const checkInstantAvailability = async (hashes, apiKey) => {
  if (!apiKey || hashes.length === 0) return new Set();

  try {
    // RD accepts multiple hashes in one request (up to 200, using 100 for safety)
    const hashString = hashes.slice(0, 100).join('/');
    const response = await axios.get(
      `${RD_API_BASE}/torrents/instantAvailability/${hashString}`,
      { headers: { 'Authorization': `Bearer ${apiKey}` } }
    );

    const cachedHashes = new Set();
    for (const [hash, data] of Object.entries(response.data)) {
      // If the hash has any cached files available, it's instant
      if (data && data.rd && data.rd.length > 0) {
        cachedHashes.add(hash.toLowerCase());
      }
    }

    console.log(`⚡ RD Cache: ${cachedHashes.size}/${hashes.length} torrents instantly available`);
    return cachedHashes;
  } catch (err) {
    console.warn('⚠️  RD instant availability check failed:', err.message);
    return new Set();
  }
};

/**
 * Add magnet link to Real-Debrid
 *
 * @param {string} magnetLink - Magnet link
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<string>} Torrent ID
 */
const addMagnet = async (magnetLink, apiKey) => {
  const response = await axios.post(
    `${RD_API_BASE}/torrents/addMagnet`,
    `magnet=${encodeURIComponent(magnetLink)}`,
    {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      }
    }
  );
  return response.data.id;
};

/**
 * Add torrent file to Real-Debrid
 *
 * @param {Buffer|string} torrentFileOrUrl - Torrent file buffer or URL
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<string>} Torrent ID
 */
const addTorrent = async (torrentFileOrUrl, apiKey) => {
  let torrentBuffer;

  if (typeof torrentFileOrUrl === 'string' && torrentFileOrUrl.startsWith('http')) {
    // Fetch torrent file from URL
    console.log('📥 Fetching torrent file from:', torrentFileOrUrl);
    const torrentResponse = await axios.get(torrentFileOrUrl, { responseType: 'arraybuffer' });
    torrentBuffer = Buffer.from(torrentResponse.data);
  } else {
    torrentBuffer = torrentFileOrUrl;
  }

  // Upload torrent file to Real-Debrid
  const FormData = require('form-data');
  const form = new FormData();
  form.append('file', torrentBuffer, { filename: 'file.torrent', contentType: 'application/x-bittorrent' });

  const response = await axios.put(
    `${RD_API_BASE}/torrents/addTorrent`,
    form,
    {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        ...form.getHeaders()
      }
    }
  );

  console.log('✅ Torrent file uploaded to RD, ID:', response.data.id);
  return response.data.id;
};

/**
 * Get torrent info from Real-Debrid
 *
 * @param {string} torrentId - Torrent ID
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<Object>} Torrent info
 */
const getTorrentInfo = async (torrentId, apiKey) => {
  const response = await axios.get(
    `${RD_API_BASE}/torrents/info/${torrentId}`,
    { headers: { 'Authorization': `Bearer ${apiKey}` } }
  );
  return response.data;
};

/**
 * Select files in torrent
 *
 * @param {string} torrentId - Torrent ID
 * @param {string|number[]} fileIds - 'all' or array of file IDs
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<void>}
 */
const selectFiles = async (torrentId, fileIds, apiKey) => {
  const filesParam = fileIds === 'all' ? 'all' : fileIds.join(',');
  await axios.post(
    `${RD_API_BASE}/torrents/selectFiles/${torrentId}`,
    `files=${filesParam}`,
    {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      }
    }
  );
};

/**
 * Delete torrent from Real-Debrid
 *
 * @param {string} torrentId - Torrent ID
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<void>}
 */
const deleteTorrent = async (torrentId, apiKey) => {
  await axios.delete(
    `${RD_API_BASE}/torrents/delete/${torrentId}`,
    { headers: { 'Authorization': `Bearer ${apiKey}` } }
  );
};

/**
 * Unrestrict a link to get download URL
 *
 * @param {string} link - Link to unrestrict
 * @param {string} apiKey - Real-Debrid API key
 * @returns {Promise<{download: string, filename: string}>} Download info
 */
const unrestrictLink = async (link, apiKey) => {
  const response = await axios.post(
    `${RD_API_BASE}/unrestrict/link`,
    `link=${encodeURIComponent(link)}`,
    {
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      }
    }
  );

  return {
    download: response.data.download,
    filename: response.data.filename
  };
};

/**
 * Find best video file in torrent files list
 *
 * @param {Array} files - Array of file objects from torrent info
 * @param {number} [season] - Season number (for TV shows)
 * @param {number} [episode] - Episode number (for TV shows)
 * @returns {Object|null} Selected file object with originalIndex
 */
const findBestVideoFile = (files, season = null, episode = null) => {
  // Filter for video files only
  const videoFiles = files
    .map((f, i) => ({ ...f, originalIndex: i }))
    .filter(f => {
      const fileName = (f.path || f.name || '').toLowerCase();
      return VIDEO_EXTENSIONS.some(ext => fileName.endsWith(ext));
    });

  if (videoFiles.length === 0) {
    return null;
  }

  let selectedFile = null;

  // If season and episode are provided, try to match the specific episode
  if (season !== null && episode !== null) {
    const s = String(season).padStart(2, '0');
    const e = String(episode).padStart(2, '0');

    // Helper to check if file path contains season indicator
    const hasSeasonContext = (filePath) => {
      const lower = filePath.toLowerCase();
      return (
        lower.includes(`s${s}`) ||
        lower.includes(`season ${season}`) ||
        lower.includes(`season${season}`) ||
        lower.includes(`/s${s}/`) ||
        lower.includes(`\\s${s}\\`) ||
        lower.includes(`/season ${season}/`) ||
        lower.includes(`/season${season}/`)
      );
    };

    // Episode patterns - ordered by specificity (most specific first)
    // Patterns with season number built-in (safe for series packs)
    const specificPatterns = [
      new RegExp(`s${s}[\\._]?e${e}(?:[^0-9]|$)`, 'i'),     // S03E05, S03.E05, S03_E05
      new RegExp(`${season}x${e}(?:[^0-9]|$)`, 'i'),         // 3x05
      new RegExp(`season\\s*${season}.*episode\\s*${episode}(?:[^0-9]|$)`, 'i'), // Season 3 Episode 5
      new RegExp(`(?:^|[^0-9])${s}${e}(?:[^0-9]|$)`, 'i'),   // 0305 (with boundaries)
    ];

    // Less specific patterns - only use if file path contains season context
    const loosePatterns = [
      new RegExp(`e${e}(?:[^0-9]|$)`, 'i'),                  // E05
      new RegExp(`[\\s\\._-]${episode}(?:[^0-9]|$)`, 'i'),   // Just episode number
    ];

    console.log(`🎯 Looking for S${s}E${e} in ${videoFiles.length} video files`);

    // First try specific patterns (include season in pattern)
    for (const pattern of specificPatterns) {
      for (const file of videoFiles) {
        const fileName = file.path || file.name || '';
        if (pattern.test(fileName)) {
          console.log(`✅ Episode match found (specific): ${fileName}`);
          selectedFile = file;
          break;
        }
      }
      if (selectedFile) break;
    }

    // If no match, try loose patterns but ONLY if path contains season context
    if (!selectedFile) {
      for (const pattern of loosePatterns) {
        for (const file of videoFiles) {
          const fileName = file.path || file.name || '';
          if (pattern.test(fileName) && hasSeasonContext(fileName)) {
            console.log(`✅ Episode match found (with season context): ${fileName}`);
            selectedFile = file;
            break;
          }
        }
        if (selectedFile) break;
      }
    }

    if (!selectedFile) {
      console.log(`❌ No episode match found for S${s}E${e}. Files in torrent:`);
      videoFiles.forEach(f => console.log(`   - ${f.path || f.name}`));
      return null;
    }
  }

  // For movies or when no season/episode specified, use largest video file
  if (!selectedFile) {
    selectedFile = videoFiles.reduce((max, f) =>
      f.bytes > max.bytes ? f : max
    );
    console.log(`📦 Selected largest file (movie/single file): ${selectedFile.path || selectedFile.name}`);
  }

  return selectedFile;
};

/**
 * Download from Real-Debrid (complete flow: add -> select -> wait -> unrestrict)
 *
 * @param {string} magnetOrTorrent - Magnet link or torrent file URL/buffer
 * @param {string} apiKey - Real-Debrid API key
 * @param {number} [season] - Season number (for TV shows)
 * @param {number} [episode] - Episode number (for TV shows)
 * @param {function} [onProgress] - Optional progress callback (progress: number, message: string)
 * @returns {Promise<{download: string, filename: string}>} Download info
 */
const downloadFromRD = async (magnetOrTorrent, apiKey, season = null, episode = null, onProgress = null) => {
  let torrentId;

  // Step 1: Add magnet or torrent file
  if (onProgress) onProgress(10, 'Adding torrent to Real-Debrid...');

  if (typeof magnetOrTorrent === 'string' && magnetOrTorrent.startsWith('magnet:')) {
    torrentId = await addMagnet(magnetOrTorrent, apiKey);
  } else {
    torrentId = await addTorrent(magnetOrTorrent, apiKey);
  }

  // Step 2: Get torrent info and select files
  if (onProgress) onProgress(15, 'Getting torrent info...');

  const infoResponse = await getTorrentInfo(torrentId, apiKey);
  const files = infoResponse.files;

  const selectedFile = findBestVideoFile(files, season, episode);

  if (!selectedFile) {
    // Delete the torrent from RD since we can't use it
    try {
      await deleteTorrent(torrentId, apiKey);
      console.log(`🗑️  Deleted unusable torrent from RD`);
    } catch (e) {
      console.log(`⚠️  Failed to delete torrent from RD: ${e.message}`);
    }

    const err = new Error(
      season !== null && episode !== null
        ? `Episode S${String(season).padStart(2, '0')}E${String(episode).padStart(2, '0')} not found in torrent`
        : 'No video file found in torrent'
    );
    err.code = 'FILE_NOT_FOUND';
    throw err;
  }

  const fileId = selectedFile.originalIndex + 1;
  await selectFiles(torrentId, [fileId], apiKey);

  // Step 3: Wait for Real-Debrid to process (poll until ready or timeout)
  if (onProgress) onProgress(20, 'Downloading from seeders...');
  console.log('⏳ Waiting for Real-Debrid to process...');

  const maxWaitTime = 120000; // 2 minutes max
  const pollInterval = 3000; // Check every 3 seconds
  const startTime = Date.now();

  let finalInfo;
  while (Date.now() - startTime < maxWaitTime) {
    await new Promise(resolve => setTimeout(resolve, pollInterval));

    finalInfo = await getTorrentInfo(torrentId, apiKey);
    const status = finalInfo.status;
    const progress = finalInfo.progress || 0;

    // Calculate progress (20% base + 70% for download)
    const downloadProgress = 20 + Math.floor(progress * 0.7);
    if (onProgress) onProgress(downloadProgress, `Downloading: ${progress}%`);

    console.log(`   RD status: ${status}, progress: ${progress}%`);

    if (status === 'downloaded') {
      break;
    } else if (status === 'error' || status === 'dead' || status === 'virus') {
      throw new Error(`Real-Debrid torrent failed: ${status}`);
    }
    // Continue polling for: magnet_conversion, waiting_files_selection, queued, downloading
  }

  if (!finalInfo || finalInfo.status !== 'downloaded') {
    throw new Error('Real-Debrid processing timed out');
  }

  // Step 4: Get download link
  if (onProgress) onProgress(90, 'Unrestricting link...');

  if (!finalInfo.links || finalInfo.links.length === 0) {
    throw new Error('Real-Debrid returned no download links');
  }

  const link = finalInfo.links[0];
  const result = await unrestrictLink(link, apiKey);

  if (onProgress) onProgress(100, 'Complete!');

  return result;
};

module.exports = {
  checkInstantAvailability,
  addMagnet,
  addTorrent,
  getTorrentInfo,
  selectFiles,
  deleteTorrent,
  unrestrictLink,
  findBestVideoFile,
  downloadFromRD,
  VIDEO_EXTENSIONS
};
