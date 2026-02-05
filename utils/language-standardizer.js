/**
 * Language Standardizer Utility
 * Normalizes various language code formats to standard names
 */

// ISO 639-1 (2-letter) codes to full names
const ISO_639_1 = {
  en: 'English',
  es: 'Spanish',
  fr: 'French',
  de: 'German',
  it: 'Italian',
  pt: 'Portuguese',
  ru: 'Russian',
  ja: 'Japanese',
  ko: 'Korean',
  zh: 'Chinese',
  ar: 'Arabic',
  hi: 'Hindi',
  nl: 'Dutch',
  pl: 'Polish',
  sv: 'Swedish',
  da: 'Danish',
  no: 'Norwegian',
  fi: 'Finnish',
  tr: 'Turkish',
  el: 'Greek',
  he: 'Hebrew',
  th: 'Thai',
  vi: 'Vietnamese',
  id: 'Indonesian',
  ms: 'Malay',
  cs: 'Czech',
  sk: 'Slovak',
  hu: 'Hungarian',
  ro: 'Romanian',
  bg: 'Bulgarian',
  uk: 'Ukrainian',
  hr: 'Croatian',
  sr: 'Serbian',
  sl: 'Slovenian',
  et: 'Estonian',
  lv: 'Latvian',
  lt: 'Lithuanian',
  fa: 'Persian',
  bn: 'Bengali',
  ta: 'Tamil',
  te: 'Telugu',
  mr: 'Marathi',
  gu: 'Gujarati',
  kn: 'Kannada',
  ml: 'Malayalam',
  pa: 'Punjabi',
  ur: 'Urdu'
};

// ISO 639-2 (3-letter) codes to full names
const ISO_639_2 = {
  eng: 'English',
  spa: 'Spanish',
  fre: 'French',
  fra: 'French',
  ger: 'German',
  deu: 'German',
  ita: 'Italian',
  por: 'Portuguese',
  rus: 'Russian',
  jpn: 'Japanese',
  kor: 'Korean',
  chi: 'Chinese',
  zho: 'Chinese',
  ara: 'Arabic',
  hin: 'Hindi',
  dut: 'Dutch',
  nld: 'Dutch',
  pol: 'Polish',
  swe: 'Swedish',
  dan: 'Danish',
  nor: 'Norwegian',
  fin: 'Finnish',
  tur: 'Turkish',
  gre: 'Greek',
  ell: 'Greek',
  heb: 'Hebrew',
  tha: 'Thai',
  vie: 'Vietnamese',
  ind: 'Indonesian',
  may: 'Malay',
  msa: 'Malay',
  cze: 'Czech',
  ces: 'Czech',
  slo: 'Slovak',
  slk: 'Slovak',
  hun: 'Hungarian',
  rum: 'Romanian',
  ron: 'Romanian',
  bul: 'Bulgarian',
  ukr: 'Ukrainian',
  hrv: 'Croatian',
  srp: 'Serbian',
  slv: 'Slovenian',
  est: 'Estonian',
  lav: 'Latvian',
  lit: 'Lithuanian',
  per: 'Persian',
  fas: 'Persian',
  ben: 'Bengali',
  tam: 'Tamil',
  tel: 'Telugu',
  mar: 'Marathi',
  guj: 'Gujarati',
  kan: 'Kannada',
  mal: 'Malayalam',
  pan: 'Punjabi',
  urd: 'Urdu',
  und: null // Undetermined - treat as non-standard
};

// Full language names (case-insensitive matching)
const FULL_NAMES = {
  english: 'English',
  spanish: 'Spanish',
  french: 'French',
  german: 'German',
  italian: 'Italian',
  portuguese: 'Portuguese',
  russian: 'Russian',
  japanese: 'Japanese',
  korean: 'Korean',
  chinese: 'Chinese',
  arabic: 'Arabic',
  hindi: 'Hindi',
  dutch: 'Dutch',
  polish: 'Polish',
  swedish: 'Swedish',
  danish: 'Danish',
  norwegian: 'Norwegian',
  finnish: 'Finnish',
  turkish: 'Turkish',
  greek: 'Greek',
  hebrew: 'Hebrew',
  thai: 'Thai',
  vietnamese: 'Vietnamese',
  indonesian: 'Indonesian',
  malay: 'Malay',
  czech: 'Czech',
  slovak: 'Slovak',
  hungarian: 'Hungarian',
  romanian: 'Romanian',
  bulgarian: 'Bulgarian',
  ukrainian: 'Ukrainian',
  croatian: 'Croatian',
  serbian: 'Serbian',
  slovenian: 'Slovenian',
  estonian: 'Estonian',
  latvian: 'Latvian',
  lithuanian: 'Lithuanian',
  persian: 'Persian',
  bengali: 'Bengali',
  tamil: 'Tamil',
  telugu: 'Telugu',
  marathi: 'Marathi',
  gujarati: 'Gujarati',
  kannada: 'Kannada',
  malayalam: 'Malayalam',
  punjabi: 'Punjabi',
  urdu: 'Urdu'
};

