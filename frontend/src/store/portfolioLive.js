import { defineStore } from 'pinia'
import { fetchAccountSummaries, fetchLivePortfolio, refreshLivePortfolio, toLegacyLiveAccount } from '@/api/accountApi.js'
import { useAccountStore } from '@/store'

const POLL_INTERVAL_MS = 2000
const BACKGROUND_OR_OFFLINE_POLL_INTERVAL_MS = 10000

/**
 * The only frontend polling owner for current portfolio data. Every mounted
 * widget reads the same snapshot and dataVersion from this store.
 */
export const usePortfolioLiveStore = defineStore('portfolioLive', {
  state: () => ({
    accounts: [],
    selectedAccountId: '',
    snapshot: null,
    loading: false,
    refreshing: false,
    error: '',
    timerId: null,
    polling: false,
    initializing: null,
    requestVersion: 0
  }),

  getters: {
    currentLegacyAccount: (state) => toLegacyLiveAccount(state.snapshot),
    isLive: (state) => state.snapshot?.mode === 'LIVE' && !state.snapshot?.stale,
    dataVersion: (state) => state.snapshot?.dataVersion ?? 0
  },

  actions: {
    async initialize() {
      if (this.initializing) return this.initializing
      this.initializing = (async () => {
        if (!this.accounts.length) {
          const data = await fetchAccountSummaries()
          this.accounts = data?.accounts || []
        }
        const accountStore = useAccountStore()
        const selected = accountStore.selectedAccountId || this.selectedAccountId || this.accounts[0]?.account_id || ''
        if (!selected) return null
        if (String(this.snapshot?.accountId || '') === String(selected)) return this.snapshot
        if (this.selectedAccountId !== String(selected)) {
          this.selectedAccountId = String(selected)
          accountStore.setSelectedAccountId(selected)
        }
        return this.loadLivePortfolio(selected)
      })()
      try {
        return await this.initializing
      } finally {
        this.initializing = null
      }
    },

    async selectAccount(accountId) {
      const normalized = String(accountId || '').trim()
      if (!normalized) return null
      if (this.selectedAccountId === normalized && String(this.snapshot?.accountId || '') === normalized) {
        return this.snapshot
      }
      this.selectedAccountId = normalized
      useAccountStore().setSelectedAccountId(normalized)
      this.snapshot = null
      return this.loadLivePortfolio(normalized)
    },

    async loadLivePortfolio(accountId = this.selectedAccountId) {
      if (!accountId || this.refreshing || this.loading) return this.snapshot
      const requestVersion = ++this.requestVersion
      this.loading = true
      this.error = ''
      try {
        const snapshot = await fetchLivePortfolio(accountId)
        if (requestVersion === this.requestVersion && String(snapshot?.accountId || '') === String(accountId)) {
          this.snapshot = snapshot
        }
        return snapshot
      } catch (error) {
        if (requestVersion === this.requestVersion) this.error = error?.message || '实时组合读取失败'
        throw error
      } finally {
        if (requestVersion === this.requestVersion) this.loading = false
      }
    },

    async manualRefresh() {
      if (!this.selectedAccountId || this.refreshing) return this.snapshot
      const requestVersion = ++this.requestVersion
      this.refreshing = true
      this.error = ''
      try {
        const snapshot = await refreshLivePortfolio(this.selectedAccountId)
        if (requestVersion === this.requestVersion) this.snapshot = snapshot
        return snapshot
      } catch (error) {
        if (requestVersion === this.requestVersion) this.error = error?.message || 'QMT手动刷新失败'
        throw error
      } finally {
        if (requestVersion === this.requestVersion) this.refreshing = false
      }
    },

    nextPollDelay() {
      const pageIsHidden = typeof document !== 'undefined' && document.hidden
      return pageIsHidden || !this.isLive
        ? BACKGROUND_OR_OFFLINE_POLL_INTERVAL_MS
        : POLL_INTERVAL_MS
    },

    scheduleNextPoll(delay = this.nextPollDelay()) {
      if (!this.polling || typeof window === 'undefined') return
      this.timerId = window.setTimeout(async () => {
        this.timerId = null
        if (!this.polling) return
        try {
          // The next timeout is scheduled after this request settles, so a slow
          // network response can never overlap the next portfolio poll.
          await this.loadLivePortfolio()
        } catch {
          // The store keeps the error for the UI and retries with backoff.
        } finally {
          this.scheduleNextPoll()
        }
      }, delay)
    },

    startPolling() {
      if (this.polling || typeof window === 'undefined') return
      this.polling = true
      this.scheduleNextPoll(POLL_INTERVAL_MS)
    },

    stopPolling() {
      if (this.timerId !== null && typeof window !== 'undefined') window.clearTimeout(this.timerId)
      this.timerId = null
      this.polling = false
    }
  }
})
