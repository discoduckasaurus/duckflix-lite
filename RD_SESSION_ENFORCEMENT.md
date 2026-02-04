# Real-Debrid Session Enforcement

## Overview

**CRITICAL**: This system prevents Real-Debrid account bans by ensuring no RD API key is ever used from multiple IPs simultaneously.

## How It Works

### RD/IP Combo Tracking

The system tracks **RD API Key + IP Address** combinations (not user IDs) to prevent concurrent streams:

1. **User presses play** → System checks for active RD/IP combos
2. **Check**: Is this RD key being used from a **different IP**?
   - ✅ **No**: Allow playback, create RD session
   - ❌ **Yes**: **REJECT** with error message
3. **During playback**: Heartbeat every 5 seconds updates session
4. **After playback**: Session removed 5 seconds after last heartbeat

### Why RD-Based (Not User-Based)?

**Old System (User-Based)**:
- Tracked by `user_id + IP`
- Main user (john) on IP A ✅
- Sub-user (jane) on IP B ✅
- **PROBLEM**: Both use same RD key = **RD BAN RISK** ⚠️

**New System (RD-Based)**:
- Tracks by `rd_api_key + IP`
- Main user (john) on IP A with RD key X ✅
- Sub-user (jane) on IP B with RD key X ❌ **BLOCKED**
- **RESULT**: Same RD never on 2 IPs = **NO BAN RISK** ✅

## Flow Diagram

```
User Presses Play
       ↓
Check: RD key + Current IP
       ↓
Query: SELECT * FROM rd_sessions
       WHERE rd_api_key = ?
       AND ip_address != ?
       AND last_heartbeat_at > (now - 5s)
       ↓
   ┌───────┴───────┐
   ↓               ↓
Active?          No Active
   ↓               ↓
REJECT          ALLOW
   ↓               ↓
Error Msg      Start Session
               Store: RD+IP
               Heartbeat: 5s
               Remove: After 5s idle
```

## Database Schema

### rd_sessions Table
```sql
CREATE TABLE rd_sessions (
  id INTEGER PRIMARY KEY,
  rd_api_key TEXT NOT NULL,        -- RD key (inherited for sub-users)
  ip_address TEXT NOT NULL,        -- Current IP
  user_id INTEGER NOT NULL,        -- User ID (for logging)
  username TEXT NOT NULL,          -- Username (for error messages)
  stream_started_at TEXT NOT NULL, -- When stream began
  last_heartbeat_at TEXT NOT NULL, -- Last heartbeat timestamp
  UNIQUE(rd_api_key, ip_address)   -- One session per RD+IP combo
);
```

## API Endpoints

### 1. Session Check (Before Playback)
**POST /api/vod/session/check**

**Request**: Authenticated (JWT token)

**Response**:
```json
// Success
{
  "success": true,
  "message": "Playback authorized"
}

// Rejected - Concurrent Stream
{
  "error": "Real-Debrid key in use elsewhere",
  "message": "User or Sub-User is Using This Service Elsewhere, Please Try Again Later",
  "details": {
    "activeUser": "john",
    "startedAt": "2024-12-31T12:34:56Z"
  }
}
```

**Status Codes**:
- `200` - Playback allowed
- `409` - Concurrent stream (RD in use elsewhere)
- `403` - No RD API key configured

### 2. Heartbeat (During Playback)
**POST /api/vod/session/heartbeat**

**Frequency**: Every 5 seconds during playback

**Purpose**:
- Keeps session alive
- Prevents timeout
- Resuming after pause resets timer

### 3. End Session (After Playback)
**POST /api/vod/session/end**

**When**: User stops/closes stream

**Purpose**:
- Explicitly ends session
- Allows other users/devices to start immediately
- Without this, session expires after 5s automatically

## Client Integration

