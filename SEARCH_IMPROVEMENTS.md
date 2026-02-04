# Search Improvements - Applied from Main DuckFlix

## Overview
Applied working search result flow from main DuckFlix project to Lite, including garbage filtering and clean movie/TV separation with borderless rounded posters.

## Changes Made

### 1. Backend Filtering (`server/routes/search.js`)
**Location:** Line 51-62

**Filters Applied:**
- Remove results without posters (blank poster path)
- Remove very low-rated content (voteAverage < 1.0)
- Sort results by rating (highest first)

**Benefits:**
- Reduces junk results from TMDB
- Improves user experience by showing quality content first
- Server-side filtering reduces client processing

### 2. Android Frontend Filtering (`SearchViewModel.kt`)
**Location:** Line 183-203

**Filters Applied:**
- Remove results without poster images
- Remove very low-rated content (voteAverage < 1.0)
- Sort by rating descending
- Apply to both movie and TV results

**Benefits:**
- Double-layer filtering (server + client) for robustness
- Ensures quality results even if server filtering is bypassed
- Maintains separation of Movies and TV Shows sections

### 3. Borderless Rounded Poster Design (`SearchScreen.kt`)
**Location:** Line 262-306 (SearchResultCard), Line 401-442 (RecentItemCard)

**Design Changes:**
- Removed card padding and background
- Applied large rounded corners (MaterialTheme.shapes.large)
- Made poster fill the card with ContentScale.Crop
- Transparent card background for borderless appearance
- Title and year displayed below poster (not inside card)
- Focus border on poster only (4.dp primary color)

**Benefits:**
- Matches main app's clean, modern poster display
- Better visual focus on content
- Cleaner TV interface experience
- Consistent with Apple TV HIG design patterns

## Structure

The search already had proper movie/TV separation:
- SearchViewModel performs parallel searches for movies and TV
- Results stored in separate lists (movieResults, tvResults)
- UI displays in separate sections with headers ("Movies", "TV Shows")

## Testing Checklist

- [ ] Search returns only results with posters
- [ ] No extremely low-rated junk content
- [ ] Results sorted by rating (best first)
- [ ] Movies and TV shows in separate sections
- [ ] Posters have rounded corners
- [ ] No visible card borders (only focus border)
- [ ] Focus indication works with D-pad navigation
- [ ] Title and year readable below poster

## Files Modified

1. `/server/routes/search.js` - Backend filtering and sorting
2. `/android/.../SearchViewModel.kt` - Frontend filtering and sorting
3. `/android/.../SearchScreen.kt` - Borderless rounded poster UI

## Notes

- Filtering threshold set to voteAverage >= 1.0 to be lenient while removing obvious junk
- Can be adjusted if too aggressive or too lenient
- Both server and client apply same filters for consistency
- Recent searches and recently watched use same poster styling
