# 聊天发送交互体验 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文字模式（chat / knowledge / internet）发送后立刻清空输入、生成中锁定控件，助手气泡展示「正在回复… / 已完成」，达到可演示水准。

**Architecture:** 在 `chat` store 增加 `isGenerating` 与消息级 `replyStatus`；`ChatInput` 乐观清空并预创建 streaming 气泡；`useSSE` finish/失败路径经 store 解锁；`ChatMessages` / `ModeSelector` 订阅状态渲染 UI。不做中断、不改媒体模式。

**Tech Stack:** Vue 3 + Pinia + TypeScript + Element Plus（`ElMessage`）+ 现有 SSE（`EventSource`）

## Global Constraints

- 范围仅限 `chat` | `knowledge` | `internet`；媒体模式行为不变
- 不做「停止」/ abort
- 无单测框架：验收用 `npm run type-check` + 手工演示清单（与 spec 一致）
- 文案固定：「生成中…」「正在回复…」「已完成」「回复失败，请重试」
- 「已完成」展示约 1.5 秒后清除 `replyStatus`
- Spec：`docs/superpowers/specs/2026-10-29-chat-send-ux-design.md`

---

## File Structure

| 文件 | 职责 |
|------|------|
| `src/types/chat.ts` | `replyStatus` 类型 |
| `src/stores/chat.ts` | `isGenerating`、预创建/完成/失败解锁、done 定时器 |
| `src/composables/useSSE.ts` | finish 解析失败时解锁 |
| `src/components/ChatInput.vue` | 乐观清空、文字模式生成态、失败处理、focus |
| `src/components/ChatMessages.vue` | 光标 / 状态文案 UI |
| `src/components/ModeSelector.vue` | 生成中禁用模式按钮 |

---

### Task 1: 类型与 Store 生成态

**Files:**
- Modify: `src/types/chat.ts`
- Modify: `src/stores/chat.ts`

**Interfaces:**
- Consumes: 现有 `ChatMessage`、`finishBotMessage`、`updateBotMessage`、`clearChat`
- Produces:
  - `ChatMessage.replyStatus?: 'streaming' | 'done'`
  - `isGenerating: Ref<boolean>`
  - `startTextGeneration(botMsgId: string): void` — 预创建空助手气泡 + `isGenerating=true`
  - `failTextGeneration(botMsgId: string, errorText?: string): void` — 解锁并写失败文案
  - `unlockGeneration(botMsgId?: string | null): void` — 仅解锁（SSE 解析失败用）
  - `finishBotMessage` 扩展：设 `done`、关 `isGenerating`、1.5s 清 `replyStatus`

- [ ] **Step 1: 扩展 `ChatMessage` 类型**

在 `src/types/chat.ts` 的 `ChatMessage` 中增加：

```ts
replyStatus?: 'streaming' | 'done'
```

- [ ] **Step 2: 在 store 增加生成态与定时器**

在 `src/stores/chat.ts`：

1. 增加状态与模块级定时器句柄：

```ts
const isGenerating = ref(false)
let doneStatusTimer: ReturnType<typeof setTimeout> | null = null

function clearDoneStatusTimer() {
  if (doneStatusTimer !== null) {
    clearTimeout(doneStatusTimer)
    doneStatusTimer = null
  }
}
```

2. 新增：

```ts
function startTextGeneration(botMsgId: string) {
  isGenerating.value = true
  chatList.value.push({
    content: '',
    chatType: 'bot',
    botMsgId,
    replyStatus: 'streaming',
  })
}

function failTextGeneration(botMsgId: string, errorText = '回复失败，请重试') {
  clearDoneStatusTimer()
  isGenerating.value = false
  const msg = chatList.value.find((m) => m.botMsgId === botMsgId)
  if (msg) {
    msg.content = errorText
    msg.replyStatus = undefined
  }
}

function unlockGeneration(botMsgId?: string | null) {
  clearDoneStatusTimer()
  isGenerating.value = false
  if (botMsgId) {
    const msg = chatList.value.find((m) => m.botMsgId === botMsgId)
    if (msg) {
      msg.replyStatus = undefined
    }
  }
}
```

3. 改 `updateBotMessage`：新建分支带上 `replyStatus: 'streaming'`（已存在则保持，勿覆盖 `done`）。

4. 改 `finishBotMessage`：

```ts
function finishBotMessage(msgId: string, content: string) {
  const msg = chatList.value.find((m) => m.botMsgId === msgId)
  if (msg) {
    msg.content = content
    msg.replyStatus = 'done'
  }
  isGenerating.value = false
  clearDoneStatusTimer()
  doneStatusTimer = setTimeout(() => {
    const target = chatList.value.find((m) => m.botMsgId === msgId)
    if (target && target.replyStatus === 'done') {
      target.replyStatus = undefined
    }
    doneStatusTimer = null
  }, 1500)
}
```

5. 改 `clearChat`：先 `clearDoneStatusTimer()`，再清空列表，并 `isGenerating.value = false`。

