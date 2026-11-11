/**
 * 多媒体相关 API（图像、音频、视频）
 */

import request from '@/utils/request'
import type {
  ImageGenerateRequest,
  TTSGenerateRequest,
  VideoGenerateRequest,
  ApiResponse,
  VideoTaskResponse,
} from '@/types'

/**
 * 生成图像
 */
export function generateImage(params: ImageGenerateRequest): Promise<ArrayBuffer> {
  return request({
    url: '/api/image/generate',
    method: 'get',
    params,
    responseType: 'arraybuffer',
  })
}

/**
 * 图生文（图像描述）
 */
export function imageDesc(formData: FormData): Promise<ApiResponse> {
  return request({
    url: '/api/image/desc',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/**
 * 文本转语音（TTS）
 */
export function generateTTS(params: TTSGenerateRequest): Promise<ArrayBuffer> {
  return request({
    url: '/api/audio/tts',
    method: 'get',
    params,
    responseType: 'arraybuffer',
  })
}

/**
 * 语音识别（ASR）
 */
export function recognizeAudioFile(formData: FormData): Promise<ApiResponse<string>> {
  return request({
    url: '/api/audio/asr',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/**
 * 通过 URL 识别音频
 */
export function recognizeAudioUrl(payload: {
  url: string
  asrModel: string
}): Promise<ApiResponse<string>> {
  return request({
    url: '/api/audio/asr_url',
    method: 'post',
    data: payload,
  })
}

/**
 * 生成视频
 */
export function generateVideo(data: VideoGenerateRequest): Promise<ApiResponse<string>> {
  return request({
    url: '/api/video/generate',
    method: 'post',
    data,
  })
}

/**
 * 提交首尾帧视频生成任务
 */
export function submitGenerateVideoFromFrames(formData: FormData): Promise<ApiResponse<string>> {
  return request({
    url: '/api/video/submitGenerateAsync',
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

/**
 * 查询首尾帧视频任务状态
 */
export function getVideoTaskStatus(taskId: string): Promise<ApiResponse<VideoTaskResponse>> {
  return request({
    url: `/api/video/task-status/${taskId}`,
    method: 'get',
  })
}
