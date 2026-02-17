// Configuration
const API_BASE = window.location.origin;
const TOKEN_KEY = 'dfl_admin_token';
const USERNAME_KEY = 'dfl_admin_username';

// State
let currentView = 'dashboard';
let currentUser = null;

// Utility: API Request Helper
let _loggingOut = false;

async function apiRequest(endpoint, options = {}) {
    const token = localStorage.getItem(TOKEN_KEY);
    const headers = {
        'Content-Type': 'application/json',
        ...options.headers
    };

    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    try {
        const response = await fetch(`${API_BASE}${endpoint}`, {
            ...options,
            headers
        });

        if (response.status === 401) {
            // Only show login screen once, silently swallow parallel 401s
            if (!_loggingOut) {
                _loggingOut = true;
                logout();
            }
            throw new Error('__session_expired__');
        }

        if (!response.ok) {
            const error = await response.json().catch(() => ({ error: 'Request failed' }));
            throw new Error(error.error || `HTTP ${response.status}`);
        }

        return await response.json();
    } catch (error) {
        console.error('API Error:', error);
        throw error;
    }
}

// Auth Functions
function isAuthenticated() {
    return !!localStorage.getItem(TOKEN_KEY);
}

function logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USERNAME_KEY);
    showScreen('login-screen');
}

async function login(username, password) {
    const data = await apiRequest('/api/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password })
    });

    if (!data.user.isAdmin) {
        throw new Error('Access denied: Admin privileges required');
    }

    _loggingOut = false;
    localStorage.setItem(TOKEN_KEY, data.token);
    localStorage.setItem(USERNAME_KEY, data.user.username);
    currentUser = data.user;

    return data;
}

// UI Functions
function showScreen(screenId) {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    document.getElementById(screenId).classList.add('active');
}

function showView(viewName) {
    currentView = viewName;
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.getElementById(`view-${viewName}`).classList.add('active');

    document.querySelectorAll('.nav-menu a').forEach(a => a.classList.remove('active'));
    document.querySelector(`.nav-menu a[data-view="${viewName}"]`).classList.add('active');
}

function isSessionExpired(error) {
    return error && error.message === '__session_expired__';
}

function showError(elementId, message) {
    if (message === '__session_expired__') return;
    const el = document.getElementById(elementId);
    el.textContent = message;
    el.style.display = 'block';
    setTimeout(() => {
        el.style.display = 'none';
    }, 5000);
}

function showModal(modalId) {
    document.getElementById(modalId).classList.add('active');
}

function hideModal(modalId) {
    document.getElementById(modalId).classList.remove('active');
}

function formatDate(dateString) {
    if (!dateString) return '-';
    const date = new Date(dateString);
    return date.toLocaleString();
}

function maskRdKey(key) {
    if (!key) return 'Not set';
    if (key.includes('*')) return key; // Already masked
    return `****${key.slice(-4)}`;
}

function getRdExpiryStatus(expiryDate) {
    if (!expiryDate) return { text: 'Not set', class: '' };

    const expiry = new Date(expiryDate);
    const now = new Date();
    const daysRemaining = Math.floor((expiry - now) / (1000 * 60 * 60 * 24));

    if (daysRemaining < 0) {
        return { text: 'Expired', class: 'expired' };
    } else if (daysRemaining < 7) {
        return { text: `${daysRemaining}d remaining`, class: 'expiring-soon' };
    } else if (daysRemaining < 30) {
        return { text: `${daysRemaining}d remaining`, class: 'expiring-warning' };
    } else {
        return { text: formatDate(expiryDate), class: '' };
    }
}

// Dashboard Functions
async function loadDashboard() {
    try {
        const data = await apiRequest('/api/admin/dashboard');

        document.getElementById('stat-users').textContent = data.userCount || 0;
        document.getElementById('stat-expiring').textContent = data.rdExpirySummary?.expiringSoon || 0;
        document.getElementById('stat-failures').textContent = data.recentFailuresCount || 0;

        const healthyServices = Object.values(data.serviceHealth || {}).filter(s => s === 'up').length;
        const totalServices = Object.keys(data.serviceHealth || {}).length;
        document.getElementById('stat-services').textContent = `${healthyServices}/${totalServices}`;
        document.getElementById('stat-services').className = healthyServices === totalServices ? 'stat-value success' : 'stat-value warning';

        // Recent failures
        const failuresHtml = (data.recentFailures || []).slice(0, 5).map(f => `
            <div class="failure-item">
                <div><strong>${f.username}</strong> - ${f.title || 'Unknown'} ${f.season ? `S${f.season}E${f.episode}` : ''}</div>
                <div class="failure-error">${f.error}</div>
                <div class="failure-time">${formatDate(f.timestamp || f.created_at)}</div>
            </div>
        `).join('') || '<p>No recent failures</p>';

        document.getElementById('recent-failures').innerHTML = failuresHtml;
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load dashboard:', error);
        showError('dashboard-error', error.message);
    }
}

// State for users list (used for parent selection dropdown)
let allUsers = [];

// Users Functions
async function loadUsers() {
    try {
        const data = await apiRequest('/api/admin/users');
        const users = data.users || [];
        allUsers = users; // Store for parent dropdown

        const tableHtml = `
            <table class="data-table">
                <thead>
                    <tr>
                        <th>ID</th>
                        <th>Username</th>
                        <th>Status</th>
                        <th>Type</th>
                        <th>Admin</th>
                        <th>RD API Key</th>
                        <th>RD Expiry</th>
                        <th>Created</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    ${users.map(u => {
                        const expiryStatus = getRdExpiryStatus(u.rdExpiryDate);
                        const isDisabled = !u.enabled && !u.isAdmin;
                        const parentUser = u.parentUserId ? users.find(p => p.id === u.parentUserId) : null;
                        const accountType = parentUser ? `Sub (${parentUser.username})` : 'Main';
                        return `
                        <tr class="${isDisabled ? 'user-disabled' : ''}">
                            <td>${u.id}</td>
                            <td>${u.username}</td>
                            <td><span class="status-badge ${u.enabled ? 'status-active' : 'status-inactive'}">${u.enabled ? 'Active' : 'Disabled'}</span></td>
                            <td><span class="account-type">${accountType}</span></td>
                            <td>${u.isAdmin ? '✓' : ''}</td>
                            <td>${maskRdKey(u.rdApiKey)}</td>
                            <td><span class="rd-expiry ${expiryStatus.class}">${expiryStatus.text}</span></td>
                            <td>${formatDate(u.createdAt)}</td>
                            <td>
                                <button class="btn btn-sm btn-secondary" onclick="editUser(${u.id})">Edit</button>
                                <button class="btn btn-sm btn-danger" onclick="deleteUser(${u.id}, '${u.username}')">Delete</button>
                            </td>
                        </tr>
                    `}).join('')}
                </tbody>
            </table>
        `;

        document.getElementById('users-table-container').innerHTML = tableHtml;
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load users:', error);
        document.getElementById('users-table-container').innerHTML = `<p class="error">Failed to load users: ${error.message}</p>`;
    }
}

function populateParentUserDropdown(excludeUserId = null) {
    const dropdown = document.getElementById('user-parent');
    dropdown.innerHTML = '<option value="">None - Standalone Account</option>';

    // Add all users except the one being edited (can't be parent of self)
    allUsers.filter(u => u.id !== excludeUserId && !u.isAdmin).forEach(u => {
        const option = document.createElement('option');
        option.value = u.id;
        option.textContent = `${u.username} (ID: ${u.id})`;
        dropdown.appendChild(option);
    });
}

async function editUser(userId) {
    try {
        const data = await apiRequest(`/api/admin/users/${userId}`);
        const user = data.user || data;

        document.getElementById('user-modal-title').textContent = 'Edit User';
        document.getElementById('user-id').value = user.id;
        document.getElementById('user-username').value = user.username;
        document.getElementById('user-password').value = '';
        document.getElementById('user-password').required = false;
        document.getElementById('password-hint').style.display = 'block';
        document.getElementById('user-rd-key').value = user.rdApiKey || user.rd_api_key || '';
        document.getElementById('user-is-admin').checked = user.isAdmin || user.is_admin;

        // Populate parent dropdown (exclude self)
        populateParentUserDropdown(user.id);
        document.getElementById('user-parent').value = user.parentUserId || '';

        showModal('user-modal');
    } catch (error) {
        if (!isSessionExpired(error)) alert('Failed to load user: ' + error.message);
    }
}

