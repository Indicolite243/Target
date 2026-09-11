<template>
  <button v-if="!opened" class="assistant-launch" @click="opened = true">投研助手</button>
  <aside v-show="opened" class="assistant-panel" aria-label="投研助手">
    <header>
      <strong>投研助手</strong>
      <div>
        <button aria-label="历史会话" @click="historyOpen = !historyOpen">会话</button>
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
      <small>当前已接通千问、当前持仓和最近回测；历史归因与知识库能力正在接入，涉及缺失数据时会明确提示。</small>
      <button v-if="working" aria-label="停止生成" @click="cancelGeneration">停止</button>
      <button v-else aria-label="发送问题" :disabled="busy || !activeId || !draft.trim()" @click="ask">发送</button>
    </footer>
  </aside>
</template>

<script setup>
import { onMounted, onBeforeUnmount, reactive, ref, shallowRef } from 'vue'
import { ElMessageBox } from 'element-plus'
import { httpClient } from '@/utils/httpClient'
import { renderSafeMarkdown } from '@/utils/safeMarkdown.js'
import { streamAssistant } from '@/services/assistantStream.js'

const opened = ref(true)
const historyOpen = ref(false)
const busy = ref(false)
const error = ref('')
const conversations = ref([])
const messages = ref([])
const activeId = ref(null)
const draft = ref('')
const working = shallowRef(null)
const phase = ref('准备生成')
const elapsedSeconds = ref(0)
let disposed = false
const controller = new AbortController()
let elapsedTimer = null
const endpoint = '/assistant/conversations'

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
function onKey(event) { if (event.key === 'Escape') { if (historyOpen.value) historyOpen.value = false; else opened.value = false } }
onMounted(() => { load(); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => {
  disposed = true
  controller.abort()
  working.value?.controller.abort()
  if (elapsedTimer) window.clearInterval(elapsedTimer)
  window.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
.assistant-panel { position: fixed; right: 16px; top: 80px; bottom: 16px; width: clamp(380px, 25vw, 560px); max-width: calc(100vw - 32px); z-index: 2000; display: flex; flex-direction: column; color: #20344e; background: #f8fbff; border: 1px solid #d6e4f3; border-radius: 16px; box-shadow: 0 12px 45px #0005; overflow: hidden; }
header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; background: white; border-bottom: 1px solid #e1e8f0; }
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
</style>
