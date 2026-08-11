<template>
  <div class="strategy-result">
    <div class="title">策略执行结果</div>

    <div class="result-content">
      <ChartSection class="transaction-curve" />

      <div class="key-metrics">
        <div class="title">关键指标</div>
        <div class="result-table-wrapper">
          <el-table
            :data="keyMetrics"
            border
            style="width: 100%"
            :header-cell-style="headerStyle"
          >
            <el-table-column prop="metricName" label="指标名称" />
            <el-table-column prop="metricValue" label="指标值" />
            <el-table-column prop="description" label="描述" />
          </el-table>
        </div>
      </div>

      <div class="execution-meta">
        <div class="title">本次执行信息</div>
        <div class="result-table-wrapper">
          <el-table
            :data="executionInfo"
            border
            style="width: 100%"
            :header-cell-style="headerStyle"
          >
            <el-table-column prop="label" label="项目" width="180" />
            <el-table-column prop="value" label="内容" />
          </el-table>
        </div>
      </div>

      <div class="report-actions">
        <el-button
          type="primary"
          class="download-button"
          :disabled="!canDownloadOperationReport"
          @click="downloadOperationReport"
        >
          策略操作报告
        </el-button>
        <el-button
          type="primary"
          plain
          class="download-button result-report-button"
          :disabled="!canDownloadResultReport"
          @click="downloadResultReport"
        >
          策略回测结果报告
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartSection from './ChartSection.vue'

const defaultMetrics = [
  { metricName: '策略收益率', metricValue: '--', description: '当前策略累计收益率' },
  { metricName: '基准收益率', metricValue: '--', description: '当前基准累计收益率' },
  { metricName: '策略年化收益率', metricValue: '--', description: '策略年化投资回报率' },
  { metricName: '基准年化收益率', metricValue: '--', description: '基准年化投资回报率' },
  { metricName: '最大回撤', metricValue: '--', description: '历史最大回撤幅度' },
  { metricName: '夏普比率', metricValue: '--', description: '风险调整后收益指标' }
]

const defaultExecutionInfo = [
  { label: '请求编号', value: '--' },
  { label: '上传文件', value: '待上传' },
  { label: '请求引擎', value: '待选择' },
  { label: '实际执行器', value: '待执行' },
  { label: '策略格式', value: '--' },
  { label: '解析引擎', value: '--' },
  { label: '基准代码', value: '--' },
  { label: '熊市保护', value: '--' },
  { label: '滑点', value: '--' },
  { label: '手续费率', value: '--' },
  { label: '成交量限制比例', value: '--' },
  { label: '报告路径', value: '待生成' },
  { label: '交易明细路径', value: '待生成' }
]

const keyMetrics = ref([...defaultMetrics])
const executionInfo = ref([...defaultExecutionInfo])
const currentPayload = ref(null)
const chartImageDataUrl = ref('')
const isRunCompleted = ref(false)

const metricMapping = [
  ['策略收益率', 'total_return', '当前策略累计收益率'],
  ['基准收益率', 'benchmark_return', '当前基准累计收益率'],
  ['策略年化收益率', 'annual_return', '策略年化投资回报率'],
  ['基准年化收益率', 'benchmark_annual_return', '基准年化投资回报率'],
  ['最大回撤', 'max_drawdown', '历史最大回撤幅度'],
  ['夏普比率', 'sharpe_ratio', '风险调整后收益指标'],
  ['Sortino 比率', 'sortino_ratio', '仅考虑下行波动的风险收益指标'],
  ['Alpha', 'alpha', '相对基准的超额收益能力'],
  ['Beta', 'beta', '相对基准的波动敏感度'],
  ['波动率', 'volatility', '策略收益年化波动率'],
  ['跟踪误差', 'tracking_error', '策略相对基准的偏离程度'],
  ['信息比率', 'information_ratio', '超额收益相对跟踪误差的比值'],
  ['下行风险', 'downside_risk', '仅统计下行波动的风险尺度'],
  ['胜率', 'win_rate', '正收益交易日占比'],
  ['期末净值', 'final_net_value', '回测结束时组合总资产'],
  ['成交笔数', 'trade_count', '回测期间总成交笔数'],
  ['买入笔数', 'buy_trade_count', '回测期间买入成交笔数'],
  ['卖出笔数', 'sell_trade_count', '回测期间卖出成交笔数'],
  ['换手率', 'turnover_ratio', '累计成交金额相对初始资金的比值'],
  ['总手续费', 'total_commission', '回测期间累计交易费用']
]

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

function buildMetricRows(metrics = {}) {
  const rows = metricMapping
    .filter(([, key]) => key in metrics)
    .map(([metricName, key, description]) => ({
      metricName,
      metricValue: metrics[key] ?? '--',
      description
    }))

  return rows.length ? rows : [...defaultMetrics]
}

