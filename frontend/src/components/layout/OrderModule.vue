<template>
  <div class="order-module">
    <div class="workbench-meta">
      <span class="live-state"><i></i>{{ selectedStock ? '实时行情' : '等待选股' }}</span>
      <span>最后刷新：{{ quoteUpdatedAt || '--:--:--' }}</span>
    </div>
    <div class="trade-workbench">
      <section class="security-panel">
        <el-input
          v-model="stockKeyword"
          class="security-search"
          placeholder="代码/名称/拼音"
          @keyup.enter="searchStock"
        >
          <template #append><el-button :loading="searching" @click="searchStock">搜索</el-button></template>
        </el-input>
        <div class="security-identity">
          <strong>{{ displayStockName }}</strong>
          <span>{{ selectedStock?.stock_code || '--' }}</span>
        </div>
        <div class="security-stat">
          <span>资金账号</span><strong>{{ selectedAccountDisplay }}</strong>
        </div>
        <div class="security-stat primary-stat">
          <span>可用资金（元）</span><strong>{{ formatMoney(selectedAccountInfo.cash) }}</strong>
        </div>
        <div class="security-stat">
          <span>资产总览（元）</span><strong>{{ formatMoney(selectedAccountInfo.total_asset) }}</strong>
        </div>
      </section>

      <section class="ticket-panel">
        <el-radio-group v-model="quantitySide" size="small" @change="applyRatio(selectedRatio)">
          <el-radio-button label="BUY">买入</el-radio-button>
          <el-radio-button label="SELL">卖出</el-radio-button>
        </el-radio-group>
        <el-alert v-if="!tradeEnabled" class="trade-status-alert" type="warning" :title="tradeDisabledMessage" :closable="false" />
        <div class="ticket-row">
          <label>委托价格</label>
          <div class="stepper-control">
            <button class="stepper-btn left" type="button" @click="adjustBuyPrice(-0.001)">−</button>
            <el-input v-model="buyPriceInput" class="stepper-input" @blur="syncBuyPriceFromInput" @keyup.enter="syncBuyPriceFromInput" />
            <button class="stepper-btn right" type="button" @click="adjustBuyPrice(0.001)">＋</button>
          </div><span class="unit">元</span>
        </div>
        <div class="ticket-row">
          <label>委托数量</label>
          <div class="stepper-control">
            <button class="stepper-btn left" type="button" @click="adjustBuyVolume(-100)">−</button>
            <el-input v-model="buyVolumeInput" class="stepper-input" @blur="syncBuyVolumeFromInput" @keyup.enter="syncBuyVolumeFromInput" />
            <button class="stepper-btn right" type="button" @click="adjustBuyVolume(100)">＋</button>
          </div><span class="unit">股</span>
        </div>
        <div class="ratio-row">
          <el-button class="ratio-all" size="small" @click="fillMax">全仓</el-button>
          <el-radio-group v-model="selectedRatio" size="small" @change="applyRatio">
            <el-radio-button label="1/2">1/2</el-radio-button><el-radio-button label="1/3">1/3</el-radio-button>
            <el-radio-button label="1/4">1/4</el-radio-button><el-radio-button label="1/5">1/5</el-radio-button>
          </el-radio-group>
        </div>
        <div class="ticket-summary">
          <span>{{ quantitySide === 'BUY' ? '可买' : '可卖' }} <strong>{{ quantityLimit }}</strong> 股</span>
          <span>预计金额 <strong>{{ estimatedAmount }}</strong> 元</span>
        </div>
        <el-button type="primary" :class="quantitySide === 'BUY' ? 'buy-btn' : 'sell-btn'" :loading="submitting" :disabled="submitting || !tradeEnabled" @click="submitSelectedSide">
          {{ quantitySide === 'BUY' ? '买入确认' : '卖出确认' }}
        </el-button>
      </section>

      <section class="right-panel">
        <div class="quote-header"><span>买卖五档</span><span>价格 / 数量</span></div>
        <div class="quote-table">
          <div v-for="row in quoteRows" :key="row.label" :class="['quote-row', row.type || '']">
            <span class="q-label">{{ row.label }}</span><span class="q-left">{{ row.valueLeft }}</span><span class="q-right">{{ row.valueRight }}</span>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchGuojinSimQuote, fetchQmtStatus, submitGuojinBuyOrder, submitGuojinSellOrder } from '@/api/accountApi.js'
import { usePortfolioLiveStore } from '@/store/portfolioLive.js'

