/**
 * Season Offset Map
 *
 * Handles shows where TMDB and TVDB disagree on season numbering.
 * Torrent indexers (EZTV, 1337x, etc.) generally use TVDB numbering,
 * so we need to convert TMDB season numbers to TVDB for search queries
 * and file matching.
 *
 * This is a rare edge case — most shows have consistent numbering.
 */

const logger = require('../utils/logger');

// Map of TMDB ID → offset rules
// Each entry: { name, getSearchSeason(tmdbSeason) → tvdbSeason }
//
// NOTE: American Dad (TMDB 1433) was previously here with a -2 offset for S13+.
// This was WRONG — TVDB and TMDB now agree on numbering (both have the 3-ep S11
// and 15-ep S12). The offset was based on outdated info and was actively causing
// wrong episodes to be returned for S11+ requests. Removed 2026-02-12.
const SEASON_OFFSETS = {};

/**
 * Convert a TMDB season number to the TVDB season number used by torrent indexers.
 * Returns the original season if no offset is defined for this show.
 *
 * @param {number} tmdbId - TMDB show ID
 * @param {number} tmdbSeason - Season number from TMDB
 * @returns {number} Season number to use for search/file matching
 */
function getSearchSeason(tmdbId, tmdbSeason) {
  if (!tmdbId || !tmdbSeason) return tmdbSeason;

  const mapping = SEASON_OFFSETS[tmdbId];
  if (!mapping) return tmdbSeason;

  const searchSeason = mapping.getSearchSeason(tmdbSeason);
  if (searchSeason !== tmdbSeason) {
    logger.info(`[Season Offset] ${mapping.name}: TMDB S${tmdbSeason} → search S${searchSeason}`);
  }
  return searchSeason;
}

module.exports = { getSearchSeason };
