const path = require('path');
const fs = require('fs');
const cron = require('node-cron');
const axios = require('axios');
const { parseEPGData } = require('@duckflix/epg-parser');
const { parseM3U } = require('@duckflix/m3u-parser');
const { db } = require('../db/init');
const logger = require('../utils/logger');

let epgSyncInProgress = false;
let m3uSyncInProgress = false;

const M3U_FILE = path.join(__dirname, '..', 'db', 'channels.m3u');

// NTV configuration (cdn-live.tv streams via ntvstream.cx)
const NTV_ENABLED = process.env.NTV_ENABLED === 'true';
const { fetchNTVChannels } = require('./ntv-service');

// IPTV configuration (external M3U provider, e.g. SmartCDN)
const IPTV_M3U_URL = process.env.IPTV_M3U_URL;
const IPTV_EPG_URL = process.env.IPTV_EPG_URL;
const sax = require('sax');

/**
 * Fetch and cache M3U playlist from local file
 */
async function syncM3U() {
  try {
    logger.info('Parsing M3U playlist from local file...');
    const channels = await parseM3U(M3U_FILE);

    logger.info(`Parsed ${channels.length} channels from M3U`);

    // Store channels in database
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `);

    const channelData = channels.map(ch => ({
      id: ch.id,
      name: ch.name,
      displayName: ch.displayName,
      url: ch.url,
      group: ch.group
    }));

    stmt.run('m3u_channels', JSON.stringify(channelData));

    logger.info('M3U sync complete');
  } catch (error) {
    logger.error('M3U sync failed:', error);
  }
}

/**
 * Normalize a channel name for fuzzy matching.
 * Strips parenthetical content, common suffixes/qualifiers, lowercases, removes non-alphanumeric.
 */
function normalizeName(name) {
  return name
    .toLowerCase()
    .replace(/\([^)]*\)/g, '')  // Strip parenthetical content: (BBCA), (OWN), (CBSSN), (SNY)
    .replace(/\b(the|us|usa|eastern|western|feed|east|west|hd|sd|fhd|uhd|new york|los angeles|ny|ca|channel|entertainment|television|live|tv|movies|movie|mystery|mysteries|mysterie|true|crime)\b/g, '')
    .replace(/[^a-z0-9]/g, '')
    .trim();
}

/**
 * Raw name aliases for NTV channels (lowercased) — checked before normalization.
 */
const NTV_RAW_ALIASES = {
  'abc': 'abc-kabc-los-angeles-ca',
  'abc new york': 'abc-wabc-new-york-ny',
  'cbs': 'cbs-kcbs-los-angeles-ca',
  'cbs new york': 'cbs-wcbs-new-york-ny',
  'nbc': 'nbc-knbc-los-angeles-ca',
  'nbc new york': 'nbc-wnbc-new-york-ny',
  'fox': 'fox-kttv-los-angeles-ca',
  'fox new york': 'fox-wnyw-new-york-ny',
  'the hallmark channel': 'hallmark-eastern-feed',
  'hallmark movies & mysteries': 'hallmark-mystery-eastern-hd',
  'sportsnet new york (sny)': 'sny-sportsnet-new-york-comcast',
};

/**
 * Normalized name aliases for NTV channels with structurally different names.
 */
const NTV_ALIASES = {
  'oprahwinfreynetwork': 'oprah-winfrey-network-usa-eastern',
  'ahc': 'american-heroes-channel',
  'nick': 'nickelodeon-usa-east-feed',
  'cwpix11': 'wpix-new-york-superstation',
  'investigationdiscovery': 'investigation-discovery-usa-eastern',
};

/**
 * Direct NTV channel_id -> TVPass channel_id mappings.
 * Highest priority — checked before name matching.
 * Covers numeric-ID channels that are hard to match by name.
 */
const NTV_ID_ALIASES = {
  // Network affiliates (NY)
  '51': 'abc-wabc-new-york-ny',
  '52': 'cbs-wcbs-new-york-ny',
  '53': 'nbc-wnbc-new-york-ny',
  '54': 'fox-wnyw-new-york-ny',
  '30': 'cw-wdcw-district-of-columbia',
  '766': 'abc-wabc-new-york-ny',
  // Entertainment channels (empty country code)
  '298': 'fxx-usa-eastern',
  '301': 'freeform-east-feed',
  '302': 'ae-us-eastern-feed',
  '303': 'amc-eastern-feed',
  '307': 'bravo-usa-eastern-feed',
  '310': 'comedy-central-us-eastern-feed',
  '315': 'e-entertainment-usa-eastern-feed',
  '317': 'fx-networks-east-coast',
  '332': 'oxygen-eastern-feed',
  '373': 'syfy-eastern-feed',
  // Collision fixes (name normalization causes wrong matches)
  '381': 'fx-movie-channel',           // "FX Movie Channel" → "fx" collides with FX
  '389': 'lifetime-movies-east',       // "Lifetime Movies Network" → collides with Lifetime
  '407': 'sportsnet-west',             // "Sportsnet West" → "sportsnet" collides
  '408': 'sportsnet-east',             // "Sportsnet East" → "sportsnet" collides
  '759': 'sny-sportsnet-new-york-comcast', // "SportsNet New York (SNY)"
  '765': 'msg-madison-square-gardens', // "MSG USA" → "msg" collides with MSG+
};

/**
 * Direct IPTV tvg-id -> TVPass channel_id mappings.
 * Needed where name normalization fails or causes collisions.
 */
const IPTV_ID_ALIASES = {
  // Structural name mismatches
  '32': 'fx-networks-east-coast',         // FX (East) → "fx" ≠ TVPass "fxnetworkscoast"
  '14': 'espn-u',                          // ESPNU College Sports → "espnucollegesports" ≠ "espnu"
  '100': 'hbo-2-eastern-feed',            // HBO Hits (East) → HBO 2 in TVPass
  '144': 'turner-classic-movies-usa',      // TCM (East) → "tcm" ≠ "turnerclassic"
  '139': 'tlc-usa-eastern',               // The Learning Channel TLC → "learningtlc" ≠ "tlc"
  '140': 'tnt-eastern-feed',              // Turner Network Television TNT → "turnernetworktnt" ≠ "tnt"
  '134': 'tbs-east',                       // TBS Superstation → "tbssuperstation" ≠ "tbs"
  '5': 'altitude-sports-denver',           // Altitude Sports → missing "Denver" in SmartCDN name
  '2479': 'metv-toons-wjlp2-new-jersey',  // MeTV Toons (KAZD) → different affiliate call sign

  // Collision fixes (multiple channels normalize to the same string)
  '94': 'hallmark-eastern-feed',           // Hallmark Channel (East) → collides with Hallmark Mystery
  '95': 'hallmark-eastern-feed',           // Hallmark Channel (West) → deduped with East, prevents mystery collision
  '97': 'hallmark-mystery-eastern-hd',     // Hallmark Mystery → collides with Hallmark Channel
  '96': 'hallmark-family-hd',             // Hallmark Family
  '108': 'lifetime-network-us-eastern-feed', // Lifetime Television → collides with Lifetime Movies
  '107': 'lifetime-movies-east',           // Lifetime Movie Network
  '128': 'sny-sportsnet-new-york-comcast', // SportsNet New York (SNY) → "sportsnet" collision
  '6234': 'sportsnet-east',               // Sportsnet East → "sportsnet" collision with West
  '6239': 'sportsnet-west',               // Sportsnet West → "sportsnet" collision with East

  // FanDuel "Network" in TVPass but not in SmartCDN names
  '70': 'fanduel-sports-network-detroit-hd',
  '71': 'fanduel-sports-network-florida',
  '72': 'fanduel-sports-network-north',
  '73': 'fanduel-sports-network-ohio-cleveland',
  '74': 'fanduel-sports-network-oklahoma',
  '76': 'fanduel-sports-network-wisconsin',
  '77': 'fanduel-sports-network-west',
  '81': 'fanduel-sports-network-socal',

  // CDN-only local channels (Chicago)
  '3251': 'cdn-3251',
  '3252': 'cdn-3252',
  '3253': 'cdn-3253',
  '3254': 'cdn-3254',
};

/**
 * Parse a remote M3U playlist text into channel objects.
 * Handles standard #EXTINF format with tvg-name, tvg-id, group-title attributes.
 */
function parseRemoteM3U(m3uText) {
  const channels = [];
  const lines = m3uText.split('\n');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF:')) continue;

    const tvgName = line.match(/tvg-name="([^"]*)"/)?.[1] || '';
    const tvgId = line.match(/tvg-id="([^"]*)"/)?.[1] || '';
    const groupTitle = line.match(/group-title="([^"]*)"/)?.[1] || '';
    const displayName = line.split(',').pop()?.trim() || tvgName;

    // Next non-empty, non-comment line is the URL
    let url = '';
    for (let j = i + 1; j < lines.length; j++) {
      const nextLine = lines[j].trim();
      if (nextLine && !nextLine.startsWith('#')) {
        url = nextLine;
        break;
      }
    }

    if (url && tvgId) {
      channels.push({ tvgId, tvgName, displayName, groupTitle, url });
    }
  }

  return channels;
}

/**
 * Fetch and sync IPTV M3U playlist.
 * Matches IPTV channels to existing TVPass channels by normalized name + aliases.
 * Stores mapping in iptv_stream_map: { tvpassId: { url, iptv_id, name, group } }
 */
async function syncIPTV() {
  if (!IPTV_M3U_URL) {
    logger.debug('IPTV M3U URL not configured, skipping');
    return;
  }

  try {
    logger.info('Fetching IPTV M3U playlist...');
    const response = await axios.get(IPTV_M3U_URL, { timeout: 30000 });
    const channels = parseRemoteM3U(response.data);
    logger.info(`Parsed ${channels.length} channels from IPTV M3U`);

    // Get existing M3U channels for matching
    const existingRow = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'
    `).get();

    let existingChannels = [];
    if (existingRow) {
      existingChannels = JSON.parse(existingRow.epg_data);
    }

    // Load channel-config display names for better matching
    let channelConfigData = {};
    try {
      channelConfigData = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'db', 'channel-config.json'), 'utf-8'));
    } catch (e) { /* ignore */ }

    // Build normalized name → channel map (skip ntv-/cab-/cdn- entries)
    const channelNameMap = new Map();
    const channelById = new Map();
    for (const ch of existingChannels) {
      if (ch.id.startsWith('ntv-') || ch.id.startsWith('cab-') || ch.id.startsWith('cdn-')) continue;
      channelById.set(ch.id, ch);

      const normalized = normalizeName(ch.displayName || ch.name);
      if (normalized) channelNameMap.set(normalized, ch);

      // Also index by config displayName for broader matching
      const configName = channelConfigData[ch.id]?.displayName;
      if (configName) {
        const configNormalized = normalizeName(configName);
        if (configNormalized && configNormalized !== normalized) {
          channelNameMap.set(configNormalized, ch);
        }
      }
    }

    // Match IPTV channels to TVPass channels
    const iptvStreamMap = {};
    const cdnOnlyChannels = []; // CDN channels with cdn- alias that need adding to channel list
    let matched = 0, unmatched = 0;
    const unmatchedNames = [];

    for (const ch of channels) {
      const normalized = normalizeName(ch.tvgName || ch.displayName);

      // Check ID alias first (most specific), then auto-match by normalized name
      const aliasId = IPTV_ID_ALIASES[ch.tvgId];
      const existingMatch = aliasId ? channelById.get(aliasId) : channelNameMap.get(normalized);

      if (existingMatch) {
        // Only store first match per TVPass channel (prefer East over West)
        if (!iptvStreamMap[existingMatch.id]) {
          iptvStreamMap[existingMatch.id] = {
            url: ch.url,
            iptv_id: ch.tvgId,
            name: ch.tvgName || ch.displayName,
            group: ch.groupTitle
          };
          matched++;
        }
      } else if (aliasId && aliasId.startsWith('cdn-')) {
        // CDN-only channel (alias points to cdn- ID, not in base channel list)
        if (!iptvStreamMap[aliasId]) {
          iptvStreamMap[aliasId] = {
            url: ch.url,
            iptv_id: ch.tvgId,
            name: ch.tvgName || ch.displayName,
            group: ch.groupTitle
          };
          cdnOnlyChannels.push({
            id: aliasId,
            name: ch.tvgName || ch.displayName,
            displayName: ch.tvgName || ch.displayName,
            url: '',
            group: ch.groupTitle || 'CDN'
          });
          matched++;
        }
      } else {
        unmatched++;
        if (unmatchedNames.length < 30) {
          unmatchedNames.push(`${ch.tvgName} (id:${ch.tvgId}) [${ch.groupTitle}]`);
        }
      }
    }

    // Merge CDN-only channels into m3u_channels list (like NTV does for ntv- channels)
    if (cdnOnlyChannels.length > 0) {
      const currentRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'`).get();
      if (currentRow) {
        const currentChannels = JSON.parse(currentRow.epg_data);
        // Remove old cdn- entries, then add fresh ones
        const withoutCdn = currentChannels.filter(ch => !ch.id.startsWith('cdn-'));
        const merged = [...withoutCdn, ...cdnOnlyChannels];
        db.prepare(`INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at) VALUES (?, ?, datetime('now'))`)
          .run('m3u_channels', JSON.stringify(merged));
        logger.info(`IPTV sync: added ${cdnOnlyChannels.length} CDN-only channels to channel list`);
      }
    }

    // Store IPTV stream mapping
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('iptv_stream_map', JSON.stringify(iptvStreamMap));

    logger.info(`IPTV sync: ${matched} matched, ${unmatched} unmatched out of ${channels.length} channels`);
    if (unmatchedNames.length > 0) {
      logger.debug(`IPTV unmatched: ${unmatchedNames.join(', ')}`);
    }
  } catch (error) {
    logger.error('IPTV sync failed:', error.message);
  }
}