async function deleteUser(userId, username) {
    if (!confirm(`Delete user "${username}"? This cannot be undone.`)) {
        return;
    }

    try {
        await apiRequest(`/api/admin/users/${userId}`, { method: 'DELETE' });
        await loadUsers();
    } catch (error) {
        alert('Failed to delete user: ' + error.message);
    }
}

// Failures Functions
async function loadFailures() {
    try {
        const data = await apiRequest('/api/admin/failures');
        const failures = data.failures || [];

        if (failures.length === 0) {
            document.getElementById('failures-table-container').innerHTML = '<p>No playback failures recorded.</p>';
            return;
        }

        const tableHtml = `
            <table class="data-table">
                <thead>
                    <tr>
                        <th>Time</th>
                        <th>User</th>
                        <th>Content</th>
                        <th>TMDB ID</th>
                        <th>Error</th>
                    </tr>
                </thead>
                <tbody>
                    ${failures.map(f => `
                        <tr>
                            <td>${formatDate(f.timestamp || f.created_at)}</td>
                            <td>${f.username}</td>
                            <td>${f.title || 'Unknown'}${f.season ? ` S${f.season}E${f.episode}` : ''}</td>
                            <td>${f.tmdb_id || '-'}</td>
                            <td class="failure-error">${f.error}</td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
            <p style="margin-top: 1rem; color: var(--text-secondary);">Total: ${data.total || failures.length} failures</p>
        `;

        document.getElementById('failures-table-container').innerHTML = tableHtml;
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load failures:', error);
        document.getElementById('failures-table-container').innerHTML = `<p class="error">Failed to load failures: ${error.message}</p>`;
    }
}

// Health Functions
async function loadHealth() {
    try {
        const data = await apiRequest('/api/admin/health');
        const services = data.services || [];

        if (services.length === 0) {
            document.getElementById('health-status-container').innerHTML = '<p>No service health data available.</p>';
            return;
        }

        const healthHtml = services.map(service => `
            <div class="health-card ${service.status}">
                <div class="health-service">${service.name}</div>
                <div class="health-status">${service.status.toUpperCase()}</div>
                ${service.message ? `<div class="health-message">${service.message}</div>` : ''}
                ${service.responseTime ? `<div class="health-time">Response: ${service.responseTime}ms</div>` : ''}
                ${service.lastChecked ? `<div class="health-time">Last checked: ${formatDate(service.lastChecked)}</div>` : ''}
            </div>
        `).join('');

        document.getElementById('health-status-container').innerHTML = healthHtml;
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load health:', error);
        document.getElementById('health-status-container').innerHTML = `<p class="error">Failed to load health status: ${error.message}</p>`;
    }
}

// Loading Phrases Functions
async function loadLoadingPhrases() {
    try {
        const data = await apiRequest('/api/admin/loading-phrases');
        const phrasesA = data.phrasesA || [];
        const phrasesB = data.phrasesB || [];

        const phrasesHtml = `
            <div class="phrases-editor">
                <p><strong>How it works:</strong> The loading screen spins two reels that land on phrases with matching first letters.</p>
                <p>Examples: <code>Analyzing algorithms</code>, <code>Buffering buffers</code>, <code>Calibrating caches</code></p>
                <div class="phrases-grid">
                    <div class="phrases-column">
                        <h3>A Phrases (Verbs)</h3>
                        <p class="help-text">One phrase per line. Will pair with B phrases that start with the same letter.</p>
                        <textarea id="phrases-a-textarea" rows="20" placeholder="Analyzing&#10;Buffering&#10;Calibrating&#10;Downloading&#10;...">${phrasesA.join('\n')}</textarea>
                    </div>
                    <div class="phrases-column">
                        <h3>B Phrases (Nouns)</h3>
                        <p class="help-text">One phrase per line. Should have at least one per A phrase's first letter.</p>
                        <textarea id="phrases-b-textarea" rows="20" placeholder="algorithms&#10;buffers&#10;caches&#10;data&#10;...">${phrasesB.join('\n')}</textarea>
                    </div>
                </div>
                <div id="phrases-validation" class="validation-message"></div>
            </div>
        `;

        document.getElementById('loading-phrases-container').innerHTML = phrasesHtml;

        // Also load scheduled phrases
        loadScheduledPhrases();
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load loading phrases:', error);
        document.getElementById('loading-phrases-container').innerHTML = `<p class="error">Failed to load loading phrases: ${error.message}</p>`;
    }
}

async function saveLoadingPhrases() {
    try {
        const textA = document.getElementById('phrases-a-textarea').value;
        const textB = document.getElementById('phrases-b-textarea').value;

        const phrasesA = textA.split('\n').filter(l => l.trim()).map(l => l.trim());
        const phrasesB = textB.split('\n').filter(l => l.trim()).map(l => l.trim());

        if (phrasesA.length === 0 || phrasesB.length === 0) {
            alert('Both A and B phrases are required!');
            return;
        }

        await apiRequest('/api/admin/loading-phrases', {
            method: 'PUT',
            body: JSON.stringify({ phrasesA, phrasesB })
        });

        alert('Loading phrases saved successfully!');
    } catch (error) {
        const validationDiv = document.getElementById('phrases-validation');
        if (validationDiv) {
            validationDiv.textContent = error.message;
            validationDiv.style.display = 'block';
        }
        alert('Failed to save loading phrases: ' + error.message);
    }
}

// =========================================
// Scheduled Loading Phrases
// =========================================

let allSchedules = [];
let scheduleHolidays = [];
let scheduleFormVisible = false;
let editingScheduleId = null;
let scheduleSelectedUserIds = new Set();

async function loadScheduledPhrases() {
    try {
        const data = await apiRequest('/api/admin/loading-phrases/schedules');
        allSchedules = data.schedules || [];
        scheduleHolidays = data.holidays || [];
        renderScheduledPhrases();
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load scheduled phrases:', error);
        document.getElementById('scheduled-phrases-container').innerHTML =
            `<p class="error">Failed to load scheduled phrases: ${error.message}</p>`;
    }
}

function renderScheduledPhrases() {
    const container = document.getElementById('scheduled-phrases-container');

    const cardsHtml = allSchedules.length > 0
        ? allSchedules.map(s => {
            const typeMeta = s.schedule_type === 'holiday'
                ? `Holiday: ${s.holidayName || s.holiday}`
                : `Date: ${formatScheduleDate(s.scheduled_date)}${s.repeat_type !== 'none' ? `, ${capitalize(s.repeat_type)}` : ''}`;

            const usersMeta = s.targetUsers
                ? `Users: ${s.targetUsers.map(u => u.username).join(', ')}`
                : 'All Users';

            const pairMode = s.ordered_pairs ? 'ordered' : 'letter-match';
            const phraseCounts = `${s.phrases_a.length} A, ${s.phrases_b.length} B (${pairMode})`;

            return `
                <div class="schedule-card ${s.is_enabled ? '' : 'disabled'}">
                    <div class="schedule-card-info">
                        <div class="schedule-card-name">${escapeHtml(s.name)}</div>
                        <div class="schedule-card-meta">
                            <span>${typeMeta}</span>
                            <span>${usersMeta}</span>
                            <span>${phraseCounts} phrases</span>
                        </div>
                    </div>
                    <div class="schedule-card-actions">
                        <span class="schedule-enabled-badge ${s.is_enabled ? 'on' : 'off'}">${s.is_enabled ? 'Enabled' : 'Disabled'}</span>
                        <button class="btn btn-sm btn-secondary" onclick="editSchedule(${s.id})">Edit</button>
                        <button class="btn btn-sm btn-danger" onclick="deleteSchedule(${s.id}, '${escapeHtml(s.name).replace(/'/g, "\\'")}')">Delete</button>
                    </div>
                </div>
            `;
        }).join('')
        : '<div class="empty-schedules">No scheduled phrases yet. Add one to override default phrases on specific dates or holidays.</div>';

    const formHtml = scheduleFormVisible ? renderScheduleForm() : '';

    container.innerHTML = `
        <div class="scheduled-phrases-section">
            <div class="section-header">
                <h2>Scheduled Phrases</h2>
                ${!scheduleFormVisible ? '<button class="btn btn-primary btn-sm" onclick="showScheduleForm()">+ Add Schedule</button>' : ''}
            </div>
            <div class="schedule-list">${cardsHtml}</div>
            ${formHtml}
        </div>
    `;
}

function renderScheduleForm() {
    const s = editingScheduleId ? allSchedules.find(x => x.id === editingScheduleId) : null;
    const isDate = s ? s.schedule_type === 'date' : true;

    const holidayOptions = scheduleHolidays.map(h =>
        `<option value="${h.key}" ${s && s.holiday === h.key ? 'selected' : ''}>${h.name}</option>`
    ).join('');

    const usersHtml = allUsers.length > 0
        ? allUsers.map(u => {
            const selected = scheduleSelectedUserIds.has(u.id);
            return `<span class="schedule-user-chip ${selected ? 'selected' : ''}" onclick="toggleScheduleUser(${u.id})" data-user-id="${u.id}">${u.username}</span>`;
        }).join('')
        : '<span style="color:var(--text-secondary);font-size:0.8rem;">Load Users view first to select specific users</span>';

    return `
        <div class="schedule-form">
            <h3>${editingScheduleId ? 'Edit Schedule' : 'New Schedule'}</h3>
            <div class="schedule-form-grid">
                <div class="form-group">
                    <label>Name</label>
                    <input type="text" id="schedule-name" value="${s ? escapeHtml(s.name) : ''}" placeholder="e.g., Christmas Phrases">
                </div>
                <div class="form-group">
                    <label>Type</label>
                    <div class="schedule-type-toggle">
                        <label><input type="radio" name="schedule-type" value="date" ${isDate ? 'checked' : ''} onchange="onScheduleTypeChange()"> Date</label>
                        <label><input type="radio" name="schedule-type" value="holiday" ${!isDate ? 'checked' : ''} onchange="onScheduleTypeChange()"> Holiday</label>
                    </div>
                </div>
                <div id="schedule-date-fields" class="schedule-conditional ${isDate ? 'active' : ''}">
                    <div class="form-group">
                        <label>Date</label>
                        <input type="date" id="schedule-date" value="${s && s.scheduled_date ? s.scheduled_date : ''}">
                    </div>
                    <div class="form-group">
                        <label>Repeat</label>
                        <select id="schedule-repeat">
                            <option value="none" ${!s || s.repeat_type === 'none' ? 'selected' : ''}>None (one-time)</option>
                            <option value="yearly" ${s && s.repeat_type === 'yearly' ? 'selected' : ''}>Yearly</option>
                            <option value="monthly" ${s && s.repeat_type === 'monthly' ? 'selected' : ''}>Monthly</option>
                            <option value="weekly" ${s && s.repeat_type === 'weekly' ? 'selected' : ''}>Weekly</option>
                        </select>
                    </div>
                </div>
                <div id="schedule-holiday-field" class="schedule-conditional ${!isDate ? 'active' : ''}">
                    <div class="form-group">
                        <label>Holiday</label>
                        <select id="schedule-holiday">
                            ${holidayOptions}
                        </select>
                    </div>
                </div>
                <div class="form-group schedule-form-full">
                    <label>Target Users <small style="font-weight:400;color:var(--text-secondary);">(leave empty for all users)</small></label>
                    <div class="schedule-users-list">${usersHtml}</div>
                </div>
                <div class="form-group schedule-form-full">
                    <label>Options</label>
                    <div style="display:flex;gap:1.5rem;flex-wrap:wrap;">
                        <label style="font-weight:400;"><input type="checkbox" id="schedule-enabled" ${!s || s.is_enabled ? 'checked' : ''}> Active</label>
                        <label style="font-weight:400;" title="When enabled, A[1] pairs with B[1], A[2] with B[2], etc. First-letter matching is skipped."><input type="checkbox" id="schedule-ordered-pairs" ${s && s.ordered_pairs ? 'checked' : ''}> Ordered pairs (A and B match by position, not first letter)</label>
                    </div>
                </div>
            </div>
            <div class="schedule-phrases-grid">
                <div>
                    <label style="font-weight:500;font-size:0.875rem;margin-bottom:0.35rem;display:block;">A Phrases (one per line)</label>
                    <textarea id="schedule-phrases-a" rows="8" placeholder="Decorating&#10;Wrapping&#10;...">${s ? s.phrases_a.join('\n') : ''}</textarea>
                </div>
                <div>
                    <label style="font-weight:500;font-size:0.875rem;margin-bottom:0.35rem;display:block;">B Phrases (one per line)</label>
                    <textarea id="schedule-phrases-b" rows="8" placeholder="decorations&#10;wreaths&#10;...">${s ? s.phrases_b.join('\n') : ''}</textarea>
                </div>
            </div>
            <div class="schedule-form-actions">
                <button class="btn btn-secondary btn-sm" onclick="hideScheduleForm()">Cancel</button>
                <button class="btn btn-primary btn-sm" onclick="saveSchedule()">Save Schedule</button>
            </div>
        </div>
    `;
}

function showScheduleForm(scheduleId) {
    editingScheduleId = scheduleId || null;
    scheduleFormVisible = true;

    // Pre-select users if editing
    scheduleSelectedUserIds.clear();
    if (editingScheduleId) {
        const s = allSchedules.find(x => x.id === editingScheduleId);
        if (s && s.target_user_ids) {
            s.target_user_ids.forEach(id => scheduleSelectedUserIds.add(id));
        }
    }

    // Ensure users are loaded for the chips
    if (allUsers.length === 0) {
        apiRequest('/api/admin/users').then(data => {
            allUsers = data.users || [];
            renderScheduledPhrases();
        }).catch(() => renderScheduledPhrases());
    } else {
        renderScheduledPhrases();
    }
}

function hideScheduleForm() {
    scheduleFormVisible = false;
    editingScheduleId = null;
    scheduleSelectedUserIds.clear();
    renderScheduledPhrases();
}

function onScheduleTypeChange() {
    const type = document.querySelector('input[name="schedule-type"]:checked').value;
    document.getElementById('schedule-date-fields').className = `schedule-conditional ${type === 'date' ? 'active' : ''}`;
    document.getElementById('schedule-holiday-field').className = `schedule-conditional ${type === 'holiday' ? 'active' : ''}`;
}

function toggleScheduleUser(userId) {
    if (scheduleSelectedUserIds.has(userId)) {
        scheduleSelectedUserIds.delete(userId);
    } else {
        scheduleSelectedUserIds.add(userId);
    }
    // Toggle chip class without full re-render
    const chip = document.querySelector(`.schedule-user-chip[data-user-id="${userId}"]`);
    if (chip) chip.classList.toggle('selected');
}

async function saveSchedule() {
    const name = document.getElementById('schedule-name').value.trim();
    const scheduleType = document.querySelector('input[name="schedule-type"]:checked').value;
    const scheduledDate = document.getElementById('schedule-date').value;
    const holiday = document.getElementById('schedule-holiday').value;
    const repeatType = document.getElementById('schedule-repeat').value;
    const isEnabled = document.getElementById('schedule-enabled').checked;
    const orderedPairs = document.getElementById('schedule-ordered-pairs').checked;

    const phrasesAText = document.getElementById('schedule-phrases-a').value;
    const phrasesBText = document.getElementById('schedule-phrases-b').value;
    const phrasesA = phrasesAText.split('\n').filter(l => l.trim()).map(l => l.trim());
    const phrasesB = phrasesBText.split('\n').filter(l => l.trim()).map(l => l.trim());

    if (!name) {
        showToast('Name is required', 'error');
        return;
    }

    if (phrasesA.length === 0 || phrasesB.length === 0) {
        showToast('Both A and B phrases are required', 'error');
        return;
    }

    const payload = {
        name,
        scheduleType,
        scheduledDate: scheduleType === 'date' ? scheduledDate : null,
        holiday: scheduleType === 'holiday' ? holiday : null,
        repeatType: scheduleType === 'date' ? repeatType : 'none',
        phrasesA,
        phrasesB,
        targetUserIds: scheduleSelectedUserIds.size > 0 ? [...scheduleSelectedUserIds] : null,
        isEnabled,
        orderedPairs
    };

    try {
        if (editingScheduleId) {
            await apiRequest(`/api/admin/loading-phrases/schedules/${editingScheduleId}`, {
                method: 'PUT',
                body: JSON.stringify(payload)
            });
            showToast('Schedule updated!', 'success');
        } else {
            await apiRequest('/api/admin/loading-phrases/schedules', {
                method: 'POST',
                body: JSON.stringify(payload)
            });
            showToast('Schedule created!', 'success');
        }

        hideScheduleForm();
        await loadScheduledPhrases();
    } catch (error) {
        showToast('Failed to save schedule: ' + error.message, 'error');
    }
}

function editSchedule(id) {
    showScheduleForm(id);
}

async function deleteSchedule(id, name) {
    if (!confirm(`Delete schedule "${name}"? This cannot be undone.`)) return;

    try {
        await apiRequest(`/api/admin/loading-phrases/schedules/${id}`, { method: 'DELETE' });
        showToast('Schedule deleted', 'success');
        await loadScheduledPhrases();
    } catch (error) {
        showToast('Failed to delete schedule: ' + error.message, 'error');
    }
}

function formatScheduleDate(dateStr) {
    if (!dateStr) return '-';
    const d = new Date(dateStr + 'T00:00:00');
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function capitalize(str) {
    return str.charAt(0).toUpperCase() + str.slice(1);
}

// =========================================
// Live TV Channel Management
// =========================================
let allAdminChannels = [];
let channelChanges = new Map(); // channelId -> { displayName, isEnabled, sortOrder }
let logoUploadTarget = null; // channelId for pending logo upload
let draggedChannelId = null; // for drag-and-drop reordering

async function loadLiveTVChannels() {
    try {
        const data = await apiRequest('/api/admin/channels');
        allAdminChannels = data.channels || [];
        channelChanges.clear();
        renderChannelList();
        updateChannelStats();
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load channels:', error);
        document.getElementById('channels-table-container').innerHTML =
            `<p class="error">Failed to load channels: ${error.message}</p>`;
    }
}

function getFilteredChannels() {
    const search = (document.getElementById('channel-search')?.value || '').toLowerCase();
    const sourceFilter = document.getElementById('channel-source-filter')?.value || 'all';
    const statusFilter = document.getElementById('channel-status-filter')?.value || 'all';

    return allAdminChannels.filter(ch => {
        // Search filter
        if (search) {
            const haystack = `${ch.displayName} ${ch.name} ${ch.id} ${ch.group}`.toLowerCase();
            if (!haystack.includes(search)) return false;
        }

        // Source filter
        if (sourceFilter === 'has-cdn') {
            if (!ch.hasCDN) return false;
        } else if (sourceFilter === 'no-cdn') {
            if (ch.hasCDN) return false;
        } else if (sourceFilter === 'has-ntv') {
            if (!ch.hasNTV) return false;
        } else if (sourceFilter === 'failover') {
            const idx = ch.activeSourceIndex || 0;
            if (idx === 0 || (ch.streamSources || []).length <= 1) return false;
        } else if (sourceFilter !== 'all' && ch.source !== sourceFilter) {
            return false;
        }

        // Status filter
        const change = channelChanges.get(ch.id);
        const isEnabled = change?.isEnabled !== undefined ? change.isEnabled : ch.isEnabled;
        if (statusFilter === 'enabled' && !isEnabled) return false;
        if (statusFilter === 'disabled' && isEnabled) return false;
        if (statusFilter === 'new' && ch.hasMetadata) return false;

        return true;
    }).sort((a, b) => {
        // Disabled channels go to the bottom
        const aChange = channelChanges.get(a.id);
        const bChange = channelChanges.get(b.id);
        const aEnabled = aChange?.isEnabled !== undefined ? aChange.isEnabled : a.isEnabled;
        const bEnabled = bChange?.isEnabled !== undefined ? bChange.isEnabled : b.isEnabled;
        if (aEnabled && !bEnabled) return -1;
        if (!aEnabled && bEnabled) return 1;
        // Then by sort order
        const aOrder = a.sortOrder;
        const bOrder = b.sortOrder;
        if (aOrder !== bOrder) return aOrder - bOrder;
        return (a.displayName || '').localeCompare(b.displayName || '');
    });
}

function renderChannelList() {
    const filtered = getFilteredChannels();
    const container = document.getElementById('channels-table-container');

    if (filtered.length === 0) {
        container.innerHTML = '<p style="padding:2rem;color:var(--text-secondary);">No channels match your filters.</p>';
        return;
    }

    // Build overall position map from ALL enabled channels (not just filtered)
    const allEnabled = allAdminChannels.filter(ch => {
        const change = channelChanges.get(ch.id);
        return change?.isEnabled !== undefined ? change.isEnabled : ch.isEnabled;
    }).sort((a, b) => {
        const aOrder = channelChanges.get(a.id)?.sortOrder ?? a.sortOrder;
        const bOrder = channelChanges.get(b.id)?.sortOrder ?? b.sortOrder;
        if (aOrder !== bOrder) return aOrder - bOrder;
        return (a.displayName || '').localeCompare(b.displayName || '');
    });
    const overallPositionMap = new Map();
    allEnabled.forEach((ch, i) => overallPositionMap.set(ch.id, i + 1));

    const headerHtml = `
        <div class="channel-header-row">
            <span></span>
            <span>On</span>
            <span>Logo</span>
            <span>Channel</span>
            <span>Sources</span>
            <span>#</span>
        </div>
    `;

    const rowsHtml = filtered.map((ch, index) => {
        const change = channelChanges.get(ch.id);
        const isEnabled = change?.isEnabled !== undefined ? change.isEnabled : ch.isEnabled;
        const position = isEnabled ? overallPositionMap.get(ch.id) : null;
        const displayName = change?.displayName !== undefined ? change.displayName : ch.displayName;

        const disabledClass = !isEnabled ? 'disabled-row' : '';
        const newClass = !ch.hasMetadata ? 'new-row' : '';

        // Build source chain display from streamSources array
        const sources = ch.streamSources || [];
        const activeIdx = ch.activeSourceIndex || 0;
        const isFailover = activeIdx > 0 && sources.length > 1;

        // Source chain: show ordered labels with active highlighted
        const sourceChainHtml = sources.map((s, i) => {
            const short = s.label === 'TVPass' ? 'TVP'
                : s.label === 'Backup' ? 'BKP'
                : s.label;
            const cls = i === activeIdx
                ? (isFailover ? 'source-chain-failover' : 'source-chain-active')
                : 'source-chain-inactive';
            return `<span class="source-chain-item ${cls}" title="${s.label} (#${i + 1})">${short}</span>`;
        }).join('<span class="source-chain-arrow">&rarr;</span>');

        const logoHtml = ch.logo
            ? `<img class="channel-logo" src="${ch.logo}" alt="" title="Click to change logo" onclick="triggerLogoUpload('${ch.id}')" onerror="this.outerHTML='<div class=\\'channel-logo-placeholder\\' onclick=\\'triggerLogoUpload(&quot;${ch.id}&quot;)\\'>+</div>'">`
            : `<div class="channel-logo-placeholder" onclick="triggerLogoUpload('${ch.id}')" title="Click to upload logo">+</div>`;

        const positionHtml = isEnabled
            ? `<input type="number" class="channel-order-input" value="${position}" min="1" onchange="onChannelReorder('${ch.id}', parseInt(this.value))">`
            : `<span class="channel-order-disabled">-</span>`;

        return `
            <div class="channel-row ${disabledClass} ${newClass}" draggable="true"
                data-channel-id="${ch.id}"
                ondragstart="onRowDragStart(event, '${ch.id}')"
                ondragover="onRowDragOver(event)"
                ondrop="onRowDrop(event, '${ch.id}')"
                ondragend="onRowDragEnd(event)">
                <div class="drag-handle" title="Drag to reorder">&#x2630;</div>
                <input type="checkbox" class="channel-enable-toggle"
                    ${isEnabled ? 'checked' : ''}
                    onchange="onChannelToggle('${ch.id}', this.checked)">
                <div class="channel-logo-cell">${logoHtml}</div>
                <div class="channel-info">
                    <input type="text" class="channel-display-name"
                        value="${escapeHtml(displayName)}"
                        data-original="${escapeHtml(ch.displayName)}"
                        onchange="onChannelRename('${ch.id}', this.value)">
                    <div class="channel-meta">
                        <span class="channel-group-text">${escapeHtml(ch.group)}</span>
                        <span style="color:#94a3b8">${ch.id}</span>
                    </div>
                </div>
                <div class="channel-source-chain">${sourceChainHtml || '<span style="color:#94a3b8">-</span>'}</div>
                ${positionHtml}
            </div>
        `;
    }).join('');

    // Preserve scroll position across re-renders
    const scrollEl = container.querySelector('.channel-list-container');
    const scrollTop = scrollEl ? scrollEl.scrollTop : 0;
    container.innerHTML = `<div class="channel-list-container">${headerHtml}${rowsHtml}</div>`;
    const newScrollEl = container.querySelector('.channel-list-container');
    if (newScrollEl) newScrollEl.scrollTop = scrollTop;
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML.replace(/"/g, '&quot;');
}

function updateChannelStats() {
    const total = allAdminChannels.length;
    const enabled = allAdminChannels.filter(ch => {
        const change = channelChanges.get(ch.id);
        return change?.isEnabled !== undefined ? change.isEnabled : ch.isEnabled;
    }).length;
    const withNTV = allAdminChannels.filter(ch => ch.hasNTV).length;
    const withCDN = allAdminChannels.filter(ch => ch.hasCDN).length;
    const newCount = allAdminChannels.filter(ch => !ch.hasMetadata).length;

    // Count how many are currently on each source (active index)
    let onCDN = 0, onNTV = 0, onTVP = 0, onFailover = 0;
    for (const ch of allAdminChannels) {
        const sources = ch.streamSources || [];
        const activeIdx = ch.activeSourceIndex || 0;
        const activeLabel = sources[activeIdx]?.label;
        if (activeLabel === 'CDN') onCDN++;
        else if (activeLabel === 'NTV') onNTV++;
        else if (activeLabel === 'TVPass') onTVP++;
        if (activeIdx > 0 && sources.length > 1) onFailover++;
    }

    const statsEl = document.getElementById('channel-stats');
    if (statsEl) {
        statsEl.innerHTML = `${enabled}/${total} enabled | ${withCDN} CDN, ${withNTV} NTV` +
            ` | Active: ${onCDN} CDN, ${onNTV} NTV, ${onTVP} TVP` +
            (onFailover > 0 ? ` | <strong style="color:var(--warning-color)">${onFailover} on failover</strong>` : '') +
            (newCount > 0 ? ` | <strong style="color:var(--warning-color)">${newCount} new</strong>` : '') +
            (channelChanges.size > 0 ? ` | <strong style="color:var(--primary-color)">${channelChanges.size} unsaved</strong>` : '');
    }
}

function onChannelToggle(channelId, enabled) {
    const existing = channelChanges.get(channelId) || {};
    channelChanges.set(channelId, { ...existing, isEnabled: enabled });
    renderChannelList();
    updateChannelStats();
}

function onChannelRename(channelId, newName) {
    const existing = channelChanges.get(channelId) || {};
    channelChanges.set(channelId, { ...existing, displayName: newName });
    updateChannelStats();
}

/**
 * Get all enabled channels sorted by current sort order (including pending changes).
 */
function getAllEnabledSorted() {
    return allAdminChannels.filter(ch => {
        const change = channelChanges.get(ch.id);
        return change?.isEnabled !== undefined ? change.isEnabled : ch.isEnabled;
    }).sort((a, b) => {
        const aOrder = channelChanges.get(a.id)?.sortOrder ?? a.sortOrder;
        const bOrder = channelChanges.get(b.id)?.sortOrder ?? b.sortOrder;
        if (aOrder !== bOrder) return aOrder - bOrder;
        return (a.displayName || '').localeCompare(b.displayName || '');
    });
}

/**
 * Move a channel to a 1-based position in the OVERALL enabled list.
 * Works correctly even when searching — positions are always global.
 */
function moveChannelToPosition(channelId, targetPos) {
    const allEnabled = getAllEnabledSorted();
    const fromIdx = allEnabled.findIndex(ch => ch.id === channelId);
    if (fromIdx === -1) return;

    const toIdx = Math.max(0, Math.min(allEnabled.length - 1, targetPos - 1));
    if (fromIdx === toIdx) return;

    // Move channel in the full sorted array
    const [moved] = allEnabled.splice(fromIdx, 1);
    allEnabled.splice(toIdx, 0, moved);

    // Reassign sequential sort_order (1-based) to ALL enabled channels
    allEnabled.forEach((ch, i) => {
        const newOrder = i + 1;
        const orig = allAdminChannels.find(c => c.id === ch.id);
        if (orig && orig.sortOrder !== newOrder) {
            const existing = channelChanges.get(ch.id) || {};
            channelChanges.set(ch.id, { ...existing, sortOrder: newOrder });
            orig.sortOrder = newOrder;
        }
    });

    renderChannelList();
    updateChannelStats();
}

function onChannelReorder(channelId, newPosition) {
    moveChannelToPosition(channelId, newPosition);
}

// ---- Drag and Drop ----

function onRowDragStart(e, channelId) {
    draggedChannelId = channelId;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', channelId);
    setTimeout(() => {
        const row = e.target.closest('.channel-row');
        if (row) row.classList.add('dragging');
    }, 0);
}

function onRowDragOver(e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';

    // Auto-scroll when dragging near top/bottom edges of the scrollable list
    const scrollEl = document.querySelector('.channel-list-container');
    if (scrollEl) {
        const rect = scrollEl.getBoundingClientRect();
        const edgeZone = 60; // px from edge to trigger scroll
        const scrollSpeed = 12;
        if (e.clientY < rect.top + edgeZone) {
            scrollEl.scrollTop -= scrollSpeed;
        } else if (e.clientY > rect.bottom - edgeZone) {
            scrollEl.scrollTop += scrollSpeed;
        }
    }

    const row = e.target.closest('.channel-row');
    if (!row || row.dataset.channelId === draggedChannelId) return;

    // Clear old indicators
    document.querySelectorAll('.drag-over-above, .drag-over-below')
        .forEach(el => el.classList.remove('drag-over-above', 'drag-over-below'));

    // Show indicator above or below based on mouse position
    const rect = row.getBoundingClientRect();
    if (e.clientY < rect.top + rect.height / 2) {
        row.classList.add('drag-over-above');
    } else {
        row.classList.add('drag-over-below');
    }
}

function onRowDrop(e, targetChannelId) {
    e.preventDefault();
    if (!draggedChannelId || draggedChannelId === targetChannelId) {
        onRowDragEnd(e);
        return;
    }

    // Use overall positions so drag works correctly even when searching
    const allEnabled = getAllEnabledSorted();
    const fromIdx = allEnabled.findIndex(ch => ch.id === draggedChannelId);
    const toIdx = allEnabled.findIndex(ch => ch.id === targetChannelId);

    if (fromIdx === -1 || toIdx === -1) {
        onRowDragEnd(e);
        return;
    }

    // Insert above or below target based on mouse position
    const row = e.target.closest('.channel-row');
    const rect = row.getBoundingClientRect();
    const insertBelow = e.clientY > rect.top + rect.height / 2;
    let targetPosition = insertBelow ? toIdx + 2 : toIdx + 1; // 1-based
    // Adjust if dragging downward (removing from above shifts indices)
    if (fromIdx < toIdx) targetPosition--;

    moveChannelToPosition(draggedChannelId, targetPosition);
    onRowDragEnd(e);
}

function onRowDragEnd(e) {
    draggedChannelId = null;
    document.querySelectorAll('.dragging, .drag-over-above, .drag-over-below')
        .forEach(el => el.classList.remove('dragging', 'drag-over-above', 'drag-over-below'));
}

function triggerLogoUpload(channelId) {
    logoUploadTarget = channelId;
    document.getElementById('logo-upload-input').click();
}

async function handleLogoUpload(file) {
    if (!logoUploadTarget || !file) return;

    const channelId = logoUploadTarget;
    logoUploadTarget = null;

    try {
        const formData = new FormData();
        formData.append('logo', file);

        const token = localStorage.getItem(TOKEN_KEY);
        const response = await fetch(`${API_BASE}/api/admin/channels/${encodeURIComponent(channelId)}/logo`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` },
            body: formData
        });

        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Upload failed' }));
            throw new Error(err.error);
        }

        const data = await response.json();

        // Update the channel in memory and re-render the row
        const ch = allAdminChannels.find(c => c.id === channelId);
        if (ch) ch.logo = data.logoUrl + '?t=' + Date.now();
        renderChannelList();
        showToast('Logo uploaded!', 'success');
    } catch (error) {
        showToast('Logo upload failed: ' + error.message, 'error');
    }
}

