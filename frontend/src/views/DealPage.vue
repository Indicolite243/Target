<template>
<!--deal-page整个页面的根容器，负责包裹背景、内容和浮动装饰。-->
  <div class="deal-page">
<!--grid-background网格背景装饰-->
    <div class="grid-background"></div>
<!-- content-wrapper主要内容区域(左侧导航栏+右侧主内容) -->
    <div class="content-wrapper">

<!-- 左侧导航栏 -->
      <div class="left-sidebar glass-panel">
        <div class="sidebar-header">
          <div class="header-line"></div>
          <h3 class="sidebar-title">交易信息</h3>
        </div>
<!-- 左侧导航栏-当前策略 -->
        <div class="sidebar-menu">
          <div
            class="menu-item"
            :class="{ active: activeMenu === 'asset' }"
            @click="setActiveMenu('asset')"
            style="cursor: pointer; user-select: none;"
          >
          <div class="menu-icon strategy-icon"></div>
            <span>执行策略</span>
          </div>
<!-- 左侧导航栏-当前账户信息 -->
           <div
            class="menu-item"
            :class="{ active: activeMenu === 'asset' }"
            @click="setActiveMenu('asset')"
            style="cursor: pointer; user-select: none;"
          >
           <div class="menu-icon account-icon"></div>
            <span>账户信息</span>
          </div>
