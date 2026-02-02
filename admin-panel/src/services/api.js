import axios from 'axios'
import { getToken, clearToken } from './auth'

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json'
  }
})

// Add auth token to requests
api.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Handle 401 responses
api.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      clearToken()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export const authApi = {
  login: (username, password) =>
    api.post('/auth/login', { username, password }),
  logout: () =>
    api.post('/auth/logout'),
  getCurrentUser: () =>
    api.get('/auth/me')
}

export const usersApi = {
  getAll: () =>
    api.get('/admin/users'),
  create: (data) =>
    api.post('/admin/users', data),
  update: (id, data) =>
    api.put(`/admin/users/${id}`, data),
  delete: (id) =>
    api.delete(`/admin/users/${id}`),
  setRdKey: (id, rdApiKey, rdExpiryDate) =>
    api.post(`/admin/users/${id}/rd-key`, { rdApiKey, rdExpiryDate })
}

export const alertsApi = {
  getRdExpiryAlerts: () =>
    api.get('/admin/rd-expiry-alerts')
}

export default api
