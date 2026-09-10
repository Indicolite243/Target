<template>
  <div class="module-card">
    <input
      ref="csvFileInput"
      type="file"
      accept=".csv"
      style="display: none;"
      @change="handleCsvRead"
    />

    <div class="order-toolbar">
      <el-input v-model="keyword" clearable placeholder="代码 / 名称 / 订单号" class="order-search" />
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        class="order-date-range"
        @change="handleDateChange"
      />
      <div class="quick-dates">
        <el-button size="small" :class="{ active: datePreset === 'today' }" @click="setDatePreset('today')">今天</el-button>
        <el-button size="small" :class="{ active: datePreset === '7d' }" @click="setDatePreset('7d')">近7天</el-button>
        <el-button size="small" :class="{ active: datePreset === '30d' }" @click="setDatePreset('30d')">近30天</el-button>
      </div>
      <el-radio-group v-model="activeStatus" size="small" class="status-tabs">
        <el-radio-button label="ALL">全部</el-radio-button>
        <el-radio-button label="PENDING">待成交</el-radio-button>
        <el-radio-button label="DONE">已成</el-radio-button>
        <el-radio-button label="CANCELLED">已撤</el-radio-button>
      </el-radio-group>
      <div class="order-counts">待成交 {{ pendingCount }} · 已成 {{ completedCount }} · 已撤 {{ cancelledCount }}</div>
      <el-button class="delete-btn" :disabled="selectedRows.length === 0" :loading="deleting" @click="deleteSelected">
        删除选中
      </el-button>
      <el-button class="clear-history-btn" :disabled="filteredOrderList.length === 0" :loading="deleting" @click="deleteFiltered">
        清空当前筛选
      </el-button>
      <el-button class="refresh-btn" :loading="loading" @click="refreshOrders">↻ 刷新</el-button>
    </div>

    <div class="table-container">
      <el-table
        :data="filteredOrderList"
        border
        stripe
        style="width: 100%"
        :max-height="tableMaxHeight"
        :header-cell-style="headerStyle"
        :cell-style="cellStyle"
        :row-class-name="tableRowClassName"
        row-key="order_key"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="42" align="center" reserve-selection :selectable="isRowSelectable" />
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
            <el-tag class="status-tag" :type="statusType(scope.row.status)">
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
              v-if="canCancel(scope.row)"
              size="small"
              class="cancel-btn"
              :loading="isCancelling(scope.row)"
              :disabled="isCancelling(scope.row)"
              @click="cancelOrder(scope.row)"
            >
              撤单
            </el-button>
            <el-button
              v-else-if="canDelete(scope.row)"
              size="small"
              class="delete-row-btn"
              :loading="isDeleting(scope.row)"
              :disabled="isDeleting(scope.row)"
              @click="deleteOne(scope.row)"
            >
              删除
            </el-button>
            <span v-else class="operation-placeholder">—</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="order-footer">共 {{ filteredOrderList.length }} 条委托 <span>进行中委托仅可撤单，终态委托可删除</span></div>
  </div>
</template>

<script setup>
import { computed, ref, onBeforeUnmount, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  fetchOrderList,
  cancelOrder as cancelOrderApi,
  deleteOrderHistory,
  deleteFilteredOrderHistory,
  fetchGuojinSimQuote
} from '@/api/accountApi.js'

const csvFileInput = ref(null)
const rowHeight = 40
const maxVisibleRows = 5
const tableHeaderHeight = 52
const orderList = ref([])
const orderTable = ref(null)
const loading = ref(false)
const cancellingRows = ref(new Set())
const deletingRows = ref(new Set())
const deleting = ref(false)
const selectedRows = ref([])
const keyword = ref('')
const activeStatus = ref('ALL')
const dateRange = ref([])
const datePreset = ref('')
let orderRefreshTimer = null

const headerStyle = () => ({
  backgroundColor: 'rgba(24, 61, 94, 0.82)',
  color: '#9fe7ff',
  fontWeight: 'bold',
  padding: '4px 0',
  textAlign: 'center',
  borderBottom: '1px solid rgba(64, 224, 255, 0.24)',
  fontSize: '12px'
})

