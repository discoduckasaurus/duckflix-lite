/**
 * EPG Parser Module
 *
 * Fetches and parses Electronic Program Guide (EPG) data from multiple sources:
 * - TVPass.org XML
 * - Pluto TV API
 * - epg.pw XML (extended schedules)
 */

const axios = require('axios');
const { XMLParser } = require('fast-xml-parser');

// Pluto TV channel ID mapping (our channel ID -> Pluto channel slug)
const PLUTO_CHANNEL_MAP = {
  'comedy-central-pluto-tv': '5ca671f215a62078d2ec0abf'
};

// epg.pw channel ID mapping (our channel ID -> epg.pw numeric ID)
// Source: https://epg.pw/xmltv/epg_US.xml
const EPG_PW_CHANNEL_MAP = {
  'abc-wabc-new-york-ny': '467679',
  'amc-eastern-feed': '465032',
  'animal-planet-us-east': '465310',
  'bet-eastern-feed': '464867',
  'bloomberg-tv-usa': '465213',
  'boomerang': '464755',
  'bravo-usa-eastern-feed': '464753',
  'cartoon-network-usa-eastern-feed': '465177',
  'cbs-sports-network-usa': '464937',
  'cbs-wcbs-new-york-ny': '466235',
  'cinemax-eastern-feed': '464917',
  'cnbc-usa': '464791',
  'cnn': '464857',
  'comedy-central-us-eastern-feed': '464922',
  'discovery-channel-us-eastern-feed': '465364',
  'disney-eastern-feed': '465170',
  'e-entertainment-usa-eastern-feed': '464832',
  'espn': '465198',
  'espn2': '465373',
  'espn-deportes': '464949',
  'espn-news': '465410',
  'espn-u': '465108',
  'food-network-usa-eastern-feed': '464984',
  'fox-news': '465372',
  'fox-sports-1': '465248',
  'fox-sports-2': '465280',
  'fox-wnyw-new-york-ny': '468913',
  'freeform-east-feed': '465331',
  'fx-networks-east-coast': '464813',
  'fxx-usa-eastern': '465357',
  'game-show-network-east': '464810',
  'golf-channel-usa': '464783',
  'hbo-2-eastern-feed': '465041',
  'hbo-comedy-hd-east': '464953',
  'hbo-eastern-feed': '464745',
  'hbo-signature-hbo-3-eastern': '465279',
  'hbo-zone-hd-east': '465104',
  'history-channel-us-eastern-feed': '465265',
  'investigation-discovery-usa-eastern': '465294',
  'lifetime-network-us-eastern-feed': '465359',
  'mlb-network': '465260',
  'msnbc-usa': '465087',
  'national-geographic-us-eastern': '465258',
  'nba-tv-usa': '464912',
  'nbc-wnbc-new-york-ny': '467015',
  'nfl-network': '465311',
  'nfl-redzone': '465401',
  'nhl-network-usa': '465348',
  'nickelodeon-usa-east-feed': '465251',
  'oxygen-eastern-feed': '464821',
  'paramount-with-showtime-eastern-feed': '464882',
  'science': '464805',
  'starz-eastern': '464975',
  'syfy-eastern-feed': '465053',
  'tbs-east': '465131',
  'the-weather-channel': '465272',
  'tlc-usa-eastern': '464919',
  'tnt-eastern-feed': '465114',
  'travel-us-east': '465186',
  'tv-land-eastern': '465235',
  'turner-classic-movies-usa': '465093',
  'usa-network-east-feed': '465006',
  'vh1-eastern-feed': '465206'
};

/**
 * Parse EPG time format (YYYYMMDDHHMMSS +OFFSET)
 *
 * @param {string} epgTime - EPG time string
 * @returns {number|null} Unix timestamp in milliseconds
 */
const parseEpgTime = (epgTime) => {
  if (!epgTime) return null;

  const year = epgTime.substring(0, 4);
  const month = epgTime.substring(4, 6);
  const day = epgTime.substring(6, 8);
  const hour = epgTime.substring(8, 10);
  const minute = epgTime.substring(10, 12);
  const second = epgTime.substring(12, 14);

  return new Date(`${year}-${month}-${day}T${hour}:${minute}:${second}`).getTime();
};

/**
 * Fetch and parse EPG XML from URL
 *
 * @param {string} epgUrl - URL to EPG XML file
 * @returns {Promise<{channels: Object, programs: Object}>} Parsed EPG data
 */
