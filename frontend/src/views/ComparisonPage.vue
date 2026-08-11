<template>
  <div class="comparison-page">
    <div class="grid-background"></div>

    <div class="content-wrapper">
      <div class="left-sidebar glass-panel">
        <div class="sidebar-header">
          <div class="header-line"></div>
          <h3 class="sidebar-title">对比评估</h3>
        </div>

        <div class="sidebar-menu">
          <div class="menu-item" :class="{ active: activeMenu === 'asset' }" @click="setActiveMenu('asset')">
            <div class="menu-icon asset-icon"></div>
            <span>资产对比</span>
          </div>
          <div class="menu-item" :class="{ active: activeMenu === 'time' }" @click="setActiveMenu('time')">
            <div class="menu-icon time-icon"></div>
            <span>时间段对比</span>
          </div>
          <div class="menu-item" :class="{ active: activeMenu === 'region' }" @click="setActiveMenu('region')">
            <div class="menu-icon region-icon"></div>
            <span>分市场对比</span>
          </div>
        </div>
      </div>

      <div class="main-content">
        <div class="content-row top-row">
          <div class="content-panel glass-panel chart-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon chart-icon"></i>
                数据可视化
              </h4>
              <div class="header-tools">
                <el-select v-model="pendingComparisonDataSource" size="small" style="width: 140px">
                  <el-option label="QMT实时" value="qmt" />
                  <el-option label="MongoDB缓存" value="mongodb" />
                </el-select>
                <el-button
                  size="small"
                  type="primary"
                  :disabled="pendingComparisonDataSource === comparisonDataSource"
                  @click="confirmComparisonSourceChange"
                >
                  确认
                </el-button>
                <span class="source-state">{{ comparisonStatusText }}</span>
                <el-tooltip
                  v-if="activeMenu === 'time' && timeReturnMode !== 'SIMULATED'"
                  placement="top"
                  effect="dark"
                  :content="timeGranularity === 'DAILY'
                    ? '当前为日线模式：每天只保留最后一条账户快照，适合查看每日净值变化。点击切换为全部快照。'
                    : '当前为全部快照模式：显示区间内每一条盘中快照，适合查看盘中持仓和资产变化。点击切换为日线。'"
                >
                  <el-button size="small" type="info" @click="toggleTimeGranularity">
                    {{ timeGranularity === 'DAILY' ? '切换：全部快照' : '切换：每日最后快照' }}
                  </el-button>
                </el-tooltip>
                <el-tooltip
                  v-else-if="activeMenu === 'time'"
                  placement="top"
                  effect="dark"
                  content="模拟收益率使用QMT历史日线回放，固定按交易日计算；全部快照/每日快照切换仅对实际账户快照模式生效。"
                >
                  <el-button size="small" type="info" disabled>模拟模式：按交易日</el-button>
                </el-tooltip>
                <el-tooltip
                  v-if="activeMenu === 'time'"
                  placement="top"
                  effect="dark"
                  :content="timeReturnMode === 'SNAPSHOT'
                    ? '当前为实际快照收益率：以所选区间第一条账户快照为基准，按真实总资产变化计算。点击后切换为模拟收益率。'
                    : '当前为模拟收益率：固定当前持仓和现金，使用QMT前复权日线回放过去约一年的组合表现，不代表账户当时真实持仓。点击后切换为实际快照收益率。'"
                >
                  <el-button size="small" type="warning" @click="toggleTimeReturnMode">
                    {{ timeReturnMode === 'SNAPSHOT' ? '收益率：实际快照' : '收益率：模拟计算' }}
                  </el-button>
                </el-tooltip>
                <div class="status-indicator"></div>
              </div>
            </div>
            <div class="panel-content">
              <ComparisonChart ref="comparisonChart" :chart-type="getChartType()" :time-granularity="timeGranularity" />
            </div>
          </div>

          <div class="content-panel glass-panel threshold-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon threshold-icon"></i>
                风险阈值
              </h4>
              <div class="header-tools">
                <el-tooltip
                  placement="top"
                  effect="dark"
                  :content="riskGranularity === 'DAILY'
                    ? '当前为日线风险计算：按每天最后一条账户快照计算最大损失、波动率、最大回撤和 VaR。点击后切换为盘中风险。'
                    : '当前为盘中风险计算：使用区间内全部账户快照计算盘中变化，展示最大损失、波动率、最大回撤和 VaR。点击后切换为日线风险。'"
                >
                  <el-button class="risk-mode-button" size="small" type="warning" @click="toggleRiskGranularity">
                    {{ riskGranularity === 'DAILY' ? '风险计算：日线' : '风险计算：盘中' }}
                  </el-button>
                </el-tooltip>
                <el-date-picker
                  v-model="pendingRiskDateRange"
                  type="daterange"
                  size="small"
                  range-separator="至"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                  value-format="YYYY-MM-DD"
                  style="width: 260px"
                />
                <el-button size="small" @click="useCurrentRiskEndDate">当前时间</el-button>
                <el-button size="small" type="primary" @click="confirmRiskDateRange">确认</el-button>
              </div>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
              <RiskThreshold :data="riskThresholdData" :meta="riskThresholdMeta" />
            </div>
          </div>
        </div>

        <div class="content-row bottom-row">
          <div class="content-panel glass-panel table-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon table-icon"></i>
                数据详情
              </h4>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
              <ComparisonTable
                ref="comparisonTable"
                :table-type="getTableType()"
                :time-granularity="timeGranularity"
                :export-timestamp="comparisonExportTimestamp"
                @account-changed="handleAccountChange"
                @refresh="refreshAllData"
              />
            </div>
          </div>

          <div class="content-panel glass-panel warning-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon warning-icon"></i>
                业绩归因
              </h4>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
              <RiskWarning
                ref="riskWarning"
                :account-id="currentAccountId"
                :enabled="activeMenu === 'asset'"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="floating-decorations">
      <div class="decoration-orb orb-1"></div>
      <div class="decoration-orb orb-2"></div>
      <div class="decoration-orb orb-3"></div>
    </div>
  </div>
