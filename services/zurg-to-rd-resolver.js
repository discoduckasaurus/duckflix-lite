const axios = require('axios');
const logger = require('../utils/logger');

/**
 * Resolve a Zurg file path to a direct Real-Debrid download link
 *
 * @param {string} zurgFilePath - Full Zurg file path (e.g., /mnt/zurg/__all__/Pack.Name/file.mkv)
 * @param {string} rdApiKey - User's Real-Debrid API key
 * @returns {Promise<string|null>} Direct download URL or null if not found
 */
async function resolveZurgToRdLink(zurgFilePath, rdApiKey) {
  try {
    // Extract pack name and file name from Zurg path
    // Path format: /mnt/zurg/__all__/PackName/filename.ext
    const pathParts = zurgFilePath.split('/').filter(p => p);
    const allIndex = pathParts.indexOf('__all__');

    if (allIndex === -1 || pathParts.length < allIndex + 3) {
      logger.error('Invalid Zurg path format:', zurgFilePath);
      return null;
    }

    const packName = pathParts[allIndex + 1];
    const fileName = pathParts[pathParts.length - 1];

    logger.info(`Resolving Zurg file to RD: pack=${packName}, file=${fileName}`);

    // Get all torrents from RD
    const torrentsRes = await axios.get('https://api.real-debrid.com/rest/1.0/torrents', {
      headers: { 'Authorization': `Bearer ${rdApiKey}` }
    });

    // Find torrent matching the pack name
    const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');
    const normPack = normalize(packName);

    const matchingTorrent = torrentsRes.data.find(t => {
      // Check if original_filename matches pack name exactly (most reliable)
      if (t.original_filename === packName) return true;

      // Only use original_filename for matching, never filename (which is just the selected file)
      if (!t.original_filename) return false;

      const normOriginal = normalize(t.original_filename);

      // Only allow exact match or if original contains the full pack name
      // Don't allow reverse (pack contains original) to avoid false positives
      return normOriginal === normPack || normOriginal.includes(normPack);
    });

    if (!matchingTorrent) {
      logger.warn(`No RD torrent found matching pack: ${packName}`);
      return null;
    }

    logger.info(`Found matching RD torrent: ${matchingTorrent.id} - ${matchingTorrent.original_filename || matchingTorrent.filename}`);

    // Get torrent info to find the specific file
    const torrentInfoRes = await axios.get(
      `https://api.real-debrid.com/rest/1.0/torrents/info/${matchingTorrent.id}`,
      { headers: { 'Authorization': `Bearer ${rdApiKey}` } }
    );

    const torrentInfo = torrentInfoRes.data;

    // Find the specific file in the torrent
    const matchingFile = torrentInfo.files.find(f => {
      // Try exact match on full path
      if (f.path === fileName) return true;

      // Try exact match on just filename
      const fName = f.path.split('/').pop();
      if (fName === fileName) return true;

      // Try fuzzy match (normalize both and compare)
      const normalize = (str) => str.toLowerCase().replace(/[^a-z0-9]/g, '');
      return normalize(fName) === normalize(fileName) || normalize(f.path) === normalize(fileName);
    });

    if (!matchingFile) {
      logger.warn(`File not found in RD torrent: ${fileName}`);
      return null;
    }

    // Check if file is selected
    if (matchingFile.selected !== 1) {
      logger.warn(`File not selected in RD torrent: ${fileName}`);
      return null;
    }

    // Get selected files and match with links array
    // Links array only contains links for SELECTED files, in order
    const selectedFiles = torrentInfo.files.filter(f => f.selected === 1);
    const selectedFileIndex = selectedFiles.findIndex(f => f.id === matchingFile.id);

    if (selectedFileIndex === -1 || selectedFileIndex >= torrentInfo.links.length) {
      logger.warn(`No download link found for file: ${fileName}`);
      return null;
    }

    const downloadLink = torrentInfo.links[selectedFileIndex];

    // Unrestrict the link to get direct download URL
    const unrestrictRes = await axios.post(
      'https://api.real-debrid.com/rest/1.0/unrestrict/link',
      `link=${encodeURIComponent(downloadLink)}`,
      {
        headers: {
          'Authorization': `Bearer ${rdApiKey}`,
          'Content-Type': 'application/x-www-form-urlencoded'
        }
      }
    );

    const directLink = unrestrictRes.data.download;
    logger.info(`Resolved Zurg file to RD direct link: ${directLink.substring(0, 60)}...`);

    return directLink;
  } catch (error) {
    logger.error('Error resolving Zurg to RD link:', {
      message: error.message,
      status: error.response?.status
    });
    return null;
  }
}

module.exports = { resolveZurgToRdLink };
