/**
 * Axios 请求配置
 */

import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import Cookies from 'js-cookie'
import { ElMessage } from 'element-plus'

/** 响应拦截器 return response.data，所以实际返回 T 而非 AxiosResponse<T> */
type RequestInstance = Omit<AxiosInstance, 'get' | 'post' | 'put' | 'delete' | 'patch' | 'head' | 'options'> & {
  <T = any>(config: AxiosRequestConfig): Promise<T>
  <T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
  put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  patch<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
  head<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  options<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
}

// 创建 axios 实例
const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  withCredentials: true,
  timeout: 600000,
}) as RequestInstance

// 请求拦截器
instance.interceptors.request.use(
  (config) => {
    // 从 Cookie 中获取用户信息和 token
    const userInfoStr = Cookies.get('user_info')
    const userToken = Cookies.get('user_token')

    if (userInfoStr) {
      try {
        const userInfo = JSON.parse(userInfoStr)
        if (userInfo?.id) {
          config.headers['headerUserId'] = userInfo.id
        }
      } catch (e) {
        console.error('Failed to parse user info:', e)
      }
    }

    if (userToken) {
      config.headers['headerUserToken'] = userToken
    }

    return config
  },
  (error) => {
    console.error('Request error:', error)
    return Promise.reject(error)
  },
)

// 响应拦截器
instance.interceptors.response.use(
  (response: AxiosResponse) => {
    return response.data
  },
  (error) => {
    console.error('Response error:', error)

    // 处理不同的错误状态码
    if (error.response) {
      const status = error.response.status
      switch (status) {
        case 401:
          ElMessage.error('未授权，请重新登录')
          break
        case 403:
          ElMessage.error('拒绝访问')
          break
        case 404:
          ElMessage.error('请求的资源不存在')
          break
        case 500:
          ElMessage.error('服务器内部错误')
          break
        default:
          ElMessage.error(`请求失败: ${error.message}`)
      }
    } else {
      ElMessage.error('网络错误，请检查您的网络连接')
    }

    return Promise.reject(error)
  },
)

export default instance