/**
 * Parse XMLTV date string to millisecond timestamp.
 * Format: "20260215120000 +0000" or "20260215120000"
 */
function parseXMLTVDate(dateStr) {
  if (!dateStr) return 0;
  const m = dateStr.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{4})?$/);
  if (!m) return 0;
  const iso = `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}${m[7] ? m[7].substring(0, 3) + ':' + m[7].substring(3) : 'Z'}`;
  return new Date(iso).getTime();
}

/**
 * Fetch and sync IPTV EPG from XMLTV source.
 * Maps IPTV channel IDs to TVPass IDs using iptv_stream_map.
 */
async function syncIPTVEPG() {
  if (!IPTV_EPG_URL) {
    logger.debug('IPTV EPG URL not configured, skipping');
    return;
  }

  try {
    // Build reverse map: iptv_id → tvpass_id
    const iptvRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'iptv_stream_map'`).get();
    if (!iptvRow) {
      logger.debug('No iptv_stream_map found, skipping IPTV EPG sync');
      return;
    }

    const iptvStreamMap = JSON.parse(iptvRow.epg_data);
    const reverseMap = {};
    for (const [tvpassId, data] of Object.entries(iptvStreamMap)) {
      reverseMap[String(data.iptv_id)] = tvpassId;
    }

    logger.info(`Fetching IPTV EPG from XMLTV source (${Object.keys(reverseMap).length} mapped channels)...`);
    const response = await axios.get(IPTV_EPG_URL, { timeout: 120000, responseType: 'stream' });

    await new Promise((resolve, reject) => {
      const parser = sax.createStream(true, { trim: true });
      const programs = {}; // tvpassId → [program, ...]
      let currentProgramme = null;
      let currentChannelId = '';
      let textContent = '';

      parser.on('opentag', (node) => {
        if (node.name === 'programme') {
          const channelId = String(node.attributes.channel || '');
          const tvpassId = reverseMap[channelId];
          if (tvpassId) {
            currentProgramme = {
              start: parseXMLTVDate(node.attributes.start),
              stop: parseXMLTVDate(node.attributes.stop),
              title: '', desc: '', category: '', icon: '', episodeNum: ''
            };
            currentChannelId = tvpassId;
          }
        } else if (currentProgramme && node.name === 'icon' && node.attributes.src) {
          currentProgramme.icon = node.attributes.src;
        }
        textContent = '';
      });

      parser.on('text', (text) => { textContent += text; });
      parser.on('cdata', (text) => { textContent += text; });

      parser.on('closetag', (name) => {
        if (currentProgramme) {
          if (name === 'title') currentProgramme.title = textContent.trim();
          else if (name === 'desc') currentProgramme.desc = textContent.trim();
          else if (name === 'category') currentProgramme.category = textContent.trim();
          else if (name === 'episode-num') currentProgramme.episodeNum = textContent.trim();
          else if (name === 'programme') {
            if (!programs[currentChannelId]) programs[currentChannelId] = [];
            programs[currentChannelId].push(currentProgramme);
            currentProgramme = null;
            currentChannelId = '';
          }
        }
        textContent = '';
      });

      parser.on('end', () => {
        // Store EPG data per channel (replaces existing EPG for matched channels)
        const stmt = db.prepare(`
          INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
          VALUES (?, ?, datetime('now'))
        `);

        let totalPrograms = 0;
        for (const [channelId, channelPrograms] of Object.entries(programs)) {
          stmt.run(channelId, JSON.stringify(channelPrograms));
          totalPrograms += channelPrograms.length;
        }

        logger.info(`IPTV EPG sync: ${Object.keys(programs).length} channels, ${totalPrograms} programs`);
        resolve();
      });

      parser.on('error', (err) => {
        logger.error('IPTV EPG XML parse error:', err.message);
        reject(err);
      });

      response.data.pipe(parser);
    });
  } catch (error) {
    logger.error('IPTV EPG sync failed:', error.message);
  }
}

/**
 * Fetch and sync NTV channels into the channel list.
 * Matched NTV channels store their channel object (not URL — URLs are ephemeral) in ntv_stream_map.
 * Unmatched NTV channels are added as ntv-* entries.
 */
async function syncNTVChannels() {
  if (!NTV_ENABLED) {
    logger.debug('NTV integration disabled, skipping');
    return;
  }

  try {
    const filteredChannels = await fetchNTVChannels();

    if (filteredChannels.length === 0) {
      logger.warn('NTV returned 0 channels after filtering, skipping merge');
      return;
    }

    // Get existing M3U channels from cache (TVPass, no old ntv-/cab- entries)
    const existingRow = db.prepare(`
      SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'
    `).get();

    let existingChannels = [];
    if (existingRow) {
      existingChannels = JSON.parse(existingRow.epg_data);
    }

    // Remove old NTV-only and legacy cab- channels (ntv-/cab- prefix)
    const baseChannels = existingChannels.filter(ch => !ch.id.startsWith('ntv-') && !ch.id.startsWith('cab-'));

    // Load channel-config display names for better matching
    let channelConfigNames = {};
    try {
      channelConfigNames = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'db', 'channel-config.json'), 'utf-8'));
    } catch (e) { /* ignore */ }

    // Build normalized name -> channel map for deduplication
    const channelNameMap = new Map();
    for (const ch of baseChannels) {
      const normalized = normalizeName(ch.displayName || ch.name);
      if (normalized) channelNameMap.set(normalized, ch);
      const configName = channelConfigNames[ch.id]?.displayName;
      if (configName) {
        const configNormalized = normalizeName(configName);
        if (configNormalized && configNormalized !== normalized) {
          channelNameMap.set(configNormalized, ch);
        }
      }
    }

    // Build ID lookup for manual aliases
    const channelById = new Map();
    for (const ch of baseChannels) {
      channelById.set(ch.id, ch);
    }

    // Deduplicate: match NTV channels to existing channels by normalized name
    const ntvStreamMap = {}; // tvpassId/cabId -> { channel_id, channel_name, channel_code, channel_url }
    const unmatchedChannels = [];

    for (const ch of filteredChannels) {
      const ntvId = String(ch.channel_id);
      const rawLower = ch.channel_name.toLowerCase().trim();
      const normalized = normalizeName(ch.channel_name);

      // Check ID aliases first (most specific), then raw name, then normalized, then auto-match
      const aliasId = NTV_ID_ALIASES[ntvId] || NTV_RAW_ALIASES[rawLower] || NTV_ALIASES[normalized];
      const existingMatch = aliasId ? channelById.get(aliasId) : channelNameMap.get(normalized);

      if (existingMatch) {
        // Only add to NTV stream map if channel has a player URL we can resolve.
        // dlhd channels (numeric IDs, empty code) have no player URL and can't be resolved.
        // Prefer cdnlive sources over scorpion (our deobfuscator handles cdn-live.tv).
        if (ch.channel_url) {
          const existing = ntvStreamMap[existingMatch.id];
          const isCdnlive = ntvId.startsWith('cdnlive-');
          if (!existing || isCdnlive) {
            ntvStreamMap[existingMatch.id] = {
              channel_id: ch.channel_id,
              channel_name: ch.channel_name,
              channel_code: ch.channel_code,
              channel_url: ch.channel_url
            };
          }
        }
      } else if (ch.channel_code) {
        // Only add as ntv-* entry if from explicit country filter (not empty-code)
        // Empty-code unmatched channels are silently dropped to avoid foreign channel clutter
        unmatchedChannels.push({
          id: `ntv-${ch.channel_id}`,
          name: ch.channel_name,
          displayName: ch.channel_name,
          url: '', // NTV URLs are ephemeral, resolved on-demand
          group: 'NTV',
          source: 'ntv'
        });
      }
    }

    // Store NTV stream mapping in epg_cache
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('ntv_stream_map', JSON.stringify(ntvStreamMap));

    // Merge: base channels + unmatched NTV-only channels
    const merged = [...baseChannels, ...unmatchedChannels];

    // Store merged list
    db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `).run('m3u_channels', JSON.stringify(merged));

    const matchedCount = Object.keys(ntvStreamMap).length;
    logger.info(`NTV sync: ${matchedCount} matched to existing, ${unmatchedChannels.length} NTV-only, ${baseChannels.length} existing = ${merged.length} total channels`);
  } catch (error) {
    if (error.code === 'ECONNREFUSED' || error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') {
      logger.warn(`NTV API offline (${error.code}), keeping existing channels`);
    } else {
      logger.error('NTV sync failed:', error.message);
    }
  }
}

