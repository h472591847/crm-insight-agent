<script setup lang="ts">
/**
 * 功能模式选择组件
 * 知识库 / 联网搜索入口由集中开关控制，关闭时不渲染入口按钮。
 */
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { FEATURES } from '@/config/features'
import type { ChatMode } from '@/types'

const chatStore = useChatStore()

const showKnowledge = FEATURES.knowledgeMode
const showInternet = FEATURES.internetMode

// 仅剩一个可选模式时，收窄为单列“当前模式”展示，避免半宽按钮留白
const visibleModeCount = computed(
  () => 1 + Number(showKnowledge) + Number(showInternet),
)
const sectionTitle = computed(() => (visibleModeCount.value > 1 ? '功能模式' : '当前模式'))

/**
 * 切换模式
 */
function handleModeSwitch(mode: ChatMode) {
  if (chatStore.isGenerating) return
  chatStore.switchMode(mode)
}
</script>

<template>
  <div class="section-box">
    <div class="section-title">{{ sectionTitle }}</div>
    <div
      class="mode-selector-grid"
      :class="{ 'mode-selector-grid--single': visibleModeCount === 1 }"
    >
      <button
        :class="['mode-button', { selected: chatStore.activeMode === 'chat' }]"
        :disabled="chatStore.isGenerating"
        @click="handleModeSwitch('chat')"
      >
        标准对话
      </button>
      <button
        v-if="showKnowledge"
        :class="['mode-button', { selected: chatStore.activeMode === 'knowledge' }]"
        :disabled="chatStore.isGenerating"
        @click="handleModeSwitch('knowledge')"
      >
        知识库
      </button>
      <button
        v-if="showInternet"
        :class="['mode-button', { selected: chatStore.activeMode === 'internet' }]"
        :disabled="chatStore.isGenerating"
        @click="handleModeSwitch('internet')"
      >
        联网搜索
      </button>
    </div>
  </div>
</template>

<style scoped>
.section-box {
  background: #fff;
  padding: 15px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.section-title {
  font-weight: bold;
  margin-bottom: 15px;
  color: var(--primary-color);
}

.mode-selector-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.mode-selector-grid--single {
  grid-template-columns: 1fr;
}

.mode-button {
  padding: 10px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  text-align: center;
  transition: all 0.3s;
  font-size: 14px;
}

.mode-button:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
}

.mode-button.selected {
  background-color: var(--primary-color);
  color: white;
  border-color: var(--primary-color);
  font-weight: bold;
}

/* 单模式时用轻量选中态 + 状态点，避免整块实心蓝按钮压过会话区 */
.mode-selector-grid--single .mode-button {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  padding: 10px 14px;
  text-align: left;
}

.mode-selector-grid--single .mode-button.selected {
  background: rgba(74, 144, 226, 0.1);
  color: #2f6fb5;
  border-color: rgba(74, 144, 226, 0.35);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(74, 144, 226, 0.08);
}

.mode-selector-grid--single .mode-button.selected::before {
  content: '';
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary-color);
  box-shadow: 0 0 0 3px rgba(74, 144, 226, 0.16);
  flex-shrink: 0;
}

.mode-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.mode-button:disabled:hover {
  border-color: var(--border-color);
  color: inherit;
}
</style>
