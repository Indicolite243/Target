import { defineStore } from 'pinia'
import { fetchAccountSummaries, fetchLivePortfolio, refreshLivePortfolio, toLegacyLiveAccount } from '@/api/accountApi.js'
import { useAccountStore } from '@/store'

const POLL_INTERVAL_MS = 2000
const BACKGROUND_OR_OFFLINE_POLL_INTERVAL_MS = 10000

/**
 * 当前账户快照的唯一前端轮询所有者。
 *
 * 页面上的资产、持仓、对比和交易组件都只读取这里的 snapshot，不能各自轮询后端。
 * 否则一次页面打开会产生多个重复请求，组件之间还可能看到不同版本的资产数据。
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
      // 多个页面同时挂载时复用同一个 Promise，避免重复加载账户列表和首个快照。
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
      // 请求序号让较早返回的请求不能覆盖用户切换账户后发起的新请求结果。
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
      // 手动刷新是明确的 QMT 同步请求，不与普通 Redis 读取混为一谈。
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
      // setTimeout 在上一轮请求 finally 后才再次安排，避免 setInterval 造成请求重叠。
      if (!this.polling || typeof window === 'undefined') return
      this.timerId = window.setTimeout(async () => {
        this.timerId = null
        if (!this.polling) return
        try {
          // 下一次 timeout 在本次请求结束后才安排，慢网络不会叠加下一轮组合轮询。
          await this.loadLivePortfolio()
        } catch {
          // 保留错误给页面展示；finally 仍会安排下一次带退避的重试。
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