6. 在 return 中导出：`isGenerating`、`startTextGeneration`、`failTextGeneration`、`unlockGeneration`。

- [ ] **Step 3: 类型检查**

Run: `npm run type-check`  
Expected: 通过（或仅有与本次无关的既有错误；本次新增符号无报错）

- [ ] **Step 4: Commit**（若本机未配置 git user，跳过并在 PR/后续由用户提交）

```bash
git add src/types/chat.ts src/stores/chat.ts
git commit -m "feat(chat): add generating state and replyStatus on messages"
```

---

### Task 2: SSE 失败解锁

**Files:**
- Modify: `src/composables/useSSE.ts`

**Interfaces:**
- Consumes: `finishBotMessage`、`unlockGeneration`、`botMsgId`
- Produces: `finish` 解析失败时调用 `unlockGeneration(msgId)`

- [ ] **Step 1: 更新 finish / 保持 add 行为**

`finish` 监听器改为（逻辑等价于现有成功路径 + 失败解锁）：

```ts
eventSource.addEventListener('finish', (e: MessageEvent) => {
  const activeMsgId = chatStore.botMsgId
  try {
    const res = JSON.parse(e.data)
    if (res.botMsgId && res.message) {
      const htmlContent = marked.parse(res.message) as string
      chatStore.finishBotMessage(res.botMsgId, htmlContent)
    } else if (activeMsgId) {
      // 缺字段时也要解锁，避免永久「生成中」
      chatStore.unlockGeneration(activeMsgId)
    }
    chatStore.botMsgId = null
  } catch (error) {
    console.error('Failed to parse SSE finish event:', error)
    chatStore.unlockGeneration(activeMsgId)
    chatStore.botMsgId = null
  }
})
```

成功路径依赖 Task 1 的 `finishBotMessage` 已将 `isGenerating` 置 false。

- [ ] **Step 2: 类型检查**

Run: `npm run type-check`  
Expected: 通过

- [ ] **Step 3: Commit**

```bash
git add src/composables/useSSE.ts
git commit -m "fix(sse): unlock generating state when finish event fails"
```

---

### Task 3: ChatInput 乐观清空与锁定

**Files:**
- Modify: `src/components/ChatInput.vue`

**Interfaces:**
- Consumes: `isGenerating`、`startTextGeneration`、`failTextGeneration`、`botMsgId`、`generateRandomId`
- Produces: 文字三模式发送体验符合 spec

- [ ] **Step 1: 调整 `handleSend` 文字模式路径**

在 `handleSend` 中：

1. 顶部：若 `chatStore.isGenerating` 直接 `return`（双保险）。
2. 校验输入后：`const input = userInput.value.trim()`，对文字三模式立刻 `userInput.value = ''`（媒体模式仍可在末尾清空，保持现有）。
3. 对 `chat` | `knowledge` | `internet`：
   - `addMessage` 用户消息
   - `botMsgId = generateRandomId(12)`
   - `startTextGeneration(botMsgId)`
   - `scrollToBottom`
   - `try { await handleX(input) } catch { failTextGeneration(botMsgId); ElMessage.error('回复失败，请重试') }`
   - **不要**在这些模式的成功路径末尾再清输入（已清）；也不要在成功时手动关 `isGenerating`（等 SSE finish）
4. 媒体模式：保持原逻辑；末尾 `userInput.value = ''` 仅在未提前清空时执行，或媒体分支单独清空。
5. `handleKeydown`：若 `isGenerating` 则不发送。

推荐结构（核心片段）：

```ts
const TEXT_STREAM_MODES = ['chat', 'knowledge', 'internet'] as const

async function handleSend() {
  if (chatStore.isGenerating) return

  const input = userInput.value.trim()
  if (input === '' && chatStore.activeMode !== 'frameVideo') return

  const mode = chatStore.activeMode
  const isTextStream = (TEXT_STREAM_MODES as readonly string[]).includes(mode)

  if (isTextStream) {
    userInput.value = ''
    chatStore.addMessage({ content: input, chatType: 'user' })
    chatStore.botMsgId = chatStore.generateRandomId(12)
    chatStore.startTextGeneration(chatStore.botMsgId!)
    scrollToBottom('chat-messages')
    try {
      if (mode === 'chat') await handleChat(input)
      else if (mode === 'knowledge') await handleKnowledge(input)
      else await handleInternet(input)
    } catch (error) {
      console.error('Chat error:', error)
      chatStore.failTextGeneration(chatStore.botMsgId!)
      ElMessage.error('回复失败，请重试')
      chatStore.botMsgId = null
    }
    return
  }

  // ... 既有媒体模式分支（含末尾清空输入）...
}
```

注意：POST 成功但 SSE 仍在流式时，`isGenerating` 保持 true，直到 `finishBotMessage`。

- [ ] **Step 2: 模板绑定禁用与文案**

