# Loading Phrases - Slot Machine System

## Overview

The loading screen displays two spinning "reels" that land on phrases with matching first letters, like a slot machine!

## How It Works

### Two Separate Lists

**A Phrases (Verbs)**: Action words that describe what's happening
- Examples: `Analyzing`, `Buffering`, `Calibrating`, `Downloading`

**B Phrases (Nouns)**: Things being acted upon
- Examples: `algorithms`, `buffers`, `caches`, `data`

### Matching Algorithm

When the reels stop, they display phrases where:
- **First letter matches** (case-insensitive)
- **Examples**:
  - ✅ `Analyzing algorithms` (A + a)
  - ✅ `Buffering buffers` (B + b)
  - ✅ `Calibrating caches` (C + c)
  - ❌ `Analyzing buffers` (A + b - no match!)
  - ❌ `buffers Analyzing` (wrong order!)

## Admin Interface

### Two-Column Editor

```
┌─────────────────────────┬─────────────────────────┐
│  A Phrases (Verbs)      │  B Phrases (Nouns)      │
├─────────────────────────┼─────────────────────────┤
│  Analyzing              │  algorithms             │
│  Allocating             │  architectures          │
│  Assembling             │  atoms                  │
│  Buffering              │  bits                   │
│  Bootstrapping          │  buffers                │
│  Building               │  bandwidths             │
│  Calibrating            │  caches                 │
│  ...                    │  ...                    │
└─────────────────────────┴─────────────────────────┘
```

**Format**: One phrase per line

### Validation

The system validates that:
1. Both lists are non-empty
2. Every A phrase has at least one B phrase with matching first letter
3. Returns error if validation fails

**Example Error**:
```json
{
  "error": "Each A phrase must have at least one B phrase with matching first letter",
  "unmatchedPhrases": ["Xeroxing", "Zapping"]
}
```

## API Endpoints

### GET /api/admin/loading-phrases

Returns separate A and B lists:

```json
{
  "phrasesA": ["Analyzing", "Buffering", "Calibrating", ...],
  "phrasesB": ["algorithms", "buffers", "caches", ...]
}
```

### PUT /api/admin/loading-phrases

Save updated phrases:

```json
{
  "phrasesA": ["Analyzing", "Buffering", ...],
  "phrasesB": ["algorithms", "buffers", ...]
}
```

## Client Implementation

### Basic Usage with Anti-Repetition Buffer

```javascript
// 1. Fetch phrases
const response = await fetch('/api/admin/loading-phrases');
const { phrasesA, phrasesB } = await response.json();

// 2. Create generator with buffer
class LoadingPhraseGenerator {
  constructor(phrasesA, phrasesB) {
    this.phrasesA = phrasesA;
    this.phrasesB = phrasesB;
    this.lastBPhrase = null; // Buffer to prevent consecutive repeats
  }

  getMatchingBPhrases(aPhrase) {
    const firstLetter = aPhrase.charAt(0).toUpperCase();
    return this.phrasesB.filter(b =>
      b.charAt(0).toUpperCase() === firstLetter
    );
  }

  generatePhrasePair() {
    // Pick random A phrase
    const phraseA = this.phrasesA[Math.floor(Math.random() * this.phrasesA.length)];

    // Get matching B phrases
    let matchingB = this.getMatchingBPhrases(aPhrase);

    // If we have more than one match, filter out the last used B phrase
    if (matchingB.length > 1 && this.lastBPhrase) {
      const filtered = matchingB.filter(b => b !== this.lastBPhrase);
      if (filtered.length > 0) {
        matchingB = filtered;
      }
    }

    // Pick random B phrase
    const phraseB = matchingB[Math.floor(Math.random() * matchingB.length)];

    // Update buffer
    this.lastBPhrase = phraseB;

    return { phraseA, phraseB };
  }
}

// Example
const generator = new LoadingPhraseGenerator(phrasesA, phrasesB);

// Generate multiple phrases - B phrases won't repeat consecutively
const pair1 = generator.generatePhrasePair(); // "Analyzing algorithms"
const pair2 = generator.generatePhrasePair(); // "Allocating atoms" (not algorithms!)
const pair3 = generator.generatePhrasePair(); // "Buffering buffers"
const pair4 = generator.generatePhrasePair(); // "Analyzing algorithms" (OK - not consecutive)

// ✅ Allowed: Films, Finales, Films
// ❌ Prevented: Films, Films
```

### Slot Machine Animation

