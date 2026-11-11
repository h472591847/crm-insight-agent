/**
 * 聊天相关 API
 */

import request from '@/utils/request'
import type { ChatRequest, ApiResponse } from '@/types'

/**
 * 标准对话
 */
export function doChat(data: ChatRequest): Promise<ApiResponse> {
  return request({
    url: '/chat/doChat',
    method: 'post',
    data,
  })
}

/**
 * 知识库搜索
 */
export function ragSearch(data: ChatRequest): Promise<ApiResponse> {
  return request({
    url: '/rag/search',
    method: 'post',
    data,
  })
}

/**
 * 联网搜索
 */
export function internetSearch(data: ChatRequest): Promise<ApiResponse> {
  return request({
    url: '/internet/search',
    method: 'post',
    data,
  })
}

/**
 * 上传知识库文档
 */
export function uploadRagDoc(formData: FormData): Promise<ApiResponse> {
  return request({
    url: '/rag/uploadRagDoc',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
