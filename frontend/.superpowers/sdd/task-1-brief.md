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
