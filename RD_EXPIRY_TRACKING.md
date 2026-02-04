# Real-Debrid Expiry Tracking & Account Validation

## Overview

Users must have a valid Real-Debrid API key to login. The system automatically validates RD keys, tracks expiry dates, and enforces account status.

## Features Implemented

### 1. RD API Key Validation
- When admin creates/updates a user with an RD API key, the system:
  - Validates the key against RD API
  - Fetches the subscription expiry date
  - Enables the account automatically if key is valid

### 2. Account Status Management
- **New Column**: `enabled` (BOOLEAN) - tracks if user can login
- **Default Behavior**: New users start as **disabled** (enabled=0) until they have a valid RD key
- **Admin Bypass**: Admin users can login even without RD keys

### 3. Login Validation
Users must meet these requirements to login:
- ✓ Valid username and password
- ✓ Account is enabled (has valid RD key)
- ✓ RD subscription is not expired

**Login Error Messages**:
- Account disabled: "Your account requires a valid Real-Debrid API key"
- Expired subscription: "Your Real-Debrid subscription has expired. Please renew."

### 4. Admin Dashboard UI

#### User Management Table Shows:
- **Status Badge**: "Active" (green) or "Disabled" (red)
- **RD Expiry Column**: Color-coded expiry dates
  - **Red (< 7 days)**: "Xd remaining" - Expiring soon
  - **Orange (< 30 days)**: "Xd remaining" - Warning
  - **Normal**: Full expiry date
  - **Red "Expired"**: Subscription has expired

#### Disabled User Styling:
- Greyed out rows (50% opacity)
- Muted text color
- Easy visual identification

### 5. Background Jobs

#### RD Expiry Checker (runs every 6 hours):
- Checks all users with RD API keys
- Updates expiry dates in database
- Runs on startup (after 30s delay)
- Logs results: `{checked: X, errors: Y}`

#### Job Schedule:
- **Initial run**: 30 seconds after server startup
- **Periodic runs**: Every 6 hours
- **Location**: `services/rd-expiry-checker.js`

### 6. RD Health Check
- **Test Key**: Uses `TEST_RD_API_KEY` from `.env`
- **Current Key**: Admin's RD key (PP6Q...3FRQ)
- **Status**: Shows "UP" with response time when working

## API Endpoints

### Create User (POST /api/admin/users)
```json
{
  "username": "john",
  "password": "password123",
  "rdApiKey": "YOUR_RD_API_KEY",  // Optional but recommended
  "isAdmin": false
}
```

**Behavior**:
- If `rdApiKey` provided: Validates key, sets expiry, enables account
- If no `rdApiKey`: User created but **disabled** (cannot login)
- Returns error if RD key is invalid

### Update User (PUT /api/admin/users/:id)
Same validation as create - validates RD key if provided

## Database Schema

### Users Table (New Columns):
```sql
enabled BOOLEAN DEFAULT 1          -- Can user login?
rd_expiry_date TEXT               -- ISO timestamp of RD expiry
updated_at TEXT                   -- Last update timestamp
```

## Files Created/Modified

### New Files:
- `services/rd-expiry-checker.js` - RD validation and expiry checking service

### Modified Files:
- `db/init.js` - Added `enabled` and `updated_at` columns
- `routes/admin.js` - Added RD validation to user create/update
- `routes/auth.js` - Added account status checks to login
- `index.js` - Added RD expiry background job
- `static/admin/app.js` - Added expiry status display logic
- `static/admin/styles.css` - Added status badges and expiry color coding
- `.env` - Added `TEST_RD_API_KEY`

## Testing

### Check RD Expiry Manually:
```javascript
const { checkUserRDExpiry } = require('./services/rd-expiry-checker');
await checkUserRDExpiry(userId, rdApiKey);
```

### Check All Users:
```javascript
const { checkAllUsersRDExpiry } = require('./services/rd-expiry-checker');
await checkAllUsersRDExpiry();
```

### Validate RD Key:
```javascript
const { validateRDKey } = require('./services/rd-expiry-checker');
const result = await validateRDKey('YOUR_RD_KEY');
// Returns: { valid: true, expiryDate: '2024-12-31T...', accountType: 'premium' }
```

## Environment Variables

Add to `.env`:
```bash
# RD API Testing (uses admin's key for health checks)
TEST_RD_API_KEY=PP6QMF2GWLJHC4H7G75OB466E4JBMOFSADRIDG2ZI6YHGTS63FRQ
```

## User Workflow

### New User Creation:
1. Admin creates user via dashboard
2. Admin adds RD API key (or user created without it)
3. System validates RD key automatically
4. If valid: User enabled, expiry date saved
5. If invalid: User stays disabled
6. User can now login (if enabled)

### Existing User Updates:
1. Admin updates user's RD API key
2. System validates new key
3. Updates expiry date
4. Enables/disables account based on validity

### Login Attempt:
1. User enters username/password
2. System checks: credentials → enabled status → RD expiry
3. If all pass: Login successful
4. If any fail: Appropriate error message shown

## Logs

**Example Log Output**:
```
[RD Expiry] User 2: 45 days remaining
[RD Expiry] User 3: -5 days remaining (expired)
[RD Expiry] Checked 5 users, 0 errors
```

## Future Enhancements

Possible additions:
- Email notifications for expiring subscriptions
- Auto-disable users when RD expires
- Bulk RD key import/validation
- RD key rotation for sub-accounts
