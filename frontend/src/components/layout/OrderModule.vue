<template>
  <div class="order-module">
    <div class="left-panel">
      <div class="form-row">
        <label>账号</label>
        <div class="readonly-box account-box">{{ selectedAccountDisplay }}</div>
      </div>

      <div class="form-row">
        <label>证券代码</label>
        <el-input
          v-model="stockKeyword"
          class="input-box"
          placeholder="输入代码或名称搜索"
          @keyup.enter="searchStock"
        />
        <el-button type="primary" class="search-btn" @click="searchStock">搜索</el-button>
      </div>

      <div class="form-row">
        <label>股票名称</label>
        <div class="readonly-box value-box stock-name">{{ displayStockName }}</div>
        <span class="unit"></span>
      </div>

      <div class="form-row">
        <label>买入价格</label>
        <div class="stepper-control">
          <button class="stepper-btn left" type="button" @click="adjustBuyPrice(-0.001)">-</button>
          <el-input
            v-model="buyPriceInput"
            class="stepper-input"
            @blur="syncBuyPriceFromInput"
            @keyup.enter="syncBuyPriceFromInput"
          />
          <button class="stepper-btn right" type="button" @click="adjustBuyPrice(0.001)">+</button>
        </div>
        <span class="unit">元</span>
      </div>

      <div class="form-row">
        <label>可用资金</label>
        <div class="readonly-box value-box highlight">{{ formatMoney(selectedAccountInfo.cash) }}</div>
        <span class="unit">元</span>
      </div>

      <div class="form-row max-buy-row">
        <label>最大可买</label>
        <div class="readonly-box value-box max-buy-box">{{ maxBuyVolume }}</div>
        <el-button class="all-btn" type="primary" @click="fillMax">全部</el-button>
      </div>

      <div class="ratio-row">
        <el-radio-group v-model="selectedRatio" size="small" @change="applyRatio">
          <el-radio-button label="0.5">1/2</el-radio-button>
          <el-radio-button label="0.333">1/3</el-radio-button>
          <el-radio-button label="0.25">1/4</el-radio-button>
          <el-radio-button label="0.2">1/5</el-radio-button>
          <el-radio-button label="0.1">1/10</el-radio-button>
        </el-radio-group>
      </div>

      <div class="form-row">
        <label>买入数量</label>
        <div class="stepper-control">
          <button class="stepper-btn left" type="button" @click="adjustBuyVolume(-100)">-</button>
          <el-input
            v-model="buyVolumeInput"
            class="stepper-input"
            @blur="syncBuyVolumeFromInput"
            @keyup.enter="syncBuyVolumeFromInput"
          />
          <button class="stepper-btn right" type="button" @click="adjustBuyVolume(100)">+</button>
        </div>
        <span class="unit">股</span>
      </div>

      <div class="trade-btn-row ">
        <el-button type="primary" class="buy-btn" :loading="submitting" :disabled="submitting" @click="submitBuy">
          买入
        </el-button>
        <el-button type="primary" class="sell-btn" :loading="submitting" :disabled="submitting" @click="submitSell">
          卖出
        </el-button>
      </div>
    </div>

    <div class="right-panel">
      <div class="quote-table">
        <div v-for="row in quoteRows" :key="row.label" :class="['quote-row', row.type || '']">
          <span class="q-label">{{ row.label }}</span>
          <span class="q-left">{{ row.valueLeft }}</span>
          <span class="q-right"><span v-if="row.rightLabel" class="right-label">{{ row.rightLabel }}</span>{{ row.valueRight }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchAccountInfo, fetchGuojinSimQuote, submitGuojinBuyOrder, submitGuojinSellOrder } from '@/api/accountApi.js'
import { useAccountStore } from '@/store'

const accountStore = useAccountStore()
const accounts = ref([])
const selectedAccount = ref(accountStore.selectedAccountId || '')
const stockKeyword = ref('')
const selectedStock = ref(null)
const buyPrice = ref(0)
const buyVolume = ref(0)
const submitting = ref(false)
const quote = ref(null)
const selectedRatio = ref('0.1')
const buyPriceInput = ref('0.0000')
const buyVolumeInput = ref('0')