const portfolioLiveStore = usePortfolioLiveStore()
const accounts = computed(() => portfolioLiveStore.accounts)
const selectedAccount = computed(() => portfolioLiveStore.selectedAccountId)
const stockKeyword = ref('')
const selectedStock = ref(null)
const buyPrice = ref(0)
const buyVolume = ref(0)
const submitting = ref(false)
const searching = ref(false)
const quote = ref(null)
const selectedRatio = ref('1/10')
const quantitySide = ref('BUY')
const buyPriceInput = ref('0.0000')
const buyVolumeInput = ref('0')
const lastAutoVolume = ref(0)
const qmtStatus = ref(null)
const qmtStatusLoading = ref(true)
const quoteUpdatedAt = ref('')
let quoteRefreshTimer = null
let quoteRefreshInFlight = false

const tradeEnabled = computed(() => qmtStatus.value?.tradeEnabled === true)
const tradeDisabledMessage = computed(() => {
  if (qmtStatusLoading.value) return '正在确认 QMT 模拟交易状态…'
  if (qmtStatus.value?.qmtConnected === false) return 'QMT 未连接，当前不能提交模拟委托'
  return 'QMT 模拟交易未启用：为防止误下单，当前页面已禁止提交委托'
})

const selectedAccountInfo = computed(() => {
  const liveAccount = portfolioLiveStore.currentLegacyAccount
  if (liveAccount?.account_id === selectedAccount.value) return liveAccount
  return accounts.value.find((acc) => acc.account_id === selectedAccount.value) || { cash: 0 }
})
const selectedAccountDisplay = computed(() => selectedAccountInfo.value.display_account_id || selectedAccount.value || '-')
const maxBuyVolume = computed(() => {
  const price = Number(buyPrice.value)
  const cash = Number(selectedAccountInfo.value.cash || 0)
  if (!Number.isFinite(price) || price <= 0) return 0
  return Math.floor(cash / price / 100) * 100
})
const maxSellVolume = computed(() => {
  const symbol = normalizeStockCode(selectedStock.value?.stock_code || stockKeyword.value)
  if (!symbol) return 0
  const position = (selectedAccountInfo.value.positions || []).find((item) =>
    normalizeStockCode(item.stock_code ?? item.symbol) === symbol)
  if (!position) return 0
  const available = Number(position.available_volume ?? position.availableQuantity ?? position.volume ?? position.quantity ?? 0)
  return Number.isFinite(available) && available > 0 ? Math.floor(available / 100) * 100 : 0
})
const quantityLimit = computed(() => quantitySide.value === 'BUY' ? maxBuyVolume.value : maxSellVolume.value)
const estimatedAmount = computed(() => formatMoney(Number(buyPrice.value || 0) * Number(buyVolume.value || 0)))

// Account/position data can finish loading after the quote request. Reapply
// the selected ratio whenever the available limit becomes available so the
// ticket is calculated from the first search click instead of a second one.
watch([quantityLimit, selectedRatio, quantitySide], () => {
  if (ratioValue(selectedRatio.value) > 0 && (buyVolume.value === 0 || buyVolume.value === lastAutoVolume.value)) {
    applyRatio(selectedRatio.value)
  }
}, { flush: 'post' })

const quoteRows = computed(() => {
  const q = quote.value || {}
  const latest = Number(q.latest || 0)
  const preClose = Number(q.pre_close || 0)
  const change = Number(q.change ?? (latest > 0 && preClose > 0 ? latest - preClose : 0))
  const limitUp = getLimitPrice(preClose, 'up')
  const limitDown = getLimitPrice(preClose, 'down')
  return [
    { label: '卖5', valueLeft: display4(q.ask_prices?.[4]), valueRight: displayInt(q.ask_volumes?.[4]), type: 'sell' },
    { label: '卖4', valueLeft: display4(q.ask_prices?.[3]), valueRight: displayInt(q.ask_volumes?.[3]), type: 'sell' },
    { label: '卖3', valueLeft: display4(q.ask_prices?.[2]), valueRight: displayInt(q.ask_volumes?.[2]), type: 'sell' },
    { label: '卖2', valueLeft: display4(q.ask_prices?.[1]), valueRight: displayInt(q.ask_volumes?.[1]), type: 'sell' },
    { label: '卖1', valueLeft: display4(q.ask_prices?.[0]), valueRight: displayInt(q.ask_volumes?.[0]), type: 'sell' },
    { label: '最新', valueLeft: display4(latest), valueRight: formatLatestChange(change), type: 'mid' },
    { label: '买1', valueLeft: display4(q.bid_prices?.[0]), valueRight: displayInt(q.bid_volumes?.[0]), type: 'buy' },
    { label: '买2', valueLeft: display4(q.bid_prices?.[1]), valueRight: displayInt(q.bid_volumes?.[1]), type: 'buy' },
    { label: '买3', valueLeft: display4(q.bid_prices?.[2]), valueRight: displayInt(q.bid_volumes?.[2]), type: 'buy' },
    { label: '买4', valueLeft: display4(q.bid_prices?.[3]), valueRight: displayInt(q.bid_volumes?.[3]), type: 'buy' },
    { label: '买5', valueLeft: display4(q.bid_prices?.[4]), valueRight: displayInt(q.bid_volumes?.[4]), type: 'buy' },
    { label: '现价', valueLeft: display4(latest), valueRight: `涨停 ${display4(limitUp)}`, type: 'bottom' },
    { label: '前收', valueLeft: display4(preClose), valueRight: `跌停 ${display4(limitDown)}`, type: 'bottom' }
  ]
})

