<template>
  <div class="display-page">
    <div class="grid-background"></div>

    <div class="content-wrapper">
      <div class="left-section glass-panel">
        <div class="panel-header">
          <div class="header-line"></div>
          <h3 class="panel-title">
            <i class="title-icon"></i>
            资产展示
          </h3>
          <div class="status-indicator"></div>
        </div>
        <div class="panel-content">
          <AssetDisplayModule />
        </div>
        <div class="panel-footer">
          <div class="footer-decoration"></div>
        </div>
      </div>

      <div ref="rightSection" class="right-section" :style="rightSectionStyle">
        <div class="strategy-intro-panel glass-panel">
          <div class="panel-header">
            <div class="header-line"></div>
            <h3 class="panel-title">
              <i class="title-icon"></i>
              策略简介
            </h3>
            <div class="status-indicator"></div>
          </div>
          <div class="panel-content">
            <StrategyExecution />
          </div>
          <div class="panel-footer">
            <div class="footer-decoration"></div>
          </div>
        </div>

        <div
          class="panel-resize-handle"
          :class="{ dragging: isDraggingPanels }"
          @mousedown="startPanelResize"
        >
          <div class="handle-track"></div>
          <div class="handle-grip">
            <span></span>
            <span></span>
            <span></span>
          </div>
        </div>

        <div
          v-if="isDraggingPanels"
          class="drag-preview-line"
          :style="{ top: `${dragPreviewTop}px` }"
        >
          <div class="drag-preview-badge">{{ Math.round(introPanelHeight || 0) }}px</div>
        </div>

        <div class="strategy-result-panel glass-panel">
          <div class="panel-header">
            <div class="header-line"></div>
            <h3 class="panel-title">
              <i class="title-icon"></i>
              策略执行结果图
            </h3>
            <div class="status-indicator"></div>
          </div>
          <div class="panel-content">
            <StrategyExecutionResult />
          </div>
          <div class="panel-footer">
            <div class="footer-decoration"></div>
          </div>
        </div>
      </div>
    </div>

    <div class="floating-decorations">
      <div class="decoration-orb orb-1"></div>
      <div class="decoration-orb orb-2"></div>
      <div class="decoration-orb orb-3"></div>
    </div>
  </div>
</template>

<script>
import AssetDisplayModule from '@/components/layout/AssetDisplayModule.vue'
import StrategyExecution from '@/components/layout/StrategyExecution.vue'
import StrategyExecutionResult from '@/components/layout/StrategyExecutionResult.vue'

const PANEL_STORAGE_KEY = 'display-page:intro-panel-height'
const DIVIDER_HEIGHT = 18
const MIN_INTRO_HEIGHT = 280
const MIN_RESULT_HEIGHT = 360

