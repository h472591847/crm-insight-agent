/**
 * 功能可见性集中开关
 *
 * 本版本默认不向用户开放「知识库」与「联网搜索」。
 * 仅当构建时环境变量显式设为 'true' 才恢复对应入口：
 *   VITE_ENABLE_KNOWLEDGE_MODE=true
 *   VITE_ENABLE_INTERNET_MODE=true
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
} as const

/** 该模式当前是否对用户开放 */
export function isModeAvailable(mode: ChatMode): boolean {
  if (mode === 'knowledge') return FEATURES.knowledgeMode
  if (mode === 'internet') return FEATURES.internetMode
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
