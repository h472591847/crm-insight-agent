<script setup lang="ts">
/**
 * 模型选择组件
 */
import { useChatStore } from '@/stores/chat'
import { ElSelect, ElOption } from 'element-plus'

const chatStore = useChatStore()
</script>

<template>
  <div class="section-box">
    <div class="section-title">模型配置</div>
    <div class="model-selector-group">
      <!-- 对话模型 -->
      <el-select v-model="chatStore.selectedModel" placeholder="选择对话模型" class="model-select">
        <el-option value="deepseek" label="DeepSeek (默认)" />
        <el-option value="qwen" label="通义千问 (Qwen)" />
        <el-option value="zhipu" label="智谱清言 (Zhipu)" />
      </el-select>

      <!-- 图像模型 (仅图像生成模式显示) -->
      <el-select
        v-if="chatStore.activeMode === 'image'"
        v-model="chatStore.selectedImageModel"
        placeholder="选择图像模型"
        class="model-select"
      >
        <el-option value="qwen" label="Qwen-Image (默认)" />
        <el-option value="flux_1" label="FLUX.1-Image" />
      </el-select>

      <!-- TTS 模型 (仅 TTS 模式显示) -->
      <el-select
        v-if="chatStore.activeMode === 'tts'"
        v-model="chatStore.selectedTTSModel"
        placeholder="选择语音模型"
        class="model-select"
      >
        <el-option value="qwen" label="Qwen-TTS (默认)" />
      </el-select>
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

.model-selector-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.model-select {
  width: 100%;
}
</style>