/**
 * Auto-disable channels that have no sources (no NTV, no backup-streams, no valid M3U URL).
 * Only disables — never re-enables (respects admin overrides).
 */
function autoDisableSourcelessChannels() {
  try {
    let ntvStreamMap = {};
    let backupStreams = {};

    let iptvStreamMap = {};

    try {
      const ntvRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'ntv_stream_map'`).get();
      if (ntvRow) ntvStreamMap = JSON.parse(ntvRow.epg_data);
    } catch (e) { /* ignore */ }

    try {
      const iptvRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'iptv_stream_map'`).get();
      if (iptvRow) iptvStreamMap = JSON.parse(iptvRow.epg_data);
    } catch (e) { /* ignore */ }

    try {
      backupStreams = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'db', 'backup-streams.json'), 'utf-8'));
    } catch (e) { /* ignore */ }

    const m3uRow = db.prepare(`SELECT epg_data FROM epg_cache WHERE channel_id = 'm3u_channels'`).get();
    if (!m3uRow) return;

    const channels = JSON.parse(m3uRow.epg_data);
    const disabledIds = [];

    const upsert = db.prepare(`
      INSERT INTO channel_metadata (channel_id, is_enabled, updated_at)
      VALUES (?, 0, datetime('now'))
      ON CONFLICT(channel_id) DO UPDATE SET
        is_enabled = 0,
        updated_at = datetime('now')
      WHERE channel_metadata.is_enabled = 1
    `);

    const transaction = db.transaction(() => {
      for (const ch of channels) {
        if (ch.id.startsWith('ntv-')) continue;

        const hasNTV = !!ntvStreamMap[ch.id];
        const hasIPTV = !!iptvStreamMap[ch.id];
        const hasBackup = !!backupStreams[ch.id];
        const hasM3UUrl = !!(ch.url && ch.url.startsWith('http'));

        if (!hasNTV && !hasIPTV && !hasBackup && !hasM3UUrl) {
          const meta = db.prepare(`SELECT is_enabled FROM channel_metadata WHERE channel_id = ?`).get(ch.id);
          if (!meta || meta.is_enabled === 1) {
            upsert.run(ch.id);
            disabledIds.push(ch.id);
          }
        }
      }
    });

    transaction();

    if (disabledIds.length > 0) {
      logger.info(`Auto-disabled ${disabledIds.length} channels with no sources: ${disabledIds.slice(0, 10).join(', ')}${disabledIds.length > 10 ? '...' : ''}`);
    }
  } catch (error) {
    logger.error('Auto-disable sourceless channels failed:', error.message);
  }
}

