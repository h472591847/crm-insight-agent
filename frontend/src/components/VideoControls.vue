<script setup lang="ts">
/**
 * 视频生成控制组件
 */
import { useChatStore } from '@/stores/chat'
import { ElRow, ElCol, ElSelect, ElOption, ElCheckbox } from 'element-plus'

const chatStore = useChatStore()
</script>

<template>
  <div class="section-box">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-select v-model="chatStore.selectedVideoModel">
          <el-option value="cogvideox-flash" label="cogvideox-flash" />
        </el-select>
      </el-col>
      
      <el-col :span="6">
        <el-select v-model="chatStore.selectedVideoQuality" placeholder="视频质量">
          <el-option value="speed" label="速度优先" />
          <el-option value="quality" label="质量优先" />
        </el-select>
      </el-col>
      
      <el-col :span="12">
        <el-select v-model="chatStore.selectedVideoSize" placeholder="视频尺寸">
          <el-option value="1024x1024" />
          <el-option value="1920x1080" />
          <el-option value="1080x1920" />
        </el-select>
      </el-col>
    </el-row>
    
    <div class="video-options">
      <el-checkbox v-model="chatStore.withAudio" label="生成AI音效" />
      <el-checkbox v-model="chatStore.removeWatermark" label="去除水印" />
    </div>
  </div>
</template>

<style scoped>
.section-box {
  background: #fff;
  padding: 15px;
  border-radius: 8px;
  border: 1px solid var(--border-color);
  margin-bottom: 15px;
}

.video-options {
  margin-top: 10px;
  display: flex;
  gap: 15px;
}
</style>

