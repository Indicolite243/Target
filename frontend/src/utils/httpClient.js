import axios from 'axios'
import { ERROR_MESSAGES, getApiConfig, RETRY_CONFIG } from '@/config/api.config.js'

const config = getApiConfig()

const redirectToLogin = () => {
  if (typeof window === 'undefined' || window.location.pathname === '/login') return
  const returnPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
  window.location.replace(`/login?redirect=${encodeURIComponent(returnPath)}`)
}

export const httpClient = axios.create({
  baseURL: config.baseURL,
  timeout: config.timeout,
  withCredentials: true,
  headers: { 'Content-Type': 'application/json', Accept: 'application/json' }
})

httpClient.interceptors.request.use((request) => {
  const token = localStorage.getItem('access_token')
  if (token) request.headers.Authorization = `Bearer ${token}`
  request.headers['X-Request-Id'] = crypto.randomUUID()
  request.headers['X-Client-Version'] = 'web-1.0.0'
  return request
})

httpClient.interceptors.response.use(
  (response) => response,
  (error) => {
    const responseData = error.response?.data
    const payload = responseData && typeof responseData === 'object' ? responseData : null
    const status = error.response?.status
    const message = payload?.message
      || (status === 400 ? ERROR_MESSAGES.VALIDATION_ERROR : null)
      || (status === 401 ? ERROR_MESSAGES.UNAUTHORIZED : null)
      || (status === 403 ? ERROR_MESSAGES.FORBIDDEN : null)
      || (status === 404 ? ERROR_MESSAGES.NOT_FOUND : null)
      || (error.code === 'ECONNABORTED' ? ERROR_MESSAGES.TIMEOUT_ERROR : null)
      || (!error.response ? ERROR_MESSAGES.NETWORK_ERROR : ERROR_MESSAGES.SERVER_ERROR)

    const authenticationFailed = status === 401 || (status === 403 && !payload?.code)
    if (authenticationFailed) {
      localStorage.removeItem('access_token')
      localStorage.removeItem('user_info')
      redirectToLogin()
    }

    const normalized = new Error(message)
    normalized.code = payload?.code || error.code
    normalized.status = status || 0
    normalized.traceId = payload?.traceId || error.response?.headers?.['x-trace-id']
    normalized.details = payload?.data
    normalized.config = error.config
    normalized.response = error.response
    return Promise.reject(normalized)
  }
)

export async function retryRequest(fn, retryCount = 0) {
  try {
    return await fn()
  } catch (error) {
    if (retryCount < RETRY_CONFIG.maxRetries && RETRY_CONFIG.retryCondition(error)) {
      await new Promise((resolve) => setTimeout(resolve, RETRY_CONFIG.retryDelay))
      return retryRequest(fn, retryCount + 1)
    }
    throw error
  }
}

export const handleApiError = (error) => Promise.reject(error)
export default httpClient
