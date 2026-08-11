<template>
  <div>
    <!-- 总和展示 -->
    <div class="sum-result" style="text-align: left; font-size: 14px; padding: 8px 12px;">
      <span style="color: #409eff">委托金额总和：</span>
      <span style="color: #1989fa; font-weight: 600">{{ formattedTotal }}</span>
    </div>

    <!-- 输出委托表格 -->
    <div class="table-container">
      <el-table
        :data="showData"
        border
        style="width: 100%"
      >
        <el-table-column prop="stock_code" label="股票代码" min-width="90" align="center" />
        <el-table-column prop="stock_name" label="股票名称" min-width="150" align="center" />
        <el-table-column prop="volume" label="股票数量" min-width="90" align="center" />
        <el-table-column prop="entrust_price" label="委托价" min-width="100" align="center" />
        <el-table-column prop="market_value" label="股票市值" min-width="100" align="center" />
        <el-table-column prop="action" label="操作" min-width="100" align="center" />
      </el-table>
    </div>

    <!-- 外层容器：实现右对齐 -->
    <div class="button-bar">
      <!-- 下单按钮 -->
      <el-button
        :loading="submitting"
        :disabled="!hasValidOrders"
        style="color: #ffffff; background-color: #2c3e50; border-color: #2c3e50;"
        @click="order"
      >
        <i class="export-icon"></i>
        下单
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, watch, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAccountStore, useSelectedDataStore } from '@/store'
import { submitGuojinBuyOrder } from '@/api/accountApi.js'

/* 从 store 获取数据 */
const selectedDataStore = useSelectedDataStore()
const accountStore = useAccountStore()
const showData = computed(() => selectedDataStore.tableSelectedData || [])
const submitting = ref(false)/* 表示当前是否正在提交订单，防止用户重复点击下单按钮 */
/* 金额格式化 */
const formatMoney = (num) => {
  const n = Number(num) || 0
  return n.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}
/* 操作类型标准化 */
const normalizeAction = (action) => String(action || '').trim()
const isBuyAction = (action) => ['买入', 'buy', 'BUY'].includes(normalizeAction(action))
const isSellAction = (action) => ['卖出', 'sell', 'SELL'].includes(normalizeAction(action))

/* 标准化订单数据 */
const normalizedOrders = computed(() => {
  return (showData.value || []).map((item, index) => ({
    index,
    stock_code: String(item.stock_code || '').trim(),
    stock_name: String(item.stock_name || '').trim(),
    volume: Number(item.volume) || 0,
    entrust_price: Number(item.entrust_price) || 0,
    action: normalizeAction(item.action),
    market_value: Number(item.market_value) || 0
  }))
})
/* 有效订单过滤 */
const validOrders = computed(() => {
  return normalizedOrders.value.filter((item) => {
    return item.stock_code && item.volume > 0 && item.entrust_price > 0 && (isBuyAction(item.action) || isSellAction(item.action))
  })
})
/* 是否存在有效订单 --只要有一条有效订单，下单按钮才可用*/
const hasValidOrders = computed(() => validOrders.value.length > 0)
/* 计算委托总金额 */
const totalValue = computed(() => {
  if (!showData.value.length) return 0

  return showData.value.reduce((sum, item) => {
    const vol = Number(item.volume) || 0
    const price = Number(item.entrust_price) || 0
    const action = normalizeAction(item.action)

    if (isBuyAction(action)) {
      return sum + vol * price
    }
    return sum
  }, 0)
})

const formattedTotal = computed(() => formatMoney(totalValue.value))

/* 下单数据组装 */
const buildOrderPayload = () => {
  const selectedAccountId = String(accountStore.selectedAccountId || '').trim()
  return validOrders.value.map((item) => ({
    account_id: selectedAccountId || undefined,
    stock_code: item.stock_code,
    stock_name: item.stock_name,
    volume: item.volume,
    entrust_price: item.entrust_price,
    action: item.action,
    order_side: isBuyAction(item.action) ? 'buy' : 'sell'
  }))
}

const submitOrders = async (payload) => {
  const response = await submitGuojinBuyOrder({
    default_side: 'buy',
    orders: payload
  })
  return response
}

const order = async () => {
  if (!hasValidOrders.value) {
    ElMessage.warning('没有可下单的数据，请先确认表格中的买入/卖出委托')
    return
  }
  if (!String(accountStore.selectedAccountId || '').trim()) {
    ElMessage.warning('当前未获取到可用账户')
    return
  }

  const summary = validOrders.value.map((item) => `${item.stock_code} ${item.action} ${item.volume}股 @ ${item.entrust_price}`).join('\n')

  try {
    await ElMessageBox.confirm(
      `确认提交以下委托吗？\n\n${summary}`,
      '下单确认',
      {
        confirmButtonText: '确认下单',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: false,
        closeOnClickModal: false,
        closeOnPressEscape: true
      }
    )
  } catch {
    return
  }

/* 提交接口 */
  submitting.value = true
  try {
    await submitOrders(buildOrderPayload())
    window.dispatchEvent(new CustomEvent('qmt-order-submitted'))
    ElMessage.success('委托已提交，等待后端返回成交/委托结果')
  } catch (error) {
    ElMessage.error(`下单失败：${error?.message || '未知错误'}`)
  } finally {
    submitting.value = false
  }
}

