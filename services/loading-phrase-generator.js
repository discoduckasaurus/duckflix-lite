/**
 * Loading Phrase Generator with Anti-Repetition Buffer
 * Prevents consecutive B phrases from repeating
 */

class LoadingPhraseGenerator {
  constructor(phrasesA, phrasesB) {
    this.phrasesA = phrasesA;
    this.phrasesB = phrasesB;
    this.lastBPhrase = null; // Buffer to prevent consecutive repeats
  }

  /**
   * Get B phrases that match A phrase's first letter
   * @param {string} aPhrase - The A phrase
   * @returns {Array<string>} Matching B phrases
   */
  getMatchingBPhrases(aPhrase) {
    const firstLetter = aPhrase.charAt(0).toUpperCase();
    return this.phrasesB.filter(b =>
      b.charAt(0).toUpperCase() === firstLetter
    );
  }

  /**
   * Generate a random phrase pair with anti-repetition buffer
   * @returns {{phraseA: string, phraseB: string}}
   */
  generatePhrasePair() {
    // Pick random A phrase
    const phraseA = this.phrasesA[Math.floor(Math.random() * this.phrasesA.length)];

    // Get matching B phrases
    let matchingB = this.getMatchingBPhrases(phraseA);

    // If we have more than one match, filter out the last used B phrase
    if (matchingB.length > 1 && this.lastBPhrase) {
      const filtered = matchingB.filter(b => b !== this.lastBPhrase);
      if (filtered.length > 0) {
        matchingB = filtered;
      }
      // If all filtered out (shouldn't happen), use original list
    }

    // Pick random B phrase
    const phraseB = matchingB[Math.floor(Math.random() * matchingB.length)];

    // Update buffer
    this.lastBPhrase = phraseB;

    return { phraseA, phraseB };
  }

  /**
   * Generate multiple phrase pairs with buffer
   * @param {number} count - Number of pairs to generate
   * @returns {Array<{phraseA: string, phraseB: string}>}
   */
  generateMultiple(count) {
    const pairs = [];
    for (let i = 0; i < count; i++) {
      pairs.push(this.generatePhrasePair());
    }
    return pairs;
  }

  /**
   * Reset the buffer
   */
  resetBuffer() {
    this.lastBPhrase = null;
  }
}

module.exports = LoadingPhraseGenerator;
