/**
 * 实时语音识别 WebSocket 组合式函数
 */

import { ref, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'

export function useRealtimeASR() {
  const realtimeText = ref<string>('')
  const translationText = ref<string>('')
  const status = ref<string>('未连接')
  const statusColor = ref<string>('#909399')
  const isConnected = ref<boolean>(false)

  let socket: WebSocket | null = null
  let audioContext: AudioContext | null = null
  let processor: ScriptProcessorNode | null = null
  let stream: MediaStream | null = null

  /**
   * 清理资源
   */
  function cleanup() {
    // 停止音频处理
    if (processor) {
      processor.disconnect()
      processor.onaudioprocess = null
      processor = null
    }

    if (audioContext) {
      audioContext.close().catch(console.error)
      audioContext = null
    }

    // 停止麦克风录制
    if (stream) {
      stream.getTracks().forEach((track) => track.stop())
      stream = null
    }

    // 更新状态
    isConnected.value = false
    status.value = '已断开'
    statusColor.value = '#F56C6C'
  }

  /**
   * 连接 WebSocket
   */
  function connectWebSocket() {
    const wsBaseURL = import.meta.env.VITE_WS_BASE_URL
    let wsUrl: string

    if (wsBaseURL) {
      wsUrl = `${wsBaseURL}/crmAgent-api/api/audio/realtime`
    } else {
      // 如果没有配置，使用当前域名和协议
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      wsUrl = `${protocol}//${window.location.host}/crmAgent-api/api/audio/realtime`
    }

    socket = new WebSocket(wsUrl)

    socket.onopen = () => {
      console.log('WebSocket connection opened.')
      status.value = '已连接，请开始说话...'
      statusColor.value = '#67C23A'
      isConnected.value = true
    }

    socket.onmessage = (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'transcription') {
          realtimeText.value = data.text
        } else if (data.type === 'translation') {
          translationText.value = data.text
        }
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    }

    socket.onclose = (event) => {
      console.log('WebSocket connection closed.', event)
      cleanup()
    }

    socket.onerror = (error) => {
      console.error('WebSocket Error:', error)
      status.value = '连接错误'
      statusColor.value = '#F56C6C'
      ElMessage.error('WebSocket 连接失败')
      cleanup()
    }
  }

  /**
   * 开始实时录音
   */
  async function startRealtimeRecording() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      connectWebSocket()

      // 创建音频上下文
      audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
        sampleRate: 16000,
      })

      const source = audioContext.createMediaStreamSource(stream)
      processor = audioContext.createScriptProcessor(4096, 1, 1)

      source.connect(processor)
      processor.connect(audioContext.destination)

      processor.onaudioprocess = (e: AudioProcessingEvent) => {
        if (!socket || socket.readyState !== WebSocket.OPEN) return

        const inputData = e.inputBuffer.getChannelData(0)
        const buffer = new ArrayBuffer(inputData.length * 2)
        const view = new DataView(buffer)

        for (let i = 0; i < inputData.length; i++) {
          const sample = inputData[i] ?? 0
          const s = Math.max(-1, Math.min(1, sample))
          const val = s < 0 ? s * 0x8000 : s * 0x7fff
          view.setInt16(i * 2, val, true)
        }

        socket.send(buffer)
      }
    } catch (error) {
      console.error('Error accessing microphone:', error)
      ElMessage.error('无法访问麦克风，请检查权限')
    }
  }

  /**
   * 停止实时录音
   */
  function stopRealtimeRecording() {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.close()
    } else {
      cleanup()
    }
  }

  /**
   * 清空识别文本
   */
  function clearRealtimeText() {
    realtimeText.value = ''
    translationText.value = ''
  }

  // 组件卸载时清理资源
  onBeforeUnmount(() => {
    cleanup()
    if (socket) {
      socket.close()
    }
  })

  return {
    realtimeText,
    translationText,
    status,
    statusColor,
    isConnected,
    startRealtimeRecording,
    stopRealtimeRecording,
    clearRealtimeText,
  }
}
