<script setup lang="ts">
/**
 * 语音识别控制组件
 */
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useRecording } from '@/composables'
import { ElSelect, ElOption, ElButton } from 'element-plus'

const chatStore = useChatStore()
const { isRecording, toggleRecording, handleFileUpload } = useRecording()

const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 触发文件选择
 */
function triggerFileSelect() {
  fileInputRef.value?.click()
}

/**
 * 处理文件选择
 */
function onFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  
  if (file) {
    handleFileUpload(file)
    // 清空输入，允许重复选择同一个文件
    target.value = ''
  }
}
</script>

<template>
  <div class="section-box">
    <div class="section-title">语音识别 (ASR)</div>
    
    <el-select 
      v-model="chatStore.selectedASRModel" 
      placeholder="选择识别模型"
      class="asr-select"
    >
      <el-option value="qwen3-asr" label="Qwen3-ASR-Flash (推荐)" />
      <el-option value="qwen-audio" label="Qwen-Audio-Asr" />
    </el-select>
    
    <div class="asr-buttons">
      <el-button 
        :type="isRecording ? 'danger' : 'primary'"
        @click="toggleRecording"
        class="asr-button"
      >
        {{ isRecording ? '录音中...' : '开始录音' }}
      </el-button>
      
      <input 
        ref="fileInputRef"
        type="file" 
        accept="audio/*"
        style="display: none;"
        @change="onFileChange"
      />
      
      <el-button 
        type="primary"
        @click="triggerFileSelect"
        class="asr-button"
      >
        上传音频
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.section-box {
  background: #fff;
  padding: 15px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.section-title {
  font-weight: bold;
  margin-bottom: 15px;
  color: var(--primary-color);
}

.asr-select {
  width: 100%;
  margin-bottom: 10px;
}

.asr-buttons {
  display: flex;
  gap: 10px;
}

.asr-button {
  flex: 1;
}
</style>

