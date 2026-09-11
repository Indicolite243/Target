<template>
  <button v-if="!opened" class="assistant-launch" @click="opened = true">投研助手</button>
  <aside ref="panel" v-show="opened" class="assistant-panel" aria-label="投研助手" :style="panelStyle">
    <header class="assistant-titlebar" tabindex="0" aria-label="拖动以移动投研助手窗口"
            @pointerdown="startDrag" @keydown="moveWithKeyboard">
      <strong>投研助手</strong>
      <div>
        <button aria-label="私有知识库" @click="toggleKnowledge">知识库</button>
        <button aria-label="历史会话" @click="toggleHistory">会话</button>
        <button aria-label="重置窗口位置和大小" title="重置窗口位置和大小" @click="resetLayout">复位</button>
        <button aria-label="关闭投研助手" @click="opened = false">×</button>
      </div>
    </header>
    <section v-if="historyOpen" class="assistant-history">
      <button :disabled="busy" @click="createConversation">＋ 新建对话</button>
      <p>历史对话</p>
      <div v-for="item in conversations" :key="item.id" class="history-item">
        <button :disabled="busy" class="history-title" @click="selectConversation(item.id)">{{ item.title }}</button>
        <button :disabled="busy" aria-label="重命名会话" @click="renameConversation(item)">✎</button>
        <button :disabled="busy" aria-label="删除会话" @click="deleteConversation(item)">×</button>
      </div>
    </section>
    <section v-if="knowledgeOpen" class="assistant-history assistant-knowledge">
      <div class="knowledge-actions">
        <button :disabled="uploading || working" @click="knowledgeInput?.click()">
          {{ uploading ? '正在处理…' : '＋ 上传文档' }}
        </button>
        <input ref="knowledgeInput" hidden type="file" multiple accept=".pdf,.docx,.md,.txt"
               aria-label="选择知识库文档"
               @change="uploadKnowledge" />
      </div>
      <p>我的私有知识库</p>
      <small>支持 PDF、DOCX、MD、TXT，单个不超过 6MB。文档和检索结果仅属于当前登录用户。</small>
      <div v-if="knowledgeError" role="alert" class="assistant-error">{{ knowledgeError }}</div>
      <p v-if="!knowledgeLoading && !documents.length" class="knowledge-empty">尚未上传文档</p>
      <div v-for="document in documents" :key="document.id" class="knowledge-item">
        <div>
          <strong>{{ document.name }}</strong>
          <small>{{ formatBytes(document.sizeBytes) }} · {{ document.chunkCount }} 个切片 · {{ document.status }}</small>
        </div>
        <button :disabled="uploading || working" aria-label="删除知识文档" @click="deleteKnowledge(document)">×</button>
      </div>
    </section>
    <div class="assistant-messages" aria-live="polite">
      <p v-if="busy">正在加载会话…</p>
      <p v-if="working" class="assistant-progress">{{ phase }} · {{ elapsedSeconds }} 秒</p>
      <div v-if="error" role="alert" class="assistant-error">{{ error }} <button @click="load">重试</button></div>
      <article v-for="message in messages" :key="message.id" :class="['assistant-message', message.role]">
        <!-- Assistant HTML is sanitized by renderSafeMarkdown before rendering. -->
        <!-- eslint-disable-next-line vue/no-v-html -->
        <div v-if="message.role === 'assistant'" class="assistant-markdown" v-html="renderSafeMarkdown(message.content)" />
        <template v-else>{{ message.content }}</template>
        <small v-if="message.role === 'assistant' && message.status && message.status !== 'COMPLETED'" class="message-status">
          {{ statusLabel(message.status) }}
        </small>
      </article>
      <button v-if="!busy && !error && !activeId" @click="createConversation">开始新对话</button>
    </div>
    <footer>
      <textarea v-model="draft" :disabled="Boolean(working)" aria-label="提问"
                placeholder="向投研助手提问；Enter 发送，Shift+Enter 换行" rows="3"
                @keydown.enter.exact.prevent="ask" />
      <small>当前已接通千问、当前持仓、最近30天个股/行业收益贡献、最近回测和当前用户私有知识库；涉及缺失数据时会明确提示。</small>
      <button v-if="working" aria-label="停止生成" @click="cancelGeneration">停止</button>
      <button v-else aria-label="发送问题" :disabled="busy || !activeId || !draft.trim()" @click="ask">发送</button>
    </footer>
    <div class="assistant-resize-handle" role="separator" tabindex="0" aria-orientation="horizontal"
         aria-label="拖动以调整投研助手窗口大小" title="拖动调整窗口大小"
         @pointerdown.stop="startResize" @keydown="resizeWithKeyboard" />
  </aside>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref, shallowRef } from 'vue'
