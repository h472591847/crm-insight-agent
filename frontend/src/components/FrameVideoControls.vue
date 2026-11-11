<script setup lang="ts">
/**
 * 首尾帧视频生成控制组件
 */
import { useChatStore } from '@/stores/chat'
import { ElSelect, ElOption, ElUpload, ElMessage } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'

const chatStore = useChatStore()

/**
 * 处理首帧上传
 */
function handleFirstFrameUpload(options: UploadRequestOptions): Promise<unknown> {
  const file = options.file as File
  chatStore.firstFrameFile = file
  ElMessage.success(`首帧图片 ${file.name} 选择成功`)
  return Promise.resolve()
}

/**
 * 处理尾帧上传
 */
function handleLastFrameUpload(options: UploadRequestOptions): Promise<unknown> {
  const file = options.file as File
  chatStore.lastFrameFile = file
  ElMessage.success(`尾帧图片 ${file.name} 选择成功`)
  return Promise.resolve()
}
</script>

<template>
  <div class="section-box">
    <div class="section-title">首尾帧视频参数</div>

    <el-select
      v-model="chatStore.selectedFrameVideoModel"
      placeholder="选择首尾帧模型"
      class="frame-select"
    >
      <el-option value="wanx2.1-kf2v-plus" label="wanx2.1-kf2v-plus" />
    </el-select>

    <div class="frame-upload-container">
      <div class="frame-upload-box">
        <el-upload
          drag
          action=""
          :http-request="handleFirstFrameUpload"
          :show-file-list="false"
          :limit="1"
          accept="image/*"
        >
          <el-icon class="el-icon--upload">
            <i class="el-icon-upload"></i>
          </el-icon>
          <div class="el-upload__text">上传首帧图片</div>
        </el-upload>
        <div v-if="chatStore.firstFrameFile" class="upload-success-tip">
          {{ chatStore.firstFrameFile.name }} 上传成功
        </div>
      </div>

      <div class="frame-upload-box">
        <el-upload
          drag
          action=""
          :http-request="handleLastFrameUpload"
          :show-file-list="false"
          :limit="1"
          accept="image/*"
        >
          <el-icon class="el-icon--upload">
            <i class="el-icon-upload"></i>
          </el-icon>
          <div class="el-upload__text">上传尾帧图片</div>
        </el-upload>
        <div v-if="chatStore.lastFrameFile" class="upload-success-tip">
          {{ chatStore.lastFrameFile.name }} 上传成功
        </div>
      </div>
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

.section-title {
  font-weight: bold;
  margin-bottom: 15px;
  color: var(--primary-color);
}

.frame-select {
  width: 100%;
  margin-bottom: 15px;
}

.frame-upload-container {
  display: flex;
  gap: 15px;
  justify-content: space-around;
}

.frame-upload-box {
  width: 48%;
  text-align: center;
}

:deep(.el-upload-dragger) {
  width: 100%;
  height: 80px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

:deep(.el-icon-upload) {
  margin: 0 !important;
}

:deep(.el-upload__text) {
  font-size: 12px;
}

.upload-success-tip {
  color: #67c23a;
  font-size: 12px;
  margin-top: 5px;
}
</style>
