import { httpClient } from '@/utils/httpClient'

async function fetchPeriod(accountId, periodType, granularity = 'DAILY') {
  if (!accountId) throw new Error('缺少账户ID，无法获取周期对比数据')
  const response = await httpClient.get(`/accounts/${accountId}/analyses/period-comparison`, {
    params: { periodType, granularity }
  })
  return response.data?.data || response.data
}

export const fetchYearlyComparisonData = (accountId) => fetchPeriod(accountId, 'YEAR')
export const fetchWeeklyComparisonData = (accountId) => fetchPeriod(accountId, 'WEEK')
