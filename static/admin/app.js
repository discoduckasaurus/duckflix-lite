// Configuration
const API_BASE = window.location.origin;
const TOKEN_KEY = 'dfl_admin_token';
const USERNAME_KEY = 'dfl_admin_username';

// State
let currentView = 'dashboard';
let currentUser = null;

// Utility: API Request Helper
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
            logout();
            throw new Error('Session expired');
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

function showError(elementId, message) {
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
        alert('Failed to load user: ' + error.message);
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
    } catch (error) {
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