export default {
  name: 'DisplayPage',
  components: {
    AssetDisplayModule,
    StrategyExecution,
    StrategyExecutionResult
  },
  data() {
    return {
      introPanelHeight: null,
      isDraggingPanels: false,
      dragPreviewTop: 0,
      resizeFrameId: null,
      pendingPointerY: null
    }
  },
  computed: {
    rightSectionStyle() {
      if (this.isMobileLayout) {
        return {}
      }

      const introHeight = this.introPanelHeight || 420
      return {
        gridTemplateRows: `${introHeight}px ${DIVIDER_HEIGHT}px minmax(${MIN_RESULT_HEIGHT}px, 1fr)`
      }
    },
    isMobileLayout() {
      return typeof window !== 'undefined' && window.innerWidth <= 1200
    }
  },
  mounted() {
    this.restorePanelHeight()
    this.$nextTick(() => {
      this.ensureValidPanelHeights()
    })
    window.addEventListener('resize', this.handleWindowResize)
  },
  beforeUnmount() {
    window.removeEventListener('resize', this.handleWindowResize)
    this.stopPanelResize()
    if (this.resizeFrameId) {
      cancelAnimationFrame(this.resizeFrameId)
      this.resizeFrameId = null
    }
  },
  methods: {
    restorePanelHeight() {
      const savedHeight = Number(localStorage.getItem(PANEL_STORAGE_KEY))
      if (Number.isFinite(savedHeight) && savedHeight > 0) {
        this.introPanelHeight = savedHeight
      }
    },
    handleWindowResize() {
      this.ensureValidPanelHeights()
    },
    ensureValidPanelHeights() {
      if (this.isMobileLayout) {
        return
      }

      const container = this.$refs.rightSection
      if (!container) {
        return
      }

      const availableHeight = container.clientHeight - DIVIDER_HEIGHT
      const maxIntroHeight = Math.max(MIN_INTRO_HEIGHT, availableHeight - MIN_RESULT_HEIGHT)
      const defaultHeight = Math.round(availableHeight * 0.42)
      const nextHeight = this.introPanelHeight ?? defaultHeight
      this.introPanelHeight = Math.min(Math.max(nextHeight, MIN_INTRO_HEIGHT), maxIntroHeight)
    },
    startPanelResize(event) {
      if (this.isMobileLayout) {
        return
      }

      event.preventDefault()
      this.ensureValidPanelHeights()
      this.isDraggingPanels = true
      this.dragPreviewTop = (this.introPanelHeight || MIN_INTRO_HEIGHT) + DIVIDER_HEIGHT / 2
      document.body.style.cursor = 'row-resize'
      window.addEventListener('mousemove', this.handlePanelResize)
      window.addEventListener('mouseup', this.stopPanelResize)
    },
    handlePanelResize(event) {
      this.pendingPointerY = event.clientY
      if (this.resizeFrameId) {
        return
      }

      this.resizeFrameId = requestAnimationFrame(() => {
        this.resizeFrameId = null
        this.applyPanelResize()
      })
    },
    applyPanelResize() {
      const container = this.$refs.rightSection
      if (!container || this.pendingPointerY === null) {
        return
      }

      const rect = container.getBoundingClientRect()
      const availableHeight = rect.height - DIVIDER_HEIGHT
      const maxIntroHeight = Math.max(MIN_INTRO_HEIGHT, availableHeight - MIN_RESULT_HEIGHT)
      const nextHeight = this.pendingPointerY - rect.top
      this.introPanelHeight = Math.min(Math.max(nextHeight, MIN_INTRO_HEIGHT), maxIntroHeight)
      this.dragPreviewTop = this.introPanelHeight + DIVIDER_HEIGHT / 2
    },
    stopPanelResize() {
      if (!this.isDraggingPanels) {
        return
      }

      this.isDraggingPanels = false
      document.body.style.cursor = ''
      window.removeEventListener('mousemove', this.handlePanelResize)
      window.removeEventListener('mouseup', this.stopPanelResize)
      this.pendingPointerY = null

      if (Number.isFinite(this.introPanelHeight)) {
        localStorage.setItem(PANEL_STORAGE_KEY, String(Math.round(this.introPanelHeight)))
      }
    }
  }
}
</script>

<style scoped>
.display-page {
  position: relative;
  width: 100%;
  height: calc(100vh - 140px);
  padding: 20px;
  box-sizing: border-box;
  overflow: auto;
  z-index: 2;
}

.grid-background {
  position: absolute;
  inset: 0;
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

.content-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  gap: 20px;
  z-index: 3;
}

.glass-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  background: rgba(12, 20, 38, 0.4);
  backdrop-filter: blur(20px);
  border: 1px solid rgba(64, 224, 255, 0.3);
  border-radius: 12px;
  box-shadow:
    0 8px 32px rgba(0, 0, 0, 0.3),
    0 0 40px rgba(64, 224, 255, 0.1),
    inset 0 1px 0 rgba(255, 255, 255, 0.1);
  overflow: hidden;
}

.left-section {
  width: 38%;
  min-width: 380px;
  overflow: auto;
}

.right-section {
  width: 62%;
  min-width: 520px;
  display: grid;
  grid-template-rows: minmax(360px, 42%) 18px minmax(460px, 1fr);
  gap: 0;
}

.strategy-intro-panel {
  min-height: 0;
}

.strategy-result-panel {
  min-height: 0;
}