```javascript
class LoadingSlotMachine {
  constructor(phrasesA, phrasesB, elementA, elementB) {
    this.phrasesA = phrasesA;
    this.phrasesB = phrasesB;
    this.elementA = elementA; // DOM element for A reel
    this.elementB = elementB; // DOM element for B reel
  }

  // Spin reel A
  spinReelA() {
    let index = 0;
    return setInterval(() => {
      this.elementA.textContent = this.phrasesA[index];
      index = (index + 1) % this.phrasesA.length;
    }, 100); // Change every 100ms
  }

  // Spin reel B
  spinReelB() {
    let index = 0;
    return setInterval(() => {
      this.elementB.textContent = this.phrasesB[index];
      index = (index + 1) % this.phrasesB.length;
    }, 120); // Slightly different speed for async effect
  }

  // Stop reels with matching pair
  async stop() {
    // Pick final A phrase
    const phraseA = this.phrasesA[Math.floor(Math.random() * this.phrasesA.length)];

    // Get matching B phrases
    const matchingB = this.getMatchingBPhrases(phraseA);
    const phraseB = matchingB[Math.floor(Math.random() * matchingB.length)];

    // Stop reel A first
    await this.stopReel(this.elementA, phraseA, 500);

    // Then stop reel B (async effect)
    await this.stopReel(this.elementB, phraseB, 800);

    return { phraseA, phraseB };
  }

  stopReel(element, finalPhrase, delay) {
    return new Promise(resolve => {
      setTimeout(() => {
        element.textContent = finalPhrase;
        element.classList.add('stopped');
        resolve();
      }, delay);
    });
  }

  getMatchingBPhrases(aPhrase) {
    const firstLetter = aPhrase.charAt(0).toUpperCase();
    return this.phrasesB.filter(b =>
      b.charAt(0).toUpperCase() === firstLetter
    );
  }
}

// Usage
const slotMachine = new LoadingSlotMachine(
  phrasesA,
  phrasesB,
  document.getElementById('reel-a'),
  document.getElementById('reel-b')
);

// Start spinning
const intervalA = slotMachine.spinReelA();
const intervalB = slotMachine.spinReelB();

// Stop after 2 seconds
setTimeout(async () => {
  clearInterval(intervalA);
  clearInterval(intervalB);

  const result = await slotMachine.stop();
  console.log(`Landed on: ${result.phraseA} ${result.phraseB}`);
}, 2000);
```

## Default Phrases

The system comes with 75+ default phrases covering A-Z:

### Coverage by Letter

| Letter | A Phrases | B Phrases | Example Pairs |
|--------|-----------|-----------|---------------|
| A | 3 | 3 | Analyzing algorithms |
| B | 3 | 3 | Buffering buffers |
| C | 3 | 3 | Calibrating caches |
| D | 3 | 3 | Downloading data |
| ... | ... | ... | ... |
| Z | 3 | 3 | Zapping zeros |

### Full Default Lists

See the API response for complete lists. All letters A-Z are covered!

## Best Practices

### 1. Balanced Lists
- Aim for multiple phrases per letter
- Ensures variety in combinations
- Example: 3-5 phrases per letter

### 2. Consistent Style
**A Phrases**: Present participle verbs (-ing form)
```
✅ Analyzing, Buffering, Calibrating
❌ Analyze, Buffer, Calibrate
```

**B Phrases**: Plural nouns or mass nouns
```
✅ algorithms, buffers, caches
❌ algorithm, a buffer, the cache
```

### 3. Technical Humor
Keep it fun and techy:
```
✅ Reticulating splines
✅ Downloading more RAM
✅ Spinning hamster wheels
❌ Doing stuff
❌ Working things
```

### 4. Length Consideration
- Keep phrases reasonably short
- Max ~20 characters for good UI display
- Avoid very long compound phrases

## Examples in the Wild

### Good Combinations
```
Analyzing algorithms
Buffering bits
Calibrating caches
Downloading data
Encrypting electrons
Fragmenting files
Generating graphics
Hashing hashes
Initializing interfaces
Juggling journeys
Knitting kernels
Loading libraries
Materializing memories
Networking nodes
Optimizing objects
Processing protocols
Quantifying queries
Reticulating splines
Syncing servers
Transmitting threads
```

### Creative Pairs
```
Downloading more RAM
Spinning hamster wheels
Reticulating splines (classic!)
Hijacking hardware
Yodeling yottabytes
Zigzagging zones
```

## Testing

### Validate Your Phrases

```bash
# Test via API
curl -X PUT https://lite.duckflix.tv/api/admin/loading-phrases \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "phrasesA": ["Analyzing", "Buffering"],
    "phrasesB": ["algorithms", "buffers"]
  }'
```

### Common Errors

**Missing Letter Coverage**:
```json
{
  "error": "Each A phrase must have at least one B phrase with matching first letter",
  "unmatchedPhrases": ["Zapping"]
}
```
Solution: Add B phrase starting with 'Z' (e.g., "zeros", "zones")

**Empty Lists**:
```json
{
  "error": "phrasesA and phrasesB must be arrays"
}
```
Solution: Ensure both lists have at least one phrase

## Future Enhancements

Possible additions:
- Themed phrase sets (holidays, seasons, events)
- Phrase statistics (most used combinations)
- Community submissions
- Multiple language support
- Sound effects on match
