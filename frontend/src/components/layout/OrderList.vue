<template>
  <div class="module-card">
    <input
      ref="csvFileInput"
      type="file"
      accept=".csv"
      style="display: none;"
      @change="handleCsvRead"
    />

    <div class="table-container">
      <el-table
        :data="orderList"
        border
        stripe
        style="width: 100%"
        :max-height="tableMaxHeight"
        :header-cell-style="headerStyle"
        :cell-style="cellStyle"
        :row-class-name="tableRowClassName"
      >
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="entrust_time" label="委托时间" min-width="100" align="center" />
        <el-table-column prop="stock_code" label="证券代码" min-width="110" align="center" />
        <el-table-column prop="stock_name" label="证券名称" min-width="120" align="center">
          <template #default="scope">
            {{ formatValue(scope.row.stock_name) }}
          </template>
        </el-table-column>
        <el-table-column prop="bs_flag" label="买卖标记" width="90" align="center">
          <template #default="scope">
            <span :class="sideTextClass(scope.row.bs_flag)">{{ formatValue(scope.row.bs_flag) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="委托状态" min-width="110" align="center">
          <template #default="scope">
            <el-tag :type="statusType(scope.row.status)">
              {{ formatValue(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="entrust_volume" label="委托量" width="90" align="center">
          <template #default="scope">{{ formatNumeric(scope.row.entrust_volume) }}</template>
        </el-table-column>
        <el-table-column prop="deal_volume" label="成交数量" width="90" align="center">
          <template #default="scope">{{ formatNumeric(scope.row.deal_volume) }}</template>
        </el-table-column>
        <el-table-column prop="cancel_volume" label="已撤数量" width="90" align="center">
          <template #default="scope">{{ formatNumeric(scope.row.cancel_volume) }}</template>
        </el-table-column>
        <el-table-column prop="entrust_price" label="委托价格" width="100" align="center">
          <template #default="scope">{{ formatPrice(scope.row.entrust_price) }}</template>
        </el-table-column>
        <el-table-column prop="deal_avg_price" label="成交均价" width="100" align="center">
          <template #default="scope">{{ formatMoneyValue(scope.row.deal_avg_price) }}</template>
        </el-table-column>
        <el-table-column prop="frozen_amount" label="冻结金额" width="110" align="center">
          <template #default="scope">{{ formatMoneyValue(scope.row.frozen_amount) }}</template>
        </el-table-column>
        <el-table-column prop="contract_no" label="订单编号" min-width="130" align="center">
          <template #default="scope">{{ formatContractNo(scope.row.contract_no) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" align="center">
          <template #default="scope">
            <el-button
              size="small"
              type="danger"
              :loading="isCancelling(scope.row)"
              :disabled="isCancelling(scope.row)"
              @click="cancelOrder(scope.row)"
            >
              撤单
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="sum-result">
      <el-button 
      type="primary" 
      :loading="loading"
       @click="refreshOrders"
       style="color: #ffffff; 
       background-color: #2c3e50; 
       border-color: #2c3e50;">
       刷新委托
       </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onBeforeUnmount, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchOrderList, cancelOrder as cancelOrderApi, fetchGuojinSimQuote } from '@/api/accountApi.js'

const csvFileInput = ref(null)
const rowHeight = 40
const maxVisibleRows = 5
const tableHeaderHeight = 52
const orderList = ref([])
const loading = ref(false)
const cancellingRows = ref(new Set())

const headerStyle = () => ({
  backgroundColor: 'rgba(64, 224, 255, 0.2)',
  color: '#000000',
  fontWeight: 'bold',
  padding: '4px 0',
  textAlign: 'center',
  borderBottom: '1px solid rgba(64, 224, 255, 0.3)',
  fontSize: '12px'
})

const cellStyle = ({ column }) => ({
  padding: '4px 0',
  textAlign: column.align || 'center',
  color: '#000000',
  backgroundColor: 'transparent',
  borderBottom: '1px solid rgba(255, 255, 255, 0.1)',
  fontSize: '12px'
})

const tableRowClassName = ({ rowIndex }) => (rowIndex % 2 === 0 ? 'even-row' : '')
const tableMaxHeight = computed(() => `${Math.min(orderList.value.length, maxVisibleRows) * rowHeight + tableHeaderHeight}px`)

const statusType = (status) => {
  const text = String(status || '')
  if (text.includes('全部') || text.includes('已成')) return 'success'
  if (text.includes('部分')) return 'warning'
  if (text.includes('撤')) return 'info'
  if (text.includes('已报') || text.includes('待报')) return 'primary'
  if (text.includes('未知') || /^\d+$/.test(text)) return 'danger'
  return 'danger'
}

const formatTimeOnly = (value) => {
  const text = String(value ?? '').trim()
  if (!text) return '--'
  if (text.includes(' ')) return text.split(' ', 1)[1].slice(0, 8)
  if (/^\d{14}$/.test(text)) return `${text.slice(8, 10)}:${text.slice(10, 12)}:${text.slice(12, 14)}`
  if (/^\d{10}$/.test(text)) {
    const d = new Date(Number(text) * 1000)
    if (!Number.isNaN(d.getTime())) {
      return [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':')
    }
  }
  return text
}

const normalizeStatus = (value, order = {}) => {
  const text = String(value ?? '').trim()
  const mapping = {
    '0': '未报',
    '1': '待报',
    '2': '已报',
    '3': '已报待撤',
    '4': '部分成交',
    '5': '全部成交',
    '6': '部成待撤',
    '7': '已撤',
    '8': '废单',
    '49': '已报',
    '50': '已报',
    '51': '部分成交',
    '52': '全部成交',
    '53': '已撤',
    '54': '废单',
    '55': '废单',
    '56': '已报',
    '57': '已报待撤'
  }
  const dealVolume = normalizeNumeric(order.deal_volume ?? order.traded_volume ?? order.dealVolume)
  const entrustVolume = normalizeNumeric(order.entrust_volume ?? order.order_volume ?? order.volume)
  const cancelVolume = normalizeNumeric(order.cancel_volume ?? order.cancelVolume)
  if (dealVolume > 0 && entrustVolume > 0 && dealVolume >= entrustVolume) return '已成'
  if (cancelVolume > 0 && dealVolume < entrustVolume) return '已撤'
  if (mapping[text]) return mapping[text]
  if (/^\d+$/.test(text)) return '未知状态'
  if (text.includes('部分')) return '部分成交'
  if (text.includes('全部') || text.includes('全成')) return '全部成交'
  if (text.includes('撤')) return '已撤'
  return text || '--'
}

const normalizeSide = (value) => {
  const text = String(value ?? '').trim().toLowerCase()
  if (['b', 'buy', '1', '0', '买', '买入', '48'].includes(text)) return '买入'
  if (['s', 'sell', '2', '1', '卖', '卖出', '49'].includes(text)) return '卖出'
  return String(value ?? '').trim() || '--'
}

const normalizeNumeric = (value) => {
  if (value === null || value === undefined || value === '') return 0
  const num = Number(value)
  return Number.isFinite(num) ? num : 0
}

const normalizePrice = (value) => {
  if (value === null || value === undefined || value === '') return '--'
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : String(value)
}

const formatNumeric = (value) => {
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num) : '--'
}

const fallbackMoney = (value, fallback) => {
  const num = Number(value)
  if (Number.isFinite(num) && num > 0) return num
  const fb = Number(fallback)
  return Number.isFinite(fb) && fb > 0 ? fb : null
}

const normalizeOrder = (order) => {
  const entrustPrice = normalizeNumeric(order.entrust_price ?? order.entrustPrice ?? order.price)
  const dealVolume = normalizeNumeric(order.deal_volume ?? order.traded_volume ?? order.dealVolume ?? order.filledQuantity)
  const entrustVolume = normalizeNumeric(order.entrust_volume ?? order.order_volume ?? order.volume ?? order.quantity)
  const cancelVolume = normalizeNumeric(order.cancel_volume ?? order.cancelVolume)
  const dealAvg = fallbackMoney(order.deal_avg_price ?? order.dealAvgPrice ?? order.avg_price, order.deal_price ?? entrustPrice)
  const frozenAmount = fallbackMoney(
    order.frozen_amount ?? order.frozenAmount,
    entrustPrice > 0 ? entrustPrice * Math.max(entrustVolume - dealVolume, 0) : 0
  )
  const contractNoRaw = order.contract_no ?? order.contractNo ?? order.externalOrderNo ?? order.contract_id ?? order.contractId ?? order.entrust_no ?? order.entrustNo ?? order.order_no ?? order.orderNo ?? order.entrust_id ?? order.entrustId ?? order.order_id ?? order.orderId ?? order.id ?? '--'
  const orderIdRaw = order.order_id ?? order.orderId ?? order.id ?? null

  return {
    account_id: order.account_id ?? '--',
    market: normalizeMarket(order.market ?? order.exchange ?? order.stock_code),
    order_id: toIntOrNull(orderIdRaw),
    entrust_time: formatTimeOnly(order.entrust_time ?? order.createdAt),
    stock_code: normalizeStockCode(order.stock_code ?? order.symbol ?? '--'),
    stock_name: formatStockName(order.stock_name ?? order.securityName, order.stock_code ?? order.symbol),
    bs_flag: normalizeSide(order.bs_flag ?? order.side),
    status: normalizeStatus(order.status ?? order.order_status ?? order.order_state, order),
    entrust_volume: entrustVolume,
    deal_volume: dealVolume,
    cancel_volume: cancelVolume,
    entrust_price: normalizePrice(entrustPrice),
    deal_avg_price: dealAvg !== null ? normalizePrice(dealAvg) : '--',
    frozen_amount: frozenAmount !== null ? normalizePrice(frozenAmount) : '0.00',
    contract_no: formatContractNo(contractNoRaw)
  }
}

const formatStockName = (name, code) => {
  const text = String(name ?? '').trim()
  if (text && text !== '--' && text !== 'null' && text !== 'None' && text !== String(code ?? '').trim()) return text
  const normalizedCode = normalizeStockCode(code)
  if (normalizedCode) {
    return instrumentNameFromCode(normalizedCode) || normalizedCode
  }
  return '--'
}

const normalizeStockCode = (code) => {
  const raw = String(code ?? '').trim().toUpperCase()
  if (!raw) return '--'
  if (raw.includes('.')) return raw
  if (/^6\d{5}$/.test(raw)) return `${raw}.SH`
  if (/^[03]\d{5}$/.test(raw)) return `${raw}.SZ`
  return raw
}

const instrumentNameFromCode = (code) => {
  const map = {
    '600000.SH': '浦发银行',
    '510300.SH': '沪深300ETF',
    '510500.SH': '中证500ETF南方',
    '000001.SZ': '平安银行',
    '000002.SZ': '万科A',
    '300750.SZ': '宁德时代'
  }
  return map[String(code || '').toUpperCase()] || ''
}

const sideTextClass = (value) => {
  const text = String(value ?? '').trim()
  if (text === '买入') return 'buy-text'
  if (text === '卖出') return 'sell-text'
  return ''
}

const formatValue = (value) => {
  if (value === null || value === undefined || value === '') return '--'
  return value
}

const formatPrice = (value) => {
  const num = Number(value)
  return Number.isFinite(num) && num > 0 ? num.toFixed(2) : '--'
}

const formatMoneyValue = (value) => {
  const num = Number(value)
  return Number.isFinite(num) ? num.toFixed(2) : '--'
}

const formatContractNo = (value) => {
  const text = String(value ?? '').trim()
  return text && text !== 'null' && text !== 'None' && text !== 'undefined' ? text : '--'
}

async function resolveOrderStockName(order) {
  const backendName = String(order.stock_name ?? '').trim()
  const codeText = String(order.stock_code ?? '').trim()
  if (backendName && backendName !== '--' && backendName !== 'null' && backendName !== 'None' && backendName !== codeText) {
    return backendName
  }

  try {
    const quote = await fetchGuojinSimQuote(order.stock_code, order.account_id)
    const quoteName = String(quote?.stock_name ?? quote?.name ?? '').trim()
    if (quoteName && quoteName !== '--' && quoteName !== codeText) {
      return quoteName
    }
  } catch (error) {
    console.warn('补充证券名称失败：', error)
  }

  return formatStockName('', order.stock_code)
}

async function refreshOrders() {
  loading.value = true
  try {
    const response = await fetchOrderList()
    const payload = response || {}
    const orders = payload.data?.items || payload.data?.orders || payload.orders || []
    const normalizedOrders = Array.isArray(orders) ? orders.map(normalizeOrder) : []
    orderList.value = await Promise.all(normalizedOrders.map(async (order) => ({
      ...order,
      stock_name: await resolveOrderStockName(order)
    })))
  } catch (error) {
    console.error('刷新委托列表失败：', error)
    orderList.value = []
    const message =
      error?.response?.data?.message ||
      error?.response?.data?.error ||
      error?.message ||
      '刷新委托列表失败'
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}

function isCancelling(row) {
  const contractNo = String(row.contract_no || '').trim()
  const orderId = Number(row.order_id ?? row.orderId ?? row.order_no ?? row.orderNo ?? row.id)
  return cancellingRows.value.has(contractNo || String(orderId))
}

async function cancelOrder(row) {
  const contractNo = String(row.contract_no || '').trim()
  const orderId = Number(row.order_id ?? row.orderId ?? row.order_no ?? row.orderNo ?? row.id)

  if (!contractNo && !Number.isFinite(orderId)) {
    ElMessage.warning('订单缺少合同编号或订单编号，无法撤单')
    return
  }

  try {
    await ElMessageBox.confirm(`确认撤销 ${contractNo || orderId} 的委托吗？`, '撤单确认', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  const cancelKey = contractNo || String(orderId)
  cancellingRows.value.add(cancelKey)
  try {
    const result = await cancelOrderApi({
      account_id: row.account_id,
      contract_no: contractNo,
      order_id: Number.isFinite(orderId) ? orderId : undefined,
      order_sysid: contractNo || undefined,
      market: row.market,
      stock_code: row.stock_code,
      stock_name: row.stock_name
    })
    if (result?.success === false) {
      ElMessage.error(result?.message || '撤单失败')
      return
    }
    ElMessage.success(result?.message || '撤单请求已提交')
    await refreshOrders()
  } catch (error) {
    console.error('撤单失败：', error)
    ElMessage.error(error?.response?.data?.message || '撤单失败')
  } finally {
    cancellingRows.value.delete(cancelKey)
  }
}

function handleCsvRead() {
  // 预留 CSV 导入能力，后续可接入真实委托数据导入
}

function normalizeMarket(value) {
  const text = String(value ?? '').trim().toUpperCase()
  if (!text) return null
  if (text.includes('.SH') || text === 'SH' || text === '1') return 1
  if (text.includes('.SZ') || text === 'SZ' || text === '0') return 0
  const num = Number(text)
  return Number.isFinite(num) ? num : null
}

function toIntOrNull(value) {
  const num = Number(value)
  return Number.isFinite(num) ? Math.trunc(num) : null
}

onMounted(() => {
  refreshOrders()
  window.addEventListener('qmt-order-submitted', refreshOrders)
})

onBeforeUnmount(() => {
  window.removeEventListener('qmt-order-submitted', refreshOrders)
})
</script>

<style scoped>
.module-card {
  width: 100%;
}

.table-container {
  width: 100%;
  display: inline-block;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.sum-result {
  text-align: right;
  font-size: 14px;
  padding: 8px 12px 0;
}

:deep(.el-table) {
  background: transparent !important;
}

:deep(.el-table th.el-table__cell) {
  background-color: rgba(64, 224, 255, 0.2) !important;
  color: #000000 !important;
  border-bottom: 1px solid rgba(64, 224, 255, 0.3) !important;
  font-weight: bold !important;
}

:deep(.el-table td.el-table__cell) {
  background-color: transparent !important;
  color: #000000 !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1) !important;
}

:deep(.el-table .el-table__body-wrapper) {
  overflow-y: auto !important;
}

:deep(.el-table tr.even-row td) {
  background-color: rgba(255, 255, 255, 0.05) !important;
}

.buy-text {
  color: #ff4d4f;
  font-weight: 700;
}

.sell-text {
  color: #2ecc71;
  font-weight: 700;
}
</style>
