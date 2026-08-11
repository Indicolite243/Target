<template>
  <div class="risk-threshold">
    <div class="threshold-content">
      <div v-if="meta.notice_message" class="notice-card">
        <div class="notice-title">当前区间暂时无法完整计算风险指标</div>
        <div class="notice-text">{{ meta.notice_message }}</div>
        <div v-if="meta.unavailable_metrics && meta.unavailable_metrics.length" class="notice-extra">
          暂不可计算：{{ meta.unavailable_metrics.join('、') }}
        </div>
        <div v-if="meta.sample_requirements && meta.sample_requirements.minimum_effective_days" class="notice-extra">
          计算要求：至少 {{ meta.sample_requirements.minimum_effective_days }} 个有效交易日样本
        </div>
        <div class="notice-rules">
          <div class="notice-rules-title">计算规则</div>
          <div class="notice-rule">有效交易日样本：指某个交易日内，系统在 09:30-11:30 或 13:00-15:00 采集到至少一条账户快照，并取该日最后一条有效快照作为当日风险计算样本。</div>
          <div class="notice-rule">1. 风险阈值只使用有效交易时段内的账户快照样本。</div>
          <div v-if="meta.sample_requirements && meta.sample_requirements.effective_trading_sessions" class="notice-rule">
            2. 有效交易时段：{{ meta.sample_requirements.effective_trading_sessions.join('、') }}
          </div>
          <div class="notice-rule">3. 同一交易日只保留该日最后一条有效快照作为风险计算样本。</div>
          <div class="notice-rule">4. 波动率、最大回撤、VaR 至少需要 2 个有效交易日样本。</div>
        </div>
      </div>
      <div class="metrics-grid">
        <div
          v-for="(item, index) in data"
          :key="index"
          class="metric-card"
          :class="item.status"
        >
          <div class="metric-header">
            <div class="metric-icon" :class="`icon-${item.status}`"></div>
            <div class="metric-name">{{ item.metric }}</div>
            <div class="status-badge" :class="item.status">
              {{ getStatusText(item.status) }}
            </div>
          </div>
          <div class="metric-value">{{ item.value }}</div>
          <div class="metric-trend">
            <div class="trend-icon" :class="getTrendClass(item.value)"></div>
            <span class="trend-text">{{ getTrendText(item.value) }}</span>
          </div>
        </div>
      </div>

      <div class="threshold-summary">
        <div class="summary-title">风险总览</div>
        <div class="summary-grid">
          <div class="summary-item warning">
            <div class="summary-count">{{ getWarningCount() }}</div>
            <div class="summary-label">预警</div>
          </div>
          <div class="summary-item danger">
            <div class="summary-count">{{ getDangerCount() }}</div>
            <div class="summary-label">风险</div>
          </div>
        </div>
        <div v-if="meta" class="source-meta">
          数据源：{{ formatSource(meta.data_source) }}
          <span v-if="meta.snapshot_time"> · 更新时间：{{ meta.snapshot_time.replace('T', ' ') }}</span>
          <span v-if="sampleCount"> · 样本数：{{ sampleCount }}</span>
          <span v-if="sampleStart && sampleEnd"> · 区间：{{ sampleStart }} 至 {{ sampleEnd }}</span>
          <span v-if="meta.risk_score !== undefined"> · 风险分：{{ meta.risk_score }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'RiskThreshold',
  props: {
    data: {
      type: Array,
      default: () => []
    },
    meta: {
      type: Object,
      default: () => ({})
    }
  },
  computed: {
    sampleCount() {
      return this.meta.period_days_available || this.meta.sample?.tradingDays || 0
    },
    sampleStart() {
      return this.meta.range_start || this.meta.sample?.startDate || ''
    },
    sampleEnd() {
      return this.meta.range_end || this.meta.sample?.endDate || ''
    }
  },
  methods: {
    getStatusText(status) {
      return { normal: '正常', warning: '预警', danger: '风险' }[status] || '未知'
    },
    getTrendClass(value) {
      const numValue = parseFloat(value)
      if (numValue > 10) return 'trend-up'
      if (numValue < 5) return 'trend-down'
      return 'trend-stable'
    },
    getTrendText(value) {
      const numValue = parseFloat(value)
      if (numValue > 10) return '偏高'
      if (numValue < 5) return '偏低'
      return '正常'
    },
    getWarningCount() {
      return this.data.filter(item => item.status === 'warning').length
    },
    getDangerCount() {
      return this.data.filter(item => item.status === 'danger').length
    },
    formatSource(source) {
      const labels = {
        mock: '模拟数据',
        qmt_history: 'QMT历史行情',
        qmt_live: 'QMT实时',
        mongodb: 'MongoDB历史快照',
        mongodb_history: 'MongoDB历史快照'
      }
      return labels[source] || source || '--'
    }
  }
}
</script>

