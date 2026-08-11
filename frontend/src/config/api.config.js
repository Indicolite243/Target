const ENV = import.meta.env.MODE || 'development'

const defaultBaseUrl = ENV === 'development' ? '/api/v1' : '/api/v1'

export const getApiConfig = () => ({
  baseURL: import.meta.env.VITE_API_BASE_URL || defaultBaseUrl,
  timeout: Number(import.meta.env.VITE_API_TIMEOUT || 15000),
  useMockData: import.meta.env.VITE_USE_MOCK_DATA === 'true'
})

export const HTTP_STATUS = {
  OK: 200,
  CREATED: 201,
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  INTERNAL_SERVER_ERROR: 500,
  SERVICE_UNAVAILABLE: 503
}

export const ERROR_MESSAGES = {
  NETWORK_ERROR: '网络连接失败，请检查 Spring Boot 服务是否启动',
  TIMEOUT_ERROR: '请求超时，请稍后重试',
  SERVER_ERROR: '服务器内部错误，请稍后重试',
  UNAUTHORIZED: '登录已过期，请重新登录',
  FORBIDDEN: '访问被拒绝，权限不足',
  NOT_FOUND: '请求的资源不存在',
  VALIDATION_ERROR: '数据验证失败，请检查输入',
  UNKNOWN_ERROR: '未知错误，请联系技术支持'
}

export const RETRY_CONFIG = {
  maxRetries: 1,
  retryDelay: 500,
  retryCondition: (error) => {
    const method = error.config?.method?.toUpperCase()
    return method === 'GET' && (!error.response || error.response.status >= 500)
  }
}

export const CACHE_CONFIG = {
  TTL: {
    ACCOUNT_INFO: 60 * 1000,
    STOCK_QUOTE: 5 * 1000,
    CHART_DATA: 5 * 60 * 1000,
    STRATEGY_RESULT: 2 * 60 * 1000
  },
  PREFIX: { ACCOUNT: 'account_', STOCK: 'stock_', CHART: 'chart_', STRATEGY: 'strategy_' }
}

export default { getApiConfig, HTTP_STATUS, ERROR_MESSAGES, RETRY_CONFIG, CACHE_CONFIG }