const fetchEPG = async (epgUrl) => {
  try {
    console.log('📡 Fetching EPG data from', epgUrl);
    const response = await axios.get(epgUrl, {
      timeout: 30000,
      headers: {
        'User-Agent': 'Mozilla/5.0'
      }
    });

    const parser = new XMLParser({
      ignoreAttributes: false,
      attributeNamePrefix: '@_'
    });

    const parsed = parser.parse(response.data);

    if (!parsed.tv) {
      console.warn('⚠️  EPG XML missing <tv> root element');
      return { channels: {}, programs: {} };
    }

    // Parse channels with icons
    const channelIcons = {};
    if (parsed.tv.channel) {
      const channelArray = Array.isArray(parsed.tv.channel) ? parsed.tv.channel : [parsed.tv.channel];
      for (const ch of channelArray) {
        const id = ch['@_id'];
        if (id) {
          channelIcons[id] = ch.icon ? ch.icon['@_src'] : null;
        }
      }
    }
    const channels = channelIcons;

    // Parse programs
    const programs = {};
    if (parsed.tv.programme) {
      const programArray = Array.isArray(parsed.tv.programme) ? parsed.tv.programme : [parsed.tv.programme];

      for (const prog of programArray) {
        const channelId = prog['@_channel'];
        if (!channelId) continue;

        if (!programs[channelId]) {
          programs[channelId] = [];
        }

        const program = {
          start: parseEpgTime(prog['@_start']),
          stop: parseEpgTime(prog['@_stop']),
          title: prog.title || 'Unknown',
          description: prog.desc || '',
          category: prog.category || null,
          icon: prog.icon ? prog.icon['@_src'] : null,
          episodeNum: prog['episode-num'] ? prog['episode-num']['#text'] : null
        };

        programs[channelId].push(program);
      }
    }

    console.log(`✅ Parsed EPG: ${Object.keys(channels).length} channels, ${Object.keys(programs).length} program listings`);
    return { channels, programs };
  } catch (err) {
    console.error('Failed to fetch EPG:', err.message);
    return { channels: {}, programs: {} };
  }
};

/**
 * Fetch Pluto TV EPG data from their API
 *
 * @returns {Promise<Object>} Programs mapped by channel ID
 */
const fetchPlutoEPG = async () => {
  try {
    // Pluto API returns ~8-10 hours of EPG data
    const now = new Date();
    const start = now.toISOString();
    const stop = new Date(now.getTime() + 8 * 60 * 60 * 1000).toISOString(); // 8 hours ahead

    const url = `http://api.pluto.tv/v2/channels?start=${encodeURIComponent(start)}&stop=${encodeURIComponent(stop)}`;
    console.log('📡 Fetching Pluto TV EPG data...');

    const response = await axios.get(url, {
      timeout: 15000,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
      }
    });

    const programs = {};

    // Map Pluto channels to our channel IDs
    for (const [ourChannelId, plutoChannelId] of Object.entries(PLUTO_CHANNEL_MAP)) {
      // Find the Pluto channel by ID
      const plutoChannel = response.data.find(ch => ch._id === plutoChannelId);
      if (!plutoChannel || !plutoChannel.timelines) continue;

      programs[ourChannelId] = plutoChannel.timelines.map(timeline => ({
        start: new Date(timeline.start).getTime(),
        stop: new Date(timeline.stop).getTime(),
        title: timeline.title || 'Unknown',
        description: timeline.episode?.description || timeline.episode?.summary || '',
        category: plutoChannel.category || null,
        icon: timeline.episode?.poster?.path || timeline.episode?.thumbnail?.path || null,
        episodeNum: timeline.episode?.number ? `S${timeline.episode.season || 1}E${timeline.episode.number}` : null
      }));

      console.log(`📺 Pluto EPG: ${ourChannelId} has ${programs[ourChannelId].length} programs`);
    }

    return programs;
  } catch (err) {
    console.error('Failed to fetch Pluto TV EPG:', err.message);
    return {};
  }
};

/**
 * Fetch epg.pw EPG data (extended schedules, more future data than tvpass)
 *
 * @returns {Promise<Object>} Programs mapped by channel ID
 */
