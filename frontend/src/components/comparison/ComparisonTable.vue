<template>
  <div class="comparison-table" v-loading="loading && tableData.length === 0">
    <div class="table-header">
      <div class="table-title">{{ getTableTitle() }}</div>

      <div class="table-controls">
        <el-select
          v-model="selectedAccount"
          placeholder="请选择账户"
          size="small"
          @change="handleAccountChange"
          class="account-selector"
        >
          <el-option
            v-for="account in accounts"
            :key="account.account_id"
            :label="account.display_account_id || account.external_account_id || account.account_id"
            :value="account.account_id"
          />
        </el-select>

        <div class="table-actions">
          <el-button size="small" type="primary" @click="exportData">
            <i class="export-icon"></i>
            导出
          </el-button>
          <el-button size="small" @click="refreshData">
            <i class="refresh-icon"></i>
            刷新
          </el-button>
        </div>
      </div>
    </div>

    <div class="table-content">
      <el-table
        :data="tableData"
        :row-key="getRowKey"
        style="width: 100%"
        height="100%"
        :header-cell-style="headerCellStyle"
        :cell-style="cellStyle"
        stripe
        fit
      >
        <el-table-column
          v-for="column in tableColumns"
          :key="column.prop"
          :prop="column.prop"
          :label="column.label"
          :min-width="column.minWidth"
          :formatter="column.formatter"
        >
          <template #default="{ row, column: col }" v-if="column.prop === 'trend'">
            <div class="trend-cell">
              <div class="trend-indicator" :class="getTrendClass(row[col.property])"></div>
              <span>{{ row[col.property] }}</span>
            </div>
          </template>
          <template #default="{ row, column: col }" v-else-if="column.prop === 'risk'">
            <div class="risk-cell">
              <el-tag :type="getRiskTagType(row[col.property])" size="small">
                {{ row[col.property] }}
              </el-tag>
            </div>
          </template>
          <template #default="{ row, column: col }" v-else>
            <span :class="{ 'stock-code-text': column.prop === 'stock_code' }">
              {{ column.formatter ? column.formatter(row, col, row[col.property]) : row[col.property] }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script>
import ExcelJS from 'exceljs'
import { usePortfolioLiveStore } from '@/store/portfolioLive.js'

export default {
  name: 'ComparisonTable',
  props: {
    tableType: {
      type: String,
      default: 'asset'
    },
    exportTimestamp: {
      type: String,
      default: ''
    },
    timeGranularity: {
      type: String,
      default: 'DAILY'
    }
  },
  data() {
    return {
      assetTableData: [],
      timeTableData: [],
      regionTableData: [],
      loading: false,
      selectedAccount: '',
      portfolioLiveStore: usePortfolioLiveStore(),
      timeReturnCalculationMode: 'SNAPSHOT'
    }
  },
  computed: {
    accounts() {
      return this.portfolioLiveStore?.accounts || []
    },
    tableData() {
      if (this.tableType === 'asset') return this.assetTableData
      if (this.tableType === 'time') return this.timeTableData
      if (this.tableType === 'region') return this.regionTableData
      return []
    },
    tableColumns() {
      if (this.tableType === 'asset') {
        return [
          { prop: 'stock_code', label: '股票代码', minWidth: '100' },
          { prop: 'stock_name', label: '股票名称', minWidth: '120' },
          { prop: 'current_price', label: '当前价格', minWidth: '100', formatter: this.formatNumber },
          { prop: 'volume', label: '持仓数量', minWidth: '100', formatter: this.formatNumber },
          { prop: 'market_value', label: '股票市值', minWidth: '120', formatter: this.formatNumber },
          { prop: 'asset_ratio', label: '资产占比(%)', minWidth: '110', formatter: this.formatPercent },
          { prop: 'daily_return', label: '持仓盈亏(%)', minWidth: '110', formatter: this.formatPercent }
        ]
      }
      if (this.tableType === 'time') {
        return [
          { prop: 'year', label: '年份', minWidth: '100' },
          { prop: 'totalAssets', label: '总资产(元)', minWidth: '150', formatter: this.formatNumber },
          { prop: 'returnRate', label: this.timeReturnCalculationMode === 'SIMULATED' ? '模拟收益率(%)' : '快照收益率(%)', minWidth: '120', formatter: this.formatPercent },
          { prop: 'investmentRate', label: '投资占比(%)', minWidth: '120', formatter: this.formatPercent }
        ]
      }
      if (this.tableType === 'region') {
        return [
          { prop: 'region', label: '地区', minWidth: '120' },
          { prop: 'totalAssets', label: '总资产(元)', minWidth: '150', formatter: this.formatNumber },
          { prop: 'returnRate', label: '回报率(%)', minWidth: '100', formatter: this.formatPercent },
          { prop: 'investmentRate', label: '投资占比(%)', minWidth: '120', formatter: this.formatPercent }
        ]
      }
      return []
    }
  },
  watch: {
    tableType: {
      handler() {
        // 页面父组件统一按当前数据源请求，避免图表、表格重复调用历史行情接口。
      },
      immediate: true
    }
  },
  methods: {
    setData(data, accountId) {
      this.selectedAccount = accountId || this.selectedAccount
      this.renderTableData(data)
    },
    renderTableData(data) {
      if (!data) return
      const type = data.type || this.tableType
      if (type === 'asset') this.processAssetData(data)
      if (type === 'time') {
        this.timeReturnCalculationMode = data.return_calculation_mode || 'SNAPSHOT'
        this.timeTableData = data.yearly_data || []
      }
      if (type === 'region') this.regionTableData = data.region_data || []
    },
    processAssetData(account) {
      const positions = account.positions || []
      const totalMarketValue = positions.reduce((sum, p) => sum + Number(p.market_value || 0), 0)
      const processedData = positions.map(pos => {
        const currentPrice = Number(pos.current_price) || Number(pos.open_price) || Number(pos.lastPrice) || 0
        const costPrice = Number(pos.cost_price) || Number(pos.avg_price) || Number(pos.open_price) || 0
        const marketValue = Number(pos.market_value) || 0
        const assetRatio = totalMarketValue > 0 ? (marketValue / totalMarketValue * 100) : 0
        const profitLossRate = costPrice > 0 ? ((currentPrice - costPrice) / costPrice * 100) : 0
        return {
          stock_code: pos.stock_code,
          stock_name: pos.stock_name,
          current_price: currentPrice,
          volume: Number(pos.volume || 0),
          market_value: marketValue,
          asset_ratio: assetRatio,
          daily_return: profitLossRate
        }
      })
      processedData.sort((a, b) => Number(b.market_value) - Number(a.market_value))
      this.assetTableData = processedData
    },
    handleAccountChange() {
      this.$emit('account-changed', this.selectedAccount)
    },
    getTableTitle() {
      const titleMap = {
        asset: '资产对比数据',
        time: '时间段对比数据',
        region: '分市场对比数据'
      }
      return titleMap[this.tableType] || '对比数据'
    },
    formatNumber(row, column, cellValue) {
      if (cellValue === null || cellValue === undefined || cellValue === '') return '-'
      const num = Number(cellValue)
      return num.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 })
    },
    formatPercent(row, column, cellValue) {
      if (cellValue === null || cellValue === undefined || cellValue === '') return '-'
      const num = typeof cellValue === 'string' && cellValue.includes('%') ? parseFloat(cellValue) : Number(cellValue)
      return `${num.toFixed(2)}%`
    },
    getRowKey(row) {
      return row.stock_code || row.region || row.year || row.timePeriod || Math.random()
    },
    getTrendClass(trend) {
      const trendMap = { 牛市: 'trend-up', 熊市: 'trend-down', 震荡: 'trend-stable' }
      return trendMap[trend] || 'trend-stable'
    },
    getRiskTagType(risk) {
      const riskMap = { 低: 'success', 中: 'warning', 高: 'danger' }
      return riskMap[risk] || 'info'
    },
    headerCellStyle() {
      return {
        backgroundColor: 'rgba(64, 224, 255, 0.15)',
        color: '#40e0ff',
        fontWeight: 'bold',
        fontSize: '12px',
        padding: '10px 0',
        borderBottom: '1px solid rgba(64, 224, 255, 0.3)'
      }
    },
    cellStyle() {
      return {
        backgroundColor: 'transparent',
        color: '#ffffff',
        fontSize: '11px',
        padding: '8px 0',
        borderBottom: '1px solid rgba(255, 255, 255, 0.05)'
      }
    },
    async exportData() {
      const headers = this.tableColumns.map(column => column.label)
      const rows = this.tableData.map(row =>
        this.tableColumns.map(column => {
          const value = row[column.prop]
          return typeof column.formatter === 'function'
            ? column.formatter(row, { property: column.prop }, value)
            : (value ?? '')
        })
      )

      const worksheetData = [
        headers,
        ...rows,
        [],
        ['数据时间', this.exportTimestamp || '']
      ]

      const fileDate = new Date()
      const timestamp = fileDate.getFullYear().toString() + String(fileDate.getMonth() + 1).padStart(2, '0') + String(fileDate.getDate()).padStart(2, '0') + '_' + String(fileDate.getHours()).padStart(2, '0') + String(fileDate.getMinutes()).padStart(2, '0') + String(fileDate.getSeconds()).padStart(2, '0')
      const workbook = new ExcelJS.Workbook()
      const worksheet = workbook.addWorksheet(this.getTableTitle())
      worksheet.addRows(worksheetData)

      const headerRow = worksheet.getRow(1)
      headerRow.font = { bold: true }

      worksheet.columns = headers.map((header, index) => {
        const maxLength = Math.max(
          String(header || '').length,
          ...rows.map(row => String(row[index] ?? '').length),
          12
        )
        return {
          header,
          key: `col_${index}`,
          width: Math.min(maxLength + 2, 24)
        }
      })

      const buffer = await workbook.xlsx.writeBuffer()
      const blob = new Blob([buffer], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = this.getTableTitle() + '_' + timestamp + '.xlsx'
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
    },
    async refreshData() {
      this.$emit('refresh')
    }
  }
}
</script>

