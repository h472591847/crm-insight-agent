<script setup lang="ts">
/**
 * 追问卡片组件 —— 当模型返回多个候选门店/会员时弹出。
 *
 * 设计理念：与侧边栏 active 态的蓝色渐变形成色彩呼应，
 * 但卡片本身是"交互确认"性质，需要更强的视觉锚点——
 * 左侧 deep-indigo accent bar + 顶部 soft gradient header + card shadow lift。
 */
import { computed } from 'vue'
import type { ClarifyCandidate, ClarifyData, ClarifySelection } from '@/types'

const props = defineProps<{
  data: ClarifyData
  /** bot 消息 content（用于自动发送时的前缀提示） */
  botMessage?: string
}>()

const emit = defineEmits<{
  select: [selection: ClarifySelection]
}>()

/** 编码 pill 只在门店/会员这类业务编码下展示，口径歧义的机器值不暴露给用户 */
const showCode = computed(() => props.data.type === 'station' || props.data.type === 'member')

/** 候选无法还原时的兜底卡：只提示补充说明，不展示空列表 */
const isEmpty = computed(() => props.data.candidates.length === 0)

const title = computed(() => {
  if (props.data.question) return props.data.question
  const typeMap: Record<string, string> = {
    station: '请选择门店',
    member: '请选择会员',
  }
  return typeMap[props.data.type] ?? '还需要确认一下口径'
})

const hint = computed(() => {
  if (isEmpty.value) return '选项未能加载，请直接补充说明'
  const n = props.data.candidates.length
  if (props.data.type === 'ambiguity') return `共 ${n} 个口径，点击选择后继续查询`
  const typeWord = props.data.type === 'member' ? '会员' : '门店'
  return `共 ${n} 个候选${typeWord}，点击选择即可继续查询`
})

function handleSelect(cand: ClarifyCandidate) {
  emit('select', {
    code: cand.code,
    label: cand.label,
    meta: cand.meta,
    type: props.data.type,
    question: props.data.question,
  })
}
</script>

<template>
  <div class="clarify-card" role="dialog" aria-label="追问选择卡片">
    <!-- 顶部 soft gradient header -->
    <div class="clarify-header">
      <div class="clarify-header__icon">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"></circle>
          <line x1="9" y1="9" x2="9.01" y2="9"></line>
          <line x1="15" y1="9" x2="15.01" y2="9"></line>
          <path d="M9 15c1.5 1.5 4.5 1.5 6 0"></path>
        </svg>
      </div>
      <div class="clarify-header__text">
        <div class="clarify-header__title">{{ title }}</div>
        <div class="clarify-header__hint">{{ hint }}</div>
      </div>
    </div>

    <!-- 候选列表 -->
    <ul v-if="!isEmpty" class="clarify-list">
      <li
        v-for="(cand, index) in data.candidates"
        :key="`${cand.code}-${index}`"
        class="clarify-item"
        @click="handleSelect(cand)"
        tabindex="0"
        @keydown.enter="handleSelect(cand)"
      >
        <div v-if="showCode" class="clarify-item__code">{{ cand.code }}</div>
        <div class="clarify-item__body">
          <div class="clarify-item__label">{{ cand.label }}</div>
          <div v-if="cand.meta" class="clarify-item__meta">{{ cand.meta }}</div>
        </div>
        <div class="clarify-item__arrow">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
               stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 18 15 12 9 6"></polyline>
          </svg>
        </div>
      </li>
    </ul>
    <!-- 兜底：没有可还原的选项时给出明确指引，避免只剩“请选择：” -->
    <div v-else class="clarify-empty">
      没有获取到可选项，请直接补充说明你想按哪种口径查询，例如“按门店级别看”。
    </div>
  </div>
</template>

<style scoped>
.clarify-card {
  margin-top: 10px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid #e2e8f0;
  box-shadow: 0 4px 16px rgba(30, 58, 138, 0.12), 0 1px 3px rgba(0, 0, 0, 0.04);
  background: #fff;
  max-width: 480px;
  animation: card-in 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes card-in {
  from { opacity: 0; transform: translateY(-6px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0) scale(1); }
}

/* Header —— deep-indigo gradient 呼应 sidebar active */
.clarify-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: linear-gradient(135deg, #4a90e2 0%, #3a6fbf 100%);
  color: #fff;
  position: relative;
}

.clarify-header::after {
  /* 微妙纹理 —— 渐变上叠加极淡的斜线网纹 */
  content: '';
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
    45deg,
    rgba(255, 255, 255, 0.025) 0,
    rgba(255, 255, 255, 0.025) 1px,
    transparent 1px,
    transparent 8px
  );
  pointer-events: none;
}

.clarify-header__icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.2);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  z-index: 1;
}

.clarify-header__text { z-index: 1; min-width: 0; }

.clarify-header__title {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.3px;
}

.clarify-header__hint {
  font-size: 11px;
  opacity: 0.75;
  margin-top: 2px;
}

/* 候选列表 */
.clarify-list {
  list-style: none;
  padding: 6px;
  margin: 0;
  /* 候选较多时卡片内滚动，避免把消息区撑得过长 */
  max-height: 320px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* 兜底提示 */
.clarify-empty {
  padding: 14px 16px 16px;
  font-size: 13px;
  line-height: 1.6;
  color: #6b7280;
  background: #f8fafc;
}

.clarify-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.18s cubic-bezier(0.4, 0, 0.2, 1);
  border-left: 3px solid transparent;
  user-select: none;
  outline: none;
}

.clarify-item:hover,
.clarify-item:focus-visible {
  background: linear-gradient(90deg, #eef3fb 0%, #f8faff 100%);
  border-left-color: #4a90e2;
  transform: translateX(2px);
}

.clarify-item:active {
  transform: translateX(2px) scale(0.99);
  background: #e1eaf5;
}

/* 左侧编码 pill */
.clarify-item__code {
  font-size: 11px;
  font-family: 'SF Mono', Consolas, 'Courier New', monospace;
  font-weight: 600;
  color: #3a6fbf;
  background: #eef3fb;
  padding: 3px 7px;
  border-radius: 4px;
  letter-spacing: 0.3px;
  flex-shrink: 0;
}

.clarify-item__body { flex: 1; min-width: 0; }

.clarify-item__label {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.3;
}

.clarify-item__meta {
  font-size: 11px;
  color: #9ca3af;
  margin-top: 2px;
}

.clarify-item__arrow {
  color: #c0c4cc;
  transition: color 0.18s, transform 0.18s;
  flex-shrink: 0;
}

.clarify-item:hover .clarify-item__arrow,
.clarify-item:focus-visible .clarify-item__arrow {
  color: #4a90e2;
  transform: translateX(2px);
}
</style>
