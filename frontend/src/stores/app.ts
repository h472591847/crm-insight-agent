/**
 * 应用全局配置 Store
 */

import { ref } from 'vue'
import { defineStore } from 'pinia'

export const useAppStore = defineStore('app', () => {
  // ========== 状态定义 ==========

  // 侧边栏是否折叠
  const sidebarCollapsed = ref<boolean>(false)

  // 应用加载状态
  const loading = ref<boolean>(false)

  // 主题模式
  const theme = ref<'light' | 'dark'>('light')

  // ========== Actions ==========

  /**
   * 切换侧边栏折叠状态
   */
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  /**
   * 设置加载状态
   */
  function setLoading(value: boolean) {
    loading.value = value
  }

  /**
   * 切换主题
   */
  function toggleTheme() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
  }

  return {
    // State
    sidebarCollapsed,
    loading,
    theme,

    // Actions
    toggleSidebar,
    setLoading,
    toggleTheme,
  }
})