const fetchEpgPw = async () => {
  try {
    console.log('📡 Fetching epg.pw EPG data (extended schedule)...');

    const response = await axios.get('https://epg.pw/xmltv/epg_US.xml', {
      timeout: 60000, // Large file, give it time
      headers: {
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
      }
    });

    const parser = new XMLParser({
      ignoreAttributes: false,
      attributeNamePrefix: '@_'
    });

    const parsed = parser.parse(response.data);
    if (!parsed.tv || !parsed.tv.programme) {
      console.warn('⚠️  epg.pw XML missing programme data');
      return {};
    }

    const programs = {};
    const programArray = Array.isArray(parsed.tv.programme) ? parsed.tv.programme : [parsed.tv.programme];

    // Create reverse mapping: epg.pw ID -> our channel ID
    const reverseMap = {};
    for (const [ourId, epgPwId] of Object.entries(EPG_PW_CHANNEL_MAP)) {
      reverseMap[epgPwId] = ourId;
    }

    for (const prog of programArray) {
      const epgPwChannelId = prog['@_channel'];
      const ourChannelId = reverseMap[epgPwChannelId];

      if (!ourChannelId) continue; // Skip channels we don't have mapped

      if (!programs[ourChannelId]) {
        programs[ourChannelId] = [];
      }

      programs[ourChannelId].push({
        start: parseEpgTime(prog['@_start']),
        stop: parseEpgTime(prog['@_stop']),
        title: typeof prog.title === 'string' ? prog.title : (prog.title?.['#text'] || 'Unknown'),
        description: typeof prog.desc === 'string' ? prog.desc : (prog.desc?.['#text'] || ''),
        category: prog.category || null,
        icon: prog.icon ? prog.icon['@_src'] : null,
        episodeNum: prog['episode-num'] ? prog['episode-num']['#text'] : null
      });
    }

    const channelCount = Object.keys(programs).length;
    const programCount = Object.values(programs).reduce((sum, p) => sum + p.length, 0);
    console.log(`✅ epg.pw: ${channelCount} channels, ${programCount} programs`);

    return programs;
  } catch (err) {
    console.error('Failed to fetch epg.pw EPG:', err.message);
    return {};
  }
};

/**
 * Parse EPG data from all sources and merge
 *
 * @param {Object} options - Parsing options
 * @param {boolean} [options.includeTvPass=true] - Include TVPass EPG
 * @param {boolean} [options.includePluto=true] - Include Pluto TV EPG
 * @param {boolean} [options.includeEpgPw=true] - Include epg.pw EPG
 * @returns {Promise<{channels: Object, programs: Object}>} Merged EPG data
 */
const parseEPGData = async (options = {}) => {
  const {
    includeTvPass = true,
    includePluto = true,
    includeEpgPw = true
  } = options;

  const sources = [];

  if (includeTvPass) {
    sources.push(fetchEPG('https://tvpass.org/epg.xml'));
  }
  if (includePluto) {
    sources.push(fetchPlutoEPG());
  }
  if (includeEpgPw) {
    sources.push(fetchEpgPw());
  }

  const results = await Promise.all(sources);

  // Merge results
  let channels = {};
  const programs = {};

  // Process TVPass result first (if included)
  if (includeTvPass && results[0]) {
    const tvpassResult = results[0];
    channels = tvpassResult.channels || {};

    // Store programs
    for (const [channelId, progs] of Object.entries(tvpassResult.programs || {})) {
      progs.sort((a, b) => a.start - b.start);
      programs[channelId] = progs;
    }
  }

  // Merge epg.pw data (has more future data, overwrites tvpass for mapped channels)
  const epgPwResult = includeEpgPw ? results[includeTvPass && includePluto ? 2 : (includeTvPass || includePluto ? 1 : 0)] : null;
  if (epgPwResult) {
    for (const [channelId, progs] of Object.entries(epgPwResult)) {
      progs.sort((a, b) => a.start - b.start);
      programs[channelId] = progs;
    }
  }

  // Merge Pluto TV EPG (overwrites for Pluto channels - most accurate for them)
  const plutoResult = includePluto ? results[includeTvPass ? 1 : 0] : null;
  if (plutoResult) {
    for (const [channelId, progs] of Object.entries(plutoResult)) {
      progs.sort((a, b) => a.start - b.start);
      programs[channelId] = progs;
    }
  }

  return { channels, programs };
};

module.exports = {
  fetchEPG,
  fetchPlutoEPG,
  fetchEpgPw,
  parseEPGData,
  parseEpgTime,
  PLUTO_CHANNEL_MAP,
  EPG_PW_CHANNEL_MAP
};
