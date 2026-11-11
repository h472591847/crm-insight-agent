<script setup lang="ts">
/**
 * 图生文图片上传组件
 */
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { ElMessage } from 'element-plus'

const chatStore = useChatStore()
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 触发文件选择
 */
function triggerFileSelect() {
  fileInputRef.value?.click()
}

/**
 * 处理图片选择
 */
function handleImageChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  
  if (file) {
    chatStore.selectedImageFile = file
    chatStore.imagePreview = URL.createObjectURL(file)
    ElMessage.info(`已选择图片: ${file.name}`)
  }
}
</script>

<template>
  <div class="section-box">
    <input 
      ref="fileInputRef"
      type="file" 
      accept="image/*"
      style="display: none;"
      @change="handleImageChange"
    />
    
    <button class="btn-upload" @click="triggerFileSelect">
      上传图片
    </button>
    
    <img 
      v-if="chatStore.imagePreview" 
      :src="chatStore.imagePreview" 
      class="image-preview"
    />
    
    <span v-if="chatStore.selectedImageFile" class="file-name">
      {{ chatStore.selectedImageFile.name }}
    </span>
  </div>
</template>

<style scoped>
.section-box {
  background: #fff;
  padding: 15px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  margin-bottom: 15px;
  display: flex;
  align-items: center;
  gap: 15px;
}

.btn-upload {
  padding: 8px 20px;
  border: none;
  background-color: var(--primary-color);
  color: white;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.3s;
}

.btn-upload:hover {
  background-color: #357abd;
}

.image-preview {
  max-width: 80px;
  max-height: 80px;
  border-radius: 5px;
  object-fit: cover;
}

.file-name {
  font-size: 14px;
  color: #606266;
}
</style>