// Reverse mapping: full name to ISO 639-1 code
const NAME_TO_CODE = {};
for (const [code, name] of Object.entries(ISO_639_1)) {
  NAME_TO_CODE[name.toLowerCase()] = code;
}

/**
 * Standardize a language input to a consistent format
 * @param {string} input - Language code, name, or variation
 * @returns {{ standardized: string|null, isStandard: boolean }}
 */
function standardizeLanguage(input) {
  if (!input || typeof input !== 'string') {
    return { standardized: null, isStandard: false };
  }

  // Trim and clean input
  let cleaned = input.trim();

  // Remove common wrapper patterns: [EN], (eng), etc.
  const bracketMatch = cleaned.match(/^\[([^\]]+)\]$|^\(([^)]+)\)$/);
  if (bracketMatch) {
    cleaned = bracketMatch[1] || bracketMatch[2];
  }

  // Remove regional suffixes: en-us, en_gb, etc.
  const regionalMatch = cleaned.match(/^([a-z]{2,3})[-_][a-z]{2}$/i);
  if (regionalMatch) {
    cleaned = regionalMatch[1];
  }

  // Remove SDH/CC suffixes: "English SDH", "English (CC)", etc.
  cleaned = cleaned.replace(/\s*\(?SDH\)?$/i, '');
  cleaned = cleaned.replace(/\s*\(?CC\)?$/i, '');
  cleaned = cleaned.replace(/\s*\(?Forced\)?$/i, '');
  cleaned = cleaned.trim();

  const lowerCleaned = cleaned.toLowerCase();

  // Try ISO 639-1 (2-letter)
  if (ISO_639_1[lowerCleaned]) {
    return { standardized: ISO_639_1[lowerCleaned], isStandard: true };
  }

  // Try ISO 639-2 (3-letter)
  if (ISO_639_2[lowerCleaned] !== undefined) {
    const result = ISO_639_2[lowerCleaned];
    if (result === null) {
      // Undetermined language code
      return { standardized: null, isStandard: false };
    }
    return { standardized: result, isStandard: true };
  }

  // Try full language name
  if (FULL_NAMES[lowerCleaned]) {
    return { standardized: FULL_NAMES[lowerCleaned], isStandard: true };
  }

  // Check if it starts with a known language name (e.g., "English (US)")
  for (const [key, name] of Object.entries(FULL_NAMES)) {
    if (lowerCleaned.startsWith(key)) {
      return { standardized: name, isStandard: true };
    }
  }

  // Non-standard: Track 1, Unknown, Japanese characters, etc.
  return { standardized: null, isStandard: false };
}

/**
 * Get ISO 639-1 code from a language name
 * @param {string} languageName - Full language name (e.g., "English")
 * @returns {string|null} ISO 639-1 code or null
 */
function getLanguageCode(languageName) {
  if (!languageName || typeof languageName !== 'string') {
    return null;
  }

  const lower = languageName.toLowerCase().trim();
  return NAME_TO_CODE[lower] || null;
}

/**
 * Check if a language matches a preferred language code
 * @param {string} input - Language input to check
 * @param {string} preferredCode - ISO 639-1 code (e.g., 'en')
 * @returns {boolean}
 */
function matchesLanguage(input, preferredCode) {
  const result = standardizeLanguage(input);
  if (!result.isStandard) {
    return false;
  }

  const inputCode = getLanguageCode(result.standardized);
  return inputCode === preferredCode.toLowerCase();
}

module.exports = {
  standardizeLanguage,
  getLanguageCode,
  matchesLanguage,
  ISO_639_1,
  ISO_639_2
};