```vue
<input
  ref="inputRef"
  v-model="userInput"
  type="text"
  class="user-input"
  placeholder="请输入内容..."
  :disabled="chatStore.isGenerating || chatStore.isTTSGenerating || chatStore.isPolling"
  @keydown="handleKeydown"
/>
<button
  class="btn-chat"
  :disabled="chatStore.isGenerating || chatStore.isTTSGenerating || chatStore.isPolling"
  @click="handleSend"
>
  {{
    chatStore.isGenerating || chatStore.isPolling || chatStore.isTTSGenerating
      ? '生成中...'
      : '发送'
  }}
</button>
```

增加 `const inputRef = ref<HTMLInputElement | null>(null)`，在 `failTextGeneration` 路径与（可选）`watch(isGenerating)` 从 true→false 时 `inputRef.value?.focus()`。

- [ ] **Step 3: 类型检查**

Run: `npm run type-check`  
Expected: 通过

- [ ] **Step 4: Commit**

```bash
git add src/components/ChatInput.vue
git commit -m "feat(chat): clear input on send and lock composer while generating"
```

---

### Task 4: 气泡状态 UI

**Files:**
- Modify: `src/components/ChatMessages.vue`

**Interfaces:**
- Consumes: `ChatMessage.replyStatus`
- Produces: streaming 光标 + 「正在回复…」；done 时「已完成」

- [ ] **Step 1: 更新文本气泡模板**

对非图片/音频/视频的 bot 文本气泡，在 `.text` 外包一层结构，例如：

```vue
<div
  v-if="!item.isImage && !item.isAudio && !item.isVideo"
  class="text-block"
>
  <div class="text">
    <span v-html="item.content"></span>
    <span
      v-if="item.chatType === 'bot' && item.replyStatus === 'streaming'"
      class="stream-cursor"
      aria-hidden="true"
    >|</span>
  </div>
  <div
    v-if="item.chatType === 'bot' && item.replyStatus === 'streaming'"
    class="reply-status"
  >
    正在回复…
  </div>
  <div
    v-if="item.chatType === 'bot' && item.replyStatus === 'done'"
    class="reply-status reply-status--done"
  >
    已完成
  </div>
</div>
```

- [ ] **Step 2: 样式**

```css
.stream-cursor {
  display: inline-block;
  margin-left: 2px;
  font-weight: 600;
  animation: blink 1s step-end infinite;
  color: var(--primary-color);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.reply-status {
  margin-top: 6px;
  font-size: 12px;
  color: #888;
}

.reply-status--done {
  color: #67c23a;
}
```

保持现有 `.text` 气泡外观；若 `.text-block` 需继承宽度，按现有布局微调，勿引入卡片式大改。

可选：`watch` 消息 `content` / `replyStatus` 变化时 `scrollToBottom`，避免流式时滚不动（若现有仅 watch length，补上对最后一条 content 的 watch）。

- [ ] **Step 3: 类型检查**

Run: `npm run type-check`  
Expected: 通过

- [ ] **Step 4: Commit**

```bash
git add src/components/ChatMessages.vue
git commit -m "feat(chat): show streaming and done status on bot bubbles"
```

---

### Task 5: 模式切换锁定

**Files:**
- Modify: `src/components/ModeSelector.vue`

**Interfaces:**
- Consumes: `chatStore.isGenerating`
- Produces: 生成中不可切换模式

- [ ] **Step 1: 禁用按钮并拦截点击**

```ts
function handleModeSwitch(mode: ChatMode) {
  if (chatStore.isGenerating) return
  chatStore.switchMode(mode)
}
```

每个 mode button 增加 `:disabled="chatStore.isGenerating"`，并补 disabled 样式（opacity / `cursor: not-allowed`）。

- [ ] **Step 2: 类型检查**

Run: `npm run type-check`  
Expected: 通过

- [ ] **Step 3: Commit**

```bash
git add src/components/ModeSelector.vue
git commit -m "feat(chat): disable mode switch while generating"
```

---

### Task 6: 演示验收

**Files:** 无新文件（手工）

**Interfaces:**
- Consumes: Task 1–5 全部行为
- Produces: spec 验收清单全部打勾

- [ ] **Step 1: 启动并走手工清单**

Run: `npm run dev`

对照 spec：

1. 发送 → 输入立刻空；按钮「生成中…」；输入锁定  
2. 气泡有光标 +「正在回复…」（首包前也有）  
3. 结束后「已完成」约 1.5s 消失；可再发  
4. 断网或故意失败 → toast +「回复失败，请重试」；可再发  
5. TTS/视频等媒体模式与改前一致  

- [ ] **Step 2: 最终 type-check**

Run: `npm run type-check`  
Expected: 通过

- [ ] **Step 3: 若有未提交改动则一并提交**

```bash
git status
# 仅当有剩余改动时 commit
```

---

## Plan Self-Review

1. **Spec coverage:** 乐观清空、锁定、模式禁用、replyStatus UI、finish/失败解锁、clearChat 清定时器、媒体不动、不做 abort — 均有对应 Task。  
2. **Placeholder scan:** 无 TBD；验证命令与关键代码已写出。  
3. **Type consistency:** `replyStatus`、`isGenerating`、`startTextGeneration` / `failTextGeneration` / `unlockGeneration` 命名在 Task 间一致。