const displayStockName = computed(() => quote.value?.stock_name || '-')


async function loadQmtTradeStatus() {
  qmtStatusLoading.value = true
  try {
    qmtStatus.value = await fetchQmtStatus()
  } catch {
    qmtStatus.value = { qmtConnected: false, tradeEnabled: false }
  } finally {
    qmtStatusLoading.value = false
  }
}

onMounted(() => {
  portfolioLiveStore.initialize().catch((error) => {
    console.error('初始化交易账户失败：', error)
  })
  loadQmtTradeStatus()
})

onBeforeUnmount(() => {
  stopQuoteRefresh()
})

async function searchStock() {
  if (searching.value) return
  const keyword = stockKeyword.value.trim()
  if (!keyword) {
    ElMessage.warning('请输入证券代码或名称')
    return
  }

  searching.value = true
  try {
    // MiniQMT subscription is asynchronous. Keep the retry inside this
    // single click so a newly subscribed symbol is not rendered half-empty
    // and the user never has to click Search a second time.
    const response = await fetchQuoteUntilComplete(keyword)
    let quoteData = response?.data || response || {}
    if (Array.isArray(quoteData)) quoteData = quoteData[0] || {}

    const normalizedQuote = {
      ...quoteData,
      stock_code: normalizeStockCode(quoteData.stock_code || keyword),
      stock_name: String(quoteData.stock_name || '-'),
      latest: Number(quoteData.latest ?? quoteData.lastPrice ?? quoteData.price ?? 0),
      pre_close: Number(quoteData.pre_close ?? quoteData.previousClose ?? 0),
      open: Number(quoteData.open ?? quoteData.openPrice ?? 0),
      high: Number(quoteData.high ?? quoteData.highPrice ?? 0),
      low: Number(quoteData.low ?? quoteData.lowPrice ?? 0),
      bid_prices: Array.isArray(quoteData.bid_prices ?? quoteData.bidPrices) ? (quoteData.bid_prices ?? quoteData.bidPrices).map((v) => Number(v || 0)) : [],
      ask_prices: Array.isArray(quoteData.ask_prices ?? quoteData.askPrices) ? (quoteData.ask_prices ?? quoteData.askPrices).map((v) => Number(v || 0)) : [],
      bid_volumes: Array.isArray(quoteData.bid_volumes ?? quoteData.bidVolumes) ? (quoteData.bid_volumes ?? quoteData.bidVolumes).map((v) => Number(v || 0)) : [],
      ask_volumes: Array.isArray(quoteData.ask_volumes ?? quoteData.askVolumes) ? (quoteData.ask_volumes ?? quoteData.askVolumes).map((v) => Number(v || 0)) : []
    }

    if (!normalizedQuote.stock_name ||
      (normalizedQuote.stock_name === normalizedQuote.stock_code && normalizedQuote.latest <= 0)) {
      quote.value = {
        stock_code: normalizedQuote.stock_code,
        stock_name: 'QMT未收录该代码（可能已退市或代码无效）'
      }
      selectedStock.value = null
      buyPrice.value = 0
      buyPriceInput.value = '0.00'
      setOrderVolume(0)
      ElMessage.warning('QMT未收录该证券，不能下单')
      return
    }

    quote.value = { ...normalizedQuote }
    selectedStock.value = { ...normalizedQuote }
    quoteUpdatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
    stockKeyword.value = normalizedQuote.stock_code || keyword
    const ticketReady = syncTicketFromQuote(normalizedQuote, true)
    if (!ticketReady) await refreshSelectedQuote()
    startQuoteRefresh()
  } catch (error) {
    console.error('搜索行情失败：', error)
    ElMessage.error('搜索失败')
  } finally {
    searching.value = false
  }
}

async function fetchQuoteUntilComplete(keyword) {
  let response = null
  for (let attempt = 0; attempt < 6; attempt += 1) {
    response = await fetchGuojinSimQuote(keyword, { allowStale: false })
    if (isCompleteQuote(response, keyword)) return response
    if (attempt < 5) await waitForQuote(180 + attempt * 80)
  }
  return response
}

