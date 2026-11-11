<script setup lang="ts">
/**
 * 语音识别结果对话框组件
 */
import { computed, ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { ElMessage } from 'element-plus'

const chatStore = useChatStore()

const localText = computed({
  get: () => chatStore.recognizedText || '',
  set: (value) => {
    chatStore.recognizedText = value
  }
})

const isVisible = computed(() => chatStore.recognizedText !== null)

/**
 * 取消
 */
function handleCancel() {
  chatStore.recognizedText = null
}

/**
 * 下载文本
 */
function downloadText() {
  if (!localText.value) return
  
  const blob = new Blob([localText.value], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'recognized_text.txt'
  link.click()
  URL.revokeObjectURL(url)
  
  ElMessage.success('文本已下载')
}

/**
 * 发送给大模型
 */
function sendToAI() {
  if (!localText.value) return
  
  // 将识别文本设置到输入框（通过 emit 事件通知父组件）
  emit('send-to-ai', localText.value)
  chatStore.recognizedText = null
}

const emit = defineEmits<{
  (e: 'send-to-ai', text: string): void
}>()
</script>

<template>
  <div v-if="isVisible" class="recognized-text-container">
    <h3>语音识别结果</h3>
    <textarea v-model="localText" class="text-area"></textarea>
    <div class="buttons">
      <button class="btn-chat" @click="handleCancel">取消</button>
      <button class="btn-chat" @click="downloadText">下载文本</button>
      <button class="btn-chat" @click="sendToAI">发送给大模型</button>
    </div>
  </div>
</template>

<style scoped>
.recognized-text-container {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 500px;
  max-width: 90%;
  background-color: #fff;
  border: 1px solid #ccc;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  padding: 20px;
  z-index: 2000;
  display: flex;
  flex-direction: column;
}

h3 {
  margin-top: 0;
  color: #333;
  font-size: 18px;
  margin-bottom: 15px;
}

.text-area {
  width: 100%;
  min-height: 100px;
  margin: 10px 0;
  padding: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
  resize: vertical;
  font-family: inherit;
}

.buttons {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}

.btn-chat {
  padding: 8px 16px;
  border: none;
  background-color: var(--primary-color);
  color: white;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.3s;
}

.btn-chat:hover {
  background-color: #357abd;
}
</style>

