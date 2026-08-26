import { httpClient } from '@/utils/httpClient'

const dataOf = (response) => response.data?.data || response.data

export async function fetchAssetComparison(accountId = '', source = 'mysql') {
  if (!accountId) throw new Error('缺少账户ID，无法获取资产对比数据')
  const response = await httpClient.get(`/accounts/${accountId}/analyses/allocation`, {
    params: { dimension: 'ASSET_CLASS', source }
  })
  return dataOf(response)
}

export async function fetchYearlyComparisonData(accountId = '', source = 'mysql', granularity = 'DAILY', calculationMode = 'SNAPSHOT') {
  if (!accountId) throw new Error('缺少账户ID，无法获取年度对比数据')
  const response = await httpClient.get(`/accounts/${accountId}/analyses/period-comparison`, {
    params: { periodType: 'YEAR', source, granularity, calculationMode }
  })
  return dataOf(response)
}

export async function fetchAreaComparison(accountId = '', source = 'mysql') {
  if (!accountId) throw new Error('缺少账户ID，无法获取分市场对比数据')
  const response = await httpClient.get(`/accounts/${accountId}/analyses/allocation`, {
    params: { dimension: 'REGION', source }
  })
  return dataOf(response)
}

export async function fetchAssetAttribution(accountId = '', source = 'mysql', options = {}) {
  if (!accountId) throw new Error('缺少账户ID，无法获取业绩归因数据')
  const params = { source }
  if (options.startDate) params.from = options.startDate
  if (options.endDate) params.to = options.endDate
  const response = await httpClient.get(`/accounts/${accountId}/analyses/attribution`, { params })
  return dataOf(response)
}