function isCompleteQuote(data, keyword) {
  const quoteData = data?.data || data || {}
  const symbol = normalizeStockCode(quoteData.stock_code ?? quoteData.symbol ?? keyword)
  const name = String(quoteData.stock_name ?? quoteData.name ?? '').trim()
  const latest = Number(quoteData.latest ?? quoteData.lastPrice ?? quoteData.price ?? 0)
  const bids = quoteData.bid_prices ?? quoteData.bidPrices ?? []
  const asks = quoteData.ask_prices ?? quoteData.askPrices ?? []
  return latest > 0 && Boolean(name) && name !== symbol && (bids.length > 0 || asks.length > 0)
}

function waitForQuote(milliseconds) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
}

function startQuoteRefresh() {
  stopQuoteRefresh()
  scheduleNextQuoteRefresh()
}

function stopQuoteRefresh() {
  if (quoteRefreshTimer !== null) window.clearTimeout(quoteRefreshTimer)
  quoteRefreshTimer = null
}

function scheduleNextQuoteRefresh() {
  const symbol = selectedStock.value?.stock_code
  if (!symbol) return
  const delay = typeof document !== 'undefined' && document.hidden ? 5000 : 1000
  quoteRefreshTimer = window.setTimeout(async () => {
    quoteRefreshTimer = null
    await refreshSelectedQuote()
    scheduleNextQuoteRefresh()
  }, delay)
}

async function refreshSelectedQuote() {
  const symbol = selectedStock.value?.stock_code
  if (!symbol || quoteRefreshInFlight) return
  quoteRefreshInFlight = true
  try {
    // Do not reuse the short-lived Redis entry when the ticket is still
    // incomplete; that entry may be the metadata-only first subscription.
    const response = await fetchGuojinSimQuote(symbol, { allowStale: Number(buyPrice.value) > 0 })
    const quoteData = response?.data || response || {}
    if (!quoteData || Number(quoteData.latest ?? quoteData.lastPrice ?? quoteData.price ?? 0) <= 0) return
    quote.value = {
      ...quote.value,
      ...quoteData,
      stock_code: normalizeStockCode(quoteData.stock_code ?? quoteData.symbol ?? symbol),
      stock_name: String(quoteData.stock_name ?? quoteData.name ?? quote.value?.stock_name ?? symbol),
      latest: Number(quoteData.latest ?? quoteData.lastPrice ?? quoteData.price),
      pre_close: Number(quoteData.pre_close ?? quoteData.previousClose ?? quote.value?.pre_close ?? 0),
      bid_prices: quoteData.bid_prices ?? quoteData.bidPrices ?? quote.value?.bid_prices ?? [],
      ask_prices: quoteData.ask_prices ?? quoteData.askPrices ?? quote.value?.ask_prices ?? [],
      bid_volumes: quoteData.bid_volumes ?? quoteData.bidVolumes ?? quote.value?.bid_volumes ?? [],
      ask_volumes: quoteData.ask_volumes ?? quoteData.askVolumes ?? quote.value?.ask_volumes ?? []
    }
    selectedStock.value = { ...selectedStock.value, stock_name: quote.value.stock_name }
    // The first subscription can initially return only instrument metadata.
    // When the first real tick arrives, fill the ticket immediately instead
    // of leaving price/quantity at zero until the user searches again.
    syncTicketFromQuote(quote.value, Number(buyPrice.value) <= 0)
    quoteUpdatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    // Keep the last valid quote visible and retry on the next scheduled refresh.
    console.warn('刷新QMT行情失败：', error)
  } finally {
    quoteRefreshInFlight = false
  }
}

function syncTicketFromQuote(quoteData, force = false) {
  const bestAsk = Number(quoteData?.ask_prices?.[0] ?? quoteData?.askPrices?.[0] ?? 0)
  const latest = Number(quoteData?.latest ?? quoteData?.lastPrice ?? quoteData?.price ?? 0)
  const targetPrice = bestAsk > 0 ? bestAsk : latest
  if (!Number.isFinite(targetPrice) || targetPrice <= 0) return false
  if (!force && Number(buyPrice.value) > 0) return false

  buyPrice.value = Number(targetPrice.toFixed(2))
  buyPriceInput.value = buyPrice.value.toFixed(2)
  applyRatio(selectedRatio.value)
  return true
}

function applyRatio(ratio) {
  const percent = ratioValue(ratio)
  if (!Number.isFinite(percent) || percent <= 0) return
  const volume = lotFloor(quantityLimit.value * percent)
  lastAutoVolume.value = volume
  setOrderVolume(volume)
}

function syncBuyPriceFromInput() {
  const value = Number(buyPriceInput.value)
  if (!Number.isFinite(value) || value <= 0) {
    buyPrice.value = 0
    buyPriceInput.value = '0.00'
    return
  }
  buyPrice.value = Number(value.toFixed(2))
  buyPriceInput.value = buyPrice.value.toFixed(2)
}