<style scoped>
.risk-threshold {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.threshold-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 15px;
  overflow-y: auto;
}

.notice-card {
  padding: 12px;
  background: rgba(254, 202, 87, 0.08);
  border: 1px solid rgba(254, 202, 87, 0.35);
  border-radius: 8px;
}

.notice-title {
  color: #feca57;
  font-size: 13px;
  font-weight: bold;
  margin-bottom: 6px;
}

.notice-text,
.notice-extra {
  color: rgba(255, 255, 255, 0.82);
  font-size: 11px;
  line-height: 1.6;
}

.notice-extra {
  margin-top: 4px;
}

.notice-rules {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid rgba(254, 202, 87, 0.18);
}

.notice-rules-title {
  color: #ffd36e;
  font-size: 11px;
  font-weight: 600;
  margin-bottom: 4px;
}

.notice-rule {
  color: rgba(255, 255, 255, 0.78);
  font-size: 11px;
  line-height: 1.6;
}

.metrics-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.metric-card {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(64, 224, 255, 0.2);
  border-radius: 8px;
  padding: 12px;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
}

.metric-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 3px;
  height: 100%;
  background: #40e0ff;
}
.metric-card.normal::before { background: #00ff88; }
.metric-card.warning::before { background: #feca57; }
.metric-card.danger::before { background: #ff6b6b; }
.metric-card:hover {
  border-color: rgba(64, 224, 255, 0.4);
  box-shadow: 0 0 15px rgba(64, 224, 255, 0.2);
  transform: translateY(-2px);
}
.metric-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.metric-icon {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}
.icon-normal { background: #00ff88; box-shadow: 0 0 8px rgba(0,255,136,0.6); }
.icon-warning { background: #feca57; box-shadow: 0 0 8px rgba(254,202,87,0.6); }
.icon-danger { background: #ff6b6b; box-shadow: 0 0 8px rgba(255,107,107,0.6); }
.metric-name { flex: 1; color: #ffffff; font-size: 11px; font-weight: 500; }
.status-badge { padding: 2px 6px; border-radius: 10px; font-size: 9px; font-weight: bold; }
.status-badge.normal { background: rgba(0,255,136,0.2); color: #00ff88; }
.status-badge.warning { background: rgba(254,202,87,0.2); color: #feca57; }
.status-badge.danger { background: rgba(255,107,107,0.2); color: #ff6b6b; }
.metric-value { font-size: 18px; font-weight: bold; color: #40e0ff; text-shadow: 0 0 10px rgba(64, 224, 255, 0.5); margin-bottom: 6px; }
.metric-trend { display: flex; align-items: center; gap: 4px; }
.trend-icon { width: 8px; height: 8px; border-radius: 2px; }
.trend-up { background: #ff6b6b; }
.trend-down { background: #00ff88; }
.trend-stable { background: #feca57; }
.trend-text { font-size: 10px; color: rgba(255, 255, 255, 0.7); }
.threshold-summary {
  margin-top: auto;
  padding: 12px;
  background: rgba(64, 224, 255, 0.05);
  border: 1px solid rgba(64, 224, 255, 0.2);
  border-radius: 8px;
}
.summary-title { color: #ffffff; font-size: 12px; font-weight: bold; margin-bottom: 10px; text-align: center; }
.summary-grid { display: flex; justify-content: space-around; gap: 10px; }
.summary-item { text-align: center; padding: 8px; border-radius: 6px; flex: 1; }
.summary-item.warning { background: rgba(254, 202, 87, 0.1); border: 1px solid rgba(254, 202, 87, 0.3); }
.summary-item.danger { background: rgba(255, 107, 107, 0.1); border: 1px solid rgba(255, 107, 107, 0.3); }
.summary-count { font-size: 16px; font-weight: bold; margin-bottom: 2px; }
.summary-item.warning .summary-count { color: #feca57; }
.summary-item.danger .summary-count { color: #ff6b6b; }
.summary-label { font-size: 10px; color: rgba(255, 255, 255, 0.8); }
.source-meta {
  margin-top: 10px;
  font-size: 11px;
  color: rgba(255,255,255,0.72);
  text-align: center;
}
.threshold-content::-webkit-scrollbar { width: 4px; }
.threshold-content::-webkit-scrollbar-track { background: rgba(64, 224, 255, 0.1); border-radius: 2px; }
.threshold-content::-webkit-scrollbar-thumb { background: rgba(64, 224, 255, 0.4); border-radius: 2px; }
</style>
