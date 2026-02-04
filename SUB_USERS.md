# Sub-Users (Sub-Accounts) Guide

## Overview

Sub-users allow multiple people to use DuckFlix Lite under a single RD subscription. Perfect for families or households sharing one Real-Debrid account.

## How Sub-Users Work

### Inheritance
Sub-users automatically inherit from their parent:
- ✅ **RD API Key** - Uses parent's key (no separate key needed)
- ✅ **Enabled Status** - Enabled if parent is enabled
- ✅ **IP Session** - Shares parent's IP session for household use

### Independence
Sub-users have their own:
- 🔐 **Login Credentials** - Separate username/password
- 📊 **Viewing History** - Individual watch history
- ⚙️ **Preferences** - Personal settings

## Creating Sub-Users

### Via Admin Dashboard

1. **Go to Users** → Click **"Add User"**
2. **Set Parent User**: Select parent from dropdown
   - Shows as: "None - Standalone Account" or parent username
3. **Enter Username/Password**: Sub-user's login credentials
4. **RD API Key**: Optional (inherits from parent)
5. **Click Save**

### What Happens:
- Sub-user created with `parent_user_id` set
- Automatically **enabled** if parent is enabled
- Inherits parent's RD key (no need to add one)
- Can login immediately (if parent is active)

## Users Table Display

The admin dashboard shows:

| Username | Status | Type | RD Key | RD Expiry |
|----------|--------|------|--------|-----------|
| john | Active | Main | ****3FRQ | 2024-12-31 |
| jane | Active | Sub (john) | ****3FRQ | 2024-12-31 |
| kids | Active | Sub (john) | ****3FRQ | 2024-12-31 |

**Type Column**:
- **Main** - Standalone account with own RD key
- **Sub (username)** - Sub-account of parent user

## Use Cases

### Family Household
- **Parent**: Dad (john) - Has RD subscription
- **Sub-users**:
  - Mom (jane) - Uses dad's RD
  - Kids (kids) - Uses dad's RD
- All share same RD key, separate logins

### Shared Apartment
- **Parent**: Roommate 1 (alice) - Pays for RD
- **Sub-users**:
  - Roommate 2 (bob)
  - Roommate 3 (charlie)
- Everyone shares RD cost, individual accounts

## Technical Details

### Database Schema
```sql
parent_user_id INTEGER REFERENCES users(id) ON DELETE CASCADE
```

### RD Key Resolution (user-service.js)
```javascript
// If user has parent_user_id, inherit parent's RD key
if (user.parent_user_id) {
  return parent.rd_api_key;
}
return user.rd_api_key;
```

### IP Session Sharing
Sub-users use parent's user ID for session checks:
- Allows household members to watch from same IP
- Prevents "multiple concurrent streams" errors
- Maintains parent's session limits

## API Endpoints

### Create Sub-User
```bash
POST /api/admin/users
{
  "username": "jane",
  "password": "password123",
  "parentUserId": 1,  // Parent user ID
  "rdApiKey": null     // Optional - inherits from parent
}
```

### Update User Parent
```bash
PUT /api/admin/users/:id
{
  "parentUserId": 1  // Set parent (or null for standalone)
}
```

## Important Notes

### Parent Account Requirements
- Parent must have valid RD API key
- Parent must be enabled
- Parent cannot be a sub-user themselves (no nested sub-accounts)

### Sub-User Limitations
- Cannot have their own sub-users (no nesting)
- Cannot be set as admin
- Inherit parent's RD expiry status
- Disabled if parent becomes disabled

### Converting Accounts
- **Standalone → Sub**: Add parent user ID
- **Sub → Standalone**: Remove parent, add own RD key

## Troubleshooting

### Sub-User Cannot Login
**Check**:
1. Parent account is enabled
2. Parent has valid RD API key
3. Parent's RD subscription is not expired

### "Multiple Streams" Error
- Sub-users share parent's IP session limit
- RD limits concurrent streams per account
- Multiple household members may need to coordinate watching

### RD Expiry
- Sub-users show **parent's RD expiry date**
- When parent's RD expires, all sub-users are affected
- Renew parent's RD to restore access for everyone

## Best Practices

1. **One RD Per Household**: Create one parent account with RD key, add family as sub-users
2. **Clear Naming**: Use descriptive usernames (dad, mom, kids, alice-phone, etc.)
3. **Password Security**: Give each sub-user a unique password
4. **Monitor Expiry**: Check parent's RD expiry regularly
5. **Communication**: Let sub-users know when RD needs renewal

## Example Setup

### Smith Family
```
Parent: john_smith (RD: expires 2024-12-31)
├── Sub: mary_smith (wife)
├── Sub: tommy_smith (son)
└── Sub: sarah_smith (daughter)
```

**Benefits**:
- One RD subscription ($18/month)
- Four separate logins
- Individual viewing history
- Shared IP session limits

**Cost**: $4.50/person vs $18/person standalone