function buildExecutionInfo(meta = {}) {
  const engineInfo = meta.engine_info || {}
  const artifacts = meta.artifacts || {}
  const rows = [
    ['请求编号', meta.request_id],
    ['上传文件', meta.uploaded_filename],
    ['请求引擎', meta.requested_engine],
    ['实际执行器', meta.executor_type],
    ['策略格式', meta.strategy_format],
    ['解析引擎', meta.resolved_engine],
    ['基准代码', meta.benchmark_symbol],
    ['熊市保护', meta.bear_protection_enabled ? '开启' : '关闭'],
    ['滑点', engineInfo.slippage_perc],
    ['手续费率', engineInfo.commission_rate],
    ['成交量限制比例', engineInfo.volume_limit_ratio],
    ['报告路径', artifacts.markdown_report],
    ['交易明细路径', artifacts.trade_excel]
  ]

  const mappedRows = rows
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([label, value]) => ({
      label,
      value: String(value)
    }))

  return mappedRows.length ? mappedRows : [...defaultExecutionInfo]
}

function resetToPendingState() {
  keyMetrics.value = [...defaultMetrics]
  executionInfo.value = [...defaultExecutionInfo]
  currentPayload.value = null
  chartImageDataUrl.value = ''
  isRunCompleted.value = false
}

function handleExecutionPending() {
  resetToPendingState()
}

function handleExecutionStarted(event) {
  const payload = event.detail || {}
  currentPayload.value = payload
  keyMetrics.value = buildMetricRows(payload.metrics || {})
  executionInfo.value = buildExecutionInfo(payload.execution_meta || {})
  isRunCompleted.value = true
}

function handleChartUpdated(event) {
  chartImageDataUrl.value = event.detail?.imageDataUrl || ''
}

const currentArtifacts = computed(() => currentPayload.value?.execution_meta?.artifacts || {})
const currentOperationReportPath = computed(() => {
  return currentArtifacts.value.markdown_report || currentArtifacts.value.trade_excel || ''
})

const canDownloadOperationReport = computed(() => {
  return isRunCompleted.value && Boolean(currentOperationReportPath.value)
})

const canDownloadResultReport = computed(() => {
  return isRunCompleted.value && Boolean(chartImageDataUrl.value)
})

function downloadOperationReport() {
  if (!canDownloadOperationReport.value) {
    ElMessage.warning('请先等待本次回测执行完成，再下载本次策略操作报告')
    return
  }

  const targetPath = encodeURIComponent(currentOperationReportPath.value)
  window.open(`/api/download-strategy-report/?path=${targetPath}`, '_blank')
}

function createTableMarkup(rows, headers) {
  const headerHtml = headers.map(header => `<th>${header}</th>`).join('')
  const bodyHtml = rows
    .map(row => `<tr>${row.map(cell => `<td>${cell ?? '--'}</td>`).join('')}</tr>`)
    .join('')

  return `
    <table>
      <thead><tr>${headerHtml}</tr></thead>
      <tbody>${bodyHtml}</tbody>
    </table>
  `
}

function requestFreshChartSnapshot() {
  return new Promise(resolve => {
    let settled = false
    let timeoutId = null

    const finish = value => {
      if (settled) return
      settled = true
      window.removeEventListener('strategy-chart-updated', handleUpdated)
      if (timeoutId) {
        clearTimeout(timeoutId)
      }
      resolve(value || chartImageDataUrl.value || '')
    }

    const handleUpdated = event => {
      const nextImage = event.detail?.imageDataUrl || ''
      chartImageDataUrl.value = nextImage
      finish(nextImage)
    }

    window.addEventListener('strategy-chart-updated', handleUpdated, { once: true })
    window.dispatchEvent(new CustomEvent('strategy-chart-request-snapshot'))
    timeoutId = setTimeout(() => {
      finish(chartImageDataUrl.value)
    }, 1200)
  })
}

