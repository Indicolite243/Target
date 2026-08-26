<template>
  <div class="attribution-panel">
    <div class="controls-card">
      <div class="controls-row">
        <div class="control-label inline-label">数据源</div>
        <div class="control-actions unified-actions">
          <el-select v-model="pendingSource" size="small" class="control-select">
            <el-option label="Redis实时快照" value="qmt" />
            <el-option label="MySQL历史快照" value="mysql" />
          </el-select>
          <el-button size="small" type="primary" @click="applySource">确认</el-button>
          <el-date-picker
            v-model="pendingDateRange"
            type="daterange"
            size="small"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            class="date-picker compact-date-picker"
          />
          <el-button size="small" @click="useCurrentEndDate">当前时间</el-button>
          <el-button size="small" type="primary" @click="applyDateRange">确认</el-button>
        </div>
      </div>
    </div>

    <div v-if="loading" class="loading-card">
      <div class="loading-title">业绩归因加载中</div>
      <div class="loading-subtitle">正在根据当前账户、数据源和时间范围计算个股与行业贡献。</div>
    </div>

    <div v-else-if="errorMessage" class="error-card">
      <div class="error-title">业绩归因加载失败</div>
      <div class="error-subtitle">{{ errorMessage }}</div>
    </div>

    <template v-else-if="hasData">
      <div class="summary-grid">
        <div class="summary-card">
          <div class="summary-top">
            <div class="summary-label">组合市值</div>
            <el-tooltip placement="top" effect="dark" :show-after="120">
              <template #content>
                <div class="tooltip-content">
                  <div>含义：统计当前区间结束时组合全部持仓的市值合计。</div>
                  <div>计算：各持仓当前市值求和。</div>
                </div>
              </template>
              <span class="hint-icon">!</span>
            </el-tooltip>
          </div>
          <div class="summary-value" :title="formatCurrency(summary.totalMarketValue)" v-fit-text>{{ formatCurrency(summary.totalMarketValue) }}</div>
        </div>

        <div class="summary-card">
          <div class="summary-top">
            <div class="summary-label">浮动盈亏</div>
            <el-tooltip placement="top" effect="dark" :show-after="120">
              <template #content>
                <div class="tooltip-content">
                  <div>含义：当前区间内，按结束持仓估算的总盈亏金额。</div>
                  <div>计算：Σ((结束价格 - 起始价格) × 持仓数量)。</div>
                </div>
              </template>
              <span class="hint-icon">!</span>
            </el-tooltip>
          </div>
          <div class="summary-value" :class="valueClass(summary.totalPnlAmount)" :title="formatSignedCurrency(summary.totalPnlAmount)" v-fit-text>
            {{ formatSignedCurrency(summary.totalPnlAmount) }}
          </div>
        </div>

        <div class="summary-card">
          <div class="summary-top">
            <div class="summary-label">正贡献个股</div>
          </div>
          <div class="summary-value" :title="String(summary.positiveCount)" v-fit-text>{{ summary.positiveCount }}</div>
        </div>

        <div class="summary-card">
          <div class="summary-top">
            <div class="summary-label">主导行业</div>
          </div>
          <div class="summary-value small" :title="summary.leadingIndustry" v-fit-text>{{ summary.leadingIndustry }}</div>
        </div>
      </div>

      <div class="top-contributors-grid">
        <div class="card-section">
          <div class="section-head">
            <div class="section-title">正贡献 Top 5</div>
            <el-tooltip placement="top" effect="dark" :show-after="120">
              <template #content>
                <div class="tooltip-content">
                  <div>左侧权重：该股票在区间结束时占组合总市值的比例。</div>
                  <div>右上百分比：该股票对组合总收益的贡献比例。</div>
                  <div>右下百分比：该股票在所选区间内的价格涨跌幅。</div>
                </div>
              </template>
              <span class="hint-icon">!</span>
            </el-tooltip>
          </div>
          <div v-if="topPositive.length" class="stock-list">
            <div v-for="item in topPositive" :key="`pos-${item.stockCode}`" class="stock-row">
              <div class="stock-main">
                <div class="stock-name">{{ item.stockName }}</div>
                <div class="stock-meta">{{ item.industry }} · 权重 {{ formatPercent(item.weightPct) }}</div>
              </div>
              <div class="stock-metrics positive">
                <div>{{ formatSignedPercent(item.contributionPct) }}</div>
                <div class="metric-sub">{{ formatSignedPercent(item.returnRate) }}</div>
              </div>
            </div>
          </div>
          <div v-else class="empty-inline">暂无正贡献个股</div>
        </div>

        <div class="card-section">
          <div class="section-head">
            <div class="section-title">负贡献 Top 5</div>
            <el-tooltip placement="top" effect="dark" :show-after="120">
              <template #content>
                <div class="tooltip-content">
                  <div>左侧权重：该股票在区间结束时占组合总市值的比例。</div>
                  <div>右上百分比：该股票对组合总收益的拖累比例。</div>
                  <div>右下百分比：该股票在所选区间内的价格涨跌幅。</div>
                </div>
              </template>
              <span class="hint-icon">!</span>
            </el-tooltip>
          </div>
          <div v-if="topNegative.length" class="stock-list">
            <div v-for="item in topNegative" :key="`neg-${item.stockCode}`" class="stock-row">
              <div class="stock-main">
                <div class="stock-name">{{ item.stockName }}</div>
                <div class="stock-meta">{{ item.industry }} · 权重 {{ formatPercent(item.weightPct) }}</div>
              </div>
              <div class="stock-metrics negative">
                <div>{{ formatSignedPercent(item.contributionPct) }}</div>
                <div class="metric-sub">{{ formatSignedPercent(item.returnRate) }}</div>
              </div>
            </div>
          </div>
          <div v-else class="empty-inline">暂无负贡献个股</div>
        </div>
      </div>

      <div class="meta-bar">
        <span>样本个股 {{ attributionRows.length }} 只</span>
        <span>数据来源 {{ sourceLabel }}</span>
        <span v-if="snapshotTime">数据时间 {{ snapshotTime }}</span>
        <span v-if="meta.sampleCount">样本数 {{ meta.sampleCount }}</span>
        <span v-if="meta.rangeStart && meta.rangeEnd">区间 {{ meta.rangeStart }} 至 {{ meta.rangeEnd }}</span>
      </div>

      <div class="industry-card">
        <div class="industry-title-bar">行业贡献</div>
        <div v-if="industryRows.length" class="industry-list">
          <div v-for="industry in industryRows" :key="industry.name" class="industry-row">
            <div class="industry-header">
              <span class="industry-name">{{ industry.name }}</span>
              <span class="industry-value" :class="valueClass(industry.contributionPct)">
                {{ formatSignedPercent(industry.contributionPct) }}
              </span>
            </div>
            <div class="industry-bar-track">
              <div
                class="industry-bar"
                :class="valueClass(industry.contributionPct)"
                :style="industryBarStyle(industry.contributionPct)"
              ></div>
            </div>
          </div>
        </div>
        <div v-else class="empty-inline">暂无行业归因数据</div>
      </div>
    </template>

    <div v-else class="empty-state">
      <div class="empty-title">暂无业绩归因数据</div>
      <div class="empty-text">切换到资产对比并加载持仓后，这里会展示个股与行业贡献。</div>
    </div>
  </div>