function adjustBuyPrice(delta) {
  const current = Number(buyPriceInput.value || buyPrice.value || 0)
  const next = current + Number(delta || 0)
  if (!Number.isFinite(next) || next <= 0) return
  buyPrice.value = Number(next.toFixed(2))
  buyPriceInput.value = buyPrice.value.toFixed(2)
}

function syncBuyVolumeFromInput() {
  const value = Number(buyVolumeInput.value)
  if (!Number.isFinite(value) || value <= 0) {
    buyVolume.value = 0
    buyVolumeInput.value = '0'
    return
  }
  setOrderVolume(lotFloor(value))
}

function adjustBuyVolume(delta) {
  const current = Number(buyVolumeInput.value || buyVolume.value || 0)
  const next = current + Number(delta || 0)
  if (!Number.isFinite(next) || next <= 0) return
  setOrderVolume(lotFloor(next))
}



function fillMax() {
  setOrderVolume(quantityLimit.value)
}

function ratioValue(ratio) {
  return {
    '1/2': 1 / 2,
    '1/3': 1 / 3,
    '1/4': 1 / 4,
    '1/5': 1 / 5,
    '1/10': 1 / 10
  }[String(ratio)] || 0
}

function lotFloor(value) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? Math.floor(number / 100) * 100 : 0
}

function setOrderVolume(value) {
  buyVolume.value = lotFloor(value)
  buyVolumeInput.value = String(buyVolume.value)
}

function submitSelectedSide() {
  return quantitySide.value === 'BUY' ? submitBuy() : submitSell()
}

async function submitBuy() {
  if (!tradeEnabled.value) return ElMessage.warning(tradeDisabledMessage.value)
  const accountId = selectedAccount.value
  if (!accountId) return ElMessage.warning('当前未获取到可用账户')
  if (!selectedStock.value?.stock_code && !stockKeyword.value.trim()) return ElMessage.warning('请先搜索证券代码')
  if (buyPrice.value <= 0 || buyVolume.value <= 0) return ElMessage.warning('请输入有效的买入价格和数量')

  const price = Number(buyPrice.value)
  const volume = Math.floor(Number(buyVolume.value) / 100) * 100
  const stockCode = normalizeStockCode(selectedStock.value?.stock_code || stockKeyword.value.trim())

  if (!stockCode) return ElMessage.warning('证券代码无效')
  if (!Number.isFinite(price) || price <= 0) return ElMessage.warning('买入价格无效')
  if (!Number.isFinite(volume) || volume <= 0) return ElMessage.warning('买入数量必须为100的整数倍')

  submitting.value = true
  try {
    const payload = {
      account_id: accountId,
      stock_code: stockCode,
      stock_name: selectedStock.value?.stock_name || '',
      price,
      volume,
      action: 'buy'
    }
    const result = await submitGuojinBuyOrder(payload)
    window.dispatchEvent(new CustomEvent('qmt-order-submitted'))
    ElMessage.success(result?.message || '买入已提交')
  } catch (error) {
    console.error('提交买入失败：', error)
    ElMessage.error(error?.response?.data?.message || error?.message || '买入失败')
  } finally {
    submitting.value = false
  }
}

async function submitSell() {
  if (!tradeEnabled.value) return ElMessage.warning(tradeDisabledMessage.value)
  const accountId = selectedAccount.value
  if (!accountId) return ElMessage.warning('当前未获取到可用账户')
  if (!selectedStock.value?.stock_code && !stockKeyword.value.trim()) return ElMessage.warning('请先搜索证券代码')
  if (buyPrice.value <= 0 || buyVolume.value <= 0) return ElMessage.warning('请输入有效的卖出价格和数量')

  const price = Number(buyPrice.value)
  const volume = Math.floor(Number(buyVolume.value) / 100) * 100
  const stockCode = normalizeStockCode(selectedStock.value?.stock_code || stockKeyword.value.trim())

  if (!stockCode) return ElMessage.warning('证券代码无效')
  if (!Number.isFinite(price) || price <= 0) return ElMessage.warning('卖出价格无效')
  if (!Number.isFinite(volume) || volume <= 0) return ElMessage.warning('卖出数量必须为100的整数倍')

  submitting.value = true
  try {
    const payload = {
      account_id: accountId,
      stock_code: stockCode,
      stock_name: selectedStock.value?.stock_name || '',
      price,
      volume,
      action: 'sell'
    }
    const result = await submitGuojinSellOrder(payload)
    window.dispatchEvent(new CustomEvent('qmt-order-submitted'))
    ElMessage.success(result?.message || '卖出已提交')
  } catch (error) {
    console.error('提交卖出失败：', error)
    ElMessage.error(error?.response?.data?.message || error?.message || '卖出失败')
  } finally {
    submitting.value = false
  }
}