async function downloadResultReport() {
  if (!canDownloadResultReport.value) {
    ElMessage.warning('请先等待本次回测执行完成，再下载本次策略回测结果报告')
    return
  }

  const freshChartImage = await requestFreshChartSnapshot()
  if (!freshChartImage) {
    ElMessage.warning('未能获取最新图表快照，请稍后重试')
    return
  }

  const meta = currentPayload.value?.execution_meta || {}
  const metricsRows = keyMetrics.value.map(item => [item.metricName, item.metricValue, item.description])
  const infoRows = executionInfo.value.map(item => [item.label, item.value])
  const metricsTable = createTableMarkup(metricsRows, ['指标名称', '指标值', '描述'])
  const infoTable = createTableMarkup(infoRows, ['项目', '内容'])
  const title = `${meta.uploaded_filename || '策略'}_策略回测结果报告`
  const generatedAt = new Date().toLocaleString('zh-CN', { hour12: false })

  const html = `
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <title>${title}</title>
  <style>
    body {
      margin: 0;
      padding: 32px;
      background: #111827;
      color: #e5f6ff;
      font-family: "Microsoft YaHei", "PingFang SC", sans-serif;
    }
    h1, h2 {
      margin: 0 0 16px;
      color: #7dd3fc;
    }
    .meta {
      margin-bottom: 24px;
      color: #c8e8f9;
      font-size: 14px;
    }
    .panel {
      margin-bottom: 28px;
      padding: 20px;
      border: 1px solid rgba(125, 211, 252, 0.25);
      border-radius: 12px;
      background: #182235;
      box-shadow: 0 0 24px rgba(0, 0, 0, 0.18);
    }
    .chart-image {
      width: 100%;
      border-radius: 10px;
      background: #1b2436;
      display: block;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 14px;
      overflow: hidden;
      border-radius: 10px;
    }
    th, td {
      border: 1px solid rgba(125, 211, 252, 0.18);
      padding: 10px 12px;
      text-align: left;
      vertical-align: top;
    }
    th {
      background: rgba(125, 211, 252, 0.18);
      color: #ffffff;
    }
    td {
      background: rgba(255, 255, 255, 0.03);
      color: #e5f6ff;
    }
  </style>
</head>
<body>
  <h1>${title}</h1>
  <div class="meta">生成时间：${generatedAt}</div>

  <div class="panel">
    <h2>完整回测执行图</h2>
    <img class="chart-image" src="${chartImageDataUrl.value}" alt="策略回测执行图" />
  </div>

  <div class="panel">
    <h2>关键指标表格</h2>
    ${metricsTable}
  </div>

  <div class="panel">
    <h2>本次执行信息</h2>
    ${infoTable}
  </div>
</body>
</html>`

  const blob = new Blob([html], { type: 'text/html;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${title}.html`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

onMounted(() => {
  window.addEventListener('strategy-execution-pending', handleExecutionPending)
  window.addEventListener('strategy-execution-started', handleExecutionStarted)
  window.addEventListener('strategy-chart-updated', handleChartUpdated)
})

onBeforeUnmount(() => {
  window.removeEventListener('strategy-execution-pending', handleExecutionPending)
  window.removeEventListener('strategy-execution-started', handleExecutionStarted)
  window.removeEventListener('strategy-chart-updated', handleChartUpdated)
})
</script>

<style scoped>
.strategy-result {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 8px;
  box-sizing: border-box;
}

.title {
  font-size: 13px;
  font-weight: bold;
  margin-bottom: 8px;
  color: #000000;
  text-shadow: 0 0 8px rgba(64, 224, 255, 0.6);
}

.result-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(64, 224, 255, 0.2);
  border-radius: 6px;
  overflow-y: auto;
}

.transaction-curve {
  min-height: 420px;
  flex-shrink: 0;
}

.report-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.key-metrics,
.execution-meta {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.result-table-wrapper {
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.download-button {
  background: linear-gradient(135deg, rgba(64, 224, 255, 0.32), rgba(30, 144, 255, 0.34)) !important;
  color: #ffffff !important;
}

.result-report-button {
  border-color: rgba(64, 224, 255, 0.3) !important;
}

:deep(.el-table),
:deep(.el-table__inner-wrapper),
:deep(.el-table__header-wrapper),
:deep(.el-table__body-wrapper),
:deep(.el-table tr),
:deep(.el-table td.el-table__cell) {
  background-color: #ffffff !important;
  color: #000000 !important;
}

:deep(.el-table th.el-table__cell) {
  background-color: rgba(64, 224, 255, 0.2) !important;
  color: #000000 !important;
  border-bottom: 1px solid rgba(64, 224, 255, 0.3) !important;
  font-weight: bold !important;
}

:deep(.el-table) {
  font-size: 11px;
  background: transparent !important;
  border-radius: 12px !important;
  overflow: hidden !important;
}

:deep(.el-table__inner-wrapper) {
  border-radius: 12px !important;
  overflow: hidden !important;
}

:deep(.el-table__inner-wrapper::before) {
  display: none !important;
}

:deep(.el-table::before),
:deep(.el-table--border::after),
:deep(.el-table--border .el-table__inner-wrapper::after) {
  display: none !important;
}

:deep(.el-table th.el-table__cell:first-child) {
  border-top-left-radius: 12px;
}

:deep(.el-table th.el-table__cell:last-child) {
  border-top-right-radius: 12px;
}

:deep(.el-table__body tr:last-child td.el-table__cell:first-child) {
  border-bottom-left-radius: 12px;
}

:deep(.el-table__body tr:last-child td.el-table__cell:last-child) {
  border-bottom-right-radius: 12px;
}
</style>
