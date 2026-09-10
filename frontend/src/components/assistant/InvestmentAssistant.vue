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
      <div v-if="error" role="alert" class="assistant-error">{{ error }} <button @click="load">重试</button></div>
      <article v-for="message in messages" :key="message.id" :class="['assistant-message', message.role]">
        {{ message.content }}
      </article>
      <button v-if="!busy && !error && !activeId" @click="createConversation">开始新对话</button>
    </div>
    <footer>
      <textarea v-model="draft" aria-label="提问" placeholder="会话管理已接通，模型问答正在接入" rows="3" />
      <small>开发阶段：已支持会话保存；分析与流式问答尚未启用。</small>
      <button disabled>发送</button>
    </footer>
  </aside>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { httpClient } from '@/utils/httpClient'

const opened = ref(true)
const historyOpen = ref(false)
const busy = ref(false)
const error = ref('')
const conversations = ref([])
const messages = ref([])
const activeId = ref(null)
const draft = ref('')
let disposed = false
const controller = new AbortController()
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
function onKey(event) { if (event.key === 'Escape') { if (historyOpen.value) historyOpen.value = false; else opened.value = false } }
onMounted(() => { load(); window.addEventListener('keydown', onKey) })
onBeforeUnmount(() => { disposed = true; controller.abort(); window.removeEventListener('keydown', onKey) })
</script>

<style scoped>
.assistant-panel { position: fixed; right: 16px; top: 80px; bottom: 16px; width: clamp(380px, 25vw, 560px); max-width: calc(100vw - 32px); z-index: 2000; display: flex; flex-direction: column; color: #20344e; background: #f8fbff; border: 1px solid #d6e4f3; border-radius: 16px; box-shadow: 0 12px 45px #0005; overflow: hidden; }
header { display: flex; align-items: center; justify-content: space-between; padding: 14px 18px; background: white; border-bottom: 1px solid #e1e8f0; }
button { cursor: pointer; background: #edf4ff; color: #245b9e; border: 0; border-radius: 6px; padding: 8px 12px; margin: 2px; }
button:disabled { cursor: not-allowed; opacity: .55; }
.assistant-launch { position: fixed; right: 20px; bottom: 24px; z-index: 2000; box-shadow: 0 5px 20px #0005; }
.assistant-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 18px; }
.assistant-message { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; background: #eaf0f7; padding: 16px; border-radius: 12px; margin-bottom: 14px; }
.assistant-message.user { background: #dcecff; }
.assistant-error { color: #b33232; }
footer { display: flex; flex-wrap: wrap; gap: 8px; padding: 14px; border-top: 1px solid #e1e8f0; background: white; }
textarea { width: 100%; resize: vertical; max-height: 160px; padding: 10px; border: 1px solid #cbd9e8; border-radius: 8px; font: inherit; color: #20344e; }
small { flex: 1; color: #66788e; font-size: 12px; }
.assistant-history { position: absolute; inset: 58px 36px 0 0; z-index: 1; background: white; box-shadow: 10px 0 25px #0002; padding: 16px; overflow-y: auto; }
.assistant-history p { margin: 14px 0; }
.history-item { display: flex; align-items: center; }
.history-title { flex: 1; min-width: 0; text-align: left; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
