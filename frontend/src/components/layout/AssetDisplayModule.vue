<template>
  <div class="left-aside">
    <div class="asset-display-module">
      <div class="title">所有账户总资产数据</div>

      <div class="source-bar">
        <span class="source-label">数据源</span>
        <el-select v-model="pendingDataSource" size="small">
          <el-option label="国金QMT模拟账户（同步到MySQL）" value="simulation" />
          <el-option label="MongoDB快照（待同步）" value="mongodb" disabled />
        </el-select>
        <el-button
          size="small"
          type="primary"
          :disabled="pendingDataSource === dataSource"
          @click="confirmSourceChange"
        >
          确认
        </el-button>
        <span class="source-state">已生效：{{ appliedSourceText }}</span>
        <el-tag size="small" :type="qmtConnected ? 'success' : 'danger'">
          {{ qmtConnected ? 'QMT已连接' : 'QMT未连接' }}
        </el-tag>
      </div>

      <div class="select-container">
        <el-select
          v-model="selectedAccount"
          placeholder="请选择账户"
          @change="handleAccountChange"
        >
          <el-option
            v-for="account in accounts"
            :key="account.account_id"
            :label="`${account.account_name || '账户'} ${account.display_account_id || account.account_no || ''}`"
            :value="account.account_id"
          />
        </el-select>
        <el-button
          type="success"
          :loading="syncing"
          :disabled="!selectedAccount || syncing"
          @click="syncSelectedAccount"
        >
          同步QMT账户
        </el-button>
      </div>

      <el-alert
        v-if="qmtStatusMessage"
        :title="qmtStatusMessage"
        :type="qmtConnected ? 'success' : 'warning'"
        show-icon
        :closable="false"
        class="load-alert"
      />

      <el-alert
        v-if="loadError"
        :title="loadError"
        type="error"
        show-icon
        :closable="false"
        class="load-alert"
      />

      <el-empty
        v-else-if="!accounts.length"
        description="当前登录账号暂无账户，请退出后重新注册或登录"
        :image-size="54"
        class="account-empty"
      />

      <div class="table-container">
        <el-table
          :data="selectedAccountData"
          style="width: 100%"
          border
          :header-cell-style="headerStyle"
          :cell-style="cellStyle"
          :row-class-name="tableRowClassName"
        >
          <el-table-column prop="account_id" label="资金账户" min-width="120" align="center" />
          <el-table-column prop="total_asset" label="总资产" min-width="150" align="right" />
          <el-table-column prop="cash" label="可用金额" min-width="150" align="right" />
          <el-table-column prop="total_return_rate" label="总收益率" min-width="120" align="right" />
          <el-table-column prop="total_positions" label="持仓股票数" min-width="120" align="right" />
          <el-table-column prop="market_value" label="持仓市值" min-width="150" align="right" />
        </el-table>
      </div>
    </div>

    <div class="asset-details">
      <div class="title">账户资产数据详情</div>

      <div class="table-stock">
        <el-table
          :data="selectedStocks"
          style="width: 100%"
          height="420"
          border
          :header-cell-style="headerStyle"
          :cell-style="cellStyle"
          :row-class-name="tableRowClassName"
        >
          <el-table-column prop="stock_code" label="股票代码" min-width="120" align="center" />
          <el-table-column prop="stock_name" label="股票名称" min-width="140" align="center" />
          <el-table-column prop="open_price" label="当前价格" min-width="120" align="right" />
          <el-table-column prop="volume" label="持仓数量" min-width="120" align="right" />
          <el-table-column prop="avg_price" label="成本价" min-width="120" align="right" />
          <el-table-column prop="market_value" label="股票市值" min-width="150" align="right" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchAccountInfo, fetchQmtStatus, syncQmtAccount } from '@/api/accountApi.js'

