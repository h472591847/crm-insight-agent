/**
 * SSE (Server-Sent Events) 组合式函数
 * 用于处理服务端推送的流式消息
 */

import { onBeforeUnmount } from 'vue'
import { useChatStore } from '@/stores/chat'
import type { ChatFinishResponse } from '@/types'
import { marked } from 'marked'

export function useSSE() {
  const chatStore = useChatStore()
  let eventSource: EventSource | null = null

  /**
   * 初始化 SSE 连接
   */
  function initSSE(userId: string, sessionId?: string) {
    // 重建前先关闭旧连接，避免同一标签页连接泄漏或重复接收
    closeSSE()

    const baseURL = import.meta.env.VITE_API_BASE_URL || ''
    const params = new URLSearchParams({ userId })
    if (sessionId) {
      params.set('sessionId', sessionId)
    }
    const sseUrl = baseURL
      ? `${baseURL}/sse/connect?${params.toString()}`
      : `/sse/connect?${params.toString()}`
    eventSource = new EventSource(sseUrl)

    // 监听消息添加事件
    eventSource.addEventListener('add', (e: MessageEvent) => {
      const msgId = chatStore.botMsgId
      if (msgId) {
        chatStore.updateBotMessage(msgId, e.data)
      }
    })

    // 监听消息完成事件
    eventSource.addEventListener('finish', (e: MessageEvent) => {
      const activeMsgId = chatStore.botMsgId
      try {
        const res = JSON.parse(e.data) as ChatFinishResponse
        // 非当前会话的 finish 直接忽略，避免切换会话/多标签时串消息
        if (res.sessionId && res.sessionId !== chatStore.sessionId) {
          return
        }
        if (res.botMsgId && res.message) {
          // finishBotMessage 内部会做 clarify 提取 + marked.parse
          chatStore.finishBotMessage(res.botMsgId, res.message)
        } else if (activeMsgId) {
          // 缺字段时也要解锁，避免永久「生成中」
          chatStore.unlockGeneration(activeMsgId)
        }
        chatStore.botMsgId = null
      } catch (error) {
        console.error('Failed to parse SSE finish event:', error)
        chatStore.unlockGeneration(activeMsgId)
        chatStore.botMsgId = null
      }
    })

    // 监听错误事件
    eventSource.addEventListener('error', (e) => {
      console.error('SSE connection error:', e)
    })
  }

  /**
   * 关闭 SSE 连接
   */
  function closeSSE() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  // 组件卸载时自动关闭连接
  onBeforeUnmount(() => {
    closeSSE()
  })

  return {
    initSSE,
    closeSSE,
  }
}