</template>

<script>
import { fetchAssetAttribution } from '@/api/comparisonModuleApi.js'

function fitTextToContainer(el) {
  const minFontSize = Number(el.dataset.minFontSize || 14)
  const maxFontSize = Number(el.dataset.maxFontSize || (el.classList.contains('small') ? 18 : 22))
  const parent = el.parentElement
  if (!parent) return

  const availableWidth = Math.max(parent.clientWidth - 18, 80)
  let fontSize = maxFontSize
  el.style.fontSize = `${fontSize}px`

  while (fontSize > minFontSize && el.scrollWidth > availableWidth) {
    fontSize -= 1
    el.style.fontSize = `${fontSize}px`
  }
}

const fitTextDirective = {
  mounted(el) {
    const rerender = () => requestAnimationFrame(() => fitTextToContainer(el))
    el.__fitTextResizeObserver__ = new ResizeObserver(rerender)
    el.__fitTextResizeObserver__.observe(el)
    if (el.parentElement) {
      el.__fitTextResizeObserver__.observe(el.parentElement)
    }
    rerender()
  },
  updated(el) {
    requestAnimationFrame(() => fitTextToContainer(el))
  },
  unmounted(el) {
    if (el.__fitTextResizeObserver__) {
      el.__fitTextResizeObserver__.disconnect()
      delete el.__fitTextResizeObserver__
    }
  }
}

