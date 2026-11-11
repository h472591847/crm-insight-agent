<script setup lang="ts">
/**
 * 知识库文档上传组件
 */
import { useChatStore } from '@/stores/chat'
import { uploadRagDoc } from '@/api/chat'
import { ElMessage, ElLoading } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'

const chatStore = useChatStore()

/**
 * 处理文档上传
 */
async function handleUpload(options: UploadRequestOptions) {
  const formData = new FormData()
  formData.append('file', options.file)

  const loading = ElLoading.service({
    lock: true,
    text: '文档正在上传和智能处理中，请稍候...',
    background: 'rgba(0, 0, 0, 0.7)',
  })

  try {
    const res = await uploadRagDoc(formData)
    loading.close()

    if (res.status === 200) {
      ElMessage.success('知识库文档上传成功！')
    } else {
      ElMessage.error(res.msg || '上传失败！')
    }
  } catch (error) {
    loading.close()
    ElMessage.error('上传请求失败，请检查网络或联系管理员。')
    console.error('Upload failed:', error)
  }
}
</script>

<template>
  <div v-if="chatStore.activeMode === 'knowledge'" class="section-box">
    <div class="section-title">知识库文档上传</div>
    <el-upload
      drag
      action=""
      accept=".txt,.md,.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.csv,.json"
      :http-request="handleUpload"
      :show-file-list="false"
      class="upload-dragger"
    >
      <el-icon class="el-icon--upload">
        <i class="el-icon-upload"></i>
      </el-icon>
      <div class="el-upload__text">拖拽 md/pdf/docx 等文件或 <em>点击上传</em></div>
    </el-upload>
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

.upload-dragger {
  width: 100%;
}

:deep(.el-upload-dragger) {
  width: 100%;
  height: 120px;
}
</style>