</template>

<script>
import ComparisonChart from '@/components/comparison/ComparisonChart.vue'
import RiskThreshold from '@/components/comparison/RiskThreshold.vue'
import ComparisonTable from '@/components/comparison/ComparisonTable.vue'
import RiskWarning from '@/components/comparison/RiskWarning.vue'
import { fetchRiskAssessment } from '@/api/riskThresholdApi.js'
import { fetchAssetComparison, fetchYearlyComparisonData, fetchAreaComparison } from '@/api/comparisonModuleApi.js'
import { fetchAccountInfo } from '@/api/accountApi.js'
import { useAccountStore } from '@/store'

export default {
  name: 'ComparisonPage',
  components: { ComparisonChart, RiskThreshold, ComparisonTable, RiskWarning },
  data() {
    return {
      activeMenu: 'asset',
      comparisonDataSource: 'qmt',
      pendingComparisonDataSource: 'qmt',
      timeGranularity: 'DAILY',
      timeReturnMode: 'SNAPSHOT',
      comparisonSourceMeta: {
        data_source: '',
        snapshot_time: '',
        fallback_reason: ''
      },
      currentAccountId: '',
      comparisonRequestId: 0,
      riskThresholdData: [],
      riskThresholdMeta: {},
      riskDateRange: [],
      pendingRiskDateRange: [],
      riskGranularity: 'DAILY',
      refreshTimer: null,
      riskWarnings: [
        {
          level: 'normal',
          message: '系统运行正常',
          time: new Date().toLocaleString(),
          action: '继续监控'
        }
      ]
    }
  },
  async mounted() {
    const accountStore = useAccountStore()
    this.currentAccountId = accountStore.selectedAccountId || ''
    if (!this.currentAccountId) {
      try {
        const accountData = await fetchAccountInfo()
        const firstAccountId = accountData?.accounts?.[0]?.account_id || ''
        if (firstAccountId) {
          this.currentAccountId = firstAccountId
          accountStore.setSelectedAccountId(firstAccountId)
        }
      } catch (error) {
        console.error('初始化对比评估账户失败:', error)
      }
    }
    this.pendingComparisonDataSource = this.comparisonDataSource
    this.initializeRiskDateRange()
    await this.loadRiskThresholdData()
    this.refreshAllData()
    this.startRefreshTimer()
  },
  beforeUnmount() {
    this.stopRefreshTimer()
  },
  methods: {
    startRefreshTimer() {
      if (this.refreshTimer) return
      this.refreshTimer = setInterval(() => {
        if (this.comparisonDataSource !== 'qmt') return
        this.refreshAllData()
      }, 5000)
    },
    initializeRiskDateRange() {
      const end = new Date()
      const start = new Date()
      start.setDate(end.getDate() - 30)
      const formatDate = (value) => {
        const year = value.getFullYear()
        const month = String(value.getMonth() + 1).padStart(2, '0')
        const day = String(value.getDate()).padStart(2, '0')
        return `${year}-${month}-${day}`
      }
      this.riskDateRange = [formatDate(start), formatDate(end)]
      this.pendingRiskDateRange = [...this.riskDateRange]
    },
    useCurrentRiskEndDate() {
      const end = new Date()
      const year = end.getFullYear()
      const month = String(end.getMonth() + 1).padStart(2, '0')
      const day = String(end.getDate()).padStart(2, '0')
      const endText = `${year}-${month}-${day}`
      if (!this.pendingRiskDateRange || this.pendingRiskDateRange.length === 0) {
        const start = new Date()
        start.setDate(end.getDate() - 30)
        const startText = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}-${String(start.getDate()).padStart(2, '0')}`
        this.pendingRiskDateRange = [startText, endText]
        return
      }
      this.pendingRiskDateRange = [this.pendingRiskDateRange[0], endText]
    },
    async confirmRiskDateRange() {
      this.riskDateRange = Array.isArray(this.pendingRiskDateRange) ? [...this.pendingRiskDateRange] : []
      let currentAccountId = this.currentAccountId || useAccountStore().selectedAccountId
      if (!currentAccountId) return
      await this.loadRiskThresholdData(currentAccountId)
    },
    async toggleRiskGranularity() {
      this.riskGranularity = this.riskGranularity === 'DAILY' ? 'ALL' : 'DAILY'
      await this.loadRiskThresholdData(this.currentAccountId)
    },
    stopRefreshTimer() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer)
        this.refreshTimer = null
      }
    },
    async confirmComparisonSourceChange() {
      if (this.pendingComparisonDataSource === this.comparisonDataSource) return
      this.comparisonDataSource = this.pendingComparisonDataSource
      this.comparisonSourceMeta = {
        data_source: '',
        snapshot_time: '',
        fallback_reason: ''
      }
      await this.refreshAllData()
    },
    async toggleTimeGranularity() {
      this.timeGranularity = this.timeGranularity === 'DAILY' ? 'ALL' : 'DAILY'
      await this.refreshAllData()
    },
    async toggleTimeReturnMode() {
      this.timeReturnMode = this.timeReturnMode === 'SNAPSHOT' ? 'SIMULATED' : 'SNAPSHOT'
      await this.refreshAllData()
    },
    async refreshAllData() {
      const currentRequestId = ++this.comparisonRequestId
      const requestedSource = this.comparisonDataSource
      let currentAccountId = this.currentAccountId || useAccountStore().selectedAccountId
      if (!currentAccountId) return

      try {
        let rawData = null
        if (this.activeMenu === 'asset') {
          rawData = await fetchAssetComparison(currentAccountId, requestedSource)
        } else if (this.activeMenu === 'time') {
          rawData = await fetchYearlyComparisonData(
            currentAccountId,
            'mongodb',
            this.timeGranularity,
            this.timeReturnMode
          )
        } else if (this.activeMenu === 'region') {
          rawData = await fetchAreaComparison(currentAccountId, requestedSource)
        }
        if (!rawData) return
        if (currentRequestId !== this.comparisonRequestId || requestedSource !== this.comparisonDataSource) {
          return
        }

        this.comparisonSourceMeta = {
          data_source: rawData.data_source || (this.activeMenu === 'time' ? 'mongodb_history' : ''),
          snapshot_time: rawData.snapshot_time || rawData.latest_time || '',
          fallback_reason: rawData.fallback_reason || '',
          calculation_method: rawData.calculation_method || '',
          return_calculation_mode: rawData.return_calculation_mode || this.timeReturnMode,
          warnings: rawData.warnings || []
        }

        if (this.$refs.comparisonChart && typeof this.$refs.comparisonChart.setData === 'function') {
          this.$refs.comparisonChart.setData(rawData, currentAccountId)
        }
        if (this.$refs.comparisonTable && typeof this.$refs.comparisonTable.setData === 'function') {
          this.$refs.comparisonTable.setData(rawData)
        }
      } catch (error) {
        console.error('刷新对比评估数据失败:', error)
      }
    },
    async loadRiskThresholdData(accountId = '') {
      if (!accountId) {
        accountId = this.currentAccountId || useAccountStore().selectedAccountId
      }
      if (!accountId) {
        this.riskThresholdData = []
        this.riskThresholdMeta = {}
        return
      }
      try {
        const options = {}
        if (Array.isArray(this.riskDateRange) && this.riskDateRange.length === 2) {
          options.startDate = this.riskDateRange[0]
          options.endDate = this.riskDateRange[1]
        }
        options.granularity = this.riskGranularity
        const data = await fetchRiskAssessment(accountId, 30, options)
        this.riskThresholdData = data.risk_indicators || []
        this.riskThresholdMeta = data.meta || {}
      } catch (error) {
        console.error('获取风险阈值失败:', error)
        this.riskThresholdData = []
        this.riskThresholdMeta = {
          notice_message:
            error?.response?.data?.message ||
            error?.response?.data?.error?.message ||
            (typeof error?.response?.data?.error === 'string' ? error.response.data.error : '') ||
            error?.message ||
            '风险阈值数据加载失败'
        }
      }
    },
    setActiveMenu(menu) {
      if (this.activeMenu === menu) return
      this.activeMenu = menu
      this.$nextTick(() => {
        this.refreshAllData()
      })
    },
    getChartType() { return this.activeMenu },
    getTableType() { return this.activeMenu },
    handleAccountChange(accountId) {
      this.currentAccountId = accountId || ''
      this.refreshAllData()
      this.loadRiskThresholdData(this.currentAccountId)
    }
  },
  computed: {
    comparisonExportTimestamp() {
      return this.comparisonSourceMeta?.snapshot_time || ''
    },
    appliedComparisonSourceLabel() {
      if (this.activeMenu === 'time' && this.timeReturnMode === 'SIMULATED') {
        return '模拟收益率（近一年日度）'
      }
      if (this.comparisonSourceMeta?.data_source === 'qmt_position_reconstruction') {
        return 'QMT日线+当前持仓重建（非券商历史净值）'
      }
      return this.comparisonDataSource === 'qmt' ? 'QMT实时' : 'MongoDB缓存'
    },
    pendingComparisonSourceLabel() {
      return this.pendingComparisonDataSource === 'qmt' ? 'QMT实时' : 'MongoDB缓存'
    },
    comparisonStatusText() {
      const activeText = `已生效:${this.appliedComparisonSourceLabel}`
      const pendingText = this.pendingComparisonDataSource !== this.comparisonDataSource
        ? ` | 待切换:${this.pendingComparisonSourceLabel}`
        : ''
      const timeText = this.comparisonSourceMeta?.snapshot_time
        ? String(this.comparisonSourceMeta.snapshot_time).replace('T', ' ')
        : '--'
      return `${activeText}${pendingText} | ${timeText}`
    }
  }
}
</script>

<style scoped>
.comparison-page {
  position: relative;
  width: 100%;
  height: calc(100vh - 120px);
  padding: 20px;
  box-sizing: border-box;
  overflow: auto;
  z-index: 2;
}
.grid-background {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(64, 224, 255, 0.05) 1px, transparent 1px), linear-gradient(90deg, rgba(64, 224, 255, 0.05) 1px, transparent 1px);
  background-size: 50px 50px;
  animation: gridMove 20s linear infinite;
  pointer-events: none;
}
@keyframes gridMove { 0% { transform: translate(0, 0); } 100% { transform: translate(50px, 50px); } }
.content-wrapper { position: relative; width: 100%; height: 100%; display: flex; gap: 20px; z-index: 3; }
.glass-panel { position: relative; background: rgba(12, 20, 38, 0.4); backdrop-filter: blur(20px); border: 1px solid rgba(64, 224, 255, 0.3); border-radius: 12px; box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3); overflow: hidden; transition: all 0.3s ease; }
.left-sidebar { width: 180px; min-width: 180px; display: flex; flex-direction: column; }
.sidebar-header, .panel-header { padding: 12px 15px; background: rgba(64, 224, 255, 0.1); border-bottom: 1px solid rgba(64, 224, 255, 0.2); display: flex; align-items: center; gap: 8px; min-width: 0; }
.header-line { width: 3px; height: 16px; background: linear-gradient(180deg, #40e0ff, #1e90ff); border-radius: 2px; }
.sidebar-title, .panel-title { margin: 0; font-size: 14px; font-weight: 600; color: #ffffff; }
.sidebar-menu { flex: 1; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.menu-item { display: flex; align-items: center; gap: 10px; padding: 12px 15px; border-radius: 8px; cursor: pointer; transition: all 0.3s ease; color: rgba(255, 255, 255, 0.7); font-size: 13px; }
.menu-item:hover { background: rgba(64, 224, 255, 0.1); color: #ffffff; }
.menu-item.active { background: rgba(64, 224, 255, 0.2); color: #40e0ff; border: 1px solid rgba(64, 224, 255, 0.3); }
.menu-icon { width: 12px; height: 12px; border-radius: 2px; }
.asset-icon { background: linear-gradient(135deg, #40e0ff, #1e90ff); }
.time-icon { background: linear-gradient(135deg, #feca57, #ff9ff3); }
.region-icon { background: linear-gradient(135deg, #48dbfb, #0abde3); }
.main-content { flex: 1; display: flex; flex-direction: column; gap: 20px; }
.content-row { flex: 1; display: flex; gap: 20px; min-height: 0; }
.content-panel { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.panel-title { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; white-space: nowrap; min-width: max-content; }
.header-tools { flex: 1 1 auto; min-width: 0; display: flex; align-items: center; justify-content: flex-end; gap: 10px; flex-wrap: nowrap; overflow: visible; }
.source-state { font-size: 12px; color: rgba(255, 255, 255, 0.9); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
.status-indicator { width: 8px; height: 8px; border-radius: 50%; background: #00ff88; box-shadow: 0 0 8px #00ff88; }
.panel-content { flex: 1; padding: 15px; overflow: hidden; position: relative; }
.warning-panel .panel-content { overflow-y: auto; }
.floating-decorations { position: absolute; inset: 0; pointer-events: none; z-index: 1; }
.decoration-orb { position: absolute; border-radius: 50%; background: radial-gradient(circle, rgba(64, 224, 255, 0.2), transparent); animation: orbFloat 8s ease-in-out infinite; }
.orb-1 { width: 80px; height: 80px; top: 10%; right: 20%; }
.orb-2 { width: 60px; height: 60px; bottom: 20%; left: 15%; animation-delay: 3s; }
.orb-3 { width: 70px; height: 70px; top: 60%; right: 10%; animation-delay: 6s; }
@keyframes orbFloat { 0%,100% { transform: translateY(0); opacity: 0.3; } 50% { transform: translateY(-15px); opacity: 0.6; } }
@media (max-width: 1200px) { .content-row { flex-direction: column; } }
</style>