function normalizeStockCode(code) {
  const raw = String(code || '').trim().toUpperCase()
  if (!raw) return ''
  if (raw.includes('.')) return raw
  if (/^6\d{5}$/.test(raw)) return `${raw}.SH`
  if (/^[03]\d{5}$/.test(raw)) return `${raw}.SZ`
  return raw
}

function getLimitPrice(preClose, direction) {
  const base = Number(preClose)
  if (!Number.isFinite(base) || base <= 0) return 0
  const ratio = direction === 'down' ? 0.9 : 1.1
  return Number((base * ratio).toFixed(3))
}


function display4(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(3) : '-'
}

function displayInt(value) {
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num).toString() : '-'
}

function formatLatestChange(change) {
  const num = Number(change)
  if (!Number.isFinite(num)) return '-'
  return `${num >= 0 ? '+' : ''}${num.toFixed(3)}`
}

function formatMoney(value) {
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : '0.00'
}
</script>

<style scoped>
.order-module {
  display: flex;
  gap: 5px;
  padding: 5px;
  background: #eef5ff;
  color: #16324f;
  min-height: 100%;
  width: 80%;
  margin: 0 auto;
  box-sizing: border-box;
}

.trade-status-alert {
  margin: 0 0 10px 0;
  --el-alert-padding: 7px 10px;
}
.left-panel {
  width: 56%;
  min-width: 0;
  padding: 12px;
  border: 1px solid #9bb8d3;
  border-radius: 2px;
  background: #eef5ff;
  box-sizing: border-box;
}
.right-panel {
  width: 44%;
  min-width: 0;
  padding: 0;
  border: 1px solid #9bb8d3;
  border-radius: 2px;
  background: #eef5ff;
  overflow: hidden;
  box-sizing: border-box;
}
.form-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: nowrap;
}
.form-row label {
  width: 78px;
  font-size: 14px;
  font-weight: 600;
  color: #1f3349;
  flex: 0 0 75px;
  text-align: right;
}
.input-box {
  flex: 1;
  min-width: 0;
}
.stepper-control {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: stretch;
}
.stepper-input {
  flex: 1;
  min-width: 0;
}
:deep(.input-box .el-input__wrapper),
:deep(.stepper-input .el-input__wrapper) {
  background: #ffffff;
  border: 1px solid #9bb8d3;
  box-shadow: none;
  height: 32px;
}
:deep(.input-box .el-input__inner),
:deep(.stepper-input .el-input__inner) {
  color: #17324b;
  font-weight: 700;
}
.stepper-btn {
  width: 30px;
  min-width: 30px;
  padding: 0;
  border: 1px solid #9bb8d3;
  background: #ffffff;
  color: #1d67ff;
  font-weight: 800;
  cursor: pointer;
}
.stepper-btn.left {
  border-right: 0;
  border-top-left-radius: 4px;
  border-bottom-left-radius: 4px;
}
.stepper-btn.right {
  border-left: 0;
  border-top-right-radius: 4px;
  border-bottom-right-radius: 4px;
}
:deep(.stepper-control .el-input__wrapper) {
  border-radius: 0;
}
.readonly-box {
  flex: 1;
  min-width: 0;
  min-height: 32px;
  display: flex;
  align-items: center;
  padding: 0 10px;
  background: #ffffff;
  border: 1px solid #9bb8d3;
  border-radius: 4px;
  color: #17324b;
  box-sizing: border-box;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.account-box,
.value-box {
  font-size: 14px;
  font-weight: 700;
}
.stock-name {
  color: #ff5a1f;
}
.highlight {
  color: #8a6a00;
}
.highlight-value {
  color: #1f67ff;
  font-weight: 800;
}
.unit {
  width: 24px;
  text-align: right;
  color: #1f3349;
  flex: 0 0 24px;
}
.search-btn,
.all-btn {
  min-width: 56px;
  padding: 0 12px;
}
.max-buy-row {
  align-items: center;
}
.max-buy-box {
  max-width: 150px;
}
.ratio-row {
  margin: 4px 0 10px 78px;
  white-space: nowrap;
}
.quantity-side-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 2px 0 6px 78px;
  font-size: 13px;
  color: #1f3349;
}
:deep(.ratio-row .el-radio-group) {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: nowrap;
}
:deep(.ratio-row .el-radio-button) {
  margin-right: 0;
}
:deep(.ratio-row .el-radio-button__inner) {
  border: 0;
  background: transparent;
  padding: 0;
  line-height: 1;
  height: auto;
  font-size: 13px;
  color: #1f67ff;
  box-shadow: none;
}
:deep(.ratio-row .el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: transparent;
  color: #1f67ff;
  box-shadow: none;
}
:deep(.ratio-row .el-radio-button__inner::before) {
  content: '';
  display: inline-block;
  width: 10px;
  height: 10px;
  border: 1.5px solid #1f67ff;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: -1px;
  background: #ffffff;
}
:deep(.ratio-row .el-radio-button__original-radio:checked + .el-radio-button__inner::before) {
  background: #ffffff;
  box-shadow: inset 0 0 0 2px #ffffff, inset 0 0 0 5px #1f67ff;
}
.trade-btn-row {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 12px;
  padding-left: 0;
}
.buy-btn,
.sell-btn {
  width: 104px;
  min-width: 104px;
  height: 34px;
  padding: 0 12px;
  font-size: 14px;
  font-weight: 800;
}
.quote-table {
  display: grid;
  grid-auto-rows: 1fr;
  background: #eef5ff;
}
.quote-row {
  display: grid;
  grid-template-columns: 42px 1fr 1fr;
  align-items: center;
  min-height: 28px;
  padding: 0 8px;
  border-bottom: 1px solid #c9d9e8;
  font-size: 14px;
  background: transparent;
  column-gap: 10px;
  white-space: nowrap;
}
.q-label {
  color: #1f3349;
  font-weight: 700;
}
.q-left,
.q-right {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: #18a000;
  font-weight: 700;
}
.right-label {
  color: #1f3349;
  font-weight: 700;
  margin-right: 4px;
}
.quote-row.sell .q-left,
.quote-row.sell .q-right {
  color: #18a000;
}
.quote-row.mid .q-left,
.quote-row.mid .q-right {
  color: #00b050;
}
.quote-row.buy .q-left,
.quote-row.buy .q-right {
  color: #d94b00;
}
.quote-row.bottom .q-left,
.quote-row.bottom .q-right {
  color: #1f3349;
}
:deep(.el-button--primary) {
  background: #1d67ff;
  border-color: #1d67ff;
}
.sell-btn {
  background: #ff8a1f;
  border-color: #ff8a1f;
  color: #ffffff;
}
.sell-btn:hover,
.sell-btn:focus {
  background: #ff7a00;
  border-color: #ff7a00;
  color: #ffffff;
}
:deep(.el-radio-button__inner) {
  background: #ffffff;
  border-color: #9bb8d3;
  color: #1f3349;
}
:deep(.el-radio-button.is-active .el-radio-button__inner) {
  background: #1d67ff;
  border-color: #1d67ff;
  box-shadow: none;
}

