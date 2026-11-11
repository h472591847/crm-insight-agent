<script setup lang="ts">
/**
 * 聊天侧边栏组件 - 包含会话管理、模式选择（知识库上传受集中开关控制）
 */
import { ref, onMounted, onUnmounted } from 'vue'
import request from '@/utils/request'
import ModeSelector from './ModeSelector.vue'
import KnowledgeUpload from './KnowledgeUpload.vue'
import { useChatStore } from '@/stores/chat'
import { useSSE } from '@/composables'
import { FEATURES } from '@/config/features'

const chatStore = useChatStore()
const { initSSE } = useSSE()

interface SessionMeta {
  conversationId: string
  summary: string
  messageCount: number
}

const conversations = ref<SessionMeta[]>([])
const loading = ref(false)
let refreshTimer: ReturnType<typeof setTimeout> | null = null

function startAutoRefresh() {
  stopAutoRefresh()
  refreshTimer = setInterval(refreshSessions, 10_000)
}
function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

async function refreshSessions() {
  // 子组件的 onMounted 早于父组件执行；这里先同步初始化 userId（幂等），
  // 保证会话列表请求不会把空 userId 发给后端（后端 userId 必填，空值会 400）。
  const userId = chatStore.initUserId()
  if (!userId) {
    conversations.value = []
    return
  }
  loading.value = true
  try {
    const resp = await request.get('/api/session/list', {
      params: { userId },
    })
    if (resp?.sessions) {
      conversations.value = resp.sessions
    }
  } catch (err) {
    console.warn('刷新会话列表失败:', err)
  } finally {
    loading.value = false
  }
}

/**
 * 新建会话：生成新 sessionId → 清空视图 → 重建 SSE 连接
 */
function handleNewSession() {
  chatStore.newSession()
  initSSE(chatStore.initUserId(), chatStore.sessionId)
  refreshSessions()
}

/**
 * 切换到已有会话：更新 sessionId → 重建 SSE → 拉历史消息回填前端视图
 */
async function handleSwitchSession(conversationId: string) {
  if (conversationId === chatStore.sessionId) return
  // 兜底：任何会话请求发出前都确保 userId 已初始化
  const userId = chatStore.initUserId()
  if (!userId) return
  chatStore.sessionId = conversationId
  try {
    sessionStorage.setItem('crm_agent_active_session', conversationId)
  } catch {}
  chatStore.clearChat()
  initSSE(userId, conversationId)

  // 拉取历史消息回填前端视图
  try {
    const resp = await request.get(`/api/session/${encodeURIComponent(conversationId)}/messages`, {
      params: { limit: 50, userId },
    })
    if (resp?.messages?.length) {
      chatStore.restoreFromHistory(resp.messages)
    }
  } catch (err) {
    console.warn('拉取历史消息失败:', err)
  }
}

/**
 * 删除会话记忆（Redis 中删 key）
 */
async function handleDeleteSession(conversationId: string) {
  const sum = conversations.value.find((s) => s.conversationId === conversationId)?.summary || conversationId
  if (!confirm(`确定删除会话"${sum}"的所有记忆吗？此操作不可恢复。`)) return
  // 兜底：删除请求前确保 userId 已初始化
  const userId = chatStore.initUserId()
  if (!userId) return
  try {
    await request.delete(`/api/session/${encodeURIComponent(conversationId)}`, {
      params: { userId },
    })
    if (conversationId === chatStore.sessionId) {
      handleNewSession()
    } else {
      refreshSessions()
    }
  } catch (err) {
    console.warn('删除会话失败:', err)
    alert('删除失败，请重试')
  }
}

/** 会话 ID 显示：取 UUID 前 8 位 + 后 4 位（legacy:xxx 完整显示） */
function shortId(id: string): string {
  if (id.startsWith('legacy:')) return id
  if (id.length <= 12) return id
  return `${id.slice(0, 8)}…${id.slice(-4)}`
}

function isActive(id: string) {
  return id === chatStore.sessionId
}

onMounted(() => {
  refreshSessions()
  startAutoRefresh()
})
onUnmounted(() => {
  stopAutoRefresh()
})
</script>

