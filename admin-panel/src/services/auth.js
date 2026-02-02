const TOKEN_KEY = 'admin_token'

export const setToken = (token) => {
  localStorage.setItem(TOKEN_KEY, token)
}

export const getToken = () => {
  return localStorage.getItem(TOKEN_KEY)
}

export const clearToken = () => {
  localStorage.removeItem(TOKEN_KEY)
}