async function saveAllChannelChanges() {
    if (channelChanges.size === 0) {
        showToast('No changes to save', 'success');
        return;
    }

    try {
        const channels = [];
        for (const [id, changes] of channelChanges) {
            const original = allAdminChannels.find(ch => ch.id === id) || {};
            channels.push({
                id,
                displayName: changes.displayName !== undefined ? changes.displayName : original.displayName,
                isEnabled: changes.isEnabled !== undefined ? changes.isEnabled : original.isEnabled,
                sortOrder: changes.sortOrder !== undefined ? changes.sortOrder : original.sortOrder
            });
        }

        await apiRequest('/api/admin/channels/batch', {
            method: 'PUT',
            body: JSON.stringify({ channels })
        });

        showToast(`Saved ${channels.length} channel changes!`, 'success');

        // Reload fresh data
        await loadLiveTVChannels();
    } catch (error) {
        showToast('Save failed: ' + error.message, 'error');
    }
}

async function seedCabernetLogos() {
    try {
        const data = await apiRequest('/api/admin/channels/seed-cabernet-logos', {
            method: 'POST'
        });
        showToast(`Auto-matched ${data.matched}/${data.total} channel logos`, 'success');
        await loadLiveTVChannels();
    } catch (error) {
        showToast('Auto-match failed: ' + error.message, 'error');
    }
}

