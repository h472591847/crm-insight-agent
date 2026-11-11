<script setup lang="ts">
/**
 * 聊天消息列表组件
 */
import { computed, ref, watch } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useScrollToBottom } from '@/composables'
import ClarifyCard from './ClarifyCard.vue'
import type { ClarifyData, ClarifySelection } from '@/types'

const chatStore = useChatStore()
const { scrollToBottom } = useScrollToBottom()

const messages = computed(() => chatStore.chatList)

// 监听消息变化，自动滚动到底部
watch(
  () => chatStore.chatList.length,
  () => {
    scrollToBottom('chat-messages')
  },
)

// 流式内容增长时也保持滚动到底部
watch(
  () => chatStore.chatList[chatStore.chatList.length - 1]?.content,
  () => {
    scrollToBottom('chat-messages')
  },
)

/**
 * 用户在追问卡片上点选候选 → 把完整候选信息交给 ChatView 组织成中文回复。
 * 不在这一层直接发送 code，避免把英文标识塞进用户气泡。
 */
function handleClarifySelect(selection: ClarifySelection) {
  const event = new CustomEvent('clarify-select', { detail: selection, bubbles: true })
  document.dispatchEvent(event)
}

const pad2 = (n: number) => String(n).padStart(2, '0')

/**
 * 消息时间展示：当天只显示时分秒；跨天补月日；跨年补年份。
 * 秒级精度用于区分同一分钟内的多条消息。
 */
function formatMessageTime(ts: number): string {
  const d = new Date(ts)
  const now = new Date()
  const clock = `${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
  if (d.toDateString() === now.toDateString()) return clock
  const date =
    d.getFullYear() === now.getFullYear()
      ? `${d.getMonth() + 1}月${d.getDate()}日`
      : `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
  return `${date} ${clock}`
}

