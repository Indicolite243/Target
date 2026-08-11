import { httpClient } from '@/utils/httpClient'

export async function fetchRiskAssessment(accountId = '', days = 30, options = {}) {
  if (!accountId) throw new Error('缺少账户ID，无法获取风险阈值数据')
  const params = { days }
  if (options.startDate) params.startDate = options.startDate
  if (options.endDate) params.endDate = options.endDate
  params.granularity = options.granularity || 'DAILY'
  const response = await httpClient.get(`/accounts/${accountId}/risk-assessments/latest`, { params })
  return transformRiskData(response.data?.data || response.data)
}

function transformRiskData(backendData = {}) {
  if (backendData.metrics) {
    const labels = {
      maxPrincipalLoss: '最大本金损失', volatility: '波动率',
      maxDrawdown: '最大回撤', var: 'VaR值'
    }
    return {
      risk_indicators: Object.entries(backendData.metrics).map(([key, metric]) => ({
        metric: labels[key] || key,
        value: metric.available === false ? '-' : `${metric.value}${metric.unit || '%'}`,
        status: mapStatus(metric.status)
      })),
      meta: {
        data_source: backendData.dataSource || backendData.source || 'qmt_history',
        snapshot_time: backendData.calculatedAt || '',
        is_mock: String(backendData.dataVersion || '').startsWith('simulation-'),
        algorithm_version: backendData.algorithmVersion,
        data_version: backendData.dataVersion,
        calculation_mode: String(backendData.dataVersion || '').includes('mongodb-snapshot-all-') ? 'ALL' : 'DAILY',
        risk_level: backendData.riskLevel,
        risk_score: backendData.riskScore,
        recommendations: backendData.recommendations || [],
        sample: backendData.sample || {},
        warnings: backendData.warnings || []
      }
    }
  }

  const indicators = []
  if (backendData.max_principal_loss) indicators.push({ metric: '最大本金损失', value: `${backendData.max_principal_loss.max_loss_rate}%`, status: mapStatus(backendData.max_principal_loss.status) })
  if (backendData.volatility) indicators.push({ metric: '波动率', value: `${backendData.volatility.annual_volatility}%`, status: mapStatus(backendData.volatility.status) })
  if (backendData.max_drawdown) indicators.push({ metric: '最大回撤', value: `${backendData.max_drawdown.max_drawdown}%`, status: mapStatus(backendData.max_drawdown.status) })
  if (backendData.var) indicators.push({ metric: 'VaR值', value: `${backendData.var.var_rate}%`, status: mapStatus(backendData.var.status) })
  return { risk_indicators: indicators, meta: { data_source: backendData.data_source || 'mongodb', snapshot_time: backendData.snapshot_time || '' } }
}

function mapStatus(status = '') {
  return { 正常: 'normal', 预警: 'warning', 危险: 'danger', NORMAL: 'normal', WARNING: 'warning', DANGER: 'danger', CRITICAL: 'danger' }[status] || 'normal'
}
