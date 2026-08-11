import { httpClient } from '@/utils/httpClient'

const REMEMBERED_LOGIN_KEY = 'remembered_login'

const compatibleResult = (payload) => ({ ...payload, success: payload?.code === 0 })

export const loginWithPassword = async (username, password) => {
  const response = await httpClient.post('/auth/login', {
    username: username.trim(),
    password,
    rememberMe: false
  })
  const result = compatibleResult(response.data)
  if (result.success && result.data?.accessToken) {
    localStorage.setItem('access_token', result.data.accessToken)
    localStorage.setItem('user_info', JSON.stringify(result.data.user || {}))
  }
  return result
}

export const registerWithPassword = async (username, password, confirmPassword) => {
  const response = await httpClient.post('/auth/register', {
    username: username.trim(),
    password,
    confirmPassword,
    displayName: username.trim()
  })
  return compatibleResult(response.data)
}

export const getCurrentUser = async () => {
  const response = await httpClient.get('/auth/me')
  if (response.data?.code === 0 && response.data?.data) {
    localStorage.setItem('user_info', JSON.stringify(response.data.data))
  }
  return compatibleResult(response.data)
}

export const logout = async () => {
  try {
    await httpClient.post('/auth/logout')
  } finally {
    clearAuthData()
  }
}

export const isAuthenticated = () => Boolean(
  localStorage.getItem('access_token') && localStorage.getItem('user_info')
)

export const getLocalUserInfo = () => {
  try {
    const userInfo = localStorage.getItem('user_info')
    return userInfo ? JSON.parse(userInfo) : null
  } catch {
    return null
  }
}

export const getAccessToken = () => localStorage.getItem('access_token')

// 兼容现有登录页函数签名，但只保存用户名，绝不保存明文密码。
export const saveRememberedLogin = (username, _password, rememberPassword) => {
  if (!rememberPassword) return localStorage.removeItem(REMEMBERED_LOGIN_KEY)
  localStorage.setItem(REMEMBERED_LOGIN_KEY, JSON.stringify({ username, rememberPassword: true }))
}

export const getRememberedLogin = () => {
  try {
    const raw = localStorage.getItem(REMEMBERED_LOGIN_KEY)
    return raw ? { ...JSON.parse(raw), password: '' } : null
  } catch {
    return null
  }
}

export const clearRememberedLogin = () => localStorage.removeItem(REMEMBERED_LOGIN_KEY)

export const clearAuthData = () => {
  localStorage.removeItem('auth_token')
  localStorage.removeItem('access_token')
  localStorage.removeItem('refresh_token')
  localStorage.removeItem('user_info')
  localStorage.removeItem('xuntou_token')
}

export default {
  loginWithPassword, registerWithPassword, getCurrentUser, logout, isAuthenticated,
  getLocalUserInfo, getAccessToken, saveRememberedLogin, getRememberedLogin,
  clearRememberedLogin, clearAuthData
}