<template>
  <aside class="sidebar">
    <!-- 会话管理区 -->
    <div class="session-section">
      <div class="session-header">
        <span class="session-title">会话</span>
        <button class="btn-new" @click="handleNewSession" title="新建会话">
          ＋ 新建
        </button>
      </div>
      <div class="session-list">
        <div v-if="conversations.length === 0 && !loading" class="empty-hint">
          暂无会话
        </div>
        <div
          v-for="s in conversations"
          :key="s.conversationId"
          class="session-item"
          :class="{ active: isActive(s.conversationId) }"
          @click="handleSwitchSession(s.conversationId)"
        >
          <div class="session-main">
            <div class="session-summary">{{ s.summary || shortId(s.conversationId) }}</div>
            <div class="session-meta">
              <span class="session-id" :title="s.conversationId">{{ shortId(s.conversationId) }}</span>
              <span class="session-count">{{ s.messageCount }} 条</span>
            </div>
          </div>
          <button
            class="btn-delete"
            title="删除此会话记忆"
            @click.stop="handleDeleteSession(s.conversationId)"
          >🗑</button>
        </div>
      </div>
    </div>

    <hr class="divider" />

    <!-- 功能模式选择 -->
    <ModeSelector />

    <!-- 知识库文档上传：集中开关关闭时不渲染，避免留下不可用控件 -->
    <KnowledgeUpload v-if="FEATURES.knowledgeMode" />
  </aside>
</template>

<style scoped>
.sidebar {
  width: 320px;
  background: var(--secondary-color);
  padding: 16px;
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}

.sidebar::-webkit-scrollbar { width: 6px; }
.sidebar::-webkit-scrollbar-track { background: #f1f1f1; border-radius: 3px; }
.sidebar::-webkit-scrollbar-thumb { background: #ccc; border-radius: 3px; }
.sidebar::-webkit-scrollbar-thumb:hover { background: #999; }

.session-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.session-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.session-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.btn-new {
  padding: 4px 12px;
  font-size: 12px;
  background: var(--primary-color, #409eff);
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}
.btn-new:hover { background: var(--primary-hover, #66b1ff); }

.session-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 260px;
  overflow-y: auto;
}

.empty-hint {
  font-size: 12px;
  color: #999;
  padding: 8px 4px;
  text-align: center;
}

.session-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  font-size: 13px;
  color: var(--text-secondary, #555);
  position: relative;
  border-left: 3px solid transparent;
}
.session-item:hover {
  background: rgba(74, 144, 226, 0.08);
  transform: translateX(2px);
}
.session-item.active {
  background: linear-gradient(135deg, #4a90e2 0%, #357abd 100%);
  color: #fff;
  font-weight: 600;
  border-left-color: #1e5aa8;
  box-shadow: 0 2px 8px rgba(74, 144, 226, 0.35);
}

.session-main {
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}

.session-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 1.3;
}

.session-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #999;
  margin-top: 3px;
}

.session-item.active .session-meta { color: rgba(255, 255, 255, 0.7); }

.session-id {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-count { flex-shrink: 0; }

.btn-delete {
  opacity: 0;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  padding: 4px 6px;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  flex-shrink: 0;
  color: inherit;
  line-height: 1;
}
.session-item:hover .btn-delete { opacity: 0.55; }
.session-item.active .btn-delete { opacity: 0.7; color: #fff; }
.btn-delete:hover {
  opacity: 1 !important;
  background: rgba(245, 108, 108, 0.15);
  border-color: rgba(245, 108, 108, 0.4);
  color: #f56c6c !important;
  transform: scale(1.15);
}

.divider {
  border: none;
  border-top: 1px solid var(--border-color, #eee);
  margin: 0;
}

/* 窄屏：侧边栏转为顶部整宽区域，避免 320px 固定宽度挤压聊天面板 */
@media (max-width: 900px) {
  .sidebar {
    width: 100%;
    /* 窄屏下侧边栏只占顶部固定比例，把剩余高度留给消息区与输入区 */
    max-height: 30vh;
    max-height: 30dvh;
    min-height: 0;
    padding: 12px 16px;
    gap: 12px;
    border-right: none;
    border-bottom: 1px solid var(--border-color);
    flex: 0 0 auto;
  }

  .session-list {
    max-height: 110px;
  }
}
</style>