/**
 * Fetch and cache EPG data from all sources
 */
async function syncEPG() {
  try {
    logger.info('Fetching EPG data from all sources...');

    const { channels, programs } = await parseEPGData({
      includeTvPass: true,
      includePluto: true,
      includeEpgshare01: true
    });

    logger.info(`Fetched EPG for ${Object.keys(programs).length} channels`);

    // Store EPG data per channel
    const stmt = db.prepare(`
      INSERT OR REPLACE INTO epg_cache (channel_id, epg_data, updated_at)
      VALUES (?, ?, datetime('now'))
    `);

    for (const [channelId, channelPrograms] of Object.entries(programs)) {
      stmt.run(channelId, JSON.stringify(channelPrograms));
    }

    logger.info('EPG sync complete');
  } catch (error) {
    logger.error('EPG sync failed:', error);
  }
}

/**
 * Sync EPG, M3U, and NTV data
 */
async function syncAll() {
  logger.info('Starting EPG/M3U sync...');
  await Promise.all([syncEPG(), syncM3U()]);
  await syncNTVChannels();
  await syncIPTV();
  await syncIPTVEPG();
  autoDisableSourcelessChannels();
  logger.info('EPG/M3U sync finished');
}

/**
 * Start cron jobs for periodic syncing
 */