const cellStyle = ({ column }) => ({
  padding: '4px 0',
  textAlign: column.align || 'center',
  color: '#d5e8f7',
  backgroundColor: 'transparent',
  borderBottom: '1px solid rgba(100, 177, 231, 0.13)',
  fontSize: '12px'
})

const tableRowClassName = ({ rowIndex }) => (rowIndex % 2 === 0 ? 'even-row' : '')
const isPendingStatus = (status) => ['已报', '部分成交', '状态确认中'].includes(String(status || '').trim())
const isCompletedStatus = (status) => ['已成', '全部成交'].includes(String(status || '').trim())
const isCancelledStatus = (status) => String(status || '').includes('撤')
const pendingCount = computed(() => orderList.value.filter((row) => isPendingStatus(row.status)).length)
const completedCount = computed(() => orderList.value.filter((row) => isCompletedStatus(row.status)).length)
const cancelledCount = computed(() => orderList.value.filter((row) => isCancelledStatus(row.status)).length)
const filteredOrderList = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return orderList.value.filter((row) => {
    const status = row.status
    const matchesStatus = activeStatus.value === 'ALL'
      || (activeStatus.value === 'PENDING' && isPendingStatus(status))
      || (activeStatus.value === 'DONE' && isCompletedStatus(status))
      || (activeStatus.value === 'CANCELLED' && isCancelledStatus(status))
    if (!matchesStatus) return false
    if (!query) return true
    return [row.stock_code, row.stock_name, row.contract_no].some((item) => String(item || '').toLowerCase().includes(query))
  })
})
const tableMaxHeight = computed(() => `${Math.min(filteredOrderList.value.length, maxVisibleRows) * rowHeight + tableHeaderHeight}px`)

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
    PENDING_SUBMIT: '提交中',
    SUBMITTED: '已报',
    UNREPORTED: '未报',
    WAIT_REPORTING: '待报',
    REPORTED: '已报',
    REPORTED_CANCEL: '已报待撤',
    PARTIALLY_FILLED_CANCEL_PENDING: '部分成交待撤',
    PARTIALLY_CANCELED: '部分撤单',
    PARTIALLY_FILLED: '部分成交',
    CANCEL_PENDING: '撤单确认中',
    CANCELED: '已撤',
    FILLED: '已成',
    REJECTED: '已拒绝',
    FAILED: '失败',
    UNKNOWN: '状态确认中',
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
    // MyBatis Snowflake IDs exceed JavaScript's safe Number range: preserve the exact string for cancel requests.
    order_id: normalizeOrderId(orderIdRaw),
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
    contract_no: formatContractNo(contractNoRaw),
    order_key: normalizeOrderId(orderIdRaw) || formatContractNo(contractNoRaw)
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

function buildOrderQuery() {
  // 日期只作为查询参数传给后端；筛选在数据库完成，避免前端先拉全量订单再截取。
  const query = {}
  if (Array.isArray(dateRange.value) && dateRange.value.length === 2) {
    query.start_date = dateRange.value[0]
    query.end_date = dateRange.value[1]
  }
  return query
}

