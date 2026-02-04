package com.duckflix.lite.utils

/**
 * Generates random loading phrase pairs with matching first letters
 * and anti-repetition for B phrases
 */
class LoadingPhraseGenerator {
    private var lastBPhrase: String? = null

    /**
     * Generates a pair of phrases where both start with the same letter
     * @return Pair<phraseA, phraseB> e.g., ("Buffering", "buffers")
     */
    fun generatePair(): Pair<String, String> {
        val phrasesA = LoadingPhrasesCache.phrasesA
        val phrasesB = LoadingPhrasesCache.phrasesB

        // Fallback if cache is empty
        if (phrasesA.isEmpty() || phrasesB.isEmpty()) {
            return Pair("Loading", "content")
        }

        // Pick random A phrase
        val phraseA = phrasesA.random()

        // Get B phrases with matching first letter
        val firstLetter = phraseA.first().uppercaseChar()
        var matchingB = phrasesB.filter {
            it.first().uppercaseChar() == firstLetter
        }

        // If no matches (shouldn't happen with proper server data), use all B phrases
        if (matchingB.isEmpty()) {
            matchingB = phrasesB
        }

        // Anti-repetition: avoid last B phrase if possible
        if (matchingB.size > 1 && lastBPhrase != null) {
            val filtered = matchingB.filter { it != lastBPhrase }
            if (filtered.isNotEmpty()) {
                matchingB = filtered
            }
        }

        // Pick random matching B phrase
        val phraseB = matchingB.random()
        lastBPhrase = phraseB

        return Pair(phraseA, phraseB)
    }

    /**
     * Generates a combined phrase string
     * @return String e.g., "Buffering buffers"
     */
    fun generatePhrase(): String {
        val (a, b) = generatePair()
        return "$a $b"
    }
}
