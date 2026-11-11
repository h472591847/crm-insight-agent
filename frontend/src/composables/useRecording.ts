/**
 * 音频录制组合式函数
 */

import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { recognizeAudioFile } from '@/api'
import { useChatStore } from '@/stores/chat'

export function useRecording() {
  const chatStore = useChatStore()

  const isRecording = ref<boolean>(false)
  const mediaRecorder = ref<MediaRecorder | null>(null)
  const audioChunks = ref<Blob[]>([])

  /**
   * 开始录音
   */
  async function startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })

      isRecording.value = true
      ElMessage.info('录音已开始...')

      audioChunks.value = []
      mediaRecorder.value = new MediaRecorder(stream)

      mediaRecorder.value.ondataavailable = (e: BlobEvent) => {
        audioChunks.value.push(e.data)
      }

      mediaRecorder.value.onstop = sendAudioToServer
      mediaRecorder.value.start()
    } catch (error) {
      console.error('Failed to access microphone:', error)
      ElMessage.error('无法获取麦克风权限')
    }
  }

  /**
   * 停止录音
   */
  function stopRecording() {
    if (mediaRecorder.value && isRecording.value) {
      mediaRecorder.value.stop()
      isRecording.value = false
      ElMessage.info('录音结束，正在发送...')

      // 停止所有音频轨道
      mediaRecorder.value.stream.getTracks().forEach((track) => track.stop())
    }
  }

  /**
   * 切换录音状态
   */
  function toggleRecording() {
    if (isRecording.value) {
      stopRecording()
    } else {
      startRecording()
    }
  }

  /**
   * 发送音频到服务器
   */
  async function sendAudioToServer() {
    const formData = new FormData()
    const audioBlob = new Blob(audioChunks.value, { type: 'audio/wav' })
    formData.append('file', audioBlob, 'recording.wav')
    formData.append('asrModel', chatStore.selectedASRModel)

    await sendFormDataToServer(formData)
  }

  /**
   * 处理文件上传
   */
  async function handleFileUpload(file: File) {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('asrModel', chatStore.selectedASRModel)

    await sendFormDataToServer(formData)
  }

  /**
   * 发送表单数据到服务器
   */
  async function sendFormDataToServer(formData: FormData) {
    try {
      ElMessage.info('正在识别音频文件...')

      const res = await recognizeAudioFile(formData)

      if (res.status === 200 && res.data) {
        chatStore.recognizedText = res.data
      } else {
        ElMessage.error(res.msg || '音频识别失败')
      }
    } catch (error) {
      console.error('Failed to recognize audio:', error)
      ElMessage.error('上传音频失败')
    }
  }

  return {
    isRecording,
    startRecording,
    stopRecording,
    toggleRecording,
    handleFileUpload,
  }
}
