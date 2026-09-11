import { getApiConfig } from '@/config/api.config.js'

export class SseParser {
  constructor(onEvent) {
    this.onEvent = onEvent
    this.buffer = ''
  }

  feed(chunk) {
    this.buffer = (this.buffer + chunk).replaceAll('\r\n', '\n')
    let boundary
    while ((boundary = this.buffer.indexOf('\n\n')) >= 0) {
      const block = this.buffer.slice(0, boundary)
      this.buffer = this.buffer.slice(boundary + 2)
      let event = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (data.length) this.onEvent(event, JSON.parse(data.join('\n')))
    }
  }
}

function authenticationFailed() {
  localStorage.removeItem('access_token')
  localStorage.removeItem('user_info')
  if (window.location.pathname !== '/login') {
    const returnPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
    window.location.replace(`/login?redirect=${encodeURIComponent(returnPath)}`)
  }
}

export async function streamAssistant({ conversationId, question, signal, onEvent }) {
  const baseURL = getApiConfig().baseURL.replace(/\/$/, '')
  const token = localStorage.getItem('access_token')
  const response = await fetch(`${baseURL}/assistant/conversations/${encodeURIComponent(conversationId)}/messages/stream`, {
    method: 'POST',
    signal,
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      'X-Request-Id': crypto.randomUUID()
    },
    body: JSON.stringify({ question })
  })
  if (!response.ok) {
    let message = response.status === 409 ? '当前会话正在生成回答' : '投研助手暂时不可用'
    try { message = (await response.json()).message || message } catch { /* 非 JSON 错误体不展示 */ }
    if (response.status === 401) authenticationFailed()
    throw new Error(message)
  }
  if (!response.body) throw new Error('浏览器无法读取流式回答')

  let terminal = false
  const parser = new SseParser((event, data) => {
    if (['done', 'error', 'cancelled'].includes(event)) terminal = true
    onEvent(event, data)
  })
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      parser.feed(decoder.decode(value, { stream: true }))
    }
    parser.feed(decoder.decode())
    if (!terminal) throw new Error('回答连接提前结束，已生成内容可能不完整')
  } finally {
    reader.releaseLock()
  }
}

