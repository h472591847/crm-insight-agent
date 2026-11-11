/**
 * 滚动到底部组合式函数
 */

import { nextTick } from 'vue'

export function useScrollToBottom() {
  /**
   * 滚动到指定元素的底部
   */
  function scrollToBottom(elementId?: string, smooth: boolean = true) {
    nextTick(() => {
      const element = elementId 
        ? document.getElementById(elementId)
        : document.querySelector('.chat-messages')
      
      if (element) {
        element.scrollTo({
          top: element.scrollHeight,
          behavior: smooth ? 'smooth' : 'auto'
        })
      }
    })
  }
  
  return {
    scrollToBottom
  }
}

