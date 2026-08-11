import { defineStore } from 'pinia'

export const useSelectedDataStore = defineStore('selectedData', {
  state: () => ({
    tableSelectedData: []
  }),
  actions: {
    setSelectedData(data) {
      this.tableSelectedData = data
    },
    clearSelectedData() {
      this.tableSelectedData = []
    }
  }
})

export const useAccountStore = defineStore('account', {
  state: () => ({
    accountInfo: {},
    selectedAccountId: ''
  }),
  actions: {
    setAccountInfo(data) {
      this.accountInfo = data
    },
    setSelectedAccountId(accountId) {
      this.selectedAccountId = String(accountId || '').trim()
    }
  }
})