async function resetAllSources() {
    try {
        const data = await apiRequest('/api/admin/channels/reset-sources', { method: 'POST' });
        showToast(`Reset ${data.resetCount} channel sources back to primary`);
        loadLiveTVChannels();
    } catch (error) {
        showToast('Failed to reset sources: ' + error.message, 'error');
    }
}

// =========================================
// Provider Management
// =========================================
let allProviders = [];
let providerChanges = new Map(); // providerId -> { displayName, isEnabled, sortOrder }
let providerLogoUploadTarget = null;
let draggedProviderId = null;
let providerBannerDismissed = false;

async function loadProviders() {
    try {
        const data = await apiRequest('/api/admin/providers');
        allProviders = data.providers || [];
        providerChanges.clear();
        renderProviderList();
        updateProviderStats();

        // Show banner if new providers were auto-disabled
        if (data.newCount > 0 && !providerBannerDismissed) {
            const banner = document.getElementById('new-providers-banner');
            const msg = document.getElementById('new-providers-message');
            msg.textContent = `${data.newCount} new provider(s) were added and auto-disabled. Review and enable them below.`;
            banner.style.display = 'flex';
        }
    } catch (error) {
        if (isSessionExpired(error)) return;
        console.error('Failed to load providers:', error);
        document.getElementById('providers-table-container').innerHTML =
            `<p class="error">Failed to load providers: ${error.message}</p>`;
    }
}

