# Random Episode Button Update

## Changes Made

Updated the random episode button on TV series detail screens with improved UX.

### Before:
- Button displayed "?" character
- No tooltip or hint

### After:
- Button displays 🎰 (slot machine emoji)
- Tooltip "Play Random Episode" appears on focus (not click)

## Implementation Details

### Visual Changes

**Button Icon:**
```kotlin
Text(
    text = "🎰",  // Changed from "?"
    style = MaterialTheme.typography.headlineMedium,
    color = Color.White
)
```

**Tooltip on Focus:**
```kotlin
val interactionSource = remember { MutableInteractionSource() }
val isFocused by interactionSource.collectIsFocusedAsState()

// Tooltip appears when button is focused
if (isFocused) {
    Surface(
        modifier = Modifier
            .offset(y = (-56).dp)
            .zIndex(10f),
        color = Color.Black.copy(alpha = 0.9f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = "Play Random Episode",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
```

## User Experience

### TV Navigation Flow:
```
1. User navigates to TV series detail screen
2. User selects a season from dropdown
3. User focuses on 🎰 button (next to season selector)
4. Tooltip appears: "Play Random Episode"
5. User presses OK/Select → Plays random episode from selected season
```

### Visual Appearance:

**Unfocused:**
```
┌──────────────────────┐  ┌────┐
│ Select Season    ▼   │  │ 🎰 │
└──────────────────────┘  └────┘
```

**Focused:**
```
                          ┌─────────────────────┐
                          │ Play Random Episode │
                          └──────────┬──────────┘
┌──────────────────────┐  ┌────────┴┐
│ Select Season    ▼   │  │   🎰    │ ← Highlighted
└──────────────────────┘  └─────────┘
```

## File Modified

- `/app/src/main/java/com/duckflix/lite/ui/screens/detail/DetailScreen.kt`

## Build Status

✅ **BUILD SUCCESSFUL** - No errors or warnings

## Testing Notes

**To Test:**
1. Navigate to any TV series detail page
2. Select a season from the dropdown
3. Use D-pad to focus on the 🎰 button
4. Verify tooltip "Play Random Episode" appears above button
5. Press OK/Select to play random episode
6. Verify tooltip disappears when focus moves away

**Expected Behavior:**
- Tooltip appears only on focus (not on hover or click)
- Tooltip is positioned above the button
- Tooltip has dark background with rounded corners
- Button still functions the same (plays random episode)
- Loading spinner replaces button during episode fetch

## Notes

- The slot machine emoji (🎰) provides a clear visual metaphor for randomness
- Tooltip only appears on focus, not click, as per requirement
- Tooltip is positioned 56dp above the button to avoid overlap
- Uses `zIndex(10f)` to ensure tooltip appears above other elements
- Maintains existing functionality while improving discoverability
