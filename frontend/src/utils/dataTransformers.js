// 数据转换工具
// 用于处理后端API返回的数据格式，转换为前端组件期望的格式

/**
 * 转换资产类别数据格式
 * @param {Object} backendData - 后端返回的资产类别数据
 * @returns {Array} 转换后的数据格式
 */
export const transformAssetCategoryData = (backendData) => {
  if (!backendData || !backendData.categoryData) {
    console.warn('资产类别数据格式不正确:', backendData);
    return [];
  }

  try {
    return backendData.categoryData.map(item => ({
      stock_code: item.stock_code || item.code || '',
      asset_ratio: item.asset_ratio || item.percentage + '%' || '0%',
      market_value: item.market_value || item.value || 0,
      daily_return: item.daily_return || item.return_rate || '0%'
    }));
  } catch (error) {
    console.error('转换资产类别数据失败:', error);
    return [];
  }
};

/**
 * 转换时间段对比数据格式
 * @param {Object} backendData - 后端返回的时间段数据
 * @returns {Array} 转换后的数据格式
 */
export const transformTimeComparisonData = (backendData) => {
  if (!backendData || !backendData.time_data) {
    console.warn('时间段数据格式不正确:', backendData);
    return [];
  }

  try {
    return backendData.time_data.map(item => ({
      timePeriod: item.time_period || item.period || item.date || '',
      totalAssets: item.total_assets || item.totalAssets || 0,
      returnRate: item.return_rate || item.returnRate || '0%',
      growthRate: item.growth_rate || item.growthRate || '0%'
    }));
  } catch (error) {
    console.error('转换时间段数据失败:', error);
    return [];
  }
};

/**
 * 转换地区对比数据格式
 * @param {Object} backendData - 后端返回的地区数据
 * @returns {Array} 转换后的数据格式
 */
export const transformRegionComparisonData = (backendData) => {
  if (!backendData || !backendData.area_data) {
    console.warn('地区数据格式不正确:', backendData);
    return [];
  }

  try {
    return backendData.area_data.map(item => ({
      region: item.region || item.area || '',
      totalAssets: item.total_assets || item.totalAssets || 0,
      returnRate: item.return_rate || item.returnRate || '0%',
      investmentRate: item.max_drawdown || item.maxDrawdown || '0%'
    }));
  } catch (error) {
    console.error('转换地区数据失败:', error);
    return [];
  }
};

/**
 * 转换账户信息数据格式
 * @param {Object} backendData - 后端返回的账户信息
 * @returns {Object} 转换后的数据格式
 */
export const transformAccountInfo = (backendData) => {
  if (!backendData || !backendData.accounts) {
    console.warn('账户信息数据格式不正确:', backendData);
    return {
      accounts: [],
      summary: {
        totalAssets: 0,
        totalCash: 0,
        totalMarketValue: 0,
        totalPositions: 0
      }
    };
  }

  try {
    const accounts = backendData.accounts.map(account => ({
      account_id: account.account_id || '',
      account_type: account.account_type || 'STOCK',
      cash: account.cash || 0,
      frozen_cash: account.frozen_cash || 0,
      market_value: account.market_value || 0,
      total_asset: account.total_asset || 0,
      positions: (account.positions || []).map(position => ({
        stock_code: position.stock_code || '',
        stock_name: position.stock_name || '',
        volume: position.volume || 0,
        can_use_volume: position.can_use_volume || 0,
        open_price: position.open_price || 0,
        market_value: position.market_value || 0,
        avg_price: position.avg_price || 0
      }))
    }));

    // 计算汇总信息
    const summary = accounts.reduce((acc, account) => {
      acc.totalAssets += account.total_asset || 0;
      acc.totalCash += account.cash || 0;
      acc.totalMarketValue += account.market_value || 0;
      acc.totalPositions += account.positions?.length || 0;
      return acc;
    }, {
      totalAssets: 0,
      totalCash: 0,
      totalMarketValue: 0,
      totalPositions: 0
    });

    return { accounts, summary };
  } catch (error) {
    console.error('转换账户信息失败:', error);
    return {
      accounts: [],
      summary: {
        totalAssets: 0,
        totalCash: 0,
        totalMarketValue: 0,
        totalPositions: 0
      }
    };
  }
};

/**
 * 转换策略执行结果数据格式
 * @param {Object} backendData - 后端返回的策略执行结果
 * @returns {Object} 转换后的数据格式
 */
export const transformStrategyResult = (backendData) => {
  if (!backendData) {
    console.warn('策略执行结果数据为空');
    return {
      success: false,
      message: '无数据',
      data: null
    };
  }

  try {
    return {
      success: backendData.success || false,
      message: backendData.message || '',
      data: backendData.data || null,
      executionTime: backendData.execution_time || new Date().toISOString(),
      strategyId: backendData.strategy_id || '',
      parameters: backendData.parameters || {}
    };
  } catch (error) {
    console.error('转换策略执行结果失败:', error);
    return {
      success: false,
      message: '数据转换失败',
      data: null
    };
  }
};

/**
 * 转换股票报价数据格式
 * @param {Object} backendData - 后端返回的股票报价数据
 * @returns {Object} 转换后的数据格式
 */
export const transformStockQuote = (backendData) => {
  if (!backendData) {
    console.warn('股票报价数据为空');
    return null;
  }

  try {
    return {
      code: backendData.stock_code || backendData.code || '',
      name: backendData.stock_name || backendData.name || '',
      price: backendData.current_price || backendData.price || 0,
      change: backendData.price_change || backendData.change || 0,
      changePercent: backendData.change_percent || backendData.changePercent || '0%',
      volume: backendData.volume || 0,
      turnover: backendData.turnover || 0,
      high: backendData.high_price || backendData.high || 0,
      low: backendData.low_price || backendData.low || 0,
      open: backendData.open_price || backendData.open || 0,
      prevClose: backendData.prev_close || backendData.prevClose || 0,
      timestamp: backendData.timestamp || new Date().toISOString()
    };
  } catch (error) {
    console.error('转换股票报价数据失败:', error);
    return null;
  }
};

/**
 * 数据验证函数
 * @param {any} data - 要验证的数据
 * @param {string} type - 数据类型
 * @returns {boolean} 验证结果
 */
export const validateData = (data, type) => {
  if (!data) return false;

  switch (type) {
    case 'array':
      return Array.isArray(data) && data.length > 0;
    case 'object':
      return typeof data === 'object' && !Array.isArray(data) && Object.keys(data).length > 0;
    case 'number':
      return typeof data === 'number' && !isNaN(data);
    case 'string':
      return typeof data === 'string' && data.trim().length > 0;
    default:
      return true;
  }
};

/**
 * 数据清理函数
 * @param {any} data - 要清理的数据
 * @returns {any} 清理后的数据
 */
export const sanitizeData = (data) => {
  if (typeof data === 'string') {
    return data.trim();
  }
  if (typeof data === 'number') {
    return isNaN(data) ? 0 : data;
  }
  if (Array.isArray(data)) {
    return data.filter(item => item !== null && item !== undefined);
  }
  if (typeof data === 'object' && data !== null) {
    const cleaned = {};
    for (const [key, value] of Object.entries(data)) {
      if (value !== null && value !== undefined) {
        cleaned[key] = sanitizeData(value);
      }
    }
    return cleaned;
  }
  return data;
};

// 导出所有转换函数
export default {
  transformAssetCategoryData,
  transformTimeComparisonData,
  transformRegionComparisonData,
  transformAccountInfo,
  transformStrategyResult,
  transformStockQuote,
  validateData,
  sanitizeData
};