function dismissProviderBanner() {
    document.getElementById('new-providers-banner').style.display = 'none';
    providerBannerDismissed = true;
}

function getFilteredProviders() {
    const search = (document.getElementById('provider-search')?.value || '').toLowerCase();
    const statusFilter = document.getElementById('provider-status-filter')?.value || 'all';

    return allProviders.filter(p => {
        if (search) {
            const haystack = `${p.displayName} ${p.name} ${p.id}`.toLowerCase();
            if (!haystack.includes(search)) return false;
        }

        const change = providerChanges.get(p.id);
        const isEnabled = change?.isEnabled !== undefined ? change.isEnabled : p.isEnabled;
        if (statusFilter === 'enabled' && !isEnabled) return false;
        if (statusFilter === 'disabled' && isEnabled) return false;

        return true;
    }).sort((a, b) => {
        const aChange = providerChanges.get(a.id);
        const bChange = providerChanges.get(b.id);
        const aEnabled = aChange?.isEnabled !== undefined ? aChange.isEnabled : a.isEnabled;
        const bEnabled = bChange?.isEnabled !== undefined ? bChange.isEnabled : b.isEnabled;
        if (aEnabled && !bEnabled) return -1;
        if (!aEnabled && bEnabled) return 1;
        const aOrder = aChange?.sortOrder ?? a.sortOrder;
        const bOrder = bChange?.sortOrder ?? b.sortOrder;
        if (aOrder !== bOrder) return aOrder - bOrder;
        return (a.displayName || '').localeCompare(b.displayName || '');
    });
}