<!-- 竖向表格 -->
          <div class="table-container">
            <div class="vertical-table account-table">
              <div class="tableRowClassName({ rowIndex: 0 })">
                <div class="account-table-label">
                <div class="menu-icon asset-icon"></div>
                    <span>资金账号</span>
                </div>
                <div class="account-table-value">{{ selectedAccountData[0]?.account_id || '--' }}</div>
              </div>
              <div class="tableRowClassName({ rowIndex: 1 })">
                <div class="account-table-label">
                  <div class="menu-icon time-icon"></div>
                    <span>总资产</span>
                </div>
                <div class="account-table-value">{{ selectedAccountData[0]?.total_asset || '--' }}</div>
              </div>
              <div class="tableRowClassName({ rowIndex: 2 })">
                <div class="account-table-label">
                  <div class="menu-icon region-icon"></div>
                    <span>可用金额</span>
                </div>
                <div class="account-table-value">{{ selectedAccountData[0]?.cash || '--' }}</div>
              </div>
              <div class="tableRowClassName({ rowIndex: 3 })">
                <div class="account-table-label">
                  <div class="menu-icon time-icon"></div>
                    <span>总收益率</span>
                </div>
                <div class="account-table-value">{{ selectedAccountData[0]?.total_return_rate || '--' }}</div>
              </div>
              <div class="tableRowClassName({ rowIndex: 4})">
                <div class="account-table-label">
                  <div class="menu-icon asset-icon"></div>
                    <span>持仓股数</span>
                </div>
                <div class="account-table-value">{{ selectedAccountData[0]?.total_positions || '--' }}</div>
              </div>
              <div class="tableRowClassName({ rowIndex: 5 })">
                <div class="account-table-label">
                  <div class="menu-icon time-icon"></div>
                    <span>持仓市值</span>
                </div>
                <div class="account-table-value">{{ selectedAccountData[0]?.market_value || '--' }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
<!-- 主要内容区域 -->
      <div class="main-content">
        <!-- 上半部分:输入委托-->
        <div class="content-row top-row">
          <div class="content-panel glass-panel chart-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon chart-icon"></i>
                   输入委托
              </h4>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
              <InputModule/>
            </div>
          </div>

          <div class="main-content">
          <!-- 右上: 手动交易 -->
          <div class="content-panel glass-panel threshold-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon threshold-icon"></i>
                手动交易
              </h4>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
               <OrderModule />
            </div>
          </div>
        </div>
     </div>

        <!-- 下半部分 -->
        <div class="content-row bottom-row">
          <div class="content-panel glass-panel table-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon table-icon"></i>
                输出委托
              </h4>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
              <OutputModule/>
            </div>
          </div>

          <!-- 右下 -->
          <div class="content-panel glass-panel warning-panel">
            <div class="panel-header">
              <div class="header-line"></div>
              <h4 class="panel-title">
                <i class="title-icon warning-icon"></i>
                订单
              </h4>
              <div class="status-indicator"></div>
            </div>
            <div class="panel-content">
            <OrderList/>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 浮动装饰元素 -->
    <div class="floating-decorations">
      <div class="decoration-orb orb-1"></div>
      <div class="decoration-orb orb-2"></div>
      <div class="decoration-orb orb-3"></div>
    </div>
  </div>
</template>

<script>
/* Vue 响应式和生命周期函数 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
/* 导入了两个 API */
import { fetchRiskAssessment } from '@/api/riskThresholdApi.js';
import { useAccountStore } from '@/store';
import { usePortfolioLiveStore } from '@/store/portfolioLive.js';
/* 4 个子组件 */
import InputModule from '@/components/layout/InputModule.vue';
import OrderModule from '@/components/layout/OrderModule.vue';
import OutputModule from '@/components/layout/OutputModule.vue';
import OrderList from '@/components/layout/OrderList.vue';

export default {
  name: 'DealPage',
  components: {
    InputModule,
    OrderModule,
    OutputModule,
    OrderList,
  },
  setup() {
    const accountStore = useAccountStore();
    const portfolioLiveStore = usePortfolioLiveStore();
    // 交易页与资产展示页共用同一份 Redis 实时组合快照，避免旧实现的
    // “账户列表 + 每个账户一次持仓请求”在 3 秒定时器中重复执行。
    const accounts = computed(() => portfolioLiveStore.accounts);
    const selectedAccount = computed(() => portfolioLiveStore.selectedAccountId);
    const currentLegacyAccount = computed(() => portfolioLiveStore.currentLegacyAccount);

    onMounted(async () => {
      try {
        await portfolioLiveStore.initialize();
        portfolioLiveStore.startPolling();
      } catch (error) {
        console.error('初始化实时账户数据失败：', error);
      }
    });

    onUnmounted(() => {
      portfolioLiveStore.stopPolling();
    });



    const selectedAccountData = computed(() => {
      const account = currentLegacyAccount.value;
      if (!account) return [];
      const totalReturnRate = Number(account.total_return_rate || 0);
      return [
        {
          account_id: account.display_account_id || account.external_account_id || account.account_id,
          total_asset: formatNumber(account.total_asset, 2),
          cash: formatNumber(account.cash, 2),
          frozen_cash: formatNumber(account.frozen_cash, 2),
          total_return_rate: `${totalReturnRate.toFixed(2)}%`,
          total_positions: formatNumber(account.total_positions || account.positions?.length, 0),
          market_value: formatNumber(account.market_value, 2),
        },
      ];
    });

    const selectedStocks = computed(() => {
      const account = currentLegacyAccount.value;
      if (!account || !account.positions) return [];
      return account.positions;
    });

    //格式化数据
    const formatNumber = (value, decimals) => {
      if (value === undefined || value === null) return '0.'.padEnd(decimals + 2, '0');
      const num = Number(value);
      if (isNaN(num)) return '0.'.padEnd(decimals + 2, '0');

      const parts = num.toFixed(decimals).toString().split('.');
      parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
      return parts.join('.');
    };

    //  风险 / 菜单相关数据
    const activeMenu = ref('asset');
    const riskThresholdData = ref([]);
    const riskWarnings = ref([
      { level: 'low', message: '市场波动率略高', time: '2025-01-25 14:30', action: '建议适当降低仓位' },
      { level: 'normal', message: '系统运行正常', time: '2025-01-25 12:00', action: '继续监控' }
    ]);

    const loadRiskThresholdData = async () => {
      try {
        const currentAccountId = selectedAccount.value || accounts.value[0]?.account_id;
        if (!currentAccountId) {
          riskThresholdData.value = [];
          return;
        }
        const data = await fetchRiskAssessment(currentAccountId, 30);
        riskThresholdData.value = data.risk_indicators || data.indicators || [];
      } catch (error) {
        console.error('获取风险阈值数据失败:', error);
        riskThresholdData.value = [];
      }
    };

    const setActiveMenu = (menu) => {
      activeMenu.value = menu;
    };

    const getChartType = computed(() => activeMenu.value);
    const getTableType = computed(() => activeMenu.value);

    watch(currentLegacyAccount, (currentAccount) => {
      accountStore.setSelectedAccountId(portfolioLiveStore.selectedAccountId);
      accountStore.setAccountInfo(currentAccount || {});
    }, { immediate: true });

    return {
      accounts,
      selectedAccount,
      selectedAccountData,
      selectedStocks,
      formatNumber,
      activeMenu,
      riskThresholdData,
      riskWarnings,
      loadRiskThresholdData,
      setActiveMenu,
      getChartType,
      getTableType,
    };
  }
};
</script>

<style scoped>
.main-content{
  width: 38%;
  min-width: 380px;
  overflow: auto;
}
.deal-page {
  position: relative;
  width: 100%;
  height: calc(100vh - 120px);
  padding: 20px;
  box-sizing: border-box;
  overflow: auto;
  z-index: 2;
}

/* 网格背景装饰 */
.grid-background {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image:
    linear-gradient(rgba(64, 224, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(64, 224, 255, 0.05) 1px, transparent 1px);
  background-size: 50px 50px;
  animation: gridMove 20s linear infinite;
  pointer-events: none;
}

@keyframes gridMove {
  0% { transform: translate(0, 0); }
  100% { transform: translate(50px, 50px); }
}

/* 主要内容包装器 */
.content-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  gap: 10px;
  z-index: 3;
}

/* 玻璃态面板样式 */
.glass-panel {
  position: relative;
  background: rgba(12, 20, 38, 0.4);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(64, 224, 255, 0.3);
  border-radius: 12px;
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.3),
    0 0 40px rgba(64, 224, 255, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
  overflow: hidden;
  transition: all 0.5s ease;
  animation: panelGlow 4s ease-in-out infinite alternate;
}

.glass-panel:hover {
  border-color: rgba(64, 224, 255, 0.6);
  box-shadow:
    0 12px 40px rgba(0, 0, 0, 0.4),
    0 0 60px rgba(64, 224, 255, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.2);
  transform: translateY(-2px);
}

@keyframes panelGlow {
  0% {
    box-shadow:
      0 8px 32px rgba(0, 0, 0, 0.3),
      0 0 40px rgba(64, 224, 255, 0.1),
      inset 0 1px 0 rgba(255, 255, 255, 0.1);
  }
  100% {
    box-shadow:
      0 8px 32px rgba(0, 0, 0, 0.3),
      0 0 60px rgba(64, 224, 255, 0.2),
      inset 0 1px 0 rgba(255, 255, 255, 0.15);
  }
}

/* 左侧导航栏 */
.left-sidebar {
  width: 180px;
  min-width: 180px;
  display: flex;
  flex-direction: column;
  background: rgba(12, 20, 38, 0.72);
  border-radius: 0;
  box-shadow: none;
  animation: none;
}

.sidebar-header {
  padding: 14px 15px;
  background: rgba(64, 224, 255, 0.04);
  border-bottom: 1px solid rgba(64, 224, 255, 0.2);
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-line {
  width: 3px;
  height: 16px;
  background: linear-gradient(180deg, #40e0ff, #1e90ff);
  border-radius: 2px;
  box-shadow: 0 0 8px rgba(64, 224, 255, 0.8);
}

.sidebar-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
  text-shadow: 0 0 8px rgba(64, 224, 255, 0.5);
}

.sidebar-menu {
  flex: 1;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.account-table {
  margin-top: 2px;
  overflow: hidden;
  border-top: 1px solid rgba(64, 224, 255, 0.18);
  border-bottom: 1px solid rgba(64, 224, 255, 0.18);
}

.account-table > div {
  display: grid;
  grid-template-columns: 1fr;
  gap: 0;
  padding: 8px 4px 9px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.account-table > div:nth-child(even) {
  background: rgba(255, 255, 255, 0.025);
}

.account-table > div:last-child {
  border-bottom: 0;
}

.account-table-label,
.account-table-value {
  display: flex;
  align-items: center;
  min-width: 0;
  padding: 0 4px;
}

.account-table-label {
  gap: 8px;
  color: #40e0ff;
  font-size: 12px;
  line-height: 20px;
}

.account-table-value {
  justify-content: flex-start;
  padding-top: 3px;
  color: #ffffff;
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  overflow-wrap: anywhere;
}

/* 执行策略图标（用「数据/策略」类图标，和图2「数据展示」风格匹配） */
.strategy-icon {
  background-image: url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="white"><path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>');
}

/* 账户信息图标（用「账户/资金」类图标，和图2「订单委托」风格匹配） */
.account-icon {
  background-image: url('data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="white"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 3c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.2c-2.5 0-4.71-1.28-6-3.22.03-1.99 4-3.08 6-3.08 1.99 0 5.97 1.09 6 3.08-1.29 1.94-3.5 3.22-6 3.22z"/></svg>');
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 15px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s ease;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
}

.menu-item:hover {
  background: rgba(64, 224, 255, 0.1);
  color: #ffffff;
  transform: translateX(3px);
}

.menu-item.active {
  background: rgba(64, 224, 255, 0.2);
  color: #40e0ff;
  border: 1px solid rgba(64, 224, 255, 0.3);
  box-shadow: 0 0 15px rgba(64, 224, 255, 0.2);
}

.menu-icon {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  transition: all 0.3s ease;
}

.asset-icon {
  background: linear-gradient(135deg, #40e0ff, #1e90ff);
}

.time-icon {
  background: linear-gradient(135deg, #feca57, #ff9ff3);
}

.region-icon {
  background: linear-gradient(135deg, #48dbfb, #0abde3);
}

.menu-item.active .menu-icon {
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.6);
  transform: scale(1.1);
}

/* 主要内容区域 */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.content-row {
  flex: 1;
  display: flex;
  gap: 20px;
}

.content-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

/* 面板头部 */
.panel-header {
  padding: 12px 15px;
  background: linear-gradient(135deg,
    rgba(64, 224, 255, 0.1) 0%,
    rgba(30, 144, 255, 0.05) 100%);
  border-bottom: 1px solid rgba(64, 224, 255, 0.2);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.panel-title {
  flex: 1;
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
  display: flex;
  align-items: center;
  gap: 6px;
  text-shadow: 0 0 8px rgba(64, 224, 255, 0.5);
}

.title-icon {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  box-shadow: 0 0 6px rgba(64, 224, 255, 0.6);
  animation: iconPulse 2s ease-in-out infinite;
}

.chart-icon {
  background: linear-gradient(135deg, #40e0ff, #1e90ff);
}

.threshold-icon {
  background: linear-gradient(135deg, #ff6b6b, #ee5a24);
}

.table-icon {
  background: linear-gradient(135deg, #feca57, #ff9ff3);
}

.warning-icon {
  background: linear-gradient(135deg, #ff9f43, #feca57);
}

@keyframes iconPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.8; transform: scale(1.1); }
}

.status-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #00ff88;
  box-shadow:
    0 0 8px #00ff88,
    0 0 16px rgba(0, 255, 136, 0.5);
  animation: statusBlink 2s ease-in-out infinite;
}

@keyframes statusBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

/* 面板内容 */
.panel-content {
  flex: 1;
  padding: 15px;
  overflow: hidden;
  position: relative;
}

/* 浮动装饰元素 */
.floating-decorations {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 1;
}

.decoration-orb {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(64, 224, 255, 0.3), transparent);
  box-shadow: 0 0 30px rgba(64, 224, 255, 0.4);
  animation: orbFloat 8s ease-in-out infinite;
}

.orb-1 {
  width: 80px;
  height: 80px;
  top: 10%;
  right: 20%;
  animation-delay: 0s;
}

.orb-2 {
  width: 60px;
  height: 60px;
  bottom: 20%;
  left: 15%;
  animation-delay: 3s;
}

.orb-3 {
  width: 70px;
  height: 70px;
  top: 60%;
  right: 10%;
  animation-delay: 6s;
}

@keyframes orbFloat {
  0%, 100% { transform: translateY(0px); opacity: 0.3; }
  50% { transform: translateY(-15px); opacity: 0.6; }
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .left-sidebar {
    width: 160px;
    min-width: 160px;
  }

  .content-row {
    flex-direction: column;
    gap: 15px;
  }

  .content-panel {
    min-height: 250px;
  }
}

@media (max-width: 768px) {
  .comparison-page {
    padding: 10px;
  }

  .content-wrapper {
    flex-direction: column;
    gap: 15px;
  }

  .left-sidebar {
    width: 100%;
    height: auto;
    flex-direction: row;
  }

  .sidebar-menu {
    flex-direction: row;
    padding: 10px;
  }

  .main-content {
    gap: 10px;
  }
}
</style>
