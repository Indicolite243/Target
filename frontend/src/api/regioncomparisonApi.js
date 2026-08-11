import { httpClient } from '@/utils/httpClient'

export async function fetchRegionComparisonData(accountId) {
  if (!accountId) throw new Error('缺少账户ID，无法获取分市场对比数据')
  const response = await httpClient.get(`/accounts/${accountId}/analyses/allocation`, {
    params: { dimension: 'REGION' }
  })
  const data = response.data?.data || response.data
  const items = data.items || data.area_data || []
  return {
    region_data: items.map((item) => ({
      region: item.name || item.region,
      totalAssets: item.marketValue || item.totalAssets,
      returnRate: item.profitLossRate || item.returnRate,
      investmentRate: item.weight || item.maxDrawdown
    }))
  }
}