const selectedAccountInfo = computed(() => accounts.value.find((acc) => acc.account_id === selectedAccount.value) || { cash: 0 })
const selectedAccountDisplay = computed(() => selectedAccountInfo.value.display_account_id || selectedAccount.value || '-')
const maxBuyVolume = computed(() => {
  const price = Number(buyPrice.value)
  const cash = Number(selectedAccountInfo.value.cash || 0)
  if (!Number.isFinite(price) || price <= 0) return 0
  return Math.floor(cash / price / 100) * 100
})

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


onMounted(async () => {
  try {
    const data = await fetchAccountInfo()
    if (data && data.accounts) {
      accounts.value = data.accounts
      const preferredAccountId = String(accountStore.selectedAccountId || '').trim()
      const matchedAccount = accounts.value.find((acc) => acc.account_id === preferredAccountId)
      selectedAccount.value = matchedAccount?.account_id || accounts.value[0]?.account_id || ''
    }
  } catch (error) {
    console.error('获取账户信息失败：', error)
  }
})

watch(selectedAccount, (accountId) => {
  accountStore.setSelectedAccountId(accountId)
  const matchedAccount = accounts.value.find((acc) => acc.account_id === accountId) || {}
  accountStore.setAccountInfo(matchedAccount)
}, { immediate: true })

async function searchStock() {
  const keyword = stockKeyword.value.trim()
  if (!keyword) {
    ElMessage.warning('请输入证券代码或名称')
    return
  }

  try {
    const response = await fetchGuojinSimQuote(keyword, selectedAccount.value)
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
      buyVolume.value = 0
      buyVolumeInput.value = '0'
      ElMessage.warning('QMT未收录该证券，不能下单')
      return
    }

    quote.value = { ...normalizedQuote }
    selectedStock.value = { ...normalizedQuote }
    stockKeyword.value = normalizedQuote.stock_code || keyword

    const bestAsk = Number(normalizedQuote.ask_prices?.[0] || 0)
    const latest = Number(normalizedQuote.latest || 0)
    const targetPrice = bestAsk > 0 ? bestAsk : latest
    buyPrice.value = targetPrice > 0 ? Number(targetPrice.toFixed(2)) : 0
    buyPriceInput.value = buyPrice.value > 0 ? buyPrice.value.toFixed(2) : '0.00'

    const cash = Number(selectedAccountInfo.value.cash || 0)
    if (buyPrice.value > 0 && cash > 0) {
      const rawVolume = Math.floor((cash / buyPrice.value) / 100) * 100
      buyVolume.value = Math.max(100, rawVolume > 0 ? rawVolume : 100)
    } else {
      buyVolume.value = 0
    }
    buyVolumeInput.value = String(buyVolume.value || 0)

    applyRatio(selectedRatio.value)
  } catch (error) {
    console.error('搜索行情失败：', error)
    ElMessage.error('搜索失败')
  }
}

function applyRatio(ratio) {
  const percent = Number(ratio || 0)
  if (!Number.isFinite(percent) || percent <= 0) return
  const base = maxBuyVolume.value
  if (base <= 0) return
  const volume = Math.floor((base * percent) / 100) * 100
  buyVolume.value = volume > 0 ? volume : 100
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
  buyVolume.value = Math.floor(value / 100) * 100
  buyVolumeInput.value = String(buyVolume.value || 0)
}

function adjustBuyVolume(delta) {
  const current = Number(buyVolumeInput.value || buyVolume.value || 0)
  const next = current + Number(delta || 0)
  if (!Number.isFinite(next) || next <= 0) return
  buyVolume.value = Math.floor(next / 100) * 100
  buyVolumeInput.value = String(buyVolume.value || 0)
}



function fillMax() {
  buyVolume.value = Math.max(Math.floor(maxBuyVolume.value / 100) * 100, 100)
}

async function submitBuy() {
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
    ElMessage.error(error?.response?.data?.message || '买入失败')
  } finally {
    submitting.value = false
  }
}

async function submitSell() {
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
    ElMessage.error(error?.response?.data?.message || '卖出失败')
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
</style>