const accounts = ref([])
const selectedAccount = ref('')
const dataSource = ref('simulation')
const pendingDataSource = ref('simulation')
const latestMeta = ref({ data_source: '', snapshot_time: '', is_realtime: false, fallback_reason: '' })
const requestId = ref(0)
const loadError = ref('')
const syncing = ref(false)
const qmtStatus = ref({ qmtConnected: false, subscribed: false, lastError: '' })
let timer = null

function headerStyle() {
  return {
    backgroundColor: 'rgba(64, 224, 255, 0.2)',
    color: '#000000',
    fontWeight: 'bold',
    padding: '8px 0',
    textAlign: 'center',
    borderBottom: '1px solid rgba(64, 224, 255, 0.3)'
  }
}

function cellStyle({ column }) {
  return {
    padding: '8px 0',
    textAlign: column.align || 'center',
    color: '#000000',
    backgroundColor: 'transparent',
    borderBottom: '1px solid rgba(255, 255, 255, 0.1)'
  }
}

function tableRowClassName({ rowIndex }) {
  return rowIndex % 2 === 0 ? 'even-row' : ''
}

function formatNumber(value, decimals = 2) {
  if (value === undefined || value === null || Number.isNaN(Number(value))) {
    return decimals === 0 ? '0' : '0.00'
  }
  return Number(value).toLocaleString(undefined, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals
  })
}

const qmtConnected = computed(() => Boolean(qmtStatus.value.qmtConnected && qmtStatus.value.subscribed !== false))
const qmtStatusMessage = computed(() => {
  if (qmtConnected.value) {
    return '国金QMT模拟账户已连接，可点击“同步QMT账户”刷新资产和持仓'
  }
  const error = qmtStatus.value.lastError || qmtStatus.value.error || ''
  if (error.includes('connect returned -1')) {
    return 'QMT连接失败（connect=-1）：请在国金QMT交易端登录资金账号，并进入“极简模式”后重试'
  }
  if (error.includes('not installed') || error.includes('unavailable to this Python')) {
    return '当前Python环境无法加载xtquant，请按根目录README检查启动方式'
  }
  return error || '请先在国金QMT交易端登录资金账号并进入极简模式'
})
const appliedSourceLabel = computed(() => {
  if (dataSource.value !== 'simulation') return 'MongoDB快照'
  return accounts.value.some(item => item.data_source === 'qmt')
    ? '国金QMT模拟账户（MySQL持久化）'
    : '本地模拟账户（MySQL）'
})
const appliedSourceText = computed(() => {
  const timeText = latestMeta.value.snapshot_time ? latestMeta.value.snapshot_time.replace('T', ' ') : ''
  return timeText ? `${appliedSourceLabel.value} ${timeText}` : appliedSourceLabel.value
})

async function loadAccountData() {
  const currentRequestId = ++requestId.value
  const requestedSource = dataSource.value
  loadError.value = ''
  try {
    const data = await fetchAccountInfo(requestedSource)
    if (currentRequestId !== requestId.value || requestedSource !== dataSource.value) {
      return
    }

    latestMeta.value = {
      data_source: data.data_source || (requestedSource === 'simulation' ? 'mysql' : 'mongodb_cache'),
      snapshot_time: data.snapshot_time || '',
      is_realtime: Boolean(data.is_realtime),
      fallback_reason: ''
    }
    if (!data?.accounts?.length) {
      accounts.value = []
      selectedAccount.value = ''
      return
    }

    const previousSelected = selectedAccount.value
    accounts.value = data.accounts.map(account => {
      const totalReturnRate = Number.isFinite(Number(account.total_return_rate))
        ? Number(account.total_return_rate)
        : 0
      const totalPositions = Number(account.total_positions || 0)
      return {
        ...account,
        total_return_rate: `${totalReturnRate.toFixed(2)}%`,
        total_positions: totalPositions
      }
    })

    if (!previousSelected || !accounts.value.find(item => item.account_id === previousSelected)) {
      selectedAccount.value = accounts.value[0].account_id
    }
  } catch (error) {
    loadError.value = error?.message || '账户数据加载失败'
    console.error('获取账户信息失败:', error)
  }
}

