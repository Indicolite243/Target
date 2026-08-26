import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({
  fetchAccountSummaries: vi.fn(),
  fetchLivePortfolio: vi.fn(),
  refreshLivePortfolio: vi.fn()
}))

vi.mock('@/api/accountApi.js', () => ({
  ...api,
  toLegacyLiveAccount: (snapshot) => snapshot?.legacy || null
}))

import { usePortfolioLiveStore } from '@/store/portfolioLive.js'

const snapshot = (version = 1) => ({
  accountId: 'account-1',
  dataVersion: version,
  mode: 'LIVE',
  stale: false,
  legacy: { account_id: 'account-1', positions: [] }
})

describe('portfolioLive store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.fetchAccountSummaries.mockResolvedValue({
      accounts: [{ account_id: 'account-1', display_account_id: 'account-1' }]
    })
    api.fetchLivePortfolio.mockResolvedValue(snapshot())
  })

  it('coalesces concurrent initialization into one account and one live-snapshot request', async () => {
    const store = usePortfolioLiveStore()

    await Promise.all([store.initialize(), store.initialize()])

    expect(api.fetchAccountSummaries).toHaveBeenCalledTimes(1)
    expect(api.fetchLivePortfolio).toHaveBeenCalledTimes(1)
    expect(store.currentLegacyAccount.account_id).toBe('account-1')
  })

  it('schedules the next automatic read only after the previous request settles', async () => {
    vi.useFakeTimers()
    const store = usePortfolioLiveStore()
    await store.initialize()

    let resolveNextRead
    api.fetchLivePortfolio.mockImplementationOnce(() => new Promise(resolve => {
      resolveNextRead = resolve
    }))

    store.startPolling()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.fetchLivePortfolio).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(10000)
    expect(api.fetchLivePortfolio).toHaveBeenCalledTimes(2)

    resolveNextRead(snapshot(2))
    await Promise.resolve()
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(2000)
    expect(api.fetchLivePortfolio).toHaveBeenCalledTimes(3)

    store.stopPolling()
    vi.useRealTimers()
  })
})
