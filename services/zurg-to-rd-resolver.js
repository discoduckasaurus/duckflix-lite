/**
 * ZURG-TO-RD RESOLVER
 * ====================
 *
 * PURPOSE:
 * Converts a Zurg FUSE mount file path into a direct Real-Debrid (RD) download URL.
 * Direct RD URLs are faster (CDN-served, good range request support) vs reading through
 * the Zurg FUSE mount (which goes through Zurg's HTTP proxy and can be slower for seeking).
 *
 * WHAT IS ZURG:
 * Zurg is a SHARED P2P NETWORK that indexes torrents cached on Real-Debrid's CDN.
 * It presents these as a virtual filesystem via FUSE mount + WebDAV + HTTP.
 *
 *   - Zurg is NOT just "the admin's personal RD library."
 *   - Zurg shows ALL cached content from the community network — torrents cached by ANY
 *     RD user. The admin contributes to this pool but the pool is much larger.
 *   - A file appearing in Zurg means the torrent IS cached on RD's servers and can be
 *     instantly added to any RD account (no seeder download needed).
 *   - Zurg connects via ONE RD API key (the admin's) for its mount, but the content it
 *     shows is not limited to that account's active torrent list.
 *
 * WHY WE RESOLVE:
 * When Zurg finds a file, we need a direct RD download URL. To get that, we:
 *   1. Find (or re-add) the torrent in the user's RD account
 *   2. Select the right file within the torrent
 *   3. Unrestrict the link to get a direct CDN URL
 *
 * THE TORRENT LOOKUP PROBLEM:
 * Zurg gives us a file path like: /mnt/zurg/__all__/Pack.Name/file.mkv
 * We extract the pack name ("Pack.Name") and need to find the matching torrent in RD.
 *
 * The RD /torrents list API:
 *   - Returns `filename` (the individual file name, NOT the torrent/pack name)
 *   - The `original_filename` (pack name) is ONLY in /torrents/info/:id (detail endpoint)
 *   - Default limit is 100 items; accounts can have 500+ torrents
 *   - Each selected file in a multi-file torrent appears as a SEPARATE list entry
 *     (all sharing the same hash)
 *
 * So matching by pack name against the list is unreliable. Instead, we:
 *   1. Fetch ALL torrents (limit=2500) to avoid pagination issues
 *   2. Deduplicate by hash (one torrent can have many files in the list)
 *   3. For unique hashes, check /torrents/info/:id to get original_filename (pack name)
 *   4. Match against the Zurg pack name
 *
 * If the torrent isn't in the user's account at all, we re-add it via addMagnet using
 * the hash. Since Zurg only shows cached content, this is instant (no download needed).
 *
 * FALLBACK:
 * If resolution fails entirely (API errors, etc.), the caller (vod.js) should fall back
 * to streaming directly from the Zurg FUSE mount path. The file is already confirmed
 * readable by zurg-search.js's isZurgFileReadable() check.
 */

const axios = require('axios');
const { db } = require('../db/init');
const { getUserRdApiKey } = require('./user-service');
const logger = require('../utils/logger');

const RD_API = 'https://api.real-debrid.com/rest/1.0';

/**
 * Get the RD API key that Zurg is configured with (admin's key).
 * Zurg uses ONE key for its mount, but shows content from the entire Zurg P2P network.
 */
function getZurgOwnerKey() {
  const admin = db.prepare('SELECT id FROM users WHERE is_admin = 1 LIMIT 1').get();
  if (!admin) return null;
  return getUserRdApiKey(admin.id);
}

/**
 * Parse Zurg FUSE mount path into torrent pack name and file name.
 *
 * Path format: /mnt/zurg/__all__/<packName>/<fileName>
 * - packName = the torrent name (e.g., "Show.S01.1080p.WEB-DL.x264-GROUP")
 * - fileName = the specific video file within the torrent
 */
function parseZurgPath(zurgFilePath) {
  const pathParts = zurgFilePath.split('/').filter(p => p);
  const allIndex = pathParts.indexOf('__all__');

  if (allIndex === -1 || pathParts.length < allIndex + 3) {
    return null;
  }

  return {
    packName: pathParts[allIndex + 1],
    fileName: pathParts[pathParts.length - 1]
  };
}

/**
 * Find a torrent in an RD account by pack name.
 *
 * IMPORTANT: The RD /torrents list endpoint returns `filename` (individual file name),
 * NOT `original_filename` (pack/torrent name). Pack name is only in /torrents/info/:id.
 * So we fetch all torrents, deduplicate by hash, then check each torrent's info.
 *
 * @returns {{ id, hash, original_filename }} or null
 */
