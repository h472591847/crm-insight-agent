<script setup lang="ts">
/**
 * 实时语音识别组件
 */
import { useRealtimeASR } from '@/composables'
import { ElButton } from 'element-plus'

const {
  realtimeText,
  translationText,
  status,
  statusColor,
  isConnected,
  startRealtimeRecording,
  stopRealtimeRecording,
  clearRealtimeText
} = useRealtimeASR()
</script>

<template>
  <div class="section-box">
    <div class="section-title-row">
      <div class="section-title">实时识别</div>
      <el-button 
        type="info" 
        size="small" 
        plain
        @click="clearRealtimeText"
      >
        清空
      </el-button>
    </div>
    
    <div class="realtime-buttons">
      <el-button 
        type="primary" 
        size="small"
        :disabled="isConnected"
        @click="startRealtimeRecording"
      >
        开始录音
      </el-button>
      <el-button 
        type="danger" 
        size="small"
        :disabled="!isConnected"
        @click="stopRealtimeRecording"
      >
        停止录音
      </el-button>
    </div>
    
    <div class="realtime-status" :style="{ color: statusColor }">
      状态：{{ status }}
    </div>
    
    <div class="realtime-text">
      {{ realtimeText || '实时转写文本将显示在这里...' }}
    </div>
    
    <div class="translation-text">
      {{ translationText || '翻译结果将显示在这里...' }}
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

.section-title-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.section-title {
  font-weight: bold;
  color: var(--primary-color);
}

.realtime-buttons {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}

.realtime-buttons button {
  flex: 1;
}

.realtime-status {
  font-size: 12px;
  margin-bottom: 10px;
}

.realtime-text,
.translation-text {
  min-height: 50px;
  border: 1px solid #DCDFE6;
  padding: 10px;
  border-radius: 4px;
  background-color: #F5F7FA;
  color: #303133;
  margin-top: 10px;
  font-size: 14px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-wrap: break-word;
}
</style>

