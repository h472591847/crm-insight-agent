/**
 * 功能可见性集中开关
 *
 * 默认只开放「标准对话」（CRM 问数主线），所有探索性功能均需构建时显式开启：
 *   VITE_ENABLE_KNOWLEDGE_MODE=true   知识库（RAG）
 *   VITE_ENABLE_INTERNET_MODE=true    联网搜索
 *   VITE_ENABLE_IMAGE_MODE=true       图像生成
 *   VITE_ENABLE_TTS_MODE=true         文本转语音
 *   VITE_ENABLE_IMAGE_DESC_MODE=true  图生文
 *   VITE_ENABLE_VIDEO_MODE=true       视频生成
 *   VITE_ENABLE_FRAME_VIDEO_MODE=true 首尾帧视频
 *   VITE_ENABLE_ASR=true              语音识别（ASR）
 *
 * 关闭时只影响前端展示与模式切换，接口与调用代码保持不动，便于后续恢复。
 */
import type { ChatMode } from '@/types'

const ALL_MODES: readonly ChatMode[] = [
  'chat',
  'knowledge',
  'internet',
  'image',
  'tts',
  'imageDesc',
  'video',
  'frameVideo',
]

function readFlag(value: unknown): boolean {
  return value === 'true'
}

export const FEATURES = {
  /** 知识库（RAG）模式 */
  knowledgeMode: readFlag(import.meta.env.VITE_ENABLE_KNOWLEDGE_MODE),
  /** 联网搜索模式 */
  internetMode: readFlag(import.meta.env.VITE_ENABLE_INTERNET_MODE),
  /** 图像生成模式 */
  imageMode: readFlag(import.meta.env.VITE_ENABLE_IMAGE_MODE),
  /** 文本转语音模式 */
  ttsMode: readFlag(import.meta.env.VITE_ENABLE_TTS_MODE),
  /** 图生文模式 */
  imageDescMode: readFlag(import.meta.env.VITE_ENABLE_IMAGE_DESC_MODE),
  /** 视频生成模式 */
  videoMode: readFlag(import.meta.env.VITE_ENABLE_VIDEO_MODE),
  /** 首尾帧视频模式 */
  frameVideoMode: readFlag(import.meta.env.VITE_ENABLE_FRAME_VIDEO_MODE),
  /** 语音识别（ASR） */
  asr: readFlag(import.meta.env.VITE_ENABLE_ASR),
} as const

/** 该模式当前是否对用户开放 */
export function isModeAvailable(mode: ChatMode): boolean {
  if (mode === 'knowledge') return FEATURES.knowledgeMode
  if (mode === 'internet') return FEATURES.internetMode
  if (mode === 'image') return FEATURES.imageMode
  if (mode === 'tts') return FEATURES.ttsMode
  if (mode === 'imageDesc') return FEATURES.imageDescMode
  if (mode === 'video') return FEATURES.videoMode
  if (mode === 'frameVideo') return FEATURES.frameVideoMode
  return true
}

/**
 * 归一化模式值：非法或未开放的模式统一回退到标准对话。
 * 用于兜住本地存储 / URL / 旧代码里可能残留的旧模式值。
 */
export function resolveMode(mode: string | null | undefined): ChatMode {
  if (!mode) return 'chat'
  const target = mode as ChatMode
  if (!ALL_MODES.includes(target)) return 'chat'
  return isModeAvailable(target) ? target : 'chat'
}
