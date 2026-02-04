# CRITICAL: Zurg → RD Link Resolution

## DO NOT REVERT THIS

### The Problem
- Zurg provides file lookup via WebDAV but its HTTP server has seeking limitations
- Direct Zurg URLs like `http://192.168.4.66:9999/http/__all__/...` cause `ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE` errors
- ExoPlayer needs proper HTTP range request support for seeking

### The Solution
**user-service.js MUST return RD_API_KEY from environment:**

```javascript
function getUserRdApiKey(userId) {
  return process.env.RD_API_KEY || null;  // ✓ CORRECT
  // return null;  // ✗ WRONG - causes fallback to broken Zurg HTTP URLs
}
```

### How It Works
1. Client requests stream for content
2. Server searches Zurg to find file path
3. **CRITICAL**: Server calls `resolveZurgToRdLink(filePath, rdApiKey)`
4. Resolver finds torrent in RD, gets direct download link
5. Client gets RD direct link with full HTTP range support

### Files Involved
- `/server/services/user-service.js` - Returns RD API key
- `/server/services/zurg-to-rd-resolver.js` - Resolves Zurg path to RD link
- `/server/routes/vod.js:42-65` - Calls resolver when Zurg match found

### If This Breaks Again
Check that `getUserRdApiKey()` returns `process.env.RD_API_KEY` and NOT null!
