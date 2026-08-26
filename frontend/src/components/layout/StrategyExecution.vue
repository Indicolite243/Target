<template>
  <div class="strategy-execution">
    <div class="strategy-display-module">
      <div class="title">当前执行策略</div>

      <div class="select-container">
        <el-select
          v-model="selectedStrategy"
          placeholder="请选择策略"
          @change="handleStrategyChange"
        >
          <el-option
            v-for="strategy in strategies"
            :key="strategy.id"
            :label="strategy.name"
            :value="strategy"
          />
        </el-select>

        <el-button type="primary" size="small" @click="openUploadDialog">
          导入策略文件
        </el-button>
      </div>

      <div class="strategy-content-card">
        <transition name="execution-progress-fade">
          <div v-if="progressVisible" class="execution-progress-panel" :class="progressState">
            <div class="execution-progress-meta">
              <div class="execution-progress-meta-left">
                <span class="execution-progress-text">{{ progressLabel }}</span>
                <span class="execution-progress-value">{{ progressValue }}%</span>
              </div>
              <button
                v-if="progressState === 'running'"
                type="button"
                class="execution-progress-cancel"
                :disabled="cancelling"
                @click="cancelExecution"
              >
                {{ cancelling ? '正在取消...' : '取消执行' }}
              </button>
            </div>
            <div class="execution-progress-track">
              <div
                class="execution-progress-fill"
                :class="progressState"
                :style="{ width: `${progressValue}%` }"
              ></div>
            </div>
          </div>
        </transition>

        <div class="strategy-description">
          <div class="title">策略简介</div>
          <div class="description-text">{{ selectedStrategy.description }}</div>
        </div>

        <div class="strategy-parameters">
          <div class="title">策略参数设置</div>
          <div class="parameters-table-wrapper">
            <el-table
              :data="selectedStrategy.parameters"
              style="width: 100%"
              :header-cell-style="headerStyle"
            >
              <el-table-column prop="paramKey" label="参数名称" />
              <el-table-column prop="paramValue" label="参数值" />
              <el-table-column prop="description" label="描述" />
            </el-table>
          </div>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="uploadDialogVisible"
      title="导入并运行策略文件"
      width="620px"
      :close-on-click-modal="false"
      append-to-body
    >
      <div class="upload-content">
        <div class="form-block">
          <div class="form-label">回测引擎</div>
          <el-select v-model="engineType" style="width: 100%">
            <el-option label="自动识别" value="auto" />
            <el-option label="MindGo / SuperMind" value="mindgo" />
            <el-option label="Backtrader" value="backtrader" />
          </el-select>
          <div class="form-tip">
            选择“自动识别”时，系统会根据上传的 .py 文件内容自动判断回测引擎。
          </div>
        </div>

        <div class="form-block">
          <div class="form-label">自定义回测时间范围</div>
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          />
        </div>

        <div class="form-block">
          <div class="form-label">策略文件</div>
          <el-upload
            ref="strategyUploadRef"
            drag
            action="#"
            :auto-upload="false"
            :on-change="handleStrategyFileChange"
            :limit="1"
            :on-exceed="handleStrategyExceed"
            accept=".py"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">
              将 <strong>.py</strong> 策略文件拖到此处，或 <em>点击上传</em>
            </div>
          </el-upload>
        </div>

        <div class="form-block">
          <div class="form-label">行情文件（可选）</div>
          <el-upload
            ref="marketUploadRef"
            action="#"
            :auto-upload="false"
            :on-change="handleMarketFileChange"
            :on-remove="handleMarketFileRemove"
            :file-list="marketFileList"
            multiple
            accept=".xlsx"
          >
            <el-button type="default">上传行情 .xlsx</el-button>
          </el-upload>
          <div class="form-tip">
            当策略依赖新的本地行情文件时，可以在这里一并上传对应的 .xlsx。
          </div>
        </div>

        <div v-if="selectedStrategyFile" class="file-preview">
          <div class="file-preview-main">
            <el-icon><Document /></el-icon>
            <span>{{ selectedStrategyFile.name }}</span>
            <el-tag type="success" size="small">已选择</el-tag>
          </div>
          <div class="engine-detect">
            自动识别结果：
            <strong>{{ detectedEngineLabel }}</strong>
          </div>
        </div>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="uploadDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="isExecuting" @click="confirmUpload">
            {{ isExecuting ? '正在运行回测...' : '确认并运行回测' }}
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { httpClient } from '@/utils/httpClient'
import { Document, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const strategyUploadRef = ref(null)
const marketUploadRef = ref(null)

const strategies = ref([
  {
    id: 1,
    name: '量化选股策略',
    description: '基于股票数量、选股范围、最大仓位和个股仓位等基本面指标的价值投资策略。',
    parameters: [
      { paramKey: '股票数量', paramValue: '10', description: '最终持有的股票数量' },
      { paramKey: '最大仓位', paramValue: '80%', description: '组合总持仓上限' }
    ]
  },
  {
    id: 2,
    name: 'ETF轮动策略',
    description: '基于价格趋势和均线信号的 ETF 轮动策略，适合与 SuperMind 同类策略结果进行对照。',
    parameters: [
      { paramKey: '调仓周期', paramValue: '60天', description: '策略重新平衡的时间间隔' },
      { paramKey: '目标权重', paramValue: '20%', description: '每只 ETF 的目标持仓比例' },
      { paramKey: '手续费率', paramValue: '0.02%', description: '每笔交易的佣金费率' },
      { paramKey: '滑点', paramValue: '0.1%', description: '交易执行的价格滑点' }
    ]
  },
  {
    id: 3,
    name: '自定义策略',
    description: '上传你自己的 .py 策略文件，系统会根据脚本格式自动选择兼容执行器。',
    parameters: [
      { paramKey: '策略文件', paramValue: '用户上传', description: '支持 MindGo / SuperMind 与 Backtrader 脚本' },
      { paramKey: '时间范围', paramValue: '自定义', description: '通过日期选择器设置回测区间' }
    ]
  }
])

const selectedStrategy = ref(strategies.value[1])
const uploadDialogVisible = ref(false)
const selectedStrategyFile = ref(null)
const selectedStrategyText = ref('')
const marketFiles = ref([])
const marketFileList = ref([])
const isExecuting = ref(false)
const engineType = ref('auto')
const dateRange = ref(['2020-01-01', '2025-01-01'])
const progressVisible = ref(false)
const progressValue = ref(0)
const progressLabel = ref('')
const progressState = ref('running')
const cancelling = ref(false)
let progressTimer = null
let hideTimer = null
let progressStageTimer = null
let activeRequestController = null
let activeTaskId = ''

const detectedEngine = computed(() => {
  if (!selectedStrategyText.value) return 'unknown'
  const lowered = selectedStrategyText.value.toLowerCase()
  if (
    lowered.includes('mindgo_api') ||
    lowered.includes('from mindgo_api import *') ||
    lowered.includes('import mindgo_api')
  ) {
    return 'mindgo'
  }
  return 'backtrader'
})

const detectedEngineLabel = computed(() => {
  if (detectedEngine.value === 'mindgo') return 'MindGo / SuperMind'
  if (detectedEngine.value === 'backtrader') return 'Backtrader'
  return '暂未识别'
})

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

function clearProgressTimers() {
  if (progressTimer) {
    clearInterval(progressTimer)
    progressTimer = null
  }
  if (progressStageTimer) {
    clearTimeout(progressStageTimer)
    progressStageTimer = null
  }
  if (hideTimer) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
}

function scheduleProgressStages(stages, index = 0) {
  if (index >= stages.length || progressState.value !== 'running') {
    return
  }

  const stage = stages[index]
  progressStageTimer = setTimeout(() => {
    if (progressState.value !== 'running') {
      return
    }

    progressValue.value = Math.max(progressValue.value, stage.value)
    progressLabel.value = stage.label
    scheduleProgressStages(stages, index + 1)
  }, stage.delay)
}

function startExecutionProgress() {
  clearProgressTimers()
  progressVisible.value = true
  progressValue.value = 8
  progressLabel.value = '正在上传策略文件...'
  progressState.value = 'running'

  progressTimer = setInterval(() => {
    if (progressValue.value < 84) {
      progressValue.value += 1
    }
  }, 900)

  scheduleProgressStages([
    { delay: 500, value: 18, label: '正在解析策略文件...' },
    { delay: 800, value: 34, label: '正在校验回测参数...' },
    { delay: 1100, value: 52, label: '正在加载行情与账户环境...' },
    { delay: 1400, value: 68, label: '正在执行策略回测...' },
    { delay: 1800, value: 82, label: '正在生成图表与指标...' },
    { delay: 2200, value: 90, label: '正在整理报告结果...' }
  ])
}

function finishExecutionProgress(state, label) {
  clearProgressTimers()
  progressVisible.value = true
  progressValue.value = 100
  progressState.value = state
  progressLabel.value = label || (state === 'success' ? '策略回测执行完成' : '策略回测执行失败')
  activeRequestController = null

  hideTimer = setTimeout(() => {
    progressVisible.value = false
    progressValue.value = 0
    progressLabel.value = ''
    progressState.value = 'running'
  }, state === 'success' ? 700 : 1000)
}

async function cancelExecution() {
  if (cancelling.value) return
  cancelling.value = true
  try {
    if (!activeTaskId) {
      activeRequestController?.abort()
      finishExecutionProgress('error', '已停止本页等待')
      ElMessage.info('已停止本页等待；若请求已受理，任务可能仍在后台执行')
      return
    }

    try {
      await httpClient.post(`/quant-tasks/${activeTaskId}/cancel`, {}, { timeout: 8000 })
      activeRequestController?.abort()
      activeTaskId = ''
      finishExecutionProgress('error', '已取消排队中的回测任务')
      ElMessage.success('已取消排队中的回测任务')
    } catch (error) {
      if (error.status === 409) {
        // The backend intentionally allows cancellation only before execution.
        // Do not claim a running calculation was cancelled; detach this page
        // from polling and leave its result available in the task query API.
        activeRequestController?.abort()
        activeTaskId = ''
        finishExecutionProgress('error', '任务已开始执行，已停止本页等待')
        ElMessage.info('任务已开始执行，后台会继续运行，可稍后查看结果')
        return
      }
      throw error
    }
  } catch (error) {
    ElMessage.error(error?.message || '取消请求失败，任务可能仍在后台执行')
  } finally {
    cancelling.value = false
  }
}

function handleStrategyChange() {}

function resetUploadState() {
  selectedStrategyFile.value = null
  selectedStrategyText.value = ''
  marketFiles.value = []
  marketFileList.value = []
  engineType.value = 'auto'
  dateRange.value = ['2020-01-01', '2025-01-01']
  strategyUploadRef.value?.clearFiles()
  marketUploadRef.value?.clearFiles()
}

function openUploadDialog() {
  resetUploadState()
  uploadDialogVisible.value = true
}

async function handleStrategyFileChange(file) {
  selectedStrategyFile.value = file.raw
  if (file?.raw?.text) {
    selectedStrategyText.value = await file.raw.text()
  } else {
    selectedStrategyText.value = ''
  }
}

function handleStrategyExceed(files) {
  strategyUploadRef.value?.clearFiles()
  const nextFile = files[0]
  strategyUploadRef.value?.handleStart(nextFile)
  selectedStrategyFile.value = nextFile
}

function handleMarketFileChange(file, uploadFiles) {
  marketFiles.value = uploadFiles.map(item => item.raw).filter(Boolean)
  marketFileList.value = uploadFiles
}

function handleMarketFileRemove(file, uploadFiles) {
  marketFiles.value = uploadFiles.map(item => item.raw).filter(Boolean)
  marketFileList.value = uploadFiles
}

async function confirmUpload() {
  if (!selectedStrategyFile.value) {
    ElMessage.warning('请先选择一个 .py 策略文件')
    return
  }

  if (!dateRange.value || dateRange.value.length !== 2) {
    ElMessage.warning('请选择回测时间范围')
    return
  }

  const [startDate, endDate] = dateRange.value
  if (new Date(startDate) >= new Date(endDate)) {
    ElMessage.error('开始日期必须早于结束日期')
    return
  }

  const formData = new FormData()
  formData.append('file', selectedStrategyFile.value)
  formData.append('start_date', startDate)
  formData.append('end_date', endDate)
  formData.append('engine_type', engineType.value)
  formData.append('enable_bear_protection', 'false')

  marketFiles.value.forEach(file => {
    formData.append('market_files', file)
  })

  isExecuting.value = true
  startExecutionProgress()
  uploadDialogVisible.value = false
  window.dispatchEvent(new CustomEvent('strategy-execution-pending'))
  try {
    activeRequestController = new AbortController()
    const response = await httpClient.post('/backtests/run', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 20000,
      signal: activeRequestController.signal
    })

    const task = response.data?.data || {}
    if (!task.id) {
      throw taskFailure('回测任务受理失败，未返回任务ID')
    }
    activeTaskId = String(task.id)
    progressLabel.value = '任务已受理，正在后台执行回测...'
    const result = await waitForBacktestTask(task.id, activeRequestController.signal)
    if (result.status !== 'success') {
      finishExecutionProgress('error')
      window.dispatchEvent(new CustomEvent('strategy-execution-failed'))
      ElMessage.error(result.message || '策略执行失败')
      return
    }

    resetUploadState()
    ElMessage.success('回测执行完成')
    finishExecutionProgress('success')
    window.dispatchEvent(new CustomEvent('strategy-execution-started', { detail: result.data }))
  } catch (error) {
    if (error.code === 'ERR_CANCELED' || error.name === 'CanceledError') {
      return
    }

    const payload = error.response?.data

    if (payload?.error_code === 'missing_market_data') {
      finishExecutionProgress('error')
      window.dispatchEvent(new CustomEvent('strategy-execution-failed'))
      const missingList = (payload.missing_market_files || []).join('<br/>')
      await ElMessageBox.alert(
        `当前策略缺少以下行情文件：<br/><br/>${missingList}<br/><br/>请重新上传策略文件，并补充这些 .xlsx 行情文件。`,
        '缺少行情文件',
        {
          dangerouslyUseHTMLString: true,
          confirmButtonText: '知道了',
          type: 'warning'
        }
      )
      return
    }

    if (payload?.detail) {
      finishExecutionProgress('error')
      window.dispatchEvent(new CustomEvent('strategy-execution-failed'))
      await ElMessageBox.alert(
        `<strong>${payload.message || '策略执行失败'}</strong><br/><br/><pre style="font-size:11px;max-height:220px;overflow:auto;background:#f5f5f5;padding:8px;">${payload.detail}</pre>`,
        '策略执行错误',
        {
          dangerouslyUseHTMLString: true,
          confirmButtonText: '关闭',
          type: 'error'
        }
      )
      return
    }

    if (error.code === 'ECONNABORTED') {
      finishExecutionProgress('error')
      window.dispatchEvent(new CustomEvent('strategy-execution-failed'))
      ElMessage.error('回测请求超时，请稍后查看后端日志')
      return
    }

    finishExecutionProgress('error')
    window.dispatchEvent(new CustomEvent('strategy-execution-failed'))
    ElMessage.error(payload?.message || error.message || '无法连接到后端服务，请确认服务已启动')
  } finally {
    isExecuting.value = false
    activeRequestController = null
    activeTaskId = ''
  }
}

