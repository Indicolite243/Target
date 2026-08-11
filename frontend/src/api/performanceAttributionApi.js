import { httpClient as api } from '@/utils/httpClient'

export async function fetchMarketCapData(accountId) {
  if (!accountId) {
    throw new Error('缺少账户ID，无法获取业绩归因数据')
  }

  const response = await api.get(`/accounts/${accountId}/analyses/attribution`, {
    params: {
      dimension: 'ASSET'
    }
  })

  return transformMarketCapData(response.data?.data || response.data)
}

function transformMarketCapData(backendData) {
  const assetData = backendData.asset_data || backendData.positions || []

  if (!Array.isArray(assetData) || assetData.length === 0) {
    return {
      stocks: [],
      summary: { small: 0, medium: 0, large: 0, total: 0 }
    }
  }

  const summary = { small: 0, medium: 0, large: 0, total: assetData.length }
  const industryStats = {}

  const stocks = assetData.map(item => {
    const marketCap = calculateMarketCapInBillion(item.market_value)
    const category = categorizeMarketCapByBillion(marketCap)

    if (category === 'small') summary.small++
    else if (category === 'medium') summary.medium++
    else if (category === 'large') summary.large++

    const industry = item.industry || '其他'
    if (!industryStats[industry]) {
      industryStats[industry] = { name: industry, count: 0, marketValue: 0 }
    }
    industryStats[industry].count++
    industryStats[industry].marketValue += (item.market_value || 0)

    return {
      name: item.stock_name || item.stock_code,
      marketCap,
      category,
      industry
    }
  })

  const industries = Object.values(industryStats).sort((a, b) => b.marketValue - a.marketValue)

  return {
    stocks,
    summary,
    industries
  }
}

function calculateMarketCapInBillion(marketValue) {
  if (!marketValue) return 0
  return Number((marketValue / 100000000).toFixed(2))
}

function categorizeMarketCapByBillion(marketCapInBillion) {
  if (marketCapInBillion < 50) return 'small'
  if (marketCapInBillion < 500) return 'medium'
  return 'large'
}
