<template>
  <div class="module-card">
    <div
      class="menu-item"
      @click="openStrategyFile"
    >
      <div class="menu-icon region-icon"></div>
      <span>选择策略Excel文件</span>
    </div>
    <input
      type="file"
      ref="strategyFileInput"
      accept=".xlsx,.xls,.csv"
      style="display: none;"
      @change="handleStrategyFileRead"
    >

    <div class="table-container">
      <el-table
        :data="tableData"
        row-key="index"
        border
        stripe
        :height="tableHeight"
        style="width: 100%;"
        ref="tableRef"
        @selection-change="handleSelectionChange"
        :select-on-indeterminate="false"
      >
        <el-table-column
          type="index"
          :index="(index) => index + 1"
          label="序号"
          width="50"
          align="center"
        />
        <el-table-column
          prop="stock_code"
          label="股票代码"
          min-width="110"
          align="center"
        />
        <el-table-column
          prop="stock_name"
          label="股票名称"
          min-width="150"
          align="center"
        />
        <el-table-column
          prop="volume"
          label="股票数量"
          min-width="100"
          align="center"
        />
        <el-table-column
          prop="entrust_price"
          label="委托价"
          min-width="100"
          align="center"
        />
        <el-table-column
          prop="market_value"
          label="股票市值"
          min-width="120"
          align="center"
        />
        <el-table-column
          prop="action"
          label="操作"
          min-width="100"
          align="center"
        />
        <el-table-column
          type="selection"
          label="勾选"
          width="55"
          align="center"
          :reserve-selection="true"
        />
      </el-table>
    </div>

    <el-button
      type="text"
      color="#409eff"
      @click="clearTable"
      v-if="tableData.length"
      style="margin-left: 10px; margin-bottom: 20px;"
    >
      清空数据
    </el-button>
    <el-button
      type="text"
      color="#409eff"
      @click="goToOutputModule"
      v-if="tableData.length"
      :disabled="selectedData.length === 0"
      style="margin-left: 10px; margin-bottom: 20px;"
    >
      获取勾选数据
    </el-button>

    <div v-if="!tableData.length && !isLoading" style="text-align: center; padding: 5px; color: #999;">
      暂无策略数据，请点击按钮选择合法的Excel或CSV文件
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import * as XLSX from 'xlsx';
import { useSelectedDataStore } from '@/store';

const selectedData = ref([]);
const strategyFileInput = ref(null);
const isLoading = ref(false);
const tableData = ref([]);
const rowHeight = 40;
const tableHeight = computed(() => `${rowHeight * 6 + 52}px`);

const STOCK_NAME_MAP = {
  '510300': '沪深300ETF',
  '510500': '中证500ETF',
  '510190': '上证180ETF',
  '510170': '市场ETF',
  '511010': '国债ETF',
  '510050': '上证50ETF'
};

const handleSelectionChange = (val) => {
  selectedData.value = val;
};

const goToOutputModule = () => {
  const selectedDataStore = useSelectedDataStore();
  selectedDataStore.setSelectedData(selectedData.value);
};

const openStrategyFile = () => {
  if (strategyFileInput.value) {
    strategyFileInput.value.value = '';
    strategyFileInput.value.click();
  }
};

const normalizeStockCode = (code) => {
  const text = String(code || '').trim();
  if (!text) return '';
  const baseCode = text.split('.')[0].replace(/\D/g, '');
  const marketSuffix = text.includes('.') ? text.split('.')[1].toUpperCase() : '';
  if (!marketSuffix) {
    if (baseCode.startsWith('5') || baseCode.startsWith('6') || baseCode.startsWith('0') || baseCode.startsWith('3')) {
      return `${baseCode}.${baseCode.startsWith('6') ? 'SH' : 'SZ'}`;
    }
  }
  return marketSuffix ? `${baseCode}.${marketSuffix}` : baseCode;
};

const getStockNameByCode = (code) => {
  const baseCode = String(code || '').split('.')[0];
  return STOCK_NAME_MAP[baseCode] || String(code || '');
};