function renderProviderList() {
    const filtered = getFilteredProviders();
    const container = document.getElementById('providers-table-container');

    if (filtered.length === 0) {
        container.innerHTML = '<p style="padding:2rem;color:var(--text-secondary);">No providers match your filters.</p>';
        return;
    }

    // Build overall position map from ALL enabled providers
    const allEnabled = allProviders.filter(p => {
        const change = providerChanges.get(p.id);
        return change?.isEnabled !== undefined ? change.isEnabled : p.isEnabled;
    }).sort((a, b) => {
        const aOrder = providerChanges.get(a.id)?.sortOrder ?? a.sortOrder;
        const bOrder = providerChanges.get(b.id)?.sortOrder ?? b.sortOrder;
        if (aOrder !== bOrder) return aOrder - bOrder;
        return (a.displayName || '').localeCompare(b.displayName || '');
    });
    const overallPositionMap = new Map();
    allEnabled.forEach((p, i) => overallPositionMap.set(p.id, i + 1));

    const headerHtml = `
        <div class="provider-header-row">
            <span></span>
            <span>On</span>
            <span>Logo</span>
            <span>Provider</span>
            <span>#</span>
        </div>
    `;

    const rowsHtml = filtered.map((p) => {
        const change = providerChanges.get(p.id);
        const isEnabled = change?.isEnabled !== undefined ? change.isEnabled : p.isEnabled;
        const position = isEnabled ? overallPositionMap.get(p.id) : null;
        const displayName = change?.displayName !== undefined ? change.displayName : p.displayName;
        const disabledClass = !isEnabled ? 'disabled-row' : '';
        const newClass = p.isNew ? 'new-row' : '';

        const logoSrc = p.logo || p.tmdbLogo;
        const logoHtml = logoSrc
            ? `<img class="provider-logo" src="${logoSrc}" alt="" title="Click to change logo" onclick="triggerProviderLogoUpload(${p.id})" onerror="this.outerHTML='<div class=\\'provider-logo-placeholder\\' onclick=\\'triggerProviderLogoUpload(${p.id})\\'>+</div>'">`
            : `<div class="provider-logo-placeholder" onclick="triggerProviderLogoUpload(${p.id})" title="Click to upload logo">+</div>`;

        const positionHtml = isEnabled
            ? `<input type="number" class="provider-order-input" value="${position}" min="1" onchange="onProviderReorder(${p.id}, parseInt(this.value))">`
            : `<span class="provider-order-disabled">-</span>`;

        return `
            <div class="provider-row ${disabledClass} ${newClass}" draggable="true"
                data-provider-id="${p.id}"
                ondragstart="onProviderDragStart(event, ${p.id})"
                ondragover="onProviderDragOver(event)"
                ondrop="onProviderDrop(event, ${p.id})"
                ondragend="onProviderDragEnd(event)">
                <div class="drag-handle" title="Drag to reorder">&#x2630;</div>
                <input type="checkbox" class="provider-enable-toggle"
                    ${isEnabled ? 'checked' : ''}
                    onchange="onProviderToggle(${p.id}, this.checked)">
                <div class="provider-logo-cell">${logoHtml}</div>
                <div class="provider-info">
                    <input type="text" class="provider-display-name"
                        value="${escapeHtml(displayName)}"
                        data-original="${escapeHtml(p.name)}"
                        onchange="onProviderRename(${p.id}, this.value)">
                    <div class="provider-meta">
                        <span style="color:#94a3b8">ID: ${p.id}</span>
                    </div>
                </div>
                ${positionHtml}
            </div>
        `;
    }).join('');

    const scrollEl = container.querySelector('.provider-list-container');
    const scrollTop = scrollEl ? scrollEl.scrollTop : 0;
    container.innerHTML = `<div class="provider-list-container">${headerHtml}${rowsHtml}</div>`;
    const newScrollEl = container.querySelector('.provider-list-container');
    if (newScrollEl) newScrollEl.scrollTop = scrollTop;
}