import { ElMessageBox } from 'element-plus'
import { httpClient } from '@/utils/httpClient'
import { renderSafeMarkdown } from '@/utils/safeMarkdown.js'
import { streamAssistant } from '@/services/assistantStream.js'

const opened = ref(true)
const historyOpen = ref(false)
const knowledgeOpen = ref(false)
const busy = ref(false)
const error = ref('')
const conversations = ref([])
const messages = ref([])
const activeId = ref(null)
const draft = ref('')
const documents = ref([])
const knowledgeInput = ref(null)
const knowledgeLoading = ref(false)
const uploading = ref(false)
const knowledgeError = ref('')
const working = shallowRef(null)
const phase = ref('准备生成')
const elapsedSeconds = ref(0)
const panel = ref(null)
const layout = reactive({ left: 0, top: 80, width: 440, height: 640 })
let disposed = false
const controller = new AbortController()
let elapsedTimer = null
let layoutOperation = null
const endpoint = '/assistant/conversations'
const knowledgeEndpoint = '/assistant/knowledge/documents'
const layoutStorageKey = 'investment_assistant_layout_v1'
const panelStyle = computed(() => ({
  left: `${layout.left}px`, top: `${layout.top}px`,
  width: `${layout.width}px`, height: `${layout.height}px`
}))

