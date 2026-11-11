<script setup lang="ts">
/**
 * 聊天输入区域组件
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useScrollToBottom } from '@/composables'
import { doChat, ragSearch, internetSearch } from '@/api/chat'
import {
  generateImage,
  generateTTS,
  imageDesc,
  generateVideo,
  submitGenerateVideoFromFrames,
  getVideoTaskStatus,
} from '@/api/media'
import { ElMessage } from 'element-plus'
import VideoControls from './VideoControls.vue'
import FrameVideoControls from './FrameVideoControls.vue'
import ImageDescUpload from './ImageDescUpload.vue'

const chatStore = useChatStore()
const { scrollToBottom } = useScrollToBottom()

const userInput = ref<string>('')
const inputRef = ref<HTMLTextAreaElement | null>(null)

// 输入框高度上限兜底值（px），实际优先取 CSS 中的 max-height
const MAX_INPUT_HEIGHT_FALLBACK = 120

/**
 * 输入框随内容自动增高：单行起步，达到上限后自身滚动，
 * 避免多行文本无限撑高把消息区挤没。
 */
function autoResizeInput() {
  const el = inputRef.value
  if (!el) return
  const cssMax = parseFloat(getComputedStyle(el).maxHeight)
  const limit = Number.isFinite(cssMax) ? cssMax : MAX_INPUT_HEIGHT_FALLBACK
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, limit)}px`
  el.style.overflowY = el.scrollHeight > limit ? 'auto' : 'hidden'
}

watch(userInput, () => nextTick(autoResizeInput))

// 展示层用：任一生成任务进行中即视为忙碌态
const isBusy = computed(
  () => chatStore.isGenerating || chatStore.isTTSGenerating || chatStore.isPolling,
)

// 走 SSE 流式的文字模式
const TEXT_STREAM_MODES = ['chat', 'knowledge', 'internet'] as const

// finish 到达余量：SSE 帧与 HTTP 响应走两条连接，留一点时间让 finish 先到，避免顺序颠倒导致误判
const FINISH_GRACE_MS = 1500

/**
 * HTTP 返回后的兜底解锁。
 *
 * 为什么需要：聊天 POST 在后端跑完整个生成流程后才返回，返回即代表服务端已经尝试投递 finish。
 * 此时前端仍是生成态，说明 finish 在链路中丢了（通道被判死后 sendMsg 静默返回、断网重连、后端重启等），
 * 而 isGenerating 只有 finish 一个解锁入口，不兜底就会永久停在「思考中」。
 *
 * 为什么只解锁不判失败：回答通常已在服务端生成并写入记忆，刷新即可看到，
 * 这里只解除等待态，避免用"生成失败"误导用户。
 * finish 若稍后才到，finishBotMessage 仍会按 botMsgId 补全内容，不会被这次解锁挡住。
 */
function unlockIfFinishMissing(botMsgId: string) {
  setTimeout(() => {
    if (!chatStore.isGenerating) return
    chatStore.unlockGeneration(botMsgId)
    ElMessage.warning('回答已生成但推送中断，请刷新查看')
  }, FINISH_GRACE_MS)
}

/**
 * 处理聊天发送
 */
async function handleSend() {
  if (chatStore.isGenerating) return

  const input = userInput.value.trim()

  // 验证输入（首尾帧视频可以没有文本输入）
  if (input === '' && chatStore.activeMode !== 'frameVideo') {
    return
  }

  const mode = chatStore.activeMode
  const isTextStream = (TEXT_STREAM_MODES as readonly string[]).includes(mode)

  // 文字流式模式：乐观清空输入、预创建助手气泡并进入生成态
  if (isTextStream) {
    userInput.value = ''
    chatStore.addMessage({ content: input, chatType: 'user' })
    // 先固定本地 botMsgId：finish 到达后会把 store 里的置空，成功分支不能再从 store 取
    const botMsgId = chatStore.generateRandomId(12)
    chatStore.botMsgId = botMsgId
    chatStore.startTextGeneration(botMsgId)
    scrollToBottom('chat-messages')
    try {
      if (mode === 'chat') await handleChat(input)
      else if (mode === 'knowledge') await handleKnowledge(input)
      else await handleInternet(input)
      // 三个发起入口（发送 / 追问卡片 / 重试）都汇到这里，兜底只写一处
      unlockIfFinishMissing(botMsgId)
    } catch (error) {
      console.error('Chat error:', error)
      chatStore.failTextGeneration(botMsgId)
      ElMessage.error('回复失败，请重试')
      chatStore.botMsgId = null
      inputRef.value?.focus()
    }
    return
  }

  // 添加用户消息
  if (input) {
    chatStore.addMessage({ content: input, chatType: 'user' })
    scrollToBottom('chat-messages')
  }

  // 生成机器人消息 ID
  chatStore.botMsgId = chatStore.generateRandomId(12)

  // 根据不同模式调用不同的 API
  try {
    switch (chatStore.activeMode) {
      case 'image':
        await handleImageGenerate(input)
        break
      case 'tts':
        await handleTTS(input)
        break
      case 'imageDesc':
        await handleImageDesc(input)
        break
      case 'video':
        await handleVideoGenerate(input)
        break
      case 'frameVideo':
        await handleFrameVideo(input)
        break
    }
  } catch (error) {
    console.error('Chat error:', error)
  }

  // 清空输入框
  userInput.value = ''
}

/**
 * 标准对话
 */
async function handleChat(message: string) {
  const request = {
    currentUserName: chatStore.initUserId(),
    message,
    botMsgId: chatStore.botMsgId!,
    modelName: chatStore.selectedModel,
    sessionId: chatStore.sessionId,
  }
  await doChat(request)
}

/**
 * 知识库搜索
 */
async function handleKnowledge(message: string) {
  const request = {
    currentUserName: chatStore.initUserId(),
    message,
    botMsgId: chatStore.botMsgId!,
    modelName: chatStore.selectedModel,
    sessionId: chatStore.sessionId,
  }
  await ragSearch(request)
}

/**
 * 联网搜索
 */
async function handleInternet(message: string) {
  const request = {
    currentUserName: chatStore.initUserId(),
    message,
    botMsgId: chatStore.botMsgId!,
    modelName: chatStore.selectedModel,
    sessionId: chatStore.sessionId,
  }
  await internetSearch(request)
}

/**
 * 图像生成
 */
async function handleImageGenerate(prompt: string) {
  try {
    const res = await generateImage({
      query: prompt,
      model: chatStore.selectedImageModel,
    })

    const url = URL.createObjectURL(new Blob([res], { type: 'image/png' }))
    chatStore.addMessage({
      content: url,
      chatType: 'bot',
      isImage: true,
    })
    scrollToBottom('chat-messages')
  } catch (error) {
    ElMessage.error('图像生成失败')
  }
}

/**
 * 文本转语音
 */
async function handleTTS(content: string) {
  try {
    ElMessage.info('正在生成音频...')
    chatStore.isTTSGenerating = true

    const res = await generateTTS({
      content,
      model: chatStore.selectedTTSModel,
    })

    // 创建 Blob URL（浏览器原生支持，不需要Range请求）
    const blob = new Blob([res], { type: 'audio/wav' })
    const blobUrl = URL.createObjectURL(blob)

    chatStore.addMessage({
      content: blobUrl,
      chatType: 'bot',
      isAudio: true,
    })

    ElMessage.success('音频生成成功！')
    scrollToBottom('chat-messages')
  } catch (error) {
    ElMessage.error('音频生成失败！')
    console.error('TTS错误:', error)
  } finally {
    chatStore.isTTSGenerating = false
  }
}

/**
 * 图生文
 */
async function handleImageDesc(prompt: string) {
  if (!chatStore.selectedImageFile) {
    ElMessage.error('请先上传一张图片')
    return
  }

  if (chatStore.selectedModel !== 'zhipu') {
    ElMessage.error('图生文仅支持智谱模型。')
    return
  }

  try {
    const formData = new FormData()
    formData.append('file', chatStore.selectedImageFile)
    formData.append('query', prompt)
    formData.append('userId', chatStore.initUserId())
    formData.append('botMsgId', chatStore.botMsgId!)

    await imageDesc(formData)

    // 清除图片选择
    chatStore.selectedImageFile = null
    chatStore.imagePreview = null
  } catch (error) {
    ElMessage.error('图生文请求失败')
  }
}

/**
 * 视频生成
 */
async function handleVideoGenerate(prompt: string) {
  try {
    ElMessage.info('正在生成视频，请稍候...')

    const res = await generateVideo({
      prompt,
      model: chatStore.selectedVideoModel,
      quality: chatStore.selectedVideoQuality,
      size: chatStore.selectedVideoSize,
      with_audio: chatStore.withAudio,
      watermark: !chatStore.removeWatermark,
    })

    if (res.status === 200 && res.data) {
      chatStore.addMessage({
        content: res.data,
        chatType: 'bot',
        isVideo: true,
      })
      ElMessage.success('视频生成成功！')
    } else {
      ElMessage.error(res.msg || '视频生成失败')
    }

    scrollToBottom('chat-messages')
  } catch (error) {
    ElMessage.error('视频生成请求失败')
  }
}

/**
 * 首尾帧视频生成
 */
async function handleFrameVideo(prompt: string) {
  if (!chatStore.firstFrameFile || !chatStore.lastFrameFile) {
    ElMessage.error('请确保已上传首帧和尾帧图片。')
    return
  }

  if (chatStore.frameVideoTask.status === 'polling') {
    ElMessage.warning('已有视频正在生成中，请稍候...')
    return
  }

  try {
    ElMessage.info('正在提交首尾帧视频生成任务...')

    const formData = new FormData()
    formData.append('prompt', prompt)
    formData.append('model', chatStore.selectedFrameVideoModel)
    formData.append('firstFrame', chatStore.firstFrameFile)
    formData.append('lastFrame', chatStore.lastFrameFile)

    const res = await submitGenerateVideoFromFrames(formData)

    if (res.status === 200 && res.data) {
      chatStore.frameVideoTask.taskId = res.data
      chatStore.frameVideoTask.status = 'polling'

      ElMessage.success(`任务提交成功！ID: ${res.data}，开始轮询状态...`)

      // 清除帧文件
      chatStore.firstFrameFile = null
      chatStore.lastFrameFile = null

      // 开始轮询
      startPolling()
    } else {
      ElMessage.error(res.msg || '任务提交失败')
    }
  } catch (error) {
    ElMessage.error('任务提交请求失败')
  }
}

/**
 * 开始轮询视频任务状态
 */
function startPolling() {
  const interval = setInterval(async () => {
    if (!chatStore.frameVideoTask.taskId) return

    try {
      const res = await getVideoTaskStatus(chatStore.frameVideoTask.taskId)

      if (res.status === 200 && res.data) {
        const task = res.data
        chatStore.frameVideoTask.status = task.taskStatus

        if (task.taskStatus === 'SUCCEEDED') {
          stopPolling()
          ElMessage.success('视频生成成功！')

          if (task.videoUrl) {
            chatStore.addMessage({
              content: task.videoUrl,
              chatType: 'bot',
              isVideo: true,
            })
            scrollToBottom('chat-messages')
          }
        } else if (task.taskStatus === 'FAILED' || task.taskStatus === 'CANCELED') {
          stopPolling()
          ElMessage.error(`视频生成失败: ${task.message}`)
        }
      }
    } catch (error) {
      stopPolling()
      ElMessage.error('查询任务状态失败')
    }
  }, 5000)

  chatStore.frameVideoTask.pollingInterval = interval as unknown as number
}

/**
 * 停止轮询
 */
function stopPolling() {
  if (chatStore.frameVideoTask.pollingInterval) {
    clearInterval(chatStore.frameVideoTask.pollingInterval)
    chatStore.resetFrameVideoTask()
  }
}

// 生成结束解锁后恢复输入焦点
watch(
  () => chatStore.isGenerating,
  (generating) => {
    if (!generating) inputRef.value?.focus()
  },
)

/**
 * 键盘回车发送
 */
function handleKeydown(event: KeyboardEvent) {
  // 输入法候选词确认时的回车不触发发送
  if (event.isComposing || chatStore.isGenerating) return
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

/**
 * 外部组件（如追问卡片）调用：设置输入内容并自动发送。
 * @param text 已组织好的自然中文回复（必要时在括号内附带业务编码）
 */
function setTextAndSend(text: string) {
  userInput.value = text
  handleSend()
}

defineExpose({ setTextAndSend })
</script>

<template>
  <div class="chat-input-container">
    <!-- 视频生成控制 -->
    <VideoControls v-if="chatStore.activeMode === 'video'" />

    <!-- 首尾帧视频控制 -->
    <FrameVideoControls v-if="chatStore.activeMode === 'frameVideo'" />

    <!-- 图生文上传 -->
    <ImageDescUpload v-if="chatStore.activeMode === 'imageDesc'" />

    <!-- 输入框 -->
    <div class="chat-input">
      <textarea
        ref="inputRef"
        v-model="userInput"
        rows="1"
        class="user-input"
        placeholder="请输入内容，Shift + Enter 换行"
        :disabled="isBusy"
        @keydown="handleKeydown"
      ></textarea>
      <button
        class="btn-chat"
        :class="{ 'btn-chat--thinking': isBusy }"
        :disabled="isBusy"
        @click="handleSend"
      >
        <span v-if="isBusy" class="btn-thinking-dot" aria-hidden="true"></span>
        {{ isBusy ? '努力思考中，请稍候...' : '发送' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.chat-input-container {
  /* 关键：输入区不参与 flex 压缩，始终占据底部可用高度 */
  flex: 0 0 auto;
  padding: 20px;
  /* iOS 底部安全区（home indicator）留白 */
  padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--border-color);
  background: var(--secondary-color);
}

.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.user-input {
  flex: 1;
  min-width: 0;
  padding: 12px 15px;
  border: 1px solid var(--border-color);
  border-radius: 20px;
  font-family: inherit;
  font-size: 1em;
  line-height: 1.4;
  /* 多行输入上限：超过后输入框自身滚动，不再挤压消息区 */
  max-height: 120px;
  overflow-y: hidden;
  resize: none;
  outline: none;
  transition: border-color 0.3s;
}

.user-input:focus {
  border-color: var(--primary-color);
}

.btn-chat {
  flex-shrink: 0;
  padding: 12px 25px;
  border: none;
  background-color: var(--primary-color);
  color: white;
  border-radius: 20px;
  cursor: pointer;
  font-size: 1em;
  transition: background-color 0.3s;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  white-space: nowrap;
}

.btn-chat:hover {
  background-color: #357abd;
}

.btn-chat:disabled {
  background-color: #357abd;
  cursor: not-allowed;
  opacity: 0.7;
}

/* ===== 思考中按钮态：流动渐变 + 呼吸光点 ===== */
.btn-chat--thinking {
  font-size: 0.86em;
  padding: 12px 18px;
  background-image: linear-gradient(
    110deg,
    #2f6fb5 0%,
    #4a90e2 38%,
    #8ec0f5 52%,
    #4a90e2 66%,
    #2f6fb5 100%
  );
  background-size: 220% 100%;
  animation: btn-thinking-flow 2.6s ease-in-out infinite;
}

.btn-chat--thinking:disabled {
  opacity: 0.95;
}

.btn-thinking-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.9);
  animation: btn-thinking-pulse 1.2s ease-in-out infinite;
}

@keyframes btn-thinking-flow {
  0% {
    background-position: 0% 50%;
  }
  100% {
    background-position: 100% 50%;
  }
}

@keyframes btn-thinking-pulse {
  0%,
  100% {
    transform: scale(0.7);
    opacity: 0.55;
  }
  50% {
    transform: scale(1.15);
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .btn-chat--thinking,
  .btn-thinking-dot {
    animation: none;
  }
}

/* 窄屏：缩小输入区留白，忙碌文案自适应缩排，避免按钮换行或挤压输入框 */
@media (max-width: 600px) {
  .chat-input-container {
    padding: 12px;
    padding-bottom: calc(12px + env(safe-area-inset-bottom, 0px));
  }

  .user-input {
    padding: 11px 12px;
    max-height: 88px;
  }

  .btn-chat {
    padding: 11px 16px;
    font-size: 0.92em;
  }

  .btn-chat--thinking {
    padding: 11px 12px;
    font-size: 0.78em;
    gap: 6px;
  }
}
</style>