.panel-resize-handle {
  position: relative;
  cursor: row-resize;
  user-select: none;
  display: flex;
  align-items: center;
  justify-content: center;
  will-change: transform;
  touch-action: none;
}

.panel-resize-handle::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: -12px;
  bottom: -12px;
}

.handle-track {
  width: 100%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 0%,
    rgba(64, 224, 255, 0.2) 20%,
    rgba(64, 224, 255, 0.55) 50%,
    rgba(64, 224, 255, 0.2) 80%,
    transparent 100%);
}

.handle-grip {
  position: absolute;
  display: flex;
  gap: 4px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(17, 28, 48, 0.92);
  border: 1px solid rgba(64, 224, 255, 0.24);
  box-shadow: 0 0 16px rgba(64, 224, 255, 0.12);
}

.handle-grip span {
  width: 28px;
  height: 3px;
  border-radius: 999px;
  background: rgba(144, 235, 255, 0.92);
}

.panel-resize-handle:hover .handle-grip,
.panel-resize-handle.dragging .handle-grip {
  border-color: rgba(64, 224, 255, 0.45);
  box-shadow: 0 0 18px rgba(64, 224, 255, 0.2);
}

.drag-preview-line {
  position: absolute;
  left: 0;
  right: 0;
  height: 0;
  pointer-events: none;
  z-index: 6;
}

.drag-preview-line::before {
  content: '';
  position: absolute;
  left: 20px;
  right: 20px;
  top: 0;
  border-top: 2px dashed rgba(125, 225, 255, 0.95);
  box-shadow: 0 0 12px rgba(64, 224, 255, 0.3);
}

.drag-preview-badge {
  position: absolute;
  right: 28px;
  top: -14px;
  padding: 2px 10px;
  border-radius: 999px;
  border: 1px solid rgba(125, 225, 255, 0.55);
  background: rgba(8, 16, 30, 0.92);
  color: #d9f9ff;
  font-size: 11px;
  letter-spacing: 0;
  box-shadow: 0 0 18px rgba(64, 224, 255, 0.16);
}

.panel-header {
  position: relative;
  padding: 15px 20px;
  background: linear-gradient(135deg,
    rgba(64, 224, 255, 0.1) 0%,
    rgba(30, 144, 255, 0.05) 100%);
  border-bottom: 1px solid rgba(64, 224, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #ffffff;
  text-shadow: 0 0 10px rgba(64, 224, 255, 0.5);
}

.title-icon {
  width: 4px;
  height: 24px;
  background: linear-gradient(180deg, #40e0ff, #1e90ff);
  border-radius: 2px;
  box-shadow: 0 0 10px rgba(64, 224, 255, 0.8);
}

.header-line {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 1px;
  background: linear-gradient(90deg,
    transparent 0%,
    rgba(64, 224, 255, 0.8) 50%,
    transparent 100%);
}

.status-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #7bff9e;
  box-shadow: 0 0 12px rgba(123, 255, 158, 0.9);
}

.panel-content {
  flex: 1;
  min-height: 0;
  padding: 12px;
}

.panel-footer {
  height: 2px;
}

.footer-decoration {
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg,
    transparent 0%,
    rgba(64, 224, 255, 0.4) 50%,
    transparent 100%);
}

.floating-decorations {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.decoration-orb {
  position: absolute;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(64, 224, 255, 0.12) 0%, transparent 70%);
}

.orb-1 {
  width: 220px;
  height: 220px;
  top: 10%;
  right: 8%;
}

.orb-2 {
  width: 180px;
  height: 180px;
  bottom: 12%;
  left: 42%;
}

.orb-3 {
  width: 140px;
  height: 140px;
  bottom: 8%;
  right: 10%;
}

@media (max-width: 1200px) {
  .content-wrapper {
    flex-direction: column;
  }

  .left-section,
  .right-section {
    width: 100%;
    min-width: 0;
  }

  .right-section {
    height: auto;
    display: flex;
    flex-direction: column;
    gap: 20px;
  }

  .strategy-intro-panel,
  .strategy-result-panel {
    height: auto;
    min-height: 420px;
  }

  .panel-resize-handle {
    display: none;
  }
}
</style>
