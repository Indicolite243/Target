<template>
  <div class="chart-section">
    <div v-if="hasExecutionData" class="series-controls">
      <el-checkbox-group v-model="visibleSeries" @change="renderCurrentChart">
        <el-checkbox label="benchmark">基准收益</el-checkbox>
        <el-checkbox label="strategy">策略收益</el-checkbox>
        <el-checkbox label="excess">超额收益</el-checkbox>
      </el-checkbox-group>
    </div>

    <div ref="chartContainer" class="chart-container"></div>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'

const SERIES_META = {
  benchmark: { label: '基准收益', color: '#2fb8e8' },
  strategy: { label: '策略收益', color: '#ff7a3d' },
  excess: { label: '超额收益', color: '#39d98a' }
}

const chartContainer = ref(null)
const visibleSeries = ref(['benchmark', 'strategy', 'excess'])
const hasExecutionData = ref(false)

let chartInstance = null
let latestPayload = null
let resizeObserver = null
let snapshotTimer = null

function emitChartSnapshot(imageDataUrl = '') {
  window.dispatchEvent(
    new CustomEvent('strategy-chart-updated', {
      detail: { imageDataUrl }
    })
  )
}

function captureChartSnapshot() {
  if (!chartInstance) return
  const currentOption = chartInstance.getOption()
  const snapshotOption = {
    dataZoom: (currentOption.dataZoom || []).map(item => ({
      ...item,
      show: item.type === 'slider' ? false : item.show
    })),
    grid: [{
      ...(currentOption.grid?.[0] || {}),
      right: 28,
      bottom: 48
    }]
  }

  chartInstance.setOption(snapshotOption, false)
  emitChartSnapshot(
    chartInstance.getDataURL({
      type: 'png',
      pixelRatio: 2,
      backgroundColor: '#1b2436'
    })
  )
  chartInstance.setOption(currentOption, true)
}

function requestChartSnapshot(delay = 220) {
  if (!chartInstance) return
  if (snapshotTimer) {
    clearTimeout(snapshotTimer)
    snapshotTimer = null
  }
  snapshotTimer = setTimeout(() => {
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        captureChartSnapshot()
      })
    })
  }, delay)
}

function handleSnapshotRequest() {
  requestChartSnapshot(60)
}

function createBaseOption(isStandby = true) {
  const standbyDates = [
    '03-25', '03-26', '03-27', '03-28', '03-29', '03-30', '03-31',
    '04-01', '04-02', '04-03', '04-04', '04-05', '04-06', '04-07',
    '04-08', '04-09', '04-10', '04-11', '04-12', '04-13', '04-14',
    '04-15', '04-16', '04-17', '04-18', '04-19', '04-20', '04-21',
    '04-22', '04-23'
  ]
  const standbyLine = new Array(standbyDates.length).fill(0)

  return {
    backgroundColor: 'transparent',
    color: [
      SERIES_META.benchmark.color,
      SERIES_META.strategy.color,
      SERIES_META.excess.color
    ],
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(10, 18, 34, 0.95)',
      borderColor: 'rgba(94, 224, 255, 0.25)',
      textStyle: { color: '#dffbff' },
      axisPointer: {
        type: 'line',
        lineStyle: {
          color: 'rgba(255,255,255,0.28)',
          width: 1
        }
      }
    },
    legend: {
      data: [
        SERIES_META.benchmark.label,
        SERIES_META.strategy.label,
        SERIES_META.excess.label
      ],
      bottom: 8,
      itemWidth: 14,
      itemHeight: 8,
      textStyle: {
        color: '#b9e7ff',
        fontSize: 12
      }
    },
    grid: {
      left: 58,
      right: 28,
      top: 46,
      bottom: 76,
      containLabel: true
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: standbyDates,
      axisLabel: {
        color: '#9fdcff',
        rotate: 35,
        fontSize: 10,
        hideOverlap: true
      },
      axisLine: {
        lineStyle: {
          color: 'rgba(94, 224, 255, 0.18)'
        }
      },
      axisTick: {
        show: false
      }
    },
    yAxis: {
      type: 'value',
      name: '收益率(%)',
      scale: true,
      min: isStandby ? -10 : null,
      max: isStandby ? 30 : null,
      interval: isStandby ? 10 : null,
      axisLabel: {
        formatter: '{value}%',
        color: '#9fdcff'
      },
      nameTextStyle: {
        color: '#b9e7ff',
        padding: [0, 0, 10, 0]
      },
      splitLine: {
        lineStyle: {
          color: 'rgba(94, 224, 255, 0.08)'
        }
      },
      axisLine: {
        lineStyle: {
          color: 'rgba(94, 224, 255, 0.18)'
        }
      }
    },
    dataZoom: [
      {
        type: 'slider',
        xAxisIndex: 0,
        bottom: 22,
        height: 18,
        borderColor: 'rgba(94, 224, 255, 0.18)',
        fillerColor: 'rgba(94, 224, 255, 0.16)',
        backgroundColor: 'rgba(255, 255, 255, 0.06)',
        textStyle: {
          color: '#86dfff'
        }
      },
      {
        type: 'slider',
        yAxisIndex: 0,
        right: 8,
        top: 82,
        bottom: 84,
        width: 16,
        borderColor: 'rgba(94, 224, 255, 0.18)',
        fillerColor: 'rgba(94, 224, 255, 0.16)',
        backgroundColor: 'rgba(255, 255, 255, 0.06)',
        textStyle: {
          color: '#86dfff'
        }
      },
      {
        type: 'inside',
        xAxisIndex: 0,
        filterMode: 'none',
        zoomOnMouseWheel: 'ctrl',
        moveOnMouseWheel: true,
        preventDefaultMouseMove: false
      },
      {
        type: 'inside',
        yAxisIndex: 0,
        filterMode: 'none',
        zoomOnMouseWheel: 'ctrl',
        moveOnMouseWheel: false,
        preventDefaultMouseMove: false
      }
    ],
    series: [
      {
        name: SERIES_META.benchmark.label,
        type: 'line',
        smooth: false,
        symbol: 'none',
        lineStyle: {
          width: 2.6,
          color: SERIES_META.benchmark.color,
          opacity: isStandby ? 0.72 : 1
        },
        data: standbyLine
      },
      {
        name: SERIES_META.strategy.label,
        type: 'line',
        smooth: false,
        symbol: 'none',
        lineStyle: {
          width: 2.6,
          color: SERIES_META.strategy.color,
          opacity: isStandby ? 0.48 : 1
        },
        data: standbyLine
      },
      {
        name: SERIES_META.excess.label,
        type: 'line',
        smooth: false,
        symbol: 'none',
        lineStyle: {
          width: 2.6,
          color: SERIES_META.excess.color,
          opacity: isStandby ? 0.48 : 1
        },
        data: standbyLine
      }
    ]
  }
}