function viewport() {
  return {
    width: Math.max(320, window.innerWidth || document.documentElement.clientWidth || 0),
    height: Math.max(480, window.innerHeight || document.documentElement.clientHeight || 0)
  }
}
function boundedLayout(candidate) {
  const screen = viewport()
  const edge = 8
  const minWidth = Math.min(360, screen.width - edge * 2)
  const minHeight = Math.min(420, screen.height - edge * 2)
  const width = Math.min(Math.max(Number(candidate.width) || minWidth, minWidth), screen.width - edge * 2)
  const height = Math.min(Math.max(Number(candidate.height) || minHeight, minHeight), screen.height - edge * 2)
  return {
    width: Math.round(width), height: Math.round(height),
    left: Math.round(Math.min(Math.max(Number(candidate.left) || edge, edge), screen.width - width - edge)),
    top: Math.round(Math.min(Math.max(Number(candidate.top) || edge, edge), screen.height - height - edge))
  }
}
function defaultLayout() {
  const screen = viewport()
  const width = Math.min(560, Math.max(380, Math.round(screen.width * 0.25)))
  const height = Math.max(420, screen.height - 96)
  return boundedLayout({ left: screen.width - width - 16, top: 80, width, height })
}
function applyLayout(candidate) { Object.assign(layout, boundedLayout(candidate)) }
function saveLayout() {
  try { localStorage.setItem(layoutStorageKey, JSON.stringify({ ...layout })) } catch { /* storage may be disabled */ }
}
function restoreLayout() {
  try {
    const saved = JSON.parse(localStorage.getItem(layoutStorageKey) || 'null')
    applyLayout(saved && typeof saved === 'object' ? saved : defaultLayout())
  } catch { applyLayout(defaultLayout()) }
}
function resetLayout() { applyLayout(defaultLayout()); saveLayout() }
function startLayoutOperation(event, mode) {
  if (event.button !== 0 || (mode === 'drag' && event.target.closest('button, input, textarea'))) return
  event.preventDefault()
  layoutOperation = { mode, x: event.clientX, y: event.clientY, initial: { ...layout } }
  window.addEventListener('pointermove', onLayoutPointerMove)
  window.addEventListener('pointerup', finishLayoutOperation)
  window.addEventListener('pointercancel', finishLayoutOperation)
}
function startDrag(event) { startLayoutOperation(event, 'drag') }
function startResize(event) { startLayoutOperation(event, 'resize') }
function onLayoutPointerMove(event) {
  if (!layoutOperation) return
  event.preventDefault()
  const dx = event.clientX - layoutOperation.x
  const dy = event.clientY - layoutOperation.y
  const initial = layoutOperation.initial
  if (layoutOperation.mode === 'drag') {
    applyLayout({ ...initial, left: initial.left + dx, top: initial.top + dy })
    return
  }
  const screen = viewport()
  applyLayout({ ...initial,
    width: Math.min(initial.width + dx, screen.width - initial.left - 8),
    height: Math.min(initial.height + dy, screen.height - initial.top - 8) })
}
function finishLayoutOperation() {
  if (!layoutOperation) return
  layoutOperation = null
  window.removeEventListener('pointermove', onLayoutPointerMove)
  window.removeEventListener('pointerup', finishLayoutOperation)
  window.removeEventListener('pointercancel', finishLayoutOperation)
  saveLayout()
}
function moveWithKeyboard(event) {
  if (event.target !== event.currentTarget || !event.key.startsWith('Arrow')) return
  event.preventDefault()
  const step = event.shiftKey ? 40 : 10
  applyLayout({ ...layout,
    left: layout.left + (event.key === 'ArrowRight' ? step : event.key === 'ArrowLeft' ? -step : 0),
    top: layout.top + (event.key === 'ArrowDown' ? step : event.key === 'ArrowUp' ? -step : 0) })
  saveLayout()
}
function resizeWithKeyboard(event) {
  if (!event.key.startsWith('Arrow')) return
  event.preventDefault()
  const step = event.shiftKey ? 40 : 10
  applyLayout({ ...layout,
    width: layout.width + (event.key === 'ArrowRight' ? step : event.key === 'ArrowLeft' ? -step : 0),
    height: layout.height + (event.key === 'ArrowDown' ? step : event.key === 'ArrowUp' ? -step : 0) })
  saveLayout()
}
function keepLayoutInViewport() { applyLayout(layout); saveLayout() }

