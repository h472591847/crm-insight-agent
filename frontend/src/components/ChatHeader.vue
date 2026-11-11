<script setup lang="ts">
/**
 * 聊天页面头部组件
 */
</script>

<template>
  <div class="chat-header">
    <span>会员系统数据统计助手</span>
  </div>
</template>

<style scoped>
.chat-header {
  background: linear-gradient(135deg, var(--primary-color), #357abd);
  color: white;
  padding: 20px;
  font-size: 1.5em;
  text-align: center;
  font-weight: bold;
  border-bottom: 1px solid var(--border-color);
}

/* 窄屏：压缩头部高度，把更多垂直空间留给消息区与输入区 */
@media (max-width: 600px) {
  .chat-header {
    padding: 14px 12px;
    font-size: 1.2em;
  }
}
</style>