async function findTorrentByPackName(rdApiKey, packName) {
  // Fetch ALL torrents (default limit is 100, accounts can have 500+)
  const torrentsRes = await axios.get(`${RD_API}/torrents`, {
    headers: { 'Authorization': `Bearer ${rdApiKey}` },
    params: { limit: 2500 }
  });

  const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');
  const normPack = normalize(packName);

  // Quick check: see if any torrent's `filename` field happens to match
  // (works for single-file torrents where filename == pack name)
  const quickMatch = torrentsRes.data.find(t => {
    if (!t.filename) return false;
    const normFilename = normalize(t.filename);
    return normFilename === normPack || normFilename.includes(normPack) || normPack.includes(normFilename);
  });
  if (quickMatch) {
    return quickMatch;
  }

  // For multi-file torrents (season packs), the `filename` field shows individual files,
  // not the pack name. We need to check torrent info for each unique hash.
  // Deduplicate by hash since each selected file in a pack appears as a separate entry.
  const uniqueHashes = new Map();
  for (const t of torrentsRes.data) {
    if (t.hash && !uniqueHashes.has(t.hash)) {
      uniqueHashes.set(t.hash, t);
    }
  }

  // Check torrent info for each unique hash to find the pack name (original_filename).
  // Limit to 10 concurrent checks to avoid rate limiting.
  const hashEntries = Array.from(uniqueHashes.values());

  // Optimization: only check torrents with filenames that share words with the pack name
  const packWords = normPack.split(/[^a-z0-9]+/).filter(w => w.length > 2);
  const candidates = hashEntries.filter(t => {
    const normFilename = normalize(t.filename || '');
    return packWords.some(w => normFilename.includes(w));
  });

  for (const candidate of candidates.slice(0, 20)) {
    try {
      const infoRes = await axios.get(`${RD_API}/torrents/info/${candidate.id}`, {
        headers: { 'Authorization': `Bearer ${rdApiKey}` }
      });
      const origName = infoRes.data.original_filename;
      if (origName) {
        const normOrig = normalize(origName);
        if (normOrig === normPack || normOrig.includes(normPack) || normPack.includes(normOrig)) {
          logger.info(`[Zurg→RD] Found torrent by pack name lookup: ${candidate.id} (${origName.substring(0, 60)})`);
          return { ...candidate, original_filename: origName };
        }
      }
    } catch (err) {
      // Skip individual lookup failures (torrent may have been deleted mid-check)
    }
  }

  return null;
}

/**
 * Get the unrestricted (direct CDN) download link for a specific file in a torrent.
 *
 * RD torrent info returns:
 *   - files[]: all files in the torrent, each with { id, path, bytes, selected }
 *   - links[]: download links, one per SELECTED file (in order of file ID)
 *
 * We find the target file, map it to its link, then unrestrict for a direct URL.
 */