async function call(method, path = '', data) {
  const response = await httpClient.request({ method, url: endpoint + path, data, signal: controller.signal })
  if (response.data.code !== 0) throw new Error(response.data.message || '会话操作失败')
  return response.data.data
}
async function perform(action) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try { await action() } catch (e) { if (!disposed) error.value = e.message || '会话操作失败' }
  finally { busy.value = false }
}
async function select(id) {
  const result = await call('get', `/${id}/messages`)
  if (disposed) return
  activeId.value = id
  messages.value = result
  draft.value = ''
  historyOpen.value = false
}
function selectConversation(id) { return perform(() => select(id)) }
function toggleHistory() { historyOpen.value = !historyOpen.value; knowledgeOpen.value = false }
async function toggleKnowledge() {
  knowledgeOpen.value = !knowledgeOpen.value
  historyOpen.value = false
  if (knowledgeOpen.value) await loadKnowledge()
}
async function loadKnowledge() {
  knowledgeLoading.value = true
  knowledgeError.value = ''
  try {
    const response = await httpClient.request({ method: 'get', url: knowledgeEndpoint, signal: controller.signal })
    if (response.data.code !== 0) throw new Error(response.data.message || '知识库加载失败')
    documents.value = response.data.data || []
  } catch (exception) {
    if (!disposed) knowledgeError.value = exception.message || '知识库加载失败'
  } finally { knowledgeLoading.value = false }
}
async function uploadKnowledge(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length || uploading.value) return
  uploading.value = true
  knowledgeError.value = ''
  try {
    for (const file of files) {
      const form = new FormData()
      form.append('file', file)
      const response = await httpClient.request({ method: 'post', url: knowledgeEndpoint,
        data: form, headers: { 'Content-Type': 'multipart/form-data' }, signal: controller.signal })
      if (response.data.code !== 0) throw new Error(response.data.message || `${file.name} 上传失败`)
    }
    await loadKnowledge()
  } catch (exception) {
    if (!disposed) knowledgeError.value = exception.message || '知识库文档处理失败'
  } finally { uploading.value = false }
}
async function deleteKnowledge(document) {
  try { await ElMessageBox.confirm(`删除知识文档“${document.name}”？`, '删除文档', { modal: false, lockScroll: false }) }
  catch { return }
  knowledgeError.value = ''
  try {
    const response = await httpClient.request({ method: 'delete',
      url: `${knowledgeEndpoint}/${document.id}`, signal: controller.signal })
    if (response.data.code !== 0) throw new Error(response.data.message || '删除文档失败')
    documents.value = documents.value.filter(value => value.id !== document.id)
  } catch (exception) { knowledgeError.value = exception.message || '删除文档失败' }
}
function formatBytes(value) {
  const bytes = Number(value || 0)
  return bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024
    ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
function load() {
  return perform(async () => {
    conversations.value = await call('get')
    if (disposed) return
    if (conversations.value.length) await select(conversations.value[0].id)
    else {
      const item = await call('post')
      conversations.value = [item]
      await select(item.id)
    }
  })
}
function createConversation() {
  return perform(async () => {
    const item = await call('post')
    conversations.value.unshift(item)
    await select(item.id)
  })
}
async function renameConversation(item) {
  let title
  try {
    const result = await ElMessageBox.prompt('输入会话标题', '重命名', {
      inputValue: item.title,
      inputValidator: value => Boolean(value?.trim() && value.trim().length <= 100) || '请输入1至100个字符',
      modal: false,
      lockScroll: false
    })
    title = result.value.trim()
  } catch { return }
  return perform(async () => { await call('patch', `/${item.id}`, { title }); item.title = title })
}
async function deleteConversation(item) {
  try { await ElMessageBox.confirm('删除此会话及其消息？', '删除会话', { modal: false, lockScroll: false }) }
  catch { return }
  return perform(async () => {
    await call('delete', `/${item.id}`)
    conversations.value = conversations.value.filter(value => value.id !== item.id)
    if (activeId.value === item.id) {
      activeId.value = null
      messages.value = []
      draft.value = ''
      if (conversations.value.length) await select(conversations.value[0].id)
    }
  })
}
function statusLabel(status) {
  return ({ GENERATING: '正在生成', PENDING: '等待回答', CANCELLED: '已停止，内容不完整',
    FAILED: '生成失败，内容可能不完整' })[status] || status
}
function updateStreamEvent(job, event, data) {
  if (event === 'accepted') {
    job.user.id = data.userMessageId
    job.assistant.id = data.assistantMessageId
  } else if (event === 'status') {
    phase.value = data.message || '正在生成回答'
  } else if (event === 'delta') {
    job.assistant.content += data.text || ''
  } else if (event === 'done') {
    job.user.status = 'COMPLETED'
    job.assistant.status = 'COMPLETED'
    phase.value = '回答完成'
  } else if (event === 'error') {
    job.user.status = 'FAILED'
    job.assistant.status = 'FAILED'
    phase.value = data.message || '生成失败'
  } else if (event === 'cancelled') {
    job.user.status = 'CANCELLED'
    job.assistant.status = 'CANCELLED'
    phase.value = '已停止生成'
  }
}
async function refreshActiveMessages(conversationId) {
  if (!disposed && activeId.value === conversationId) messages.value = await call('get', `/${conversationId}/messages`)
}
async function ask() {
  const question = draft.value.trim()
  if (!question || !activeId.value || busy.value || working.value) return
  error.value = ''
  const conversationId = activeId.value
  const streamController = new AbortController()
  const user = reactive({ id: `local-user-${Date.now()}`, role: 'user', content: question, status: 'PENDING' })
  const assistant = reactive({ id: `local-assistant-${Date.now()}`, role: 'assistant', content: '', status: 'GENERATING' })
  const job = { conversationId, controller: streamController, user, assistant }
  working.value = job
  messages.value.push(user, assistant)
  draft.value = ''
  phase.value = '正在连接千问'
  elapsedSeconds.value = 0
  elapsedTimer = window.setInterval(() => { elapsedSeconds.value += 1 }, 1000)
  try {
    await streamAssistant({ conversationId, question, signal: streamController.signal,
      onEvent: (event, data) => updateStreamEvent(job, event, data) })
  } catch (exception) {
    if (exception.name !== 'AbortError') {
      user.status = 'FAILED'
      assistant.status = 'FAILED'
      error.value = exception.message || '回答生成失败'
    }
  } finally {
    if (elapsedTimer) window.clearInterval(elapsedTimer)
    elapsedTimer = null
    if (working.value === job) working.value = null
    try { await refreshActiveMessages(conversationId) } catch (exception) {
      if (!disposed) error.value = exception.message || '消息状态刷新失败'
    }
  }
}
async function cancelGeneration() {
  const job = working.value
  if (!job) return
  phase.value = '正在停止'
  try {
    await call('post', `/${job.conversationId}/cancel`)
    job.user.status = 'CANCELLED'
    job.assistant.status = 'CANCELLED'
  } catch (exception) {
    error.value = exception.message || '停止失败'
  } finally {
    job.controller.abort()
  }
}
function onKey(event) {
  if (event.key !== 'Escape') return
  if (knowledgeOpen.value) knowledgeOpen.value = false
  else if (historyOpen.value) historyOpen.value = false
  else opened.value = false
}
onMounted(() => {
  restoreLayout()
  load()
  window.addEventListener('keydown', onKey)
  window.addEventListener('resize', keepLayoutInViewport)
})
onBeforeUnmount(() => {
  disposed = true
  finishLayoutOperation()
  controller.abort()
  working.value?.controller.abort()
  if (elapsedTimer) window.clearInterval(elapsedTimer)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', keepLayoutInViewport)
})
</script>

<style scoped>
.assistant-panel { position: fixed; min-width: 360px; min-height: 420px; max-width: calc(100vw - 16px); max-height: calc(100vh - 16px); z-index: 2000; display: flex; flex-direction: column; color: #20344e; background: #f8fbff; border: 1px solid #d6e4f3; border-radius: 16px; box-shadow: 0 12px 45px #0005; overflow: hidden; }
header { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px 12px 18px; background: white; border-bottom: 1px solid #e1e8f0; }
.assistant-titlebar { cursor: move; user-select: none; touch-action: none; outline: none; }
.assistant-titlebar:focus-visible { box-shadow: inset 0 0 0 2px #6ba9ef; }
.assistant-titlebar button { cursor: pointer; }
button { cursor: pointer; background: #edf4ff; color: #245b9e; border: 0; border-radius: 6px; padding: 8px 12px; margin: 2px; }
button:disabled { cursor: not-allowed; opacity: .55; }
.assistant-launch { position: fixed; right: 20px; bottom: 24px; z-index: 2000; box-shadow: 0 5px 20px #0005; }
.assistant-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 18px; }
.assistant-message { min-width: 0; overflow-wrap: anywhere; line-height: 1.8; background: #eaf0f7; padding: 16px; border-radius: 12px; margin-bottom: 14px; }
.assistant-message.user { white-space: pre-wrap; background: #dcecff; }
.assistant-markdown { min-width: 0; max-width: 100%; overflow-x: auto; }
.assistant-markdown :deep(> :first-child) { margin-top: 0; }
.assistant-markdown :deep(> :last-child) { margin-bottom: 0; }
.assistant-markdown :deep(h1),
.assistant-markdown :deep(h2),
.assistant-markdown :deep(h3),
.assistant-markdown :deep(h4) { margin: 1em 0 .45em; line-height: 1.4; color: #173a62; }
.assistant-markdown :deep(h1) { font-size: 20px; }
.assistant-markdown :deep(h2) { font-size: 18px; }
.assistant-markdown :deep(h3) { font-size: 16px; }
.assistant-markdown :deep(h4) { font-size: 14px; }
.assistant-markdown :deep(p) { margin: .55em 0; }
.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) { margin: .55em 0; padding-left: 1.5em; }
.assistant-markdown :deep(li) { margin: .25em 0; }
.assistant-markdown :deep(table) { width: max-content; min-width: 100%; margin: .8em 0; border-collapse: collapse; font-size: 13px; white-space: nowrap; }
.assistant-markdown :deep(th),
.assistant-markdown :deep(td) { padding: 7px 10px; text-align: left; border: 1px solid #b9cbe0; }
.assistant-markdown :deep(th) { color: #173a62; background: #dce9f7; }
.assistant-markdown :deep(tbody tr:nth-child(even)) { background: #f5f9fd; }
.assistant-markdown :deep(blockquote) { margin: .7em 0; padding: .1em .8em; color: #51677f; border-left: 3px solid #7ba8d8; }
.assistant-markdown :deep(pre) { overflow-x: auto; padding: 12px; color: #eef6ff; background: #172a42; border-radius: 8px; }
.assistant-markdown :deep(code) { padding: .12em .35em; background: #dce6f1; border-radius: 4px; font-family: Consolas, monospace; }
.assistant-markdown :deep(pre code) { padding: 0; background: transparent; }
.assistant-markdown :deep(a) { color: #1769aa; text-decoration: underline; }
.assistant-error { color: #b33232; }
.assistant-progress { position: sticky; top: 0; z-index: 1; margin: 0 0 12px; padding: 8px 10px; color: #245b9e; background: #edf5ff; border-radius: 8px; }
.message-status { display: block; margin-top: 8px; color: #9b5b13; }
footer { display: flex; flex-wrap: wrap; gap: 8px; padding: 14px; border-top: 1px solid #e1e8f0; background: white; }
textarea { width: 100%; resize: vertical; max-height: 160px; padding: 10px; border: 1px solid #cbd9e8; border-radius: 8px; font: inherit; color: #20344e; }
small { flex: 1; color: #66788e; font-size: 12px; }
.assistant-history { position: absolute; inset: 58px 36px 0 0; z-index: 1; background: white; box-shadow: 10px 0 25px #0002; padding: 16px; overflow-y: auto; }
.assistant-history p { margin: 14px 0; }
.history-item { display: flex; align-items: center; }
.history-title { flex: 1; min-width: 0; text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-actions { display: flex; justify-content: flex-start; }
.assistant-knowledge > small { display: block; margin-bottom: 14px; line-height: 1.5; }
.knowledge-item { display: flex; align-items: flex-start; gap: 8px; padding: 10px 4px; border-bottom: 1px solid #e6edf5; }
.knowledge-item > div { flex: 1; min-width: 0; }
.knowledge-item strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-item small { display: block; margin-top: 4px; }
.knowledge-empty { color: #66788e; }
.assistant-resize-handle { position: absolute; right: 0; bottom: 0; z-index: 4; width: 22px; height: 22px; cursor: nwse-resize; touch-action: none; outline: none; }
.assistant-resize-handle::after { content: ''; position: absolute; right: 5px; bottom: 5px; width: 10px; height: 10px; border-right: 2px solid #72a3d8; border-bottom: 2px solid #72a3d8; }
.assistant-resize-handle:focus-visible { background: #dcecff; border-radius: 6px 0 14px; }
@media (max-width: 520px), (max-height: 560px) {
  .assistant-panel { min-width: 0; min-height: 0; border-radius: 12px; }
  header { padding-left: 12px; }
  button { padding: 7px 9px; }
}
</style>