function updateProviderStats() {
    const total = allProviders.length;
    const enabled = allProviders.filter(p => {
        const change = providerChanges.get(p.id);
        return change?.isEnabled !== undefined ? change.isEnabled : p.isEnabled;
    }).length;
    const newCount = allProviders.filter(p => p.isNew).length;

    const statsEl = document.getElementById('provider-stats');
    if (statsEl) {
        statsEl.innerHTML = `${enabled}/${total} enabled` +
            (newCount > 0 ? ` | <strong style="color:var(--warning-color)">${newCount} new</strong>` : '') +
            (providerChanges.size > 0 ? ` | <strong style="color:var(--primary-color)">${providerChanges.size} unsaved</strong>` : '');
    }
}

function onProviderToggle(providerId, enabled) {
    const existing = providerChanges.get(providerId) || {};
    providerChanges.set(providerId, { ...existing, isEnabled: enabled });
    renderProviderList();
    updateProviderStats();
}

function onProviderRename(providerId, newName) {
    const existing = providerChanges.get(providerId) || {};
    providerChanges.set(providerId, { ...existing, displayName: newName });
    updateProviderStats();
}

function getAllEnabledProvidersSorted() {
    return allProviders.filter(p => {
        const change = providerChanges.get(p.id);
        return change?.isEnabled !== undefined ? change.isEnabled : p.isEnabled;
    }).sort((a, b) => {
        const aOrder = providerChanges.get(a.id)?.sortOrder ?? a.sortOrder;
        const bOrder = providerChanges.get(b.id)?.sortOrder ?? b.sortOrder;
        if (aOrder !== bOrder) return aOrder - bOrder;
        return (a.displayName || '').localeCompare(b.displayName || '');
    });
}

function moveProviderToPosition(providerId, targetPos) {
    const allEnabled = getAllEnabledProvidersSorted();
    const fromIdx = allEnabled.findIndex(p => p.id === providerId);
    if (fromIdx === -1) return;

    const toIdx = Math.max(0, Math.min(allEnabled.length - 1, targetPos - 1));
    if (fromIdx === toIdx) return;

    const [moved] = allEnabled.splice(fromIdx, 1);
    allEnabled.splice(toIdx, 0, moved);

    allEnabled.forEach((p, i) => {
        const newOrder = i + 1;
        const orig = allProviders.find(op => op.id === p.id);
        if (orig && orig.sortOrder !== newOrder) {
            const existing = providerChanges.get(p.id) || {};
            providerChanges.set(p.id, { ...existing, sortOrder: newOrder });
            orig.sortOrder = newOrder;
        }
    });

    renderProviderList();
    updateProviderStats();
}

function onProviderReorder(providerId, newPosition) {
    moveProviderToPosition(providerId, newPosition);
}

// Provider Drag and Drop
function onProviderDragStart(e, providerId) {
    draggedProviderId = providerId;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', String(providerId));
    setTimeout(() => {
        const row = e.target.closest('.provider-row');
        if (row) row.classList.add('dragging');
    }, 0);
}

function onProviderDragOver(e) {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';

    const scrollEl = document.querySelector('.provider-list-container');
    if (scrollEl) {
        const rect = scrollEl.getBoundingClientRect();
        const edgeZone = 60;
        const scrollSpeed = 12;
        if (e.clientY < rect.top + edgeZone) {
            scrollEl.scrollTop -= scrollSpeed;
        } else if (e.clientY > rect.bottom - edgeZone) {
            scrollEl.scrollTop += scrollSpeed;
        }
    }

    const row = e.target.closest('.provider-row');
    if (!row || parseInt(row.dataset.providerId) === draggedProviderId) return;

    document.querySelectorAll('.provider-row.drag-over-above, .provider-row.drag-over-below')
        .forEach(el => el.classList.remove('drag-over-above', 'drag-over-below'));

    const rect = row.getBoundingClientRect();
    if (e.clientY < rect.top + rect.height / 2) {
        row.classList.add('drag-over-above');
    } else {
        row.classList.add('drag-over-below');
    }
}

function onProviderDrop(e, targetProviderId) {
    e.preventDefault();
    if (!draggedProviderId || draggedProviderId === targetProviderId) {
        onProviderDragEnd(e);
        return;
    }

    const allEnabled = getAllEnabledProvidersSorted();
    const fromIdx = allEnabled.findIndex(p => p.id === draggedProviderId);
    const toIdx = allEnabled.findIndex(p => p.id === targetProviderId);

    if (fromIdx === -1 || toIdx === -1) {
        onProviderDragEnd(e);
        return;
    }

    const row = e.target.closest('.provider-row');
    const rect = row.getBoundingClientRect();
    const insertBelow = e.clientY > rect.top + rect.height / 2;
    let targetPosition = insertBelow ? toIdx + 2 : toIdx + 1;
    if (fromIdx < toIdx) targetPosition--;

    moveProviderToPosition(draggedProviderId, targetPosition);
    onProviderDragEnd(e);
}

function onProviderDragEnd(e) {
    draggedProviderId = null;
    document.querySelectorAll('.provider-row.dragging, .provider-row.drag-over-above, .provider-row.drag-over-below')
        .forEach(el => el.classList.remove('dragging', 'drag-over-above', 'drag-over-below'));
}

function triggerProviderLogoUpload(providerId) {
    providerLogoUploadTarget = providerId;
    document.getElementById('provider-logo-upload-input').click();
}

async function handleProviderLogoUpload(file) {
    if (!providerLogoUploadTarget || !file) return;

    const providerId = providerLogoUploadTarget;
    providerLogoUploadTarget = null;

    try {
        const formData = new FormData();
        formData.append('logo', file);

        const token = localStorage.getItem(TOKEN_KEY);
        const response = await fetch(`${API_BASE}/api/admin/providers/${encodeURIComponent(providerId)}/logo`, {
            method: 'POST',
            headers: { 'Authorization': `Bearer ${token}` },
            body: formData
        });

        if (!response.ok) {
            const err = await response.json().catch(() => ({ error: 'Upload failed' }));
            throw new Error(err.error);
        }

        const data = await response.json();
        const p = allProviders.find(pr => pr.id === providerId);
        if (p) p.logo = data.logoUrl + '?t=' + Date.now();
        renderProviderList();
        showToast('Provider logo uploaded!', 'success');
    } catch (error) {
        showToast('Logo upload failed: ' + error.message, 'error');
    }
}

