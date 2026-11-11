/**
 * API 响应类型定义
 */

export interface ApiResponse<T = any> {
  status: number
  msg?: string
  data?: T
}
