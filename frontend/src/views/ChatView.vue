<script setup lang="ts">
/**
 * 聊天主视图
 */
import { onMounted, onBeforeUnmount, ref } from 'vue'
import request from '@/utils/request'
import { useChatStore } from '@/stores/chat'
import { useSSE } from '@/composables'
import ChatHeader from '@/components/ChatHeader.vue'
import ChatSidebar from '@/components/ChatSidebar.vue'
import ChatMessages from '@/components/ChatMessages.vue'
import ChatInput from '@/components/ChatInput.vue'
import RecognizedTextDialog from '@/components/RecognizedTextDialog.vue'
import type { ClarifySelection } from '@/types'

const chatStore = useChatStore()
const { initSSE } = useSSE()

// 用于 ChatInput 的 ref
const chatInputRef = ref<InstanceType<typeof ChatInput>>()

/**
 * 移动端软键盘 / 浏览器工具栏会改变可视视口（visualViewport），
 * 这里把聊天容器的高度与顶部偏移同步到可视视口，避免输入区被键盘遮挡。
 * 桌面端（>900px）清理相关变量，保持原有 95vh 居中布局。
 */
function syncViewportHeight() {
  const viewport = window.visualViewport
  const root = document.documentElement
  if (!viewport || window.innerWidth > 900) {
    root.style.removeProperty('--app-vh')
    root.style.removeProperty('--app-top')
    return
  }
  root.style.setProperty('--app-vh', `${Math.round(viewport.height)}px`)
  root.style.setProperty('--app-top', `${Math.round(viewport.offsetTop)}px`)
}

/**
 * 拉取当前会话的历史消息回填视图（刷新页面后恢复上下文）。
 * 如果会话在 Redis 里没有历史（全新会话）则跳过。
 */
async function restoreCurrentSessionHistory() {
  // 兜底：历史请求前确保 userId 已初始化（幂等），避免空 userId 触发后端 400
  const userId = chatStore.initUserId()
  if (!userId) return
  try {
    const resp = await request.get(`/api/session/${encodeURIComponent(chatStore.sessionId)}/messages`, {
      params: { limit: 50, userId },
    })
    if (resp?.messages?.length) {
      chatStore.restoreFromHistory(resp.messages)
    }
  } catch (err) {
    console.warn('恢复会话历史失败:', err)
  }
}

/**
 * 把追问选项组织成模型能自然理解的中文回复。
 * - 门店 / 会员：中文为主，必要业务编码放在中文括号内，便于模型精确定位
 * - 口径歧义：纯中文，机器编码仍在模型上一轮的 <clarify> 块上下文中，无需回传
 */
function buildClarifyReply(selection: ClarifySelection): string {
  const label = selection.label?.trim()
  const name = label || selection.code
  if (selection.type === 'station') {
    return `请查询“${name}”这个门店的数据（门店编码：${selection.code}）`
  }
  if (selection.type === 'member') {
    return `请查询“${name}”这位会员的数据（会员编码：${selection.code}）`
  }
  const topic = selection.question?.trim()
  const prefix = topic ? `关于“${topic}”` : '这个问题'
  const meta = selection.meta?.trim()
  return `${prefix}，我选择“${name}”${meta ? `（${meta}）` : ''}，请按这个口径查询。`
}

/**
 * 追问卡片用户选候选 → 组织中文后自动发送
 */
function handleClarifySelect(e: Event) {
  const selection = (e as CustomEvent).detail as ClarifySelection | undefined
  if (selection?.code && chatInputRef.value) {
    chatInputRef.value.setTextAndSend(buildClarifyReply(selection))
  }
}

/**
 * 组件挂载时初始化
 */
onMounted(() => {
  // 顺序要求：先 userId，再 sessionId，最后才建立 SSE / 拉历史
  const userId = chatStore.initUserId()
  chatStore.initSessionId()
  initSSE(userId, chatStore.sessionId)
  restoreCurrentSessionHistory()
  document.addEventListener('clarify-select', handleClarifySelect as EventListener)
  syncViewportHeight()
  window.visualViewport?.addEventListener('resize', syncViewportHeight)
  window.visualViewport?.addEventListener('scroll', syncViewportHeight)
})

onBeforeUnmount(() => {
  document.removeEventListener('clarify-select', handleClarifySelect as EventListener)
  window.visualViewport?.removeEventListener('resize', syncViewportHeight)
  window.visualViewport?.removeEventListener('scroll', syncViewportHeight)
})

/**
 * 处理发送识别文本到AI
 */
function handleSendRecognizedText(text: string) {
  console.log('Send to AI:', text)
}
</script>

<template>
  <div class="chat-container">
    <!-- 语音识别结果对话框 -->
    <RecognizedTextDialog @send-to-ai="handleSendRecognizedText" />

    <!-- 页面头部 -->
    <ChatHeader />

    <!-- 主内容区域 -->
    <main class="main-content">
      <!-- 侧边栏 -->
      <ChatSidebar />

      <!-- 聊天面板 -->
      <div class="control-panel">
        <!-- 消息列表 -->
        <ChatMessages />

        <!-- 输入区域 -->
        <ChatInput ref="chatInputRef" />
      </div>
    </main>
  </div>
</template>

<style scoped>
.chat-container {
  width: 90%;
  max-width: 1200px;
  height: 95vh;
  /* 动态视口高度：移动端浏览器工具栏收缩时仍完整可见 */
  height: 95dvh;
  min-height: 0;
  display: flex;
  flex-direction: column;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
  border-radius: 12px;
  overflow: hidden;
  background: #fff;
}

.main-content {
  display: flex;
  flex: 1;
  /* 关键：限制 flex item 的最小高度，否则窄屏纵向排列时会被消息内容撑开 */
  min-height: 0;
  overflow: hidden;
}

.control-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  /* 关键：消息列表 + 输入区同处纵向 flex，必须允许整体被压缩，输入区才不会被挤出视口 */
  min-height: 0;
  /* 允许 flex item 被压缩到比内容更窄，防止 Markdown 表格撑破面板 */
  min-width: 0;
  overflow: hidden;
}

/* 窄屏：容器铺满可视视口，侧边栏改为顶部整宽区块，聊天区优先占据剩余高度 */
@media (max-width: 900px) {
  .chat-container {
    position: fixed;
    left: 0;
    right: 0;
    top: var(--app-top, 0px);
    width: 100%;
    height: 100vh;
    /* 优先使用 visualViewport 同步值，其次动态视口高度 */
    height: var(--app-vh, 100dvh);
    border-radius: 0;
    box-shadow: none;
  }

  .main-content {
    flex-direction: column;
  }
}
</style>
