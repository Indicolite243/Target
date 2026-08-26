<template>
  <div class="comparison-chart" v-loading="loading && !myChart">
    <div ref="chartContainer" class="chart-container"></div>
  </div>
</template>

<script>
import * as echarts from 'echarts';
import { fetchYearlyComparisonData, fetchAreaComparison } from '@/api/comparisonModuleApi.js';
import { usePortfolioLiveStore } from '@/store/portfolioLive.js';

export default {
  name: 'ComparisonChart',
  props: {
    chartType: {
      type: String,
      default: 'asset'
    },
    timeGranularity: {
      type: String,
      default: 'DAILY'
    }
  },
  data() {
    return {
      myChart: null,
      loading: false,
      currentAccountId: null,
      lastOption: null,
      lastChartType: null, // 记录上一次渲染的图表类型
      timer: null,
      updateTaskId: 0 // 用于追踪异步请求，防止数据重合
    };
  },
  mounted() {
    this.initChart();
    window.addEventListener('resize', this.handleResize);
  },
  beforeUnmount() {
    window.removeEventListener('resize', this.handleResize);
    if (this.myChart) {
      this.myChart.dispose();
    }
  },
  watch: {
    chartType: {
      handler(newType) {
        console.log(`📊 切换图表类型为: ${newType}`);
        this.lastOption = null;
        this.lastChartType = null;
        if (this.myChart) this.myChart.clear();
      },
      immediate: false
    }
  },
  methods: {
    initChart() {
      const chartDom = this.$refs.chartContainer;
      if (!chartDom) return;
      this.myChart = echarts.init(chartDom);
      this.myChart.setOption(this.getEmptyChartOption('数据加载中'), true);
    },

    /**
     * 设置外部传入的数据，加速渲染并确保同步
     */
    setData(data, accountId) {
      if (!this.myChart) return;
      this.currentAccountId = accountId;
      this.renderChartData(data);
    },

    async updateChart(accountId, forceRefresh = false) {
      if (!this.myChart) return;

      if (accountId) {
        this.currentAccountId = accountId;
      }

      const targetAccountId = accountId || this.currentAccountId;
      const requestedType = this.chartType;
      const taskId = ++this.updateTaskId;

      // 仅在首次加载或强制刷新时显示 loading
      if (!this.lastOption || forceRefresh) {
        this.loading = true;
      }

      // 如果是强制刷新（切换类型），先清空图表
      if (forceRefresh && this.myChart) {
        this.myChart.clear();
      }

      try {
        let rawData = null;
        if (requestedType === 'asset') {
          const portfolioLiveStore = usePortfolioLiveStore();
          await portfolioLiveStore.initialize();
          rawData = portfolioLiveStore.currentLegacyAccount;
          if (!rawData && targetAccountId) {
            await portfolioLiveStore.loadLivePortfolio(targetAccountId);
            rawData = portfolioLiveStore.currentLegacyAccount;
          }
        } else if (requestedType === 'time') {
          rawData = await fetchYearlyComparisonData(targetAccountId, 'mysql', this.timeGranularity);
        } else if (requestedType === 'region') {
          rawData = await fetchAreaComparison(targetAccountId, 'mysql');
        }

        if (taskId !== this.updateTaskId) return;
        this.renderChartData(rawData, forceRefresh);
      } catch (error) {
        console.error(`❌ 更新图表失败: ${requestedType}`, error);
      } finally {
        this.loading = false;
      }
    },

    /**
     * 核心渲染逻辑，支持同步/异步调用
     */
    async renderChartData(rawData, forceRefresh = false) {
      if (!rawData || !this.myChart) return;

      const requestedType = this.chartType;
      let option = {};

      try {
        if (requestedType === 'asset') {
          option = await this.processAssetOption(rawData);
        } else if (requestedType === 'time') {
          option = await this.processTimeOption(rawData);
        } else if (requestedType === 'region') {
          option = await this.processRegionOption(rawData);
        }

        // 彻底清空之前的标题和配置干扰
        if (!option.title) option.title = { show: false };

        // 💡 保持滚动条位置 (仅在非强制刷新且类型未变时)
        if (this.myChart && !forceRefresh && this.lastChartType === requestedType) {
          const currentOption = this.myChart.getOption();
          if (currentOption && currentOption.dataZoom) {
            const currentZooms = Array.isArray(currentOption.dataZoom) ? currentOption.dataZoom : [currentOption.dataZoom];
            if (option.dataZoom) {
              const newZooms = Array.isArray(option.dataZoom) ? option.dataZoom : [option.dataZoom];
              newZooms.forEach((zoom, idx) => {
                if (currentZooms[idx]) {
                  // 保持百分比位置
                  if (currentZooms[idx].start !== undefined) {
                    zoom.start = currentZooms[idx].start;
                    zoom.end = currentZooms[idx].end;
                    // 移除具体的数值范围，让百分比生效
                    delete zoom.startValue;
                    delete zoom.endValue;
                  }
                }
              });
            }
          }
        }

        // 💡 核心优化：仅在类型切换或强制刷新时使用 notMerge = true
        // 这样可以保持 periodic 刷新时的交互状态（如正在滑动的 scrollbar）
        const isTypeChanged = this.lastChartType !== requestedType;
        const previousSeries = Array.isArray(this.lastOption?.series) ? this.lastOption.series : [];
        const nextSeries = Array.isArray(option.series) ? option.series : [];
        const dataShapeChanged = previousSeries.length !== nextSeries.length
          || previousSeries.some((series, index) => {
            const previousData = Array.isArray(series?.data) ? series.data : [];
            const nextData = Array.isArray(nextSeries[index]?.data) ? nextSeries[index].data : [];
            return previousData.length !== nextData.length;
          });
        const notMerge = forceRefresh || isTypeChanged || dataShapeChanged;

        if (isTypeChanged && this.myChart) {
          this.myChart.clear(); // 切换类型时彻底清空，防止图形重合
        }

        this.myChart.setOption(option, notMerge);

        this.lastOption = option;
        this.lastChartType = requestedType;
      } catch (error) {
        console.error(`❌ 更新图表失败: ${this.chartType}`, error);
        if (!this.lastOption || forceRefresh) {
          this.myChart.setOption(this.getEmptyChartOption(`加载失败: ${error.message || '网络异常'}`), true);
        }
      } finally {
        this.loading = false;
      }
    },

    async processAssetOption(targetAccount) {
      const positions = targetAccount.positions || [];
      if (positions.length === 0) {
        return this.getEmptyChartOption('该账户目前没有持仓数据');
      }

      // 按市值降序排序
      positions.sort((a, b) => b.market_value - a.market_value);

      // 计算总市值以便后面计算占比
      const totalMarketValue = positions.reduce((sum, p) => sum + Number(p.market_value), 0);

      const stockNames = [];
      const stockCodes = [];
      const marketValues = [];
      const percentages = [];

      positions.forEach(pos => {
        stockNames.push(pos.stock_name || '未知');
        stockCodes.push(pos.stock_code);
        const val = Number(pos.market_value) || 0;
        marketValues.push(val);
        // 计算并保留两位小数的占比
        percentages.push(totalMarketValue > 0 ? ((val / totalMarketValue) * 100).toFixed(2) : '0.00');
      });

      return {
        tooltip: {
          show: true,
          trigger: 'axis',
          triggerOn: 'mousemove|click',
          renderMode: 'html',
          appendToBody: true,
          confine: false,
          z: 99999,
          axisPointer: {
            type: 'cross',
            snap: true,
            label: {
              show: true,
              backgroundColor: '#2b5876',
              color: '#ffffff'
            }
          },
          backgroundColor: 'rgba(26, 31, 58, 0.95)',
          borderColor: 'rgba(64, 224, 255, 0.3)',
          textStyle: { color: '#ffffff' },
          formatter: (params) => {
            if (!params || params.length === 0) return '';
            const idx = params[0].dataIndex;
            return `${stockNames[idx]} (${stockCodes[idx]})<br/>市值: ${marketValues[idx].toLocaleString()} 元<br/>占比: ${percentages[idx]}%`;
          }
        },
        grid: {
          left: '80',
          right: '30',
          bottom: '80', // 留够空间给双行标签和 dataZoom
          top: '50',
          containLabel: true
        },
        dataZoom: [
          {
            type: 'slider',
            show: true,
            xAxisIndex: [0],
            startValue: 0,
            endValue: 4, // 默认显示前5个柱子
            bottom: 5,
            height: 15,
            borderColor: 'rgba(64, 224, 255, 0.3)',
            fillerColor: 'rgba(64, 224, 255, 0.2)',
            handleStyle: { color: '#40e0ff' },
            textStyle: { color: '#fff' }
          },
          {
            type: 'inside',
            xAxisIndex: [0],
            zoomOnMouseWheel: false,
            moveOnMouseWheel: true
          }
        ],
        xAxis: {
          type: 'category',
          show: true,
          data: stockNames,
          axisLabel: {
            show: true,
            interval: 0,
            rotate: 0, // 水平显示，配合 dataZoom 分页
            formatter: (value, index) => {
              const code = stockCodes[index] || '';
              // 根据要求：在股票代码下方显示股票名
              // 第一行显示股票代码 (蓝绿色)，第二行显示股票名称 (白色)
              return `{code|${code}}
{name|${value}}`;
            },
            rich: {
              code: {
                color: '#40e0ff',
                fontSize: 10,
                fontWeight: 'bold',
                align: 'center',
                lineHeight: 18
              },
              name: {
                color: '#ffffff',
                fontSize: 11,
                align: 'center',
                lineHeight: 18
              }
            }
          },
          axisLine: { show: true, lineStyle: { color: 'rgba(64, 224, 255, 0.5)' } },
          axisTick: { show: true }
        },
        yAxis: {
          type: 'value',
          show: true,
          name: '市值(元)',
          nameTextStyle: { color: '#ffffff', fontSize: 11, padding: [0, 0, 10, 0] },
          axisLabel: {
            show: true,
            color: '#ffffff',
            fontSize: 10,
            formatter: (value) => {
              return value.toLocaleString();
            }
          },
          axisLine: { show: true, lineStyle: { color: 'rgba(64, 224, 255, 0.5)' } },
          splitLine: { show: true, lineStyle: { color: 'rgba(255, 255, 255, 0.1)' } }
        },
        series: [
          {
            name: '持仓市值',
            type: 'bar',
            data: marketValues,
            barMaxWidth: 40,
            itemStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#40e0ff' },
                { offset: 1, color: 'rgba(64, 224, 255, 0.2)' }
              ]),
              borderRadius: [4, 4, 0, 0]
            },
            label: {
              show: true,
              position: 'top',
              color: '#ffffff',
              fontSize: 10,
              formatter: (params) => `${percentages[params.dataIndex]}%`
            }
          }
        ]
      };
    },

    async processTimeOption(data) {
      const yearlyData = data.yearly_data || [];
      if (yearlyData.length === 0) return this.getEmptyChartOption('暂无年度对比历史数据');

      const years = yearlyData.map(item => item.year || item.timePeriod || '');
      const totalAssets = yearlyData.map(item => item.totalAssets || 0);
      const returnRates = yearlyData.map(item => item.returnRate || 0);
      const returnLabel = data.return_calculation_mode === 'SIMULATED' ? '模拟收益率' : '快照收益率';

      return {
        tooltip: {
          show: true,
          trigger: 'axis',
          triggerOn: 'mousemove|click',
          renderMode: 'html',
          appendToBody: true,
          confine: false,
          z: 99999,
          axisPointer: {
            type: 'cross',
            snap: true,
            label: {
              show: true,
              backgroundColor: '#2b5876',
              color: '#ffffff'
            }
          },
          backgroundColor: 'rgba(26, 31, 58, 0.95)',
          borderColor: 'rgba(64, 224, 255, 0.3)',
          textStyle: { color: '#ffffff' }
        },
        legend: {
          data: ['总资产', returnLabel],
          textStyle: { color: '#ffffff', fontSize: 12 },
          bottom: '10'
        },
        grid: {
          left: '80',
          right: '80',
          bottom: '60',
          top: '60',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          data: years,
          axisLabel: { color: '#ffffff', fontSize: 11 },
          axisLine: { lineStyle: { color: 'rgba(64, 224, 255, 0.3)' } },
          axisTick: { show: false }
        },
        yAxis: [
          {
            type: 'value',
            name: '总资产(元)',
            nameTextStyle: { color: '#ffffff', fontSize: 11, padding: [0, 0, 10, 0] },
            axisLabel: {
              color: '#ffffff',
              fontSize: 10,
              formatter: (value) => value >= 10000 ? (value / 10000).toFixed(1) + '万' : value
            },
            axisLine: { show: true, lineStyle: { color: 'rgba(64, 224, 255, 0.3)' } },
            splitLine: { lineStyle: { color: 'rgba(255, 255, 255, 0.1)' } }
          },
          {
            type: 'value',
            name: '回报率(%)',
            nameTextStyle: { color: '#ffffff', fontSize: 11, padding: [0, 0, 10, 0] },
            position: 'right',
            axisLabel: { color: '#ffffff', fontSize: 10, formatter: '{value}%' },
            axisLine: { show: true, lineStyle: { color: '#ff6b6b' } },
            splitLine: { show: false }
          }
        ],
        series: [
          {
            name: '总资产',
            type: 'bar',
            data: totalAssets,
            barMaxWidth: 35,
            itemStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: '#40e0ff' },
                { offset: 1, color: 'rgba(64, 224, 255, 0.2)' }
              ]),
              borderRadius: [4, 4, 0, 0]
            }
          },
          {
            name: returnLabel,
            type: 'line',
            data: returnRates,
            yAxisIndex: 1,
            smooth: true,
            symbol: 'circle',
            symbolSize: 8,
            itemStyle: { color: '#ff6b6b' },
            lineStyle: { width: 3, color: '#ff6b6b' },
            areaStyle: {
              color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(255, 107, 107, 0.3)' },
                { offset: 1, color: 'transparent' }
              ])
            }
          }
        ]
      };
    },

    async processRegionOption(data) {
      const regionData = data.region_data || [];
      if (regionData.length === 0) return this.getEmptyChartOption('该账户暂无地区分布数据');

      const pieData = regionData.map(item => ({ name: item.region, value: item.totalAssets }));

      return {
        tooltip: { trigger: 'item' },
        legend: { bottom: '0', textStyle: { color: '#fff' } },
        series: [
          {
            type: 'pie',
            radius: ['40%', '70%'],
            center: ['50%', '45%'],
            data: pieData,
            label: { show: true, color: '#fff', formatter: '{b}: {d}%' }
          }
        ]
      };
    },

    handleResize() {
      if (this.myChart) this.myChart.resize();
    },

    getEmptyChartOption(message = '暂无数据') {
      return {
        title: {
          show: true,
          text: message,
          left: 'center',
          top: 'center',
          textStyle: { color: '#aaaaaa', fontSize: 14, fontWeight: 'normal' }
        },
        xAxis: { show: false },
        yAxis: { show: false },
        series: []
      };
    }
  }
};
</script>

<style scoped>
.comparison-chart {
  width: 100%;
  height: 100%;
}
.chart-container {
  width: 100%;
  height: 100%;
  min-height: 250px;
}
:deep(.el-loading-mask) {
  background-color: rgba(12, 20, 38, 0.6) !important;
}
</style>