function startSyncJobs() {
  // Sync EPG every 30 minutes
  cron.schedule('*/30 * * * *', () => {
    if (epgSyncInProgress) {
      logger.warn('Cron: EPG sync skipped - previous sync still running');
      return;
    }
    logger.info('Cron: EPG sync triggered');
    epgSyncInProgress = true;
    syncEPG()
      .catch(err => logger.error('Cron EPG sync error:', err))
      .finally(() => { epgSyncInProgress = false; });
  });

  // Sync M3U every hour (then merge NTV on top)
  cron.schedule('0 * * * *', () => {
    if (m3uSyncInProgress) {
      logger.warn('Cron: M3U sync skipped - previous sync still running');
      return;
    }
    logger.info('Cron: M3U sync triggered');
    m3uSyncInProgress = true;
    syncM3U()
      .then(() => syncNTVChannels())
      .then(() => syncIPTV())
      .then(() => syncIPTVEPG())
      .then(() => autoDisableSourcelessChannels())
      .catch(err => logger.error('Cron M3U sync error:', err))
      .finally(() => { m3uSyncInProgress = false; });
  });

  logger.info('EPG/M3U cron jobs started (EPG: 30min, M3U+NTV: 1hr)');

  // Run initial sync
  syncAll().catch(err => logger.error('Initial sync error:', err));
}

module.exports = {
  syncEPG,
  syncM3U,
  syncNTVChannels,
  syncIPTV,
  syncIPTVEPG,
  syncAll,
  startSyncJobs
};