/* C 方案：证券、下单、盘口三列工作台。这里显式覆盖旧版固定宽度和浅色背景。 */
.order-module { display: block; width: 100%; max-width: none; height: 100%; min-height: 0; margin: 0; padding: 0; border: 0; background: transparent; color: #dcecff; font-variant-numeric: tabular-nums; }
.workbench-meta { height: 20px; margin-bottom: 6px; padding-right: 4px; display: flex; justify-content: flex-end; align-items: center; gap: 18px; color: #6e91ad; font-size: 11px; pointer-events: none; }
.live-state { color: #42df91; }
.live-state i { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #37e894; box-shadow: 0 0 7px #37e894; }
.trade-workbench { display: grid; grid-template-columns: minmax(170px, .78fr) minmax(240px, 1.05fr) minmax(210px, .88fr); gap: 10px; width: 100%; height: calc(100% - 26px); min-height: 300px; }
.security-panel, .ticket-panel, .order-module .right-panel { width: auto; min-width: 0; box-sizing: border-box; border: 1px solid rgba(64, 224, 255, .22); border-radius: 7px; background: linear-gradient(145deg, rgba(17, 39, 67, .94), rgba(8, 21, 41, .96)); }
.security-panel, .ticket-panel { padding: 12px; display: flex; flex-direction: column; }
.security-search { margin-bottom: 13px; }
:deep(.security-search .el-input__wrapper), :deep(.security-search .el-input-group__append) { background: rgba(5, 17, 35, .75); border: 0; box-shadow: 0 0 0 1px rgba(100, 177, 231, .38) inset; color: #dff3ff; }
:deep(.security-search .el-input__inner) { color: #eef9ff; }
:deep(.security-search .el-input-group__append button) { color: #7edfff; }
.security-identity { display: flex; align-items: baseline; gap: 7px; padding-bottom: 12px; margin-bottom: 7px; border-bottom: 1px solid rgba(100, 177, 231, .18); }
.security-identity strong { color: #ecf7ff; font-size: 17px; }
.security-identity span, .security-stat span { color: #7799b5; font-size: 11px; }
.security-stat { display: flex; flex-direction: column; gap: 5px; padding: 8px 0; }
.security-stat strong { color: #dcecff; font-size: 14px; }
.security-stat.primary-stat strong { color: #44d9ff; font-size: 19px; }
.ticket-panel > :deep(.el-radio-group) { width: 100%; display: flex; margin-bottom: 13px; }
.ticket-panel > :deep(.el-radio-group .el-radio-button) { flex: 1; }
.ticket-panel > :deep(.el-radio-group .el-radio-button__inner) { width: 100%; height: 34px; padding: 9px 12px; background: rgba(14, 35, 62, .9); border-color: rgba(100, 177, 231, .35); color: #c1d9eb; box-shadow: none; }
.ticket-panel > :deep(.el-radio-group .el-radio-button.is-active:first-child .el-radio-button__inner) { color: #ff7880; background: rgba(114, 25, 42, .42); border-color: #db4353; }
.ticket-panel > :deep(.el-radio-group .el-radio-button.is-active:last-child .el-radio-button__inner) { color: #45dc93; background: rgba(20, 90, 66, .4); border-color: #31b97d; }
.ticket-row { display: flex; align-items: center; gap: 7px; margin-bottom: 10px; }
.ticket-row label { flex: 0 0 55px; color: #aac5da; font-size: 12px; }
.stepper-control { flex: 1; min-width: 0; }
.ticket-panel :deep(.stepper-input .el-input__wrapper) { height: 32px; background: rgba(5, 17, 35, .72); border-color: rgba(100, 177, 231, .4); }
.ticket-panel :deep(.stepper-input .el-input__inner) { color: #eef8ff; text-align: center; }
.ticket-panel .stepper-btn { height: 32px; background: rgba(7, 25, 49, .96); border-color: rgba(100, 177, 231, .4); color: #48d8ff; }
.ticket-panel .unit { width: 18px; flex-basis: 18px; color: #7799b5; font-size: 11px; }
.ticket-panel .ratio-row { display: flex; gap: 7px; margin: 0 0 10px; }
.ratio-all { color: #4edcff; border-color: rgba(63, 193, 232, .45); background: rgba(12, 48, 76, .74); }
.ticket-panel .ratio-row :deep(.el-radio-group) { flex: 1; display: flex; gap: 5px; }
.ticket-panel .ratio-row :deep(.el-radio-button) { flex: 1; }
.ticket-panel .ratio-row :deep(.el-radio-button__inner) { width: 100%; padding: 6px 4px; color: #9cc7e2; border: 1px solid rgba(100, 177, 231, .34); border-radius: 4px; background: rgba(9, 29, 54, .82); box-shadow: none; }
.ticket-panel .ratio-row :deep(.el-radio-button.is-active .el-radio-button__inner) { color: #fff; border-color: #228acb; background: #155f98; }
.ticket-summary { display: flex; justify-content: space-between; gap: 8px; margin: 2px 0 12px; color: #82a4bd; font-size: 11px; }
.ticket-summary strong { color: #e7f7ff; font-size: 13px; }
.ticket-panel .buy-btn, .ticket-panel .sell-btn { width: 100%; height: 38px; margin-top: auto; border: 0; color: #fff; font-weight: 700; background: linear-gradient(90deg, #f04455, #ff5966); box-shadow: 0 4px 15px rgba(235, 55, 72, .23); }
.ticket-panel .sell-btn { background: linear-gradient(90deg, #1ba968, #32c47e); box-shadow: 0 4px 15px rgba(32, 183, 111, .22); }
.order-module .right-panel { padding: 0; overflow: hidden; }
.quote-header { display: flex; justify-content: space-between; padding: 10px 11px; color: #b0cada; font-size: 12px; border-bottom: 1px solid rgba(100, 177, 231, .22); background: rgba(6, 22, 43, .72); }
.quote-table { display: grid; background: transparent; }
.quote-row { min-height: 22px; padding: 0 10px; grid-template-columns: 34px 1fr 1fr; border-color: rgba(100, 177, 231, .13); font-size: 12px; }
.q-label { color: #b9d1e4; }.quote-row.sell .q-left, .quote-row.sell .q-right { color: #ff626b; }.quote-row.buy .q-left, .quote-row.buy .q-right { color: #34d486; }
.quote-row.mid { background: rgba(28, 82, 114, .25); }.quote-row.mid .q-left, .quote-row.mid .q-right { color: #46dfff; }.quote-row.bottom .q-left, .quote-row.bottom .q-right { color: #d9eafd; }
.trade-status-alert { margin-bottom: 8px; --el-alert-padding: 5px 8px; }
@media (max-width: 1400px) { .trade-workbench { grid-template-columns: minmax(155px, .72fr) minmax(220px, 1fr) minmax(190px, .82fr); gap: 7px; }.security-panel, .ticket-panel { padding: 9px; } }
</style>