/** 悬停提示用的完整时间 */
function formatFullTime(ts: number): string {
  const d = new Date(ts)
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(
    d.getMinutes(),
  )}:${pad2(d.getSeconds())}`
}

// 音频加载完成
function handleAudioLoaded(_event: Event) {}
// 音频加载错误
function handleAudioError(event: Event) {
  const audio = event.target as HTMLAudioElement
  console.error('音频加载失败:', audio.error)
}
</script>

<template>
  <div id="chat-messages" class="chat-messages">
    <div v-for="(item, index) in messages" :key="index" :class="['message', item.chatType]">
      <!-- 机器人头像 -->
      <div v-if="item.chatType === 'bot'" class="avatar">AI</div>

      <!-- 消息主体：气泡内容 + 时间 -->
      <div class="message-body">
        <!-- 文本消息 -->
        <div v-if="!item.isImage && !item.isAudio && !item.isVideo" class="text-block">
          <div v-if="item.content" class="text">
            <span v-html="item.content"></span>
            <span
              v-if="item.chatType === 'bot' && item.replyStatus === 'streaming'"
              class="stream-cursor"
              aria-hidden="true"
              >|</span
            >
          </div>
          <!-- 追问卡片：仅 bot 消息 + done 状态；一条消息可能有多个 clarify 块 -->
          <template v-if="item.chatType === 'bot' && item.replyStatus !== 'streaming'">
            <ClarifyCard
              v-for="(clarify, clarifyIndex) in item.clarifyList ||
              (item.clarifyData ? [item.clarifyData] : [])"
              :key="clarifyIndex"
              :data="clarify as ClarifyData"
              @select="handleClarifySelect"
            />
          </template>
          <div
            v-if="item.chatType === 'bot' && item.replyStatus === 'streaming'"
            class="thinking-status"
            role="status"
            aria-live="polite"
          >
            <span class="thinking-dots" aria-hidden="true">
              <i></i><i></i><i></i>
            </span>
            <span class="thinking-text">努力思考中，请稍候...</span>
            <span class="thinking-sweep" aria-hidden="true"></span>
          </div>
          <div
            v-if="item.chatType === 'bot' && item.replyStatus === 'done'"
            class="reply-status reply-status--done"
          >
            已完成
          </div>
        </div>

        <!-- 图片消息 -->
        <div v-if="item.isImage" class="text">
          <img :src="item.content" class="message-image" />
        </div>

        <!-- 音频消息 -->
        <div v-if="item.isAudio" class="text audio-container">
          <audio
            controls
            :src="item.content"
            preload="metadata"
            @error="handleAudioError"
            @loadedmetadata="handleAudioLoaded"
          ></audio>
          <a :href="item.content" download="generated_audio.wav" class="download-btn"> 下载音频 </a>
        </div>

        <!-- 视频消息 -->
        <div v-if="item.isVideo" class="text">
          <video controls width="300">
            <source :src="item.content" type="video/mp4" />
          </video>
        </div>

        <!-- 对话时间：历史消息缺少服务端时间时不展示，不用当前时间兜底 -->
        <time
          v-if="item.createdAt"
          class="message-time"
          :datetime="new Date(item.createdAt).toISOString()"
          :title="formatFullTime(item.createdAt)"
        >
          {{ formatMessageTime(item.createdAt) }}
        </time>
      </div>

      <!-- 用户头像 -->
      <div v-if="item.chatType === 'user'" class="user-avatar">我</div>
    </div>
  </div>
</template>

<style scoped>
.chat-messages {
  flex: 1;
  /* 关键：只占剩余高度，不被消息内容撑开，长对话在此容器内独立滚动 */
  min-height: 0;
  padding: 20px;
  overflow-y: auto;
  /* 防止 Markdown 表格等宽内容横向撑破聊天面板 */
  overflow-x: hidden;
  /* 滚动到边界时不带动外层页面滚动 */
  overscroll-behavior: contain;
  min-width: 0;
  background: var(--container-bg);
}

.message {
  display: flex;
  align-items: flex-start;
  margin-bottom: 15px;
  gap: 10px;
}

.message.user {
  justify-content: flex-end;
}

/* 消息主体：纵向排列气泡与时间，宽度跟随内容 */
.message-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.message.bot .message-body {
  align-items: flex-start;
  max-width: 560px;
}

.message.user .message-body {
  align-items: flex-end;
  max-width: 60%;
}

.avatar,
.user-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: bold;
  flex-shrink: 0;
}

.avatar {
  background: var(--primary-color);
  color: white;
}

.user-avatar {
  background: #67c23a;
  color: white;
}

.text {
  padding: 12px 16px;
  border-radius: 8px;
  word-wrap: break-word;
  line-height: 1.6;
}

.text-block {
  /* 宽度上限由 .message-body 控制；内部表格靠 overflow-x 横滚 */
  max-width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.message-body .text {
  max-width: 100%;
}

/* 对话时间：气泡下方小字，融入现有灰色辅助文案风格 */
.message-time {
  margin-top: 5px;
  font-size: 11px;
  line-height: 1;
  color: #9aa3af;
  letter-spacing: 0.2px;
  font-variant-numeric: tabular-nums;
  user-select: none;
}

.message.bot .text {
  background-color: var(--bot-msg-bg);
  border: 1px solid var(--border-color);
  /* 允许 Markdown 表格列多时横向滚动，避免串版 */
  overflow-x: auto;
}

/* 气泡内横向滚动条（表格等） */
.message.bot .text::-webkit-scrollbar {
  height: 6px;
}

.message.bot .text::-webkit-scrollbar-track {
  background: transparent;
}

.message.bot .text::-webkit-scrollbar-thumb {
  background: #c0c4cc;
  border-radius: 3px;
}

.message.bot .text::-webkit-scrollbar-thumb:hover {
  background: #909399;
}

.message.user .text {
  background-color: var(--user-msg-bg);
}

.message-image {
  max-width: 300px;
  border-radius: 5px;
  display: block;
}

.stream-cursor {
  display: inline-block;
  margin-left: 2px;
  font-weight: 600;
  animation: blink 1s step-end infinite;
  color: var(--primary-color);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* ===== 思考中状态条 ===== */
.thinking-status {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 9px 16px 10px 14px;
  border-radius: 999px;
  background: linear-gradient(
    120deg,
    rgba(74, 144, 226, 0.18) 0%,
    rgba(74, 144, 226, 0.07) 46%,
    rgba(255, 255, 255, 0.95) 100%
  );
  border: 1px solid rgba(74, 144, 226, 0.3);
  color: #2f6fb5;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.2px;
  overflow: hidden;
  isolation: isolate;
  animation: thinking-breathe 2.8s ease-in-out infinite;
}

.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  position: relative;
  z-index: 1;
}

.thinking-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary-color);
  box-shadow: 0 0 6px rgba(74, 144, 226, 0.45);
  animation: thinking-dot 1.3s ease-in-out infinite;
}

.thinking-dots i:nth-child(2) {
  animation-delay: 0.16s;
}

.thinking-dots i:nth-child(3) {
  animation-delay: 0.32s;
}

.thinking-text {
  position: relative;
  z-index: 1;
  white-space: nowrap;
}

.thinking-sweep {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 42%;
  background: linear-gradient(100deg, transparent, rgba(255, 255, 255, 0.85), transparent);
  transform: translateX(-140%) skewX(-18deg);
  animation: thinking-sweep 2.6s ease-in-out infinite;
  pointer-events: none;
  z-index: 0;
}

@keyframes thinking-dot {
  0%,
  60%,
  100% {
    transform: translateY(0) scale(0.8);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-4px) scale(1.15);
    opacity: 1;
  }
}

@keyframes thinking-breathe {
  0%,
  100% {
    box-shadow: 0 2px 10px rgba(74, 144, 226, 0.1);
  }
  50% {
    box-shadow: 0 2px 18px rgba(74, 144, 226, 0.26);
  }
}

@keyframes thinking-sweep {
  0% {
    transform: translateX(-140%) skewX(-18deg);
  }
  62%,
  100% {
    transform: translateX(320%) skewX(-18deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .thinking-status,
  .thinking-sweep,
  .thinking-dots i,
  .stream-cursor {
    animation: none;
  }
}

.reply-status {
  margin-top: 6px;
  font-size: 12px;
  color: #888;
}

.reply-status--done {
  color: #67c23a;
}

.audio-container {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 320px;
}

.audio-container audio {
  width: 100%;
  min-width: 320px;
  height: 40px;
  outline: none;
}

.download-btn {
  display: inline-block;
  padding: 6px 12px;
  background-color: var(--primary-color);
  color: white;
  text-decoration: none;
  border-radius: 4px;
  font-size: 12px;
  text-align: center;
  transition: background-color 0.3s;
}

.download-btn:hover {
  background-color: #357abd;
}

/* 滚动条样式 */
.chat-messages::-webkit-scrollbar {
  width: 8px;
}

.chat-messages::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 4px;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: #888;
  border-radius: 4px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: #555;
}

/* 窄屏：压缩留白并放宽气泡占比，保证状态条与正文不被挤成窄列 */
@media (max-width: 600px) {
  .chat-messages {
    padding: 14px;
  }

  .message.bot .message-body {
    max-width: 100%;
  }

  .message.user .message-body {
    max-width: 84%;
  }
}
</style>