<style scoped>
.comparison-table {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.comparison-table :deep(.el-table) {
  background-color: transparent !important;
  color: #ffffff !important;
}

.comparison-table :deep(.el-table__body tr),
.comparison-table :deep(.el-table__header tr) {
  background-color: transparent !important;
}

.comparison-table :deep(.el-table__body td) {
  color: #ffffff !important;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05) !important;
}

.comparison-table :deep(.el-table__header th) {
  color: #40e0ff !important;
  background-color: rgba(64, 224, 255, 0.05) !important;
  border-bottom: 1px solid rgba(64, 224, 255, 0.2) !important;
}

.comparison-table :deep(.el-table--striped .el-table__row--striped td) {
  background-color: rgba(255, 255, 255, 0.02) !important;
}

.comparison-table :deep(.el-table__body tr:hover > td) {
  background-color: rgba(64, 224, 255, 0.1) !important;
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  margin-bottom: 10px;
}

.table-title {
  color: #ffffff;
  font-size: 14px;
  font-weight: bold;
}

.table-controls {
  display: flex;
  align-items: center;
  gap: 15px;
}

.account-selector {
  width: 140px;
}

.table-content {
  flex: 1;
  overflow: hidden;
}

.stock-code-text {
  color: #40e0ff;
  font-weight: 500;
}

.trend-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.trend-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.trend-up { background: #00ff88; }
.trend-down { background: #ff6b6b; }
.trend-stable { background: #feca57; }

:deep(.el-table) {
  background-color: transparent !important;
  --el-table-border-color: rgba(255, 255, 255, 0.1);
}

:deep(.el-table__inner-wrapper::before) {
  display: none;
}

:deep(.el-table tr) {
  background-color: transparent !important;
}

:deep(.el-table--striped .el-table__row--striped td.el-table__cell) {
  background-color: rgba(255, 255, 255, 0.02) !important;
}

:deep(.el-table__row:hover td.el-table__cell) {
  background-color: rgba(64, 224, 255, 0.05) !important;
}

:deep(.el-button) {
  background: rgba(64, 224, 255, 0.1) !important;
  border: 1px solid rgba(64, 224, 255, 0.3) !important;
  color: #40e0ff !important;
}

:deep(.el-button--primary) {
  background: rgba(64, 224, 255, 0.2) !important;
}

:deep(.el-select .el-input__wrapper) {
  background: rgba(12, 20, 38, 0.8) !important;
  box-shadow: 0 0 0 1px rgba(64, 224, 255, 0.2) inset !important;
}

:deep(.el-select .el-input__inner) {
  color: #ffffff !important;
}
</style>