watch(showData, (newVal) => {
  selectedDataStore.setSelectedData(newVal)
}, { deep: true })
</script>



<style scoped>
.table-actions {
  display: flex;
  gap: 8px;
}
.button-bar {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding: 10px 15px 0;
  border-radius: 4px;
}

.export-icon {
  display: inline-block;
  width: 16px;
  height: 16px;
  margin-right: 4px;
  vertical-align: middle;
  background: currentColor;
  mask-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z'/%3E%3C/svg%3E");
  mask-size: contain;
  mask-repeat: no-repeat;
  mask-position: center;
}

.left-aside {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px;
  box-sizing: border-box;
}

.title {
  font-size: 14px;
  font-weight: bold;
  margin-bottom: 10px;
  color: #000000;
  text-align: center;
  text-shadow: 0 0 10px rgba(64, 224, 255, 0.8);
}

.select-container {
  margin-bottom: 10px;
}

.table-container, .table-stock {
  width: 100%;
  display: inline-block;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.el-select {
  width: 100%;
}

.el-select .el-input__inner {
  border-radius: 4px;
  border: 1px solid rgba(64, 224, 255, 0.3);
  padding: 5px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.1);
  color: #ffffff;
}

.el-select .el-input__inner:focus {
  border-color: rgba(64, 224, 255, 0.6);
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.3);
}

.el-table {
  font-size: 12px;
  color: #000000;
  background: transparent;
}

.el-table th {
  padding: 4px 0;
  font-size: 12px;
  background-color: rgba(64, 224, 255, 0.2);
  color: #000000;
  border-bottom: 1px solid rgba(64, 224, 255, 0.3);
}

.el-table td {
  padding: 4px 0;
  font-size: 12px;
  color: #000000;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.even-row {
  background-color: rgba(255, 255, 255, 0.05);
}

.even-row td {
  background-color: rgba(255, 255, 255, 0.05);
}

.el-table__body-wrapper {
  overflow-x: auto;
}

/* 修复Element UI表格在深色主题下的样式 */
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

:deep(.el-table tr.even-row td) {
  background-color: rgba(255, 255, 255, 0.05) !important;
}

:deep(.el-table__border-left-patch) {
  background-color: rgba(64, 224, 255, 0.2) !important;
}

:deep(.el-table__border-bottom-patch) {
  background-color: rgba(64, 224, 255, 0.2) !important;
}

/* 选择框下拉选项样式 */
:deep(.el-select-dropdown) {
  background: rgba(26, 31, 58, 0.95) !important;
  backdrop-filter: blur(10px);
  border: 1px solid rgba(64, 224, 255, 0.3) !important;
}

:deep(.el-select-dropdown .el-option) {
  color: #ffffff !important;
  background: transparent !important;
}

:deep(.el-select-dropdown .el-option:hover) {
  background: rgba(64, 224, 255, 0.2) !important;
}

:deep(.el-select-dropdown .el-option.is-selected) {
  background: rgba(64, 224, 255, 0.3) !important;
  color: #ffffff !important;
}

/* 选择框输入框样式 */
:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.1) !important;
  border: 1px solid rgba(64, 224, 255, 0.3) !important;
  border-radius: 4px;
}

:deep(.el-input__wrapper:hover) {
  border-color: rgba(64, 224, 255, 0.5) !important;
}

:deep(.el-input__wrapper.is-focus) {
  border-color: rgba(64, 224, 255, 0.6) !important;
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.3) !important;
}

:deep(.el-input__inner) {
  color: #000000 !important;
  background: transparent !important;
}

:deep(.el-input__inner::placeholder) {
  color: rgba(0, 0, 0, 0.6) !important;
}

.table-container, .table-stock {
  width: 100%;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.05);
  backdrop-filter: blur(10px);
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}


/* 点击触发区域样式 */
.trigger-wrapper {
  width: 120px;
  height: 40px;
  line-height: 40px;
  transition: all 0.3s;

}

/* .trigger-wrapper:hover {
  border-color: #409eff;
  background-color: #f5f7fa;
} */

.display-text {
  color: #666;
}

/* 弹窗遮罩层 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

/* 弹窗内容区 */
.modal-content {
  width: 320px;
  background-color: #fff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
}

.modal-title {
  margin: 0 0 16px 0;
  font-size: 18px;
  color: #333;
  font-weight: 600;
}

/* 弹窗输入框 */
.modal-input {
  width: 100%;
  height: 40px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  padding: 0 10px;
  font-size: 16px;
  margin-bottom: 20px;
  box-sizing: border-box;
}

.modal-input:focus {
  outline: none;
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.2);
}

/* 弹窗按钮组 */
.modal-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.btn {
  padding: 8px 16px;
  border-radius: 4px;
  border: none;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.2s;
}

.cancel-btn {
  background-color: #f5f7fa;
  color: #666;
}

.cancel-btn:hover {
  background-color: #e5e6eb;
}

.confirm-btn {
  background-color: #409eff;
  color: #fff;
}

.confirm-btn:hover {
  background-color: #66b1ff;
}

当前值展示
.value-display {
  margin-top: 10px;
  color: #333;
}

</style>
