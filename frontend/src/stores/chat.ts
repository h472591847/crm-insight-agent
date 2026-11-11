/**
 * 聊天状态管理 Store
 */

import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { marked } from 'marked'
import { resolveMode } from '@/config/features'
import { parseClarifyMarkdown } from '@/utils/clarify'
import type {
  ChatMessage,
  ChatMode,
  ModelType,
  ImageModelType,
  TTSModelType,
  ASRModelType,
  VideoModelType,
  FrameVideoModelType,
  VideoQuality,
  VideoSize,
  FrameVideoTask,
  ClarifyData,
} from '@/types'

/**
 * 从 markdown 原文中提取追问信息，并移除标记后返回干净的 markdown。
 * 调用时机：SSE finish 时、历史消息 restore 时。
 *
 * 具体解析与兜底逻辑见 @/utils/clarify：
 * 字段名差异、JSON 损坏、块被截断、候选为空等情况都会尽量还原，
 * 还原不出来时返回兜底卡，避免界面上只剩“请选择：”半句话。
 *
 * @param rawMarkdown 模型原始输出（含可能的 <clarify> 标记）
 * @returns html（移除 marker 后 parse 的 HTML）、clarifyList（全部追问卡片）、clarifyData（第一张）
 */
function parseBotMarkdown(rawMarkdown: string): {
  html: string
  clarifyList: ClarifyData[]
  clarifyData: ClarifyData | null
} {
  const { markdown, clarifyList, clarifyData } = parseClarifyMarkdown(rawMarkdown)
  const html = marked.parse(markdown || rawMarkdown) as string
  return { html, clarifyList, clarifyData }
}

/**
 * 把服务端返回的时间值归一化为 epoch 毫秒。
 * 兼容毫秒/秒级数字时间戳与 ISO-8601 字符串；无法解析时返回 undefined（不做"当前时间"兜底）。
 */
function toEpochMs(value: unknown): number | undefined {
  if (value === null || value === undefined || value === '') return undefined
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return undefined
    return value < 1e12 ? value * 1000 : value
  }
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (trimmed === '') return undefined
    const numeric = Number(trimmed)
    if (Number.isFinite(numeric)) return toEpochMs(numeric)
    const parsed = Date.parse(trimmed)
    return Number.isNaN(parsed) ? undefined : parsed
  }
  return undefined
}

// 匿名用户ID：生成一次后长期保存在 localStorage，刷新不变
const USER_ID_STORAGE_KEY = 'crm_agent_uid'
// 当前会话ID：保存在 sessionStorage，按标签页隔离
const SESSION_STORAGE_KEY = 'crm_agent_active_session'