const parseNumber = (value) => {
  const num = Number(String(value).replace(/,/g, '').trim());
  return Number.isFinite(num) ? num : 0;
};

const extractColumns = (headerRow) => {
  const header = headerRow.map((item) => String(item || '').trim().toLowerCase());
  const findIndex = (keywords) => header.findIndex((h) => keywords.some((kw) => h.includes(kw)));

  return {
    codeIndex: findIndex(['代码', 'stock code', 'stock_code', '证券代码']),
    quantityIndex: findIndex(['数量', '股票数量', '持仓数量', 'volume']),
    priceIndex: findIndex(['价格', '委托价', '成交价', 'price']),
    actionIndex: findIndex(['操作', '买卖', '方向', 'action'])
  };
};

const handleStrategyFileRead = async (e) => {
  const file = e.target.files[0];
  if (!file) return;

  const isValidFile = /\.(csv|xlsx|xls)$/i.test(file.name);
  if (!isValidFile) {
    ElMessage.error('仅支持上传.csv、.xlsx或.xls格式文件！');
    return;
  }

  isLoading.value = true;
  try {
    const ext = file.name.split('.').pop().toLowerCase();
    let rows = [];

    if (ext === 'csv') {
      const csvText = await file.text();
      rows = csvText.split(/\r?\n/).filter((r) => r.trim() !== '').map(parseCsvRow);
    } else {
      const buffer = await file.arrayBuffer();
      const workbook = XLSX.read(buffer, { type: 'array' });
      const firstSheetName = workbook.SheetNames[0];
      const sheet = workbook.Sheets[firstSheetName];
      rows = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: '' });
    }

    if (!rows.length) throw new Error('文件无内容');

    const { codeIndex, quantityIndex, priceIndex, actionIndex } = extractColumns(rows[0]);
    if (codeIndex === -1) throw new Error('未找到【代码】列');
    if (quantityIndex === -1) throw new Error('未找到【数量】列');
    if (priceIndex === -1) throw new Error('未找到【价格/委托价】列');

    tableData.value = [];
    for (let i = 1; i < rows.length && tableData.value.length < 20; i++) {
      const row = rows[i];
      if (!row || !row.length) continue;

      const rawCode = row[codeIndex];
      const stock_code = normalizeStockCode(rawCode);
      if (!stock_code) continue;

      const volume = Math.round(Math.abs(parseNumber(row[quantityIndex])) / 100) * 100;
      const entrust_price = parseNumber(row[priceIndex]).toFixed(2);
      const action = actionIndex !== -1 ? String(row[actionIndex] || '').trim() : '买入';
      const stock_name = getStockNameByCode(stock_code);
      const market_value = (volume * Number(entrust_price)).toFixed(2);

      tableData.value.push({
        index: i,
        stock_code,
        stock_name,
        volume,
        entrust_price,
        market_value,
        action
      });
    }

    ElMessage.success(`解析成功！共${tableData.value.length}条数据`);
  } catch (err) {
    ElMessage.error(`解析失败：${err.message}`);
    tableData.value = [];
  } finally {
    isLoading.value = false;
  }
};

const parseCsvRow = (row) => {
  if (!row) return [];
  const result = [];
  let current = '';
  let insideQuote = false;
  for (let i = 0; i < row.length; i++) {
    const c = row[i];
    if (c === '"') {
      insideQuote = !insideQuote;
    } else if (c === ',' && !insideQuote) {
      result.push(current.trim());
      current = '';
    } else {
      current += c;
    }
  }
  result.push(current.trim());
  return result;
};

const clearTable = async () => {
  await ElMessageBox.confirm('确定要清空当前策略数据吗？', '提示', {
    type: 'warning',
    confirmButtonColor: '#409eff'
  });
  tableData.value = [];
  selectedData.value = [];
  ElMessage.success('已清空数据');
};
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

.module-card {
  width: 100%;
}

.table-container, .table-stock {
  width: 100%;
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

:deep(.el-table .el-table__body-wrapper) {
  overflow-y: auto !important;
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
</style>