### Example Flow
```javascript
// 1. Check if can play
const checkResponse = await fetch('/api/vod/session/check', {
  method: 'POST',
  headers: { 'Authorization': `Bearer ${token}` }
});

if (!checkResponse.ok) {
  const error = await checkResponse.json();
  alert(error.message); // "User or Sub-User is Using..."
  return;
}

// 2. Start playback
player.play();

// 3. Send heartbeats every 5s
const heartbeatInterval = setInterval(async () => {
  await fetch('/api/vod/session/heartbeat', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
}, 5000);

// 4. End session on stop
player.on('ended', async () => {
  clearInterval(heartbeatInterval);
  await fetch('/api/vod/session/end', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` }
  });
});
```

## Background Jobs

### Cleanup Job (Every 30 seconds)
Removes stale sessions older than 30 seconds:
```javascript
DELETE FROM rd_sessions
WHERE last_heartbeat_at < (now - 30s)
```

**Purpose**:
- Handles crashed/closed apps without proper end call
- Prevents sessions from blocking forever
- Keeps database clean

## Sub-Users & RD Key Inheritance

### How It Works
```javascript
// Sub-user inherits parent's RD key
function getUserRdApiKey(userId) {
  const user = db.get('SELECT rd_api_key, parent_user_id FROM users WHERE id = ?', userId);

  if (user.rd_api_key) {
    return user.rd_api_key; // User has own key
  }

  if (user.parent_user_id) {
    const parent = db.get('SELECT rd_api_key FROM users WHERE id = ?', user.parent_user_id);
    return parent.rd_api_key; // Inherit parent's key
  }

  return null;
}
```

### Example Scenario
**Setup**:
- Parent: john (RD key: ABC123)
- Sub-user: jane (inherits john's key)

**Playback Attempt**:
1. John plays on IP `192.168.1.5` → Session created with `ABC123 + 192.168.1.5`
2. Jane tries to play on IP `192.168.1.10` → Uses same RD key `ABC123`
3. **System checks**: `ABC123` active on different IP?
4. **Result**: ❌ **BLOCKED** - "User or Sub-User is Using This Service Elsewhere"

**Allowed Scenario**:
1. John plays on IP `192.168.1.5`
2. John's playback ends (or 5s timeout)
3. Jane plays on IP `192.168.1.10` → ✅ **ALLOWED** (no active session)

## Timing Details

### 5-Second Timeout
**Why 5 seconds?**
- Fast enough to free sessions quickly
- Long enough to handle network hiccups
- Prevents "stuck" sessions blocking playback

**Timeline**:
```
0s:  Stream starts → Session created
5s:  Heartbeat #1 → Session extended
10s: Heartbeat #2 → Session extended
15s: User closes app (no end call)
20s: Cleanup job runs → Session removed (15s + 5s = 20s)
```

### Session States
1. **Active** - Heartbeat within last 5s
2. **Expiring** - 5-30s since last heartbeat
3. **Expired** - 30s+ since last heartbeat (cleaned up)

## Error Messages

### For Users
**Message**: "User or Sub-User is Using This Service Elsewhere, Please Try Again Later"

**Meaning**: Someone (you or a family member) is already streaming with this RD account from a different location.

**Solution**:
1. Stop the other stream
2. Wait 5 seconds
3. Try again

### For Admins (Logs)
```
[RD Session] BLOCKED: jane attempted stream from 192.168.1.10,
             but RD key in use by john on 192.168.1.5
```

## Benefits

### Prevents RD Bans
- ✅ No concurrent streams from same RD
- ✅ Protects against account suspension
- ✅ Enforces RD ToS compliance

### Family-Friendly
- ✅ Clear error messages
- ✅ Fast timeout (5s)
- ✅ Works with sub-users

### Self-Healing
- ✅ Automatic cleanup of stale sessions
- ✅ Handles app crashes gracefully
- ✅ No manual intervention needed

## Monitoring

### Check Active Sessions
```sql
SELECT
  username,
  rd_api_key,
  ip_address,
  stream_started_at,
  last_heartbeat_at
FROM rd_sessions
WHERE last_heartbeat_at > datetime('now', '-30 seconds')
ORDER BY last_heartbeat_at DESC;
```

### Count Active Streams
```sql
SELECT COUNT(*) as active_streams
FROM rd_sessions
WHERE last_heartbeat_at > datetime('now', '-30 seconds');
```

## Troubleshooting

### "Session blocked but no one else is streaming"
**Cause**: Stale session from previous crash

**Solution**:
1. Wait 5-30 seconds for cleanup
2. Or manually clear: `DELETE FROM rd_sessions WHERE rd_api_key = 'YOUR_KEY'`

### Sessions not cleaning up
**Check**: Cleanup job running
```javascript
// Should see in logs every 30s:
[RD Session] Cleaned up X expired sessions
```

### Heartbeats not received
**Check**:
1. Client sending heartbeats every 5s?
2. JWT token valid?
3. Network connectivity?

## Security Notes

1. **RD Key Privacy**: Only last 6 chars logged
2. **IP Privacy**: Only shown to user as `192.168.*.*`
3. **Session Isolation**: Each RD+IP combo independent
4. **No Cross-Contamination**: User A can't see User B's sessions

## Future Enhancements

Possible improvements:
- Grace period for IP switches (roaming)
- Admin override to force-end sessions
- Session history/analytics
- Mobile network handling (IP changes)
