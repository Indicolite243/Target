import { httpClient } from '@/utils/httpClient'

const idempotencyKey = () => crypto.randomUUID()

const toLegacyPosition = (position) => ({
  ...position,
  position_id: position.positionId,
  stock_code: position.symbol,
  stock_name: position.securityName,
  volume: position.quantity,
  available_volume: position.availableQuantity,
  avg_price: position.costPrice,
  cost_price: position.costPrice,
  open_price: position.lastPrice,
  current_price: position.lastPrice,
  market_value: position.marketValue,
  profit_loss: position.profitLoss
})

const toLegacyAccount = (account, positions = []) => ({
  ...account,
  account_id: account.accountId,
  external_account_id: account.externalAccountId,
  display_account_id: account.externalAccountId || account.accountNoMasked || account.accountId,
  account_no: account.accountNoMasked,
  account_name: account.accountName,
  account_type: account.environment,
  total_asset: account.totalAsset,
  total_value: account.totalAsset,
  cash: account.cash,
  available_cash: account.cash,
  market_value: account.marketValue,
  total_profit: account.profitLoss,
  total_profit_loss: account.profitLoss,
  total_return_rate: account.totalAsset && account.profitLoss
    ? Number(account.profitLoss) / (Number(account.totalAsset) - Number(account.profitLoss)) * 100
    : 0,
  total_positions: positions.length,
  positions,
  snapshot_time: account.lastSyncTime,
  data_source: account.broker === 'GUOJIN_QMT' ? 'qmt' : 'mysql',
  is_realtime: !account.stale
})

export async function fetchAccountInfo() {
  const response = await httpClient.get('/accounts')
  const accounts = await Promise.all((response.data?.data || []).map(async (account) => {
    const positionsResponse = await httpClient.get(`/accounts/${account.accountId}/positions`)
    const positions = (positionsResponse.data?.data || []).map(toLegacyPosition)
    return toLegacyAccount(account, positions)
  }))
  return {
    success: response.data?.code === 0,
    accounts,
    data_source: accounts.some((item) => item.data_source === 'qmt') ? 'qmt' : 'mysql',
    snapshot_time: accounts[0]?.lastSyncTime || '',
    is_realtime: accounts.some((item) => !item.stale)
  }
}

export async function fetchQmtStatus() {
  const response = await httpClient.get('/accounts/qmt/status')
  return response.data?.data || {}
}

export async function syncQmtAccount(accountId) {
  const response = await httpClient.post(`/accounts/${accountId}/sync`)
  return {
    success: response.data?.code === 0,
    message: response.data?.message,
    ...response.data?.data
  }
}

export const fetchGuojinAccountInfo = fetchAccountInfo

export async function fetchGuojinSimQuote(stockCode) {
  if (!stockCode) return null
  const response = await httpClient.get('/market/quotes', { params: { symbols: stockCode } })
  const quote = response.data?.data?.quotes?.[0]
  if (!quote) return null
  return {
    ...quote,
    stock_code: quote.symbol,
    stock_name: quote.name,
    current_price: quote.lastPrice,
    last_price: quote.lastPrice,
    open_price: quote.openPrice,
    high_price: quote.highPrice,
    low_price: quote.lowPrice,
    latest: quote.lastPrice,
    pre_close: quote.previousClose,
    bid_prices: quote.bidPrices || [],
    ask_prices: quote.askPrices || [],
    bid_volumes: quote.bidVolumes || [],
    ask_volumes: quote.askVolumes || []
  }
}

export async function searchGuojinStock(keyword) {
  const quote = await fetchGuojinSimQuote(keyword)
  return quote ? [quote] : []
}

export async function submitGuojinBuyOrder(payload) {
  return submitGuojinTradeOrder({ ...payload, action: 'buy' })
}

export async function submitGuojinSellOrder(payload) {
  return submitGuojinTradeOrder({ ...payload, action: 'sell' })
}

export async function submitGuojinTradeOrder(payload = {}) {
  if (Array.isArray(payload.orders)) {
    const results = []
    for (const order of payload.orders) {
      results.push(await submitGuojinTradeOrder({
        ...order,
        action: order.order_side || order.action || payload.default_side || payload.action
      }))
    }
    return { success: results.every(item => item.success !== false), results }
  }
  const side = String(payload.action || payload.side || 'buy').toUpperCase()
  const body = {
    accountId: String(payload.account_id || payload.accountId || ''),
    symbol: payload.stock_code || payload.symbol,
    securityName: payload.stock_name || payload.securityName || payload.stock_code || payload.symbol,
    side: side === 'SELL' ? 'SELL' : 'BUY',
    orderType: payload.order_type || payload.orderType || 'LIMIT',
    quantity: String(payload.volume || payload.quantity || 0),
    price: String(payload.price || payload.order_price || payload.entrust_price || 0),
    environment: payload.environment || 'SIMULATION',
    remark: payload.remark || 'Vue migrated client'
  }
  const response = await httpClient.post('/orders', body, {
    headers: { 'X-Idempotency-Key': idempotencyKey() }
  })
  return { ...response.data, success: response.data?.code === 0 }
}

export async function fetchOrderList(params = {}) {
  const response = await httpClient.get('/orders', { params })
  return { ...response.data, success: response.data?.code === 0 }
}

export async function cancelOrder(payload = {}) {
  const orderId = payload.orderId || payload.order_id || payload.id
  const response = await httpClient.post(`/orders/${orderId}/cancel`, {
    reason: '用户主动撤单'
  }, { headers: { 'X-Idempotency-Key': idempotencyKey() } })
  return { ...response.data, success: response.data?.code === 0 }
}

export async function fetchAssetCategoryData(accountId) {
  const id = accountId || (await fetchAccountInfo()).accounts[0]?.account_id
  const response = await httpClient.get(`/accounts/${id}/allocations`, {
    params: { dimension: 'ASSET_CLASS' }
  })
  return response.data?.data
}

export async function fetchRegionDataFromBackend(accountId) {
  const id = accountId || (await fetchAccountInfo()).accounts[0]?.account_id
  const response = await httpClient.get(`/accounts/${id}/analyses/allocation`, {
    params: { dimension: 'REGION' }
  })
  return response.data?.data
}

export async function fetchTimeDataFromBackend(params = {}) {
  const id = params.accountId || params.account_id || (await fetchAccountInfo()).accounts[0]?.account_id
  const response = await httpClient.get(`/accounts/${id}/snapshots`, { params })
  return response.data?.data
}