async function saveAllProviderChanges() {
    if (providerChanges.size === 0) {
        showToast('No changes to save', 'success');
        return;
    }

    try {
        const providers = [];
        for (const [id, changes] of providerChanges) {
            const original = allProviders.find(p => p.id === id) || {};
            providers.push({
                id,
                displayName: changes.displayName !== undefined ? changes.displayName : original.displayName,
                isEnabled: changes.isEnabled !== undefined ? changes.isEnabled : original.isEnabled,
                sortOrder: changes.sortOrder !== undefined ? changes.sortOrder : original.sortOrder
            });
        }

        await apiRequest('/api/admin/providers/batch', {
            method: 'PUT',
            body: JSON.stringify({ providers })
        });

        showToast(`Saved ${providers.length} provider changes!`, 'success');
        await loadProviders();
    } catch (error) {
        showToast('Save failed: ' + error.message, 'error');
    }
}

function showToast(message, type = 'success') {
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => toast.remove(), 3000);
}

// Event Handlers
document.addEventListener('DOMContentLoaded', () => {
    // Check auth state
    if (isAuthenticated()) {
        showScreen('admin-screen');
        const username = localStorage.getItem(USERNAME_KEY);
        document.getElementById('admin-info').textContent = `Logged in as ${username}`;
        loadDashboard();
    } else {
        showScreen('login-screen');
    }

    // Login form
    document.getElementById('login-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;

        try {
            await login(username, password);
            showScreen('admin-screen');
            document.getElementById('admin-info').textContent = `Logged in as ${username}`;
            loadDashboard();
        } catch (error) {
            showError('login-error', error.message);
        }
    });

    // Logout
    document.getElementById('logout-btn').addEventListener('click', logout);

    // Navigation
    document.querySelectorAll('.nav-menu a').forEach(link => {
        link.addEventListener('click', (e) => {
            e.preventDefault();
            const view = e.target.getAttribute('data-view');
            showView(view);

            // Load data for the view
            switch(view) {
                case 'dashboard':
                    loadDashboard();
                    break;
                case 'users':
                    loadUsers();
                    break;
                case 'failures':
                    loadFailures();
                    break;
                case 'health':
                    loadHealth();
                    break;
                case 'loading-phrases':
                    loadLoadingPhrases();
                    break;
                case 'livetv':
                    loadLiveTVChannels();
                    break;
                case 'providers':
                    loadProviders();
                    break;
            }
        });
    });

    // Add User button
    document.getElementById('add-user-btn').addEventListener('click', () => {
        document.getElementById('user-modal-title').textContent = 'Add User';
        document.getElementById('user-form').reset();
        document.getElementById('user-password').required = true;
        document.getElementById('password-hint').style.display = 'none';
        populateParentUserDropdown();
        updateRdKeyRequirement(); // Update based on parent selection
        showModal('user-modal');
    });

    // Update RD key requirement based on parent selection
    document.getElementById('user-parent').addEventListener('change', updateRdKeyRequirement);

    function updateRdKeyRequirement() {
        const parentSelect = document.getElementById('user-parent');
        const rdKeyInput = document.getElementById('user-rd-key');
        const rdKeyLabel = rdKeyInput.previousElementSibling;

        if (parentSelect.value) {
            // Sub-account - RD key disabled (inherits from parent)
            rdKeyInput.disabled = true;
            rdKeyInput.value = ''; // Clear any entered value
            rdKeyInput.required = false;
            rdKeyInput.placeholder = 'Inherits from parent user';
            rdKeyLabel.innerHTML = 'Real-Debrid API Key <span style="color: var(--text-secondary);">(Inherited from parent)</span>';
        } else {
            // Standalone - RD key enabled and required
            rdKeyInput.disabled = false;
            rdKeyInput.required = false;
            rdKeyInput.placeholder = 'Enter RD API key';
            rdKeyLabel.innerHTML = 'Real-Debrid API Key <span style="color: var(--danger-color);">REQUIRED</span>';
        }
    }

    // User form submit
    document.getElementById('user-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const formData = new FormData(e.target);
        const userId = formData.get('id');
        const parentUserId = formData.get('parent_user_id');

        const userData = {
            username: formData.get('username'),
            isAdmin: formData.get('is_admin') === 'on',
            rdApiKey: formData.get('rd_api_key') || undefined,
            parentUserId: parentUserId ? parseInt(parentUserId, 10) : undefined
        };

        if (formData.get('password')) {
            userData.password = formData.get('password');
        }

        try {
            if (userId) {
                await apiRequest(`/api/admin/users/${userId}`, {
                    method: 'PUT',
                    body: JSON.stringify(userData)
                });
            } else {
                userData.password = formData.get('password'); // Required for new users
                await apiRequest('/api/admin/users', {
                    method: 'POST',
                    body: JSON.stringify(userData)
                });
            }

            hideModal('user-modal');
            loadUsers();
        } catch (error) {
            showError('user-form-error', error.message);
        }
    });

    // Modal close buttons
    document.querySelectorAll('.modal-close').forEach(btn => {
        btn.addEventListener('click', () => {
            hideModal('user-modal');
        });
    });

    // Save loading phrases
    document.getElementById('save-phrases-btn').addEventListener('click', saveLoadingPhrases);

    // Live TV handlers
    document.getElementById('save-channels-btn').addEventListener('click', saveAllChannelChanges);
    document.getElementById('seed-logos-btn').addEventListener('click', seedCabernetLogos);
    document.getElementById('reset-sources-btn').addEventListener('click', resetAllSources);

    // Channel search with debounce
    let searchTimeout;
    document.getElementById('channel-search').addEventListener('input', () => {
        clearTimeout(searchTimeout);
        searchTimeout = setTimeout(() => {
            renderChannelList();
            updateChannelStats();
        }, 200);
    });

    // Channel filters
    document.getElementById('channel-source-filter').addEventListener('change', () => {
        renderChannelList();
        updateChannelStats();
    });
    document.getElementById('channel-status-filter').addEventListener('change', () => {
        renderChannelList();
        updateChannelStats();
    });

    // Logo upload handler
    document.getElementById('logo-upload-input').addEventListener('change', (e) => {
        if (e.target.files[0]) {
            handleLogoUpload(e.target.files[0]);
            e.target.value = '';
        }
    });

    // Provider handlers
    document.getElementById('save-providers-btn').addEventListener('click', saveAllProviderChanges);

    let providerSearchTimeout;
    document.getElementById('provider-search').addEventListener('input', () => {
        clearTimeout(providerSearchTimeout);
        providerSearchTimeout = setTimeout(() => {
            renderProviderList();
            updateProviderStats();
        }, 200);
    });

    document.getElementById('provider-status-filter').addEventListener('change', () => {
        renderProviderList();
        updateProviderStats();
    });

    document.getElementById('provider-logo-upload-input').addEventListener('change', (e) => {
        if (e.target.files[0]) {
            handleProviderLogoUpload(e.target.files[0]);
            e.target.value = '';
        }
    });

    // Close modal on outside click
    window.addEventListener('click', (e) => {
        if (e.target.classList.contains('modal')) {
            hideModal('user-modal');
        }
    });
});

// Make functions available globally
window.editUser = editUser;
window.deleteUser = deleteUser;
window.onChannelToggle = onChannelToggle;
window.onChannelRename = onChannelRename;
window.onChannelReorder = onChannelReorder;
window.triggerLogoUpload = triggerLogoUpload;
window.onRowDragStart = onRowDragStart;
window.onRowDragOver = onRowDragOver;
window.onRowDrop = onRowDrop;
window.onRowDragEnd = onRowDragEnd;
window.onProviderToggle = onProviderToggle;
window.onProviderRename = onProviderRename;
window.onProviderReorder = onProviderReorder;
window.triggerProviderLogoUpload = triggerProviderLogoUpload;
window.onProviderDragStart = onProviderDragStart;
window.onProviderDragOver = onProviderDragOver;
window.onProviderDrop = onProviderDrop;
window.onProviderDragEnd = onProviderDragEnd;
window.dismissProviderBanner = dismissProviderBanner;
window.showScheduleForm = showScheduleForm;
window.hideScheduleForm = hideScheduleForm;
window.editSchedule = editSchedule;
window.deleteSchedule = deleteSchedule;
window.saveSchedule = saveSchedule;
window.toggleScheduleUser = toggleScheduleUser;
window.onScheduleTypeChange = onScheduleTypeChange;
