/**
 * 聊天相关类型定义
 */

// 聊天模式类型
export type ChatMode =
  | 'chat'
  | 'knowledge'
  | 'internet'
  | 'image'
  | 'tts'
  | 'imageDesc'
  | 'video'
  | 'frameVideo'

// 聊天类型（用户/机器人）
export type ChatType = 'user' | 'bot'

// 模型类型
export type ModelType = 'deepseek' | 'qwen' | 'zhipu'
export type ImageModelType = 'qwen' | 'flux_1'
export type TTSModelType = 'qwen'
export type ASRModelType = 'qwen3-asr' | 'qwen-audio'
export type VideoModelType = 'cogvideox-flash'
export type FrameVideoModelType = 'wanx2.1-kf2v-plus'

// 视频质量类型
export type VideoQuality = 'speed' | 'quality'
export type VideoSize = '1024x1024' | '1920x1080' | '1080x1920'

// 追问卡片候选
export interface ClarifyCandidate {
  code: string
  label: string
  meta?: string
}
// 追问卡片数据（模型输出 <clarify> 块后解析）
export interface ClarifyData {
  type: 'station' | 'member' | 'ambiguity' | string
  /** 口径歧义时的提问主题（如"要客消费额的口径"），用于组织中文回复 */
  question?: string
  candidates: ClarifyCandidate[]
  /** 兜底卡：模型输出的候选无法还原，提示用户直接补充说明 */
  fallback?: boolean
}

/** 追问选项点击后回传的完整上下文 */
export interface ClarifySelection {
  code: string
  label: string
  meta?: string
  type: string
  question?: string
}

// 聊天消息接口
export interface ChatMessage {
  content: string
  chatType: ChatType
  isImage?: boolean
  isAudio?: boolean
  isVideo?: boolean
  botMsgId?: string
  replyStatus?: 'streaming' | 'done'
  /** bot 消息的原始 markdown（用于检测 <clarify> 等结构化标记） */
  rawContent?: string
  /** 追问卡片数据（从 rawContent 的 <clarify> 块解析得出） */
  clarifyData?: ClarifyData | null
  /** 一条消息里出现多个 <clarify> 块时逐块渲染，避免只保留第一个 */
  clarifyList?: ClarifyData[]
  /**
   * 消息时间（epoch 毫秒）。
   * 实时消息取发送/完成时刻；历史消息只取服务端返回值，取不到时保持 undefined，
   * 绝不用当前时间兜底，避免刷新后历史时间全部变成现在。
   */
  createdAt?: number
}

// 聊天请求
export interface ChatRequest {
  currentUserName: string
  message: string
  botMsgId: string
  modelName: ModelType
  // 前端生成的会话ID，用于隔离不同标签页的对话记忆与 SSE 通道
  sessionId: string
}

// SSE finish 事件回包
export interface ChatFinishResponse {
  message: string
  botMsgId: string
  sessionId?: string
}

// 图像生成请求
export interface ImageGenerateRequest {
  query: string
  model: ImageModelType
}

// TTS 生成请求
export interface TTSGenerateRequest {
  content: string
  model: TTSModelType
}

// 视频生成请求
export interface VideoGenerateRequest {
  prompt: string
  model: VideoModelType
  quality: VideoQuality
  size: VideoSize
  with_audio: boolean
  watermark: boolean
}

// 首尾帧视频任务状态
export type FrameVideoTaskStatus = 'idle' | 'polling' | 'SUCCEEDED' | 'FAILED' | 'CANCELED'

// 首尾帧视频任务
export interface FrameVideoTask {
  pollingInterval: number | null
  taskId: string | null
  status: FrameVideoTaskStatus
}

// 视频任务响应
export interface VideoTaskResponse {
  taskStatus: FrameVideoTaskStatus
  videoUrl?: string
  message?: string
}