function formatDateInput(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function setDatePreset(preset) {
  // 快捷范围包含今天：7d/30d 的起点分别向前推 6/29 天。
  datePreset.value = preset
  const end = new Date()
  const start = new Date(end)
  if (preset === '7d') start.setDate(start.getDate() - 6)
  if (preset === '30d') start.setDate(start.getDate() - 29)
  dateRange.value = [formatDateInput(start), formatDateInput(end)]
  clearSelectedRows()
  refreshOrders()
}

function handleDateChange() {
  // 手动选日期后清除快捷按钮状态，防止界面出现“近7天”和自定义范围同时高亮。
  datePreset.value = ''
  clearSelectedRows()
  refreshOrders()
}

function handleSelectionChange(rows) {
  selectedRows.value = rows
}

function clearSelectedRows() {
  selectedRows.value = []
  orderTable.value?.clearSelection()
}

function isDeletableStatus(status) {
  return ['已成', '全部成交', '已撤', '已拒绝', '失败', '废单'].includes(String(status || '').trim())
}

function canDelete(row) {
  return Boolean(row?.order_id) && !canCancel(row) && isDeletableStatus(row.status)
}

function isRowSelectable(row) {
  return canDelete(row)
}

function isDeleting(row) {
  const key = String(row?.order_id || row?.contract_no || '')
  return deletingRows.value.has(key)
}

async function refreshOrders() {
  if (loading.value) return
  loading.value = true
  try {
    // 返回数据统一经过 normalizeOrder，兼容 QMT 字段名、旧接口字段名和中文状态。
    const response = await fetchOrderList(buildOrderQuery())
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

async function deleteOne(row) {
  // 删除是历史记录软删除；终态才允许删除，进行中的订单必须走撤单流程。
  if (!canDelete(row)) return
  const orderId = normalizeOrderId(row.order_id)
  try {
    await ElMessageBox.confirm(`确认从历史记录中删除 ${row.contract_no || orderId} 吗？此操作不会撤销券商委托。`, '删除委托记录', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  const key = String(orderId)
  deletingRows.value = new Set(deletingRows.value).add(key)
  try {
    const result = await deleteOrderHistory(orderId)
    if (result?.success === false) {
      ElMessage.error(result?.message || '删除委托记录失败')
      return
    }
    ElMessage.success('委托记录已删除')
    clearSelectedRows()
    await refreshOrders()
  } catch (error) {
    console.error('删除委托记录失败：', error)
    ElMessage.error(error?.response?.data?.message || '删除委托记录失败')
  } finally {
    const next = new Set(deletingRows.value)
    next.delete(key)
    deletingRows.value = next
  }
}

async function deleteSelected() {
  // 复选框本身只允许选择终态，二次过滤是为了防止状态在刷新期间发生变化。
  const rows = selectedRows.value.filter(canDelete)
  if (!rows.length || deleting.value) return
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${rows.length} 条终态委托记录吗？此操作不会撤单。`, '批量删除委托记录', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    const results = await Promise.all(rows.map((row) => deleteOrderHistory(row.order_id)))
    const failed = results.filter((item) => item?.success === false)
    if (failed.length) ElMessage.warning(`${rows.length - failed.length} 条已删除，${failed.length} 条删除失败`)
    else ElMessage.success(`已删除 ${rows.length} 条委托记录`)
    clearSelectedRows()
    await refreshOrders()
  } catch (error) {
    console.error('批量删除委托记录失败：', error)
    ElMessage.error(error?.response?.data?.message || '批量删除委托记录失败')
  } finally {
    deleting.value = false
  }
}

async function deleteFiltered() {
  // “清空当前筛选”仍由后端按日期范围执行，且后端只删除终态记录。
  if (!filteredOrderList.value.length || deleting.value) return
  const rangeText = dateRange.value?.length === 2 ? `${dateRange.value[0]} 至 ${dateRange.value[1]}` : '当前全部日期'
  try {
    await ElMessageBox.confirm(`确认删除 ${rangeText} 内的终态委托记录吗？进行中的委托不会被删除，也不会撤单。`, '清空当前筛选', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    const result = await deleteFilteredOrderHistory(buildOrderQuery())
    if (result?.success === false) {
      ElMessage.error(result?.message || '清空委托记录失败')
      return
    }
    const count = Number(result?.data?.deleted ?? result?.deleted ?? 0)
    ElMessage.success(count ? `已清空 ${count} 条终态委托记录` : '当前筛选没有可删除的终态记录')
    clearSelectedRows()
    await refreshOrders()
  } catch (error) {
    console.error('清空委托记录失败：', error)
    ElMessage.error(error?.response?.data?.message || '清空委托记录失败')
  } finally {
    deleting.value = false
  }
}

function isCancelling(row) {
  const contractNo = String(row.contract_no || '').trim()
  const orderId = normalizeOrderId(row.order_id ?? row.orderId ?? row.order_no ?? row.orderNo ?? row.id)
  return cancellingRows.value.has(contractNo || orderId || '')
}

// 与后端/QMT 生命周期保持一致：只有已接受、未完成或部分成交的委托可撤单；
// 已成、已撤、废单等终态只能删除历史展示记录，不能再向券商发撤单请求。
function canCancel(row) {
  return isPendingStatus(row.status)
}

async function cancelOrder(row) {
  const contractNo = String(row.contract_no || '').trim()
  const orderId = normalizeOrderId(row.order_id ?? row.orderId ?? row.order_no ?? row.orderNo ?? row.id)

  if (!contractNo && !orderId) {
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

  const cancelKey = contractNo || orderId
  cancellingRows.value.add(cancelKey)
  try {
    const result = await cancelOrderApi({
      account_id: row.account_id,
      contract_no: contractNo,
      order_id: orderId || undefined,
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

function normalizeOrderId(value) {
  const text = String(value ?? '').trim()
  return text && text !== 'null' && text !== 'undefined' ? text : null
}

onMounted(() => {
  refreshOrders()
  orderRefreshTimer = window.setInterval(refreshOrders, 3000)
  window.addEventListener('qmt-order-submitted', refreshOrders)
})

onBeforeUnmount(() => {
  if (orderRefreshTimer) window.clearInterval(orderRefreshTimer)
  window.removeEventListener('qmt-order-submitted', refreshOrders)
})
</script>

<style scoped>
.module-card {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 9px;
  color: #dcecff;
  font-variant-numeric: tabular-nums;
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(31, 91, 128, .3);
  --el-table-header-bg-color: rgba(24, 61, 94, .92);
  --el-table-border-color: rgba(100, 177, 231, .16);
  --el-fill-color-lighter: rgba(255, 255, 255, .025);
}

.order-overview { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.overview-card {
  appearance: none;
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 42px;
  padding: 7px 11px;
  border: 1px solid rgba(64, 224, 255, .2);
  border-radius: 6px;
  background: linear-gradient(135deg, rgba(21, 61, 99, .72), rgba(13, 30, 54, .8));
  color: #b5d0e7;
  cursor: pointer;
  text-align: left;
}
.overview-card:hover, .overview-card.active { border-color: #2fbde9; box-shadow: inset 0 0 18px rgba(37, 188, 233, .11); }
.overview-card strong { color: #49d9ff; font-size: 21px; line-height: 1; }
.overview-card.done { background: linear-gradient(135deg, rgba(17, 78, 66, .62), rgba(11, 38, 47, .8)); }
.overview-card.done strong { color: #44dd94; }
.overview-card.cancelled { background: linear-gradient(135deg, rgba(63, 76, 96, .58), rgba(22, 30, 47, .82)); }
.overview-card.cancelled strong { color: #9db0c3; }
.order-toolbar { display: flex; align-items: center; gap: 7px; min-width: 0; flex-wrap: wrap; }
.order-search { width: 180px; flex: 0 1 180px; }
.order-date-range { width: 238px; flex: 0 1 238px; }
.quick-dates { display: inline-flex; align-items: center; gap: 3px; white-space: nowrap; }
.quick-dates .el-button { margin-left: 0; min-width: 40px; padding: 5px 7px; color: #9ebbd0; border-color: rgba(100, 177, 231, .28); background: rgba(11, 31, 57, .76); }
.quick-dates .el-button:hover, .quick-dates .el-button.active { color: #fff; border-color: #2fbde9; background: rgba(23, 100, 189, .82); }
.order-counts { margin-left: auto; color: #7194af; font-size: 11px; white-space: nowrap; }
.delete-btn, .clear-history-btn { color: #e2bd9c; border-color: rgba(226, 145, 95, .4); background: rgba(104, 54, 34, .3); }
.delete-btn:hover, .delete-btn:focus, .clear-history-btn:hover, .clear-history-btn:focus { color: #fff; border-color: #e89863; background: rgba(157, 77, 44, .78); }
.delete-btn.is-disabled, .clear-history-btn.is-disabled { color: #637b8d; border-color: rgba(100, 125, 144, .2); background: rgba(22, 35, 52, .55); }
.refresh-btn { margin-left: 0; color: #a9daef; border-color: rgba(64, 224, 255, .36); background: rgba(15, 58, 85, .62); }
.refresh-btn:hover, .refresh-btn:focus { color: #fff; border-color: #2fc4ee; background: rgba(25, 106, 148, .75); }
.status-tabs { min-width: 0; white-space: nowrap; }
.order-footer { color: #7e9ab4; font-size: 11px; text-align: right; }
.order-footer span { margin-left: 14px; color: #68879f; }

.table-container {
  width: 100%;
  display: inline-block;
  overflow: hidden;
  background: rgba(7, 20, 39, 0.68);
  backdrop-filter: blur(10px);
  border-radius: 7px;
  border: 1px solid rgba(64, 224, 255, 0.18);
}

:deep(.el-table) {
  background: transparent !important;
  color: #d5e8f7 !important;
}
:deep(.el-table__inner-wrapper), :deep(.el-table__body-wrapper), :deep(.el-scrollbar__view), :deep(.el-table tr) { background: #091a31 !important; }
:deep(.el-table__inner-wrapper::before) { background-color: rgba(100, 177, 231, .18) !important; }

:deep(.el-table th.el-table__cell) {
  background-color: rgba(24, 61, 94, .82) !important;
  color: #9fe7ff !important;
  border-bottom: 1px solid rgba(64, 224, 255, .24) !important;
  font-weight: bold !important;
}

:deep(.el-table td.el-table__cell) {
  background-color: transparent !important;
  color: #d5e8f7 !important;
  border-bottom: 1px solid rgba(100, 177, 231, .13) !important;
}

:deep(.el-table .el-table__body-wrapper) {
  overflow-y: auto !important;
}

:deep(.el-table tr.even-row td) {
  background-color: rgba(21, 43, 67, .78) !important;
}
:deep(.el-table tr:not(.even-row) td) { background-color: rgba(8, 25, 47, .92) !important; }

:deep(.order-search .el-input__wrapper) { background: rgba(7, 21, 41, .86); box-shadow: 0 0 0 1px rgba(100, 177, 231, .34) inset; }
:deep(.order-search .el-input__inner) { color: #e6f5ff; }
:deep(.status-tabs .el-radio-button__inner) { background: rgba(11, 31, 57, .9); border-color: rgba(100, 177, 231, .35); color: #afcde3; box-shadow: none; }
:deep(.status-tabs .el-radio-button.is-active .el-radio-button__inner) { background: #1764bd; border-color: #299ee0; color: #fff; box-shadow: none; }
:deep(.status-tag) {
  border-color: transparent;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: .3px;
  text-shadow: 0 0 1px currentColor;
}
.cancel-btn { color: #ff9f94; border-color: rgba(255, 110, 100, .55); background: rgba(110, 31, 40, .25); }
.cancel-btn:hover, .cancel-btn:focus { color: #fff; border-color: #ff7068; background: #bf4645; }
.delete-row-btn { color: #f2c19d; border-color: rgba(232, 152, 99, .5); background: rgba(105, 57, 36, .25); }
.delete-row-btn:hover, .delete-row-btn:focus { color: #fff; border-color: #e89863; background: #a95d3c; }
.operation-placeholder { color: #62788c; }

.buy-text {
  color: #ff4d4f;
  font-weight: 700;
}

.sell-text {
  color: #2ecc71;
  font-weight: 700;
}

:deep(.order-date-range .el-input__wrapper) { background: rgba(7, 21, 41, .86); box-shadow: 0 0 0 1px rgba(100, 177, 231, .34) inset; }
:deep(.order-date-range .el-range-input) { color: #111827 !important; font-weight: 600; }
:deep(.order-date-range .el-range-separator), :deep(.order-date-range .el-input__icon) { color: #5f7180; }
:deep(.el-table-column--selection .el-checkbox__inner) { border-color: rgba(100, 177, 231, .58); background: rgba(9, 27, 48, .88); }
:deep(.el-table-column--selection .el-checkbox__input.is-checked .el-checkbox__inner), :deep(.el-table-column--selection .el-checkbox__input.is-indeterminate .el-checkbox__inner) { border-color: #2fbde9; background: #1764bd; }

@media (max-width: 1280px) {
  .order-toolbar { flex-wrap: wrap; }
  .order-search { flex-basis: 100%; width: 100%; }
  .order-date-range { flex: 1 1 210px; width: auto; }
  .refresh-btn { margin-left: 0; }
}
</style>