function ensureChart() {
  if (!chartContainer.value) return
  if (!chartInstance) {
    chartInstance = echarts.init(chartContainer.value)
  }
}

function setEmptyChart() {
  ensureChart()
  if (!chartInstance) return
  hasExecutionData.value = false
  latestPayload = null
  visibleSeries.value = ['benchmark', 'strategy', 'excess']
  chartInstance.setOption(createBaseOption(true), true)
  emitChartSnapshot('')
}

function normalizeSeries(data) {
  if (!Array.isArray(data)) return []
  return data.map(item => {
    if (item === null || item === undefined || item === '') return null
    const numeric = Number(item)
    return Number.isFinite(numeric) ? numeric : null
  })
}

function renderCurrentChart() {
  ensureChart()
  if (!chartInstance || !latestPayload) return

  const dates = Array.isArray(latestPayload?.dates) ? latestPayload.dates : []
  const strategy = normalizeSeries(latestPayload?.strategy)
  const benchmark = normalizeSeries(latestPayload?.benchmark)
  const excess = normalizeSeries(latestPayload?.excess)

  if (!dates.length || (!strategy.length && !benchmark.length && !excess.length)) {
    setEmptyChart()
    return
  }

  const option = createBaseOption(false)
  option.xAxis.data = dates
  option.series[0].data = visibleSeries.value.includes('benchmark') ? benchmark : []
  option.series[1].data = visibleSeries.value.includes('strategy') ? strategy : []
  option.series[2].data = visibleSeries.value.includes('excess') ? excess : []

  option.legend.selected = {
    [SERIES_META.benchmark.label]: visibleSeries.value.includes('benchmark'),
    [SERIES_META.strategy.label]: visibleSeries.value.includes('strategy'),
    [SERIES_META.excess.label]: visibleSeries.value.includes('excess')
  }

  chartInstance.setOption(option, true)
  requestChartSnapshot()
}

function renderChart(payload) {
  const dates = Array.isArray(payload?.dates) ? payload.dates : []
  const strategy = normalizeSeries(payload?.strategy)
  const benchmark = normalizeSeries(payload?.benchmark)
  const excess = normalizeSeries(payload?.excess)

  if (!dates.length || (!strategy.length && !benchmark.length && !excess.length)) {
    setEmptyChart()
    return
  }

  latestPayload = payload
  hasExecutionData.value = true
  if (!visibleSeries.value.length) {
    visibleSeries.value = ['benchmark', 'strategy', 'excess']
  }
  renderCurrentChart()
}

function handleResize() {
  if (chartInstance) {
    chartInstance.resize()
    requestChartSnapshot(260)
  }
}

function handleExecutionStarted(event) {
  renderChart(event.detail || {})
}

function handleExecutionPending() {
  setEmptyChart()
}

onMounted(async () => {
  await nextTick()
  setEmptyChart()
  window.addEventListener('resize', handleResize)
  window.addEventListener('strategy-execution-started', handleExecutionStarted)
  window.addEventListener('strategy-execution-pending', handleExecutionPending)
  window.addEventListener('strategy-chart-request-snapshot', handleSnapshotRequest)
  window.addEventListener('strategy-chart-request-snapshot', handleSnapshotRequest)
  if (typeof ResizeObserver !== 'undefined' && chartContainer.value) {
    resizeObserver = new ResizeObserver(() => {
      handleResize()
    })
    resizeObserver.observe(chartContainer.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('strategy-execution-started', handleExecutionStarted)
  window.removeEventListener('strategy-execution-pending', handleExecutionPending)
  window.removeEventListener('strategy-chart-request-snapshot', handleSnapshotRequest)
  if (snapshotTimer) {
    clearTimeout(snapshotTimer)
    snapshotTimer = null
  }
  window.removeEventListener('strategy-chart-request-snapshot', handleSnapshotRequest)
  if (snapshotTimer) {
    clearTimeout(snapshotTimer)
    snapshotTimer = null
  }
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (chartInstance) {
    chartInstance.dispose()
    chartInstance = null
  }
})
</script>

<style scoped>
.chart-section {
  width: 100%;
  height: 100%;
}

.series-controls {
  margin-bottom: 10px;
  display: flex;
  justify-content: flex-end;
}

.series-controls :deep(.el-checkbox-group) {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}

.series-controls :deep(.el-checkbox) {
  color: #d8f7ff;
}

.series-controls :deep(.el-checkbox__label) {
  color: #d8f7ff;
  font-size: 12px;
}

.series-controls :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
  color: #ffffff;
}

.chart-container {
  width: 100%;
  height: 100%;
  min-height: 420px;
}
</style>