async function waitForBacktestTask(taskId, signal) {
  const deadline = Date.now() + 330000
  while (Date.now() < deadline) {
    await delay(1000)
    if (signal?.aborted) {
      const error = taskFailure('已停止等待回测任务')
      error.code = 'ERR_CANCELED'
      throw error
    }
    const response = await httpClient.get(`/quant-tasks/${taskId}`, { signal, timeout: 8000 })
    const task = response.data?.data || {}
    if (task.progress !== undefined) {
      progressValue.value = Math.max(progressValue.value, Math.min(95, Number(task.progress) || 0))
    }
    if (task.stage) {
      progressLabel.value = task.stage
    }
    if (task.status === 'SUCCEEDED') {
      const resultResponse = await httpClient.get(`/quant-tasks/${taskId}/result`, { signal, timeout: 12000 })
      return resultResponse.data?.data || {}
    }
    if (task.status === 'FAILED' || task.status === 'CANCELLED') {
      throw taskFailure(task.errorMessage || '回测任务执行失败')
    }
  }
  throw taskFailure('回测任务等待超时，可稍后通过任务ID查询结果')
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function taskFailure(message) {
  const error = new Error(message)
  error.code = 'BACKTEST_TASK_FAILED'
  return error
}

onBeforeUnmount(() => {
  clearProgressTimers()
})
</script>

<style scoped>
.strategy-execution {
  width: 100%;
  height: 100%;
  max-height: min(72vh, 760px);
  padding: 6px;
  box-sizing: border-box;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-gutter: stable;
}

.strategy-execution::-webkit-scrollbar { width: 8px; }
.strategy-execution::-webkit-scrollbar-thumb {
  background: rgba(64, 224, 255, 0.48);
  border-radius: 999px;
}

.strategy-display-module {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px;
  border: 1px solid rgba(64, 224, 255, 0.3);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
}

.title {
  font-size: 13px;
  font-weight: bold;
  margin-bottom: 8px;
  color: #000000;
  text-shadow: 0 0 8px rgba(64, 224, 255, 0.6);
}

.select-container {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.select-container :deep(.el-select) {
  flex: 1;
}

.strategy-content-card {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: auto;
  padding: 12px;
  background: rgba(25, 39, 67, 0.65);
  border: 1px solid rgba(64, 224, 255, 0.18);
  border-radius: 8px;
}

.execution-progress-panel {
  margin-bottom: 14px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(10, 18, 34, 0.72);
  border: 1px solid rgba(64, 224, 255, 0.16);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.execution-progress-panel.success {
  border-color: rgba(76, 255, 155, 0.22);
}

.execution-progress-panel.error {
  border-color: rgba(255, 106, 125, 0.22);
}

.execution-progress-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.execution-progress-meta-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.execution-progress-text {
  font-size: 13px;
  color: rgba(235, 248, 255, 0.92);
  font-weight: 600;
}

.execution-progress-value {
  font-size: 13px;
  color: #8eefff;
  font-weight: 700;
  min-width: 44px;
  text-align: right;
}

.execution-progress-cancel {
  border: 1px solid rgba(255, 122, 138, 0.45);
  background: linear-gradient(135deg, rgba(140, 26, 48, 0.92), rgba(186, 46, 64, 0.9));
  color: #ffe7eb;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 0 14px rgba(255, 106, 125, 0.18);
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease;
}

.execution-progress-cancel:hover {
  transform: translateY(-1px);
  border-color: rgba(255, 145, 157, 0.7);
  box-shadow: 0 0 18px rgba(255, 106, 125, 0.26);
}

.execution-progress-cancel:disabled {
  cursor: wait;
  opacity: 0.62;
  transform: none;
}

.execution-progress-track {
  width: 100%;
  height: 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.execution-progress-fill {
  height: 100%;
  width: 0;
  border-radius: 999px;
  background: linear-gradient(90deg, #39d6ff 0%, #4f9dff 55%, #99ebff 100%);
  box-shadow: 0 0 12px rgba(64, 224, 255, 0.55);
  transition: width 0.22s ease-out;
}

.execution-progress-fill.success {
  background: linear-gradient(90deg, #59ff9a 0%, #5bf2bf 55%, #b8ffe0 100%);
  box-shadow: 0 0 12px rgba(76, 255, 155, 0.55);
}

.execution-progress-fill.error {
  background: linear-gradient(90deg, #ff6a7d 0%, #ff8b67 55%, #ffc0a6 100%);
  box-shadow: 0 0 12px rgba(255, 106, 125, 0.55);
}

.strategy-description {
  margin-bottom: 12px;
  flex-shrink: 0;
}

.description-text {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.86);
  line-height: 1.6;
}

.strategy-parameters {
  display: flex;
  flex-direction: column;
  flex: 0 0 auto;
  min-height: 0;
}

.parameters-table-wrapper {
  flex: 0 0 auto;
  min-height: 0;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.upload-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.form-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form-label {
  font-size: 13px;
  font-weight: 600;
  color: #1b273f;
}

.form-tip {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}

.file-preview {
  padding: 10px 12px;
  background: rgba(64, 224, 255, 0.08);
  border: 1px solid rgba(64, 224, 255, 0.3);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: #333;
}

.file-preview-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.engine-detect {
  color: #1f3b57;
}

.execution-progress-fade-enter-active,
.execution-progress-fade-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.execution-progress-fade-enter-from,
.execution-progress-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

:deep(.el-table),
:deep(.el-table__inner-wrapper),
:deep(.el-table__header-wrapper),
:deep(.el-table__body-wrapper),
:deep(.el-table tr),
:deep(.el-table th.el-table__cell),
:deep(.el-table td.el-table__cell) {
  background-color: #ffffff !important;
  color: #000000 !important;
}

:deep(.el-table th.el-table__cell) {
  background-color: rgba(64, 224, 255, 0.2) !important;
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