export const useChatStore = defineStore('chat', () => {
  // ========== 状态定义 ==========

  // 聊天消息列表
  const chatList = ref<ChatMessage[]>([])

  // 当前用户ID（匿名，持久化于 localStorage）
  const currentUserName = ref<string>('')

  // 当前会话ID（前端生成，持久化于 sessionStorage，按标签页隔离）
  const sessionId = ref<string>('')

  // 当前机器人消息ID
  const botMsgId = ref<string | null>(null)

  // 当前激活的功能模式（默认标准对话；未开放的模式会被统一回退）
  const activeMode = ref<ChatMode>(resolveMode('chat'))

  // ========== 模型选择 ==========

  // 对话模型
  const selectedModel = ref<ModelType>('deepseek')

  // 图像模型
  const selectedImageModel = ref<ImageModelType>('qwen')

  // TTS 模型
  const selectedTTSModel = ref<TTSModelType>('qwen')

  // ASR 模型
  const selectedASRModel = ref<ASRModelType>('qwen3-asr')

  // 视频模型
  const selectedVideoModel = ref<VideoModelType>('cogvideox-flash')

  // 首尾帧视频模型
  const selectedFrameVideoModel = ref<FrameVideoModelType>('wanx2.1-kf2v-plus')

  // ========== 视频生成参数 ==========

  const selectedVideoQuality = ref<VideoQuality>('speed')
  const selectedVideoSize = ref<VideoSize>('1920x1080')
  const withAudio = ref<boolean>(false)
  const removeWatermark = ref<boolean>(true)

  // ========== 首尾帧视频任务 ==========

  const frameVideoTask = ref<FrameVideoTask>({
    pollingInterval: null,
    taskId: null,
    status: 'idle',
  })

  // 首尾帧文件
  const firstFrameFile = ref<File | null>(null)
  const lastFrameFile = ref<File | null>(null)

  // ========== TTS 状态 ==========

  const isTTSGenerating = ref<boolean>(false)

  // ========== 文本生成态 ==========

  const isGenerating = ref(false)
  let doneStatusTimer: ReturnType<typeof setTimeout> | null = null

  function clearDoneStatusTimer() {
    if (doneStatusTimer !== null) {
      clearTimeout(doneStatusTimer)
      doneStatusTimer = null
    }
  }

  // ========== 语音识别 ==========

  const recognizedText = ref<string | null>(null)

  // ========== 图生文 ==========

  const selectedImageFile = ref<File | null>(null)
  const imagePreview = ref<string | null>(null)

  // ========== Getters ==========

  const chatCount = computed(() => chatList.value.length)
  const isPolling = computed(() => frameVideoTask.value.status === 'polling')

  // ========== Actions ==========

  /**
   * 添加聊天消息
   */
  function addMessage(message: ChatMessage) {
    // 实时消息用本地发送时刻；历史消息由 restoreFromHistory 按服务端字段填充
    chatList.value.push({ createdAt: Date.now(), ...message })
  }

  /**
   * 预创建空助手气泡并进入生成态
   */
  function startTextGeneration(botMsgId: string) {
    isGenerating.value = true
    chatList.value.push({
      content: '',
      chatType: 'bot',
      botMsgId,
      replyStatus: 'streaming',
      createdAt: Date.now(),
    })
  }

  /**
   * 解锁生成态并写入失败文案
   */
  function failTextGeneration(botMsgId: string, errorText = '回复失败，请重试') {
    clearDoneStatusTimer()
    isGenerating.value = false
    const msg = chatList.value.find((m) => m.botMsgId === botMsgId)
    if (msg) {
      msg.content = errorText
      msg.replyStatus = undefined
    }
  }

  /**
   * 仅解锁生成态（SSE 解析失败等场景）
   */
  function unlockGeneration(botMsgId?: string | null) {
    clearDoneStatusTimer()
    isGenerating.value = false
    if (botMsgId) {
      const msg = chatList.value.find((m) => m.botMsgId === botMsgId)
      if (msg) {
        msg.replyStatus = undefined
      }
    }
  }

  /**
   * 更新机器人消息（流式返回时追加内容）
   */
  function updateBotMessage(msgId: string, content: string) {
    const msg = chatList.value.find((m) => m.botMsgId === msgId)
    if (msg) {
      msg.content += content
    } else {
      chatList.value.push({
        content,
        chatType: 'bot',
        botMsgId: msgId,
        replyStatus: 'streaming',
        createdAt: Date.now(),
      })
    }
  }

  /**
   * 完成机器人消息 —— 接收 markdown 原文，内部做 clarify 提取 + parse。
   *
   * @param msgId 对应的 botMsgId
   * @param rawMarkdown 模型原始输出（含可能的 <clarify> 标记，如
   *                    {"count":2,"candidates":[...]} 这种）
   */
  function finishBotMessage(msgId: string, rawMarkdown: string) {
    const msg = chatList.value.find((m) => m.botMsgId === msgId)
    const { html, clarifyList, clarifyData } = parseBotMarkdown(rawMarkdown)
    if (msg) {
      msg.content = html
      msg.rawContent = rawMarkdown
      msg.clarifyData = clarifyData
      msg.clarifyList = clarifyList
      msg.replyStatus = 'done'
      // 助手消息以回复完成时刻为准，与后端持久化时间最接近
      msg.createdAt = Date.now()
    }
    isGenerating.value = false
    clearDoneStatusTimer()
    doneStatusTimer = setTimeout(() => {
      const target = chatList.value.find((m) => m.botMsgId === msgId)
      if (target && target.replyStatus === 'done') {
        target.replyStatus = undefined
      }
      doneStatusTimer = null
    }, 1500)
  }

  /**
   * 切换功能模式
   * 统一走 resolveMode：入口隐藏或存在旧模式残留时回退到标准对话
   */
  function switchMode(mode: ChatMode) {
    activeMode.value = resolveMode(mode)
  }

  /**
   * 生成随机ID
   */
  function generateRandomId(length: number = 12): string {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    return Array.from({ length }, () =>
      chars.charAt(Math.floor(Math.random() * chars.length)),
    ).join('')
  }

  /**
   * 清空聊天记录
   */
  function clearChat() {
    clearDoneStatusTimer()
    chatList.value = []
    isGenerating.value = false
  }

  /**
   * 重置首尾帧任务
   */
  function resetFrameVideoTask() {
    if (frameVideoTask.value.pollingInterval) {
      clearInterval(frameVideoTask.value.pollingInterval)
    }
    frameVideoTask.value = {
      pollingInterval: null,
      taskId: null,
      status: 'idle',
    }
    firstFrameFile.value = null
    lastFrameFile.value = null
  }

  /**
   * 生成会话ID：优先 crypto.randomUUID，不可用时回退随机串
   */
  function generateSessionId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID()
    }
    return generateRandomId(16)
  }

  /**
   * 初始化用户ID：首次生成后写入 localStorage，刷新保持不变。
   * <p>
   * 幂等：已初始化时直接返回，可在任意调用点安全重复调用（组件挂载顺序不可控时的兜底）。
   * 多标签并发首次打开时，A/B 可能各自生成不同 ID 并互相覆盖：
   * 这里在写入后回读 localStorage，采用最终落盘的值，尽量让各标签收敛到同一个 userId。
   * </p>
   *
   * @returns 当前生效的 userId（保证非空）
   */
  function initUserId(): string {
    if (currentUserName.value) return currentUserName.value
    try {
      const stored = localStorage.getItem(USER_ID_STORAGE_KEY)
      if (stored) {
        currentUserName.value = stored
        return stored
      }
    } catch (error) {
      console.warn('读取本地 userId 失败:', error)
    }
    const newUserId = Math.random().toString(36).substring(2, 12)
    try {
      localStorage.setItem(USER_ID_STORAGE_KEY, newUserId)
      // 写入后回读：若其他标签页刚好抢先写入，以自己的写入为准再读一次最终值
      const persisted = localStorage.getItem(USER_ID_STORAGE_KEY)
      currentUserName.value = persisted || newUserId
    } catch (error) {
      console.warn('保存本地 userId 失败:', error)
      currentUserName.value = newUserId
    }
    return currentUserName.value
  }

  /**
   * 初始化会话ID：优先复用当前标签页已保存的会话，否则新建
   */
  function initSessionId() {
    if (sessionId.value) return
    let stored: string | null = null
    try {
      stored = sessionStorage.getItem(SESSION_STORAGE_KEY)
    } catch (error) {
      console.warn('读取本地 sessionId 失败:', error)
    }
    if (stored) {
      sessionId.value = stored
      return
    }
    newSession()
  }

  /**
   * 新建会话：生成新 sessionId、清空当前消息视图。
   * SSE 连接的重建由调用方负责（ChatView）。
   */
  function newSession(): string {
    sessionId.value = generateSessionId()
    try {
      sessionStorage.setItem(SESSION_STORAGE_KEY, sessionId.value)
    } catch (error) {
      console.warn('保存本地 sessionId 失败:', error)
    }
    clearChat()
    botMsgId.value = null
    return sessionId.value
  }

  /**
   * 从后端拉取的历史消息中恢复 chatList。
   * 后端存的是 markdown 原文（AssistantMessage.getText()），而前端 ChatMessages 用 v-html 渲染，
   * 所以 bot 消息必须用 marked.parse 转成 HTML；user 消息走 v-html 也需要 parse（换行、引号等转义）。
   */
  function restoreFromHistory(
    backendMessages: Array<{
      type: string
      content: string
      /** 服务端消息时间：当前后端未返回，后端补齐后前端即可自动展示 */
      createdAt?: unknown
      createTime?: unknown
      timestamp?: unknown
      time?: unknown
    }>,
  ) {
    const result: ChatMessage[] = []
    for (const m of backendMessages) {
      if (!m.content) continue
      // 只认服务端字段；全部缺失时保持 undefined，绝不用当前时间兜底
      const createdAt =
        toEpochMs(m.createdAt) ?? toEpochMs(m.createTime) ?? toEpochMs(m.timestamp) ?? toEpochMs(m.time)
      if (m.type === 'user') {
        result.push({ chatType: 'user', content: m.content, createdAt })
      } else if (m.type === 'assistant') {
        // 后端存的是 markdown 原文（可能含 <clarify> 标记）
        const { html, clarifyList, clarifyData } = parseBotMarkdown(m.content)
        result.push({
          chatType: 'bot',
          content: html,
          rawContent: m.content,
          clarifyData,
          clarifyList,
          createdAt,
        })
      } else if (m.type === 'system') {
        continue
      } else if (m.type === 'tool') {
        result.push({ chatType: 'bot', content: `🔧 ${m.content}`, createdAt })
      }
    }
    chatList.value = result
  }

  return {
    // State
    chatList,
    currentUserName,
    sessionId,
    botMsgId,
    activeMode,
    selectedModel,
    selectedImageModel,
    selectedTTSModel,
    selectedASRModel,
    selectedVideoModel,
    selectedFrameVideoModel,
    selectedVideoQuality,
    selectedVideoSize,
    withAudio,
    removeWatermark,
    frameVideoTask,
    firstFrameFile,
    lastFrameFile,
    isTTSGenerating,
    isGenerating,
    recognizedText,
    selectedImageFile,
    imagePreview,

    // Getters
    chatCount,
    isPolling,

    // Actions
    addMessage,
    startTextGeneration,
    failTextGeneration,
    unlockGeneration,
    updateBotMessage,
    finishBotMessage,
    switchMode,
    generateRandomId,
    clearChat,
    resetFrameVideoTask,
    initUserId,
    initSessionId,
    newSession,
    restoreFromHistory,
  }
})