async function loadQmtStatus() {
  try {
    qmtStatus.value = await fetchQmtStatus()
  } catch (error) {
    qmtStatus.value = {
      qmtConnected: false,
      subscribed: false,
      lastError: error?.message || 'Python QMT服务不可用'
    }
  }
}

async function syncSelectedAccount() {
  if (!selectedAccount.value || syncing.value) return
  syncing.value = true
  loadError.value = ''
  try {
    const result = await syncQmtAccount(selectedAccount.value)
    await Promise.all([loadAccountData(), loadQmtStatus()])
    const warningText = result.warnings?.length ? `；${result.warnings.join('；')}` : ''
    ElMessage.success(`QMT账户同步成功${warningText}`)
  } catch (error) {
    loadError.value = error?.message || 'QMT账户同步失败'
    await loadQmtStatus()
    ElMessage.error(loadError.value)
  } finally {
    syncing.value = false
  }
}

function handleAccountChange(accountId) {
  selectedAccount.value = accountId
}

function confirmSourceChange() {
  if (pendingDataSource.value === dataSource.value) return
  dataSource.value = pendingDataSource.value
  accounts.value = []
  selectedAccount.value = ''
  latestMeta.value = { data_source: '', snapshot_time: '', is_realtime: false, fallback_reason: '' }
  loadError.value = ''
  loadAccountData()
}

const selectedAccountData = computed(() => {
  const account = accounts.value.find(item => item.account_id === selectedAccount.value)
  if (!account) return []
  return [{
    account_id: `${account.account_name || '账户'} ${account.display_account_id || account.account_no || ''}`,
    total_asset: formatNumber(account.total_asset),
    cash: formatNumber(account.cash),
    total_return_rate: account.total_return_rate,
    total_positions: formatNumber(account.total_positions, 0),
    market_value: formatNumber(account.market_value)
  }]
})

const selectedStocks = computed(() => {
  const account = accounts.value.find(item => item.account_id === selectedAccount.value)
  if (!account?.positions?.length) return []
  return [...account.positions]
    .sort((a, b) => Number(b.market_value || 0) - Number(a.market_value || 0))
    .map(position => ({
      stock_code: position.stock_code,
      stock_name: position.stock_name || position.securityName || position.stock_code,
      open_price: formatNumber(position.open_price ?? position.current_price),
      volume: formatNumber(position.volume, 0),
      avg_price: formatNumber(position.avg_price ?? position.cost_price),
      market_value: formatNumber(position.market_value)
    }))
})

onMounted(() => {
  pendingDataSource.value = dataSource.value
  Promise.all([loadAccountData(), loadQmtStatus()])
  timer = setInterval(() => {
    if (dataSource.value !== 'simulation' || syncing.value) return
    loadAccountData()
    loadQmtStatus()
  }, 5000)
})

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.left-aside {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  box-sizing: border-box;
}

.asset-display-module,
.asset-details {
  display: flex;
  flex-direction: column;
}

.title {
  font-size: 14px;
  font-weight: bold;
  margin-bottom: 10px;
  color: #000000;
  text-align: center;
  text-shadow: 0 0 10px rgba(64, 224, 255, 0.8);
}

.source-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.source-label {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.86);
}

.source-state {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.9);
}

.select-container {
  margin-bottom: 10px;
  display: flex;
  gap: 8px;
}

.select-container :deep(.el-select) {
  flex: 1;
}

.load-alert,
.account-empty {
  margin-bottom: 10px;
}

.table-container,
.table-stock {
  width: 100%;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

:deep(.el-select) {
  width: 100%;
}

:deep(.el-select .el-input__wrapper) {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(64, 224, 255, 0.3);
}

:deep(.el-table) {
  font-size: 12px;
  color: #000000;
  background: transparent;
}
</style>