export default {
  name: 'RiskWarning',
  directives: {
    fitText: fitTextDirective
  },
  props: {
    accountId: {
      type: String,
      default: ''
    },
    enabled: {
      type: Boolean,
      default: false
    }
  },
  data() {
    const range = this.buildDefaultDateRange()
    return {
      loading: false,
      errorMessage: '',
      source: 'mysql',
      pendingSource: 'mysql',
      dateRange: range,
      pendingDateRange: [...range],
      attributionRows: [],
      industryRows: [],
      summary: {
        totalMarketValue: 0,
        totalPnlAmount: 0,
        positiveCount: 0,
        negativeCount: 0,
        leadingIndustry: '--'
      },
      meta: {
        sampleCount: 0,
        rangeStart: '',
        rangeEnd: ''
      },
      sourceLabel: '--',
      snapshotTime: '',
      requestId: 0
    }
  },
  computed: {
    hasData() {
      return this.attributionRows.length > 0
    },
    topPositive() {
      return this.attributionRows.filter((item) => item.contributionPct > 0).slice(0, 5)
    },
    topNegative() {
      return this.attributionRows.filter((item) => item.contributionPct < 0).slice(0, 5)
    },
    maxIndustryContributionAbs() {
      const values = this.industryRows.map((item) => Math.abs(item.contributionPct))
      return values.length ? Math.max(...values, 0.01) : 0.01
    }
  },
  watch: {
    enabled: {
      immediate: true,
      handler(value) {
        if (value) {
          this.loadAttribution()
        }
      }
    },
    accountId(newValue, oldValue) {
      if (!this.enabled || !newValue || newValue === oldValue) return
      this.loadAttribution()
    }
  },
  methods: {
    buildDefaultDateRange() {
      const end = new Date()
      const start = new Date()
      start.setDate(end.getDate() - 30)
      return [this.formatDate(start), this.formatDate(end)]
    },
    formatDate(value) {
      const year = value.getFullYear()
      const month = String(value.getMonth() + 1).padStart(2, '0')
      const day = String(value.getDate()).padStart(2, '0')
      return `${year}-${month}-${day}`
    },
    async loadAttribution() {
      if (!this.enabled || !this.accountId) return
      const currentRequestId = ++this.requestId
      this.loading = true
      this.errorMessage = ''
      try {
        const data = await fetchAssetAttribution(this.accountId, this.source, {
          startDate: this.dateRange[0],
          endDate: this.dateRange[1]
        })
        if (currentRequestId !== this.requestId) return
        this.applyPayload(data)
      } catch (error) {
        if (currentRequestId !== this.requestId) return
        this.errorMessage = error?.response?.data?.error || error?.message || '加载失败'
        this.resetState()
      } finally {
        if (currentRequestId === this.requestId) {
          this.loading = false
        }
      }
    },
    applyPayload(data) {
      this.attributionRows = Array.isArray(data.attributionRows) ? data.attributionRows : []
      this.industryRows = Array.isArray(data.industryRows) ? data.industryRows : []
      this.summary = data.summary || {
        totalMarketValue: 0,
        totalPnlAmount: 0,
        positiveCount: 0,
        negativeCount: 0,
        leadingIndustry: '--'
      }
      this.meta = {
        sampleCount: Number(data.sample_count || 0),
        rangeStart: data.range_start || '',
        rangeEnd: data.range_end || ''
      }
      this.sourceLabel = this.resolveSourceLabel(data.data_source)
      this.snapshotTime = data.snapshot_time ? String(data.snapshot_time).replace('T', ' ') : ''
    },
    resetState() {
      this.attributionRows = []
      this.industryRows = []
      this.summary = {
        totalMarketValue: 0,
        totalPnlAmount: 0,
        positiveCount: 0,
        negativeCount: 0,
        leadingIndustry: '--'
      }
      this.meta = {
        sampleCount: 0,
        rangeStart: '',
        rangeEnd: ''
      }
      this.sourceLabel = '--'
      this.snapshotTime = ''
    },
    applySource() {
      if (this.pendingSource === this.source) return
      this.source = this.pendingSource
      this.loadAttribution()
    },
    applyDateRange() {
      if (!Array.isArray(this.pendingDateRange) || this.pendingDateRange.length !== 2) return
      this.dateRange = [...this.pendingDateRange]
      this.loadAttribution()
    },
    useCurrentEndDate() {
      const endText = this.formatDate(new Date())
      if (!Array.isArray(this.pendingDateRange) || this.pendingDateRange.length !== 2) {
        this.pendingDateRange = this.buildDefaultDateRange()
      }
      this.pendingDateRange = [this.pendingDateRange[0], endText]
    },
    resolveSourceLabel(source) {
      if (source === 'qmt_live') return 'QMT实时'
      if (source === 'qmt_history') return 'QMT历史行情'
      if (source === 'mysql_current') return 'MySQL最近同步快照'
      if (source === 'mysql_account_snapshots') return 'MySQL历史快照'
      return '--'
    },
    valueClass(value) {
      if (value > 0) return 'positive'
      if (value < 0) return 'negative'
      return 'neutral'
    },
    industryBarStyle(value) {
      return {
        width: `${Math.max(6, (Math.abs(value) / this.maxIndustryContributionAbs) * 100)}%`
      }
    },
    formatCurrency(value) {
      return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })
    },
    formatSignedCurrency(value) {
      const amount = Number(value || 0)
      const prefix = amount > 0 ? '+' : ''
      return `${prefix}${amount.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
    },
    formatSignedPercent(value) {
      const amount = Number(value || 0)
      const prefix = amount > 0 ? '+' : ''
      return `${prefix}${amount.toFixed(2)}%`
    },
    formatPercent(value) {
      return `${Number(value || 0).toFixed(2)}%`
    }
  }
}
</script>

<style scoped>
.attribution-panel {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  color: #ffffff;
  overflow-y: auto;
  padding-right: 4px;
  box-sizing: border-box;
}

.controls-card,
.summary-card,
.card-section,
.meta-bar,
.industry-card,
.loading-card,
.error-card {
  background: rgba(21, 31, 56, 0.72);
  border: 1px solid rgba(64, 224, 255, 0.14);
  border-radius: 12px;
  box-shadow: inset 0 0 18px rgba(64, 224, 255, 0.05);
}

.controls-card,
.loading-card,
.error-card,
.meta-bar,
.industry-card,
.card-section,
.summary-card {
  padding: 14px;
  box-sizing: border-box;
}

.controls-row {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 14px;
  flex-wrap: wrap;
}

.control-label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.72);
  white-space: nowrap;
}

.control-label.inline-label {
  min-width: 56px;
}

.control-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex-wrap: wrap;
}

.unified-actions {
  flex: 1 1 auto;
}

.control-select {
  width: 140px;
}

.date-picker {
  width: 260px;
}

.compact-date-picker {
  width: 430px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-top,
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.summary-label,
.section-title,
.stock-meta,
.meta-bar {
  color: rgba(255, 255, 255, 0.72);
}

.summary-label,
.section-title {
  font-size: 13px;
}

.summary-value {
  font-size: 22px;
  font-weight: 700;
  color: #9ad9ff;
  white-space: nowrap;
}

.summary-value.small {
  font-size: 18px;
}

.hint-icon {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: 1px solid rgba(64, 224, 255, 0.4);
  color: #7bdcff;
  font-size: 12px;
  font-weight: 700;
  cursor: help;
  flex-shrink: 0;
}

.tooltip-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 260px;
  line-height: 1.5;
}

.top-contributors-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  align-items: start;
}

.stock-list,
.industry-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.stock-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.03);
  border-radius: 10px;
}

.stock-main {
  min-width: 0;
  flex: 1;
}

.stock-name {
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
  margin-bottom: 4px;
  line-height: 1.35;
  word-break: break-all;
}

.stock-meta,
.metric-sub {
  font-size: 12px;
  line-height: 1.4;
}

.stock-metrics {
  text-align: right;
  font-weight: 700;
  min-width: 86px;
  flex-shrink: 0;
}

.meta-bar {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  min-height: 48px;
  font-size: 12px;
}

.meta-bar span {
  display: inline-flex;
  align-items: center;
  line-height: 1;
}

.industry-title-bar {
  display: flex;
  align-items: center;
  min-height: 40px;
  padding: 0 14px;
  margin-bottom: 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.03);
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
}

.industry-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 0 12px 0 2px;
}

.industry-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.industry-name {
  font-size: 13px;
  color: #ffffff;
}

.industry-value {
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}

.industry-bar-track {
  width: 100%;
  height: 10px;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  overflow: hidden;
}

.industry-bar {
  height: 100%;
  border-radius: 999px;
}

.loading-card,
.error-card,
.empty-state,
.empty-inline {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.72);
}

.loading-title,
.error-title,
.empty-title {
  font-size: 16px;
  font-weight: 600;
  color: #ffffff;
}

.loading-subtitle,
.error-subtitle,
.empty-text {
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
  max-width: 420px;
}

.positive {
  color: #ff8c8c;
}

.negative {
  color: #6ee7a8;
}

.neutral {
  color: #9ad9ff;
}

.industry-bar.positive {
  background: linear-gradient(90deg, rgba(255, 140, 140, 0.35), #ff8c8c);
}

.industry-bar.negative {
  background: linear-gradient(90deg, rgba(110, 231, 168, 0.35), #6ee7a8);
}

.industry-bar.neutral {
  background: linear-gradient(90deg, rgba(154, 217, 255, 0.35), #9ad9ff);
}

@media (max-width: 1200px) {
  .summary-grid,
  .top-contributors-grid {
    grid-template-columns: 1fr;
  }

  .date-picker {
    width: 100%;
    max-width: 320px;
  }

  .controls-row {
    align-items: stretch;
  }

  .control-block.inline-block {
    flex-direction: column;
    align-items: stretch;
  }

  .control-actions {
    align-items: stretch;
  }
}
</style>