async function getFileLink(rdApiKey, torrentId, fileName) {
  const torrentInfoRes = await axios.get(
    `${RD_API}/torrents/info/${torrentId}`,
    { headers: { 'Authorization': `Bearer ${rdApiKey}` } }
  );

  const torrentInfo = torrentInfoRes.data;
  const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');

  // Find the specific file in the torrent
  const matchingFile = torrentInfo.files.find(f => {
    if (f.path === fileName) return true;
    const fName = f.path.split('/').pop();
    if (fName === fileName) return true;
    return normalize(fName) === normalize(fileName) || normalize(f.path) === normalize(fileName);
  });

  if (!matchingFile || matchingFile.selected !== 1) return null;

  // Map selected file to its download link
  // links[] is ordered by file ID among selected files only
  const selectedFiles = torrentInfo.files.filter(f => f.selected === 1);
  const selectedFileIndex = selectedFiles.findIndex(f => f.id === matchingFile.id);

  if (selectedFileIndex === -1 || selectedFileIndex >= torrentInfo.links.length) return null;

  const downloadLink = torrentInfo.links[selectedFileIndex];

  // Unrestrict to get direct CDN download URL
  const unrestrictRes = await axios.post(
    `${RD_API}/unrestrict/link`,
    `link=${encodeURIComponent(downloadLink)}`,
    {
      headers: {
        'Authorization': `Bearer ${rdApiKey}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      }
    }
  );

  return unrestrictRes.data.download;
}

/**
 * Re-add a torrent to the user's RD account using its hash.
 *
 * Since Zurg only shows content that is CACHED on RD's CDN (from the Zurg P2P network),
 * adding via hash is instant — no actual torrent download from seeders is needed.
 * RD recognizes the hash is already cached and makes it available immediately.
 *
 * @param {string} rdApiKey - User's RD API key
 * @param {string} hash - Torrent info hash
 * @param {string} fileName - Target file name (to select the right file)
 * @returns {{ torrentId: string, link: string }|null}
 */
async function addCachedTorrent(rdApiKey, hash, fileName) {
  // Add magnet hash to user's RD — instant for cached content
  let addRes;
  try {
    addRes = await axios.post(
      `${RD_API}/torrents/addMagnet`,
      `magnet=magnet:?xt=urn:btih:${hash}`,
      {
        headers: {
          'Authorization': `Bearer ${rdApiKey}`,
          'Content-Type': 'application/x-www-form-urlencoded'
        }
      }
    );
  } catch (addErr) {
    if (addErr.response?.status === 451) {
      logger.warn(`[Zurg→RD] DMCA'd on RD (451): hash ${hash.substring(0, 12)}`);
    } else {
      logger.warn(`[Zurg→RD] addMagnet failed (${addErr.response?.status || addErr.message}): hash ${hash.substring(0, 12)}`);
    }
    return null;
  }

  const torrentId = addRes.data.id;
  logger.info(`[Zurg→RD] Added cached torrent ${hash.substring(0, 12)}... as ${torrentId}`);

  // Wait briefly for RD to process the cached torrent
  await new Promise(resolve => setTimeout(resolve, 1000));

  // Get torrent info to find and select the right file
  const infoRes = await axios.get(`${RD_API}/torrents/info/${torrentId}`, {
    headers: { 'Authorization': `Bearer ${rdApiKey}` }
  });

  const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');
  const normTarget = normalize(fileName);

  // Find the target file
  const targetFile = infoRes.data.files.find(f => {
    const fName = f.path.split('/').pop();
    return normalize(fName) === normTarget || normalize(f.path) === normTarget;
  });

  if (!targetFile) {
    logger.warn(`[Zurg→RD] File "${fileName}" not found in torrent ${torrentId}`);
    return null;
  }

  // Select all video files (same behavior as downloadFromRD)
  const videoExts = ['.mkv', '.mp4', '.avi', '.mov', '.webm'];
  const videoFiles = infoRes.data.files.filter(f =>
    videoExts.some(ext => f.path.toLowerCase().endsWith(ext))
  );
  const fileIds = videoFiles.length > 0
    ? videoFiles.map(f => f.id)
    : [targetFile.id];

  await axios.post(
    `${RD_API}/torrents/selectFiles/${torrentId}`,
    `files=${fileIds.join(',')}`,
    {
      headers: {
        'Authorization': `Bearer ${rdApiKey}`,
        'Content-Type': 'application/x-www-form-urlencoded'
      }
    }
  );

  // Brief wait for file selection to process
  await new Promise(resolve => setTimeout(resolve, 500));

  // Get the unrestricted link
  const link = await getFileLink(rdApiKey, torrentId, fileName);
  return link ? { torrentId, link } : null;
}

/**
 * Resolve a Zurg FUSE mount file path to a direct Real-Debrid download URL.
 *
 * RESOLUTION STRATEGY:
 * 1. Parse the Zurg path to get pack name + file name
 * 2. Search user's RD account for a matching torrent (by pack name)
 *    → If found and has the file, unrestrict and return direct URL
 * 3. If not found in user's account, search by filename across all user's torrents
 *    to find the hash, then use it to get a link
 * 4. If torrent not in user's account at all, find the hash from any available source
 *    (Zurg owner's account) and re-add it — instant since content is cached on RD
 * 5. If all else fails, return null (caller should fall back to Zurg FUSE mount streaming)
 *
 * @param {string} zurgFilePath - Full path (e.g., /mnt/zurg/__all__/Pack.Name/file.mkv)
 * @param {string} rdApiKey - User's Real-Debrid API key
 * @returns {Promise<string|null>} Direct download URL, or null (fall back to FUSE mount)
 */
async function resolveZurgToRdLink(zurgFilePath, rdApiKey) {
  try {
    const parsed = parseZurgPath(zurgFilePath);
    if (!parsed) {
      logger.error('[Zurg→RD] Invalid Zurg path format:', zurgFilePath);
      return null;
    }

    const { packName, fileName } = parsed;
    logger.info(`[Zurg→RD] Resolving: pack="${packName}", file="${fileName}"`);

    // ── Step 1: Search user's OWN RD account for the torrent ──
    const matchingTorrent = await findTorrentByPackName(rdApiKey, packName);

    if (matchingTorrent) {
      logger.info(`[Zurg→RD] Found in user's RD: torrent ${matchingTorrent.id}`);
      const link = await getFileLink(rdApiKey, matchingTorrent.id, fileName);
      if (link) {
        logger.info(`[Zurg→RD] Resolved to direct link: ${link.substring(0, 60)}...`);
        return link;
      }
      // Torrent found but couldn't get file link — try re-adding by hash
      if (matchingTorrent.hash) {
        logger.info(`[Zurg→RD] Torrent found but file link failed, re-adding by hash...`);
        const result = await addCachedTorrent(rdApiKey, matchingTorrent.hash, fileName);
        if (result) {
          logger.info(`[Zurg→RD] Re-added and resolved: ${result.link.substring(0, 60)}...`);
          return result.link;
        }
      }
    }

    // ── Step 2: Search user's RD by filename (covers cases where pack name doesn't match) ──
    try {
      const allTorrents = await axios.get(`${RD_API}/torrents`, {
        headers: { 'Authorization': `Bearer ${rdApiKey}` },
        params: { limit: 2500 }
      });

      const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');
      const normFile = normalize(fileName);

      const fileMatch = allTorrents.data.find(t => {
        if (!t.filename) return false;
        return normalize(t.filename) === normFile;
      });

      if (fileMatch) {
        logger.info(`[Zurg→RD] Found by filename match: torrent ${fileMatch.id} (hash ${fileMatch.hash?.substring(0, 12)})`);
        const link = await getFileLink(rdApiKey, fileMatch.id, fileName);
        if (link) {
          logger.info(`[Zurg→RD] Resolved via filename: ${link.substring(0, 60)}...`);
          return link;
        }
      }
    } catch (err) {
      logger.warn(`[Zurg→RD] Filename search failed: ${err.message}`);
    }

    // ── Step 3: Torrent not in user's account — find hash and re-add ──
    // Zurg shows cached content from the P2P network. If the torrent isn't in the
    // user's account, we need to find the hash and re-add it (instant since it's cached).
    //
    // Try the Zurg owner's (admin's) account to find the hash.
    // This works even when user IS the admin — we search by filename instead of pack name.
    const zurgOwnerKey = getZurgOwnerKey();
    if (zurgOwnerKey) {
      try {
        const ownerTorrents = await axios.get(`${RD_API}/torrents`, {
          headers: { 'Authorization': `Bearer ${zurgOwnerKey}` },
          params: { limit: 2500 }
        });

        const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');
        const normFile = normalize(fileName);

        // Find by exact filename match
        const ownerMatch = ownerTorrents.data.find(t => {
          if (!t.filename) return false;
          return normalize(t.filename) === normFile;
        });

        if (ownerMatch && ownerMatch.hash) {
          logger.info(`[Zurg→RD] Found hash via Zurg owner: ${ownerMatch.hash.substring(0, 12)}...`);

          // If user is NOT the admin, re-add to their account
          if (zurgOwnerKey !== rdApiKey) {
            const result = await addCachedTorrent(rdApiKey, ownerMatch.hash, fileName);
            if (result) {
              logger.info(`[Zurg→RD] Cloned to user's RD: ${result.link.substring(0, 60)}...`);
              return result.link;
            }
          } else {
            // User IS the admin — the torrent is in their account but pack name search missed it.
            // Try getting the file link directly.
            const link = await getFileLink(rdApiKey, ownerMatch.id, fileName);
            if (link) {
              logger.info(`[Zurg→RD] Resolved from owner's own torrent: ${link.substring(0, 60)}...`);
              return link;
            }
            // If file link failed, try re-adding by hash (re-adds are idempotent on RD)
            const result = await addCachedTorrent(rdApiKey, ownerMatch.hash, fileName);
            if (result) {
              logger.info(`[Zurg→RD] Re-added to own account: ${result.link.substring(0, 60)}...`);
              return result.link;
            }
          }
        }
      } catch (err) {
        logger.warn(`[Zurg→RD] Owner lookup failed: ${err.message}`);
      }
    }

    // ── Resolution failed ──
    // Caller (vod.js) should fall back to Zurg FUSE mount streaming.
    // The file IS accessible via the mount (already passed readability check in zurg-search.js).
    logger.warn(`[Zurg→RD] Could not resolve to direct RD link: ${packName}/${fileName}`);
    return null;
  } catch (error) {
    logger.error('[Zurg→RD] Resolution error:', {
      message: error.message,
      status: error.response?.status
    });
    return null;
  }
}

module.exports = { resolveZurgToRdLink };
