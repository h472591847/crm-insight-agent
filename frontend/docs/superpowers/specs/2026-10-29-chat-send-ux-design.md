# 聊天发送交互体验设计

**日期：** 2026-10-29  
**状态：** 已确认，待写实现计划  
**目标：** 让文字对话的发送 / 回复反馈达到可给老板演示的完成度（发送后立刻清空、能看清模型是否回复完毕）。

## 问题

1. 输入框要等模式 API 的 `await` 结束后才清空，长请求期间文字一直留在框里。
2. 流式回复没有「进行中 / 已完成」反馈，气泡只是在变长，看不出模型是否结束。

## 范围

**本轮包含：**
- 模式：`chat`、`knowledge`、`internet`（SSE 文字流式）
- 发送后乐观清空输入框
- 生成中锁定输入框与发送按钮（文案：「生成中…」）
- 生成中禁用模式切换
- 助手气泡状态：流式指示 → 短暂「已完成」→ 消失
- 错误路径下正确解锁 UI

**本轮不做：**
- 中断 / 「停止」按钮（留到后续）
- 媒体模式：`image`、`tts`、`imageDesc`、`video`、`frameVideo`（保持现有行为）
- 强制新增自动化测试（演示级手工验收即可）

## 方案

在现有 Pinia store、SSE composable、聊天组件上做局部修补，不引入完整会话状态机。

## 发送与锁定

对 `chat` | `knowledge` | `internet` 发送时：

1. 校验输入非空。
2. 取出 trim 后的文本，然后**立刻清空**输入框（乐观清空）。
3. 追加用户消息；**预先创建**一条空的助手消息（带 `botMsgId`，`replyStatus: 'streaming'`），使首包 SSE 到达前气泡就可见。
4. 设置 `isGenerating = true`。
5. 再走现有 API / SSE 流程。

`isGenerating` 为 true 时：

- 输入框 `disabled`（不可编辑、不可 Enter 发送）
- 发送按钮禁用，文案为「生成中…」（不做中断）
- 模式切换禁用

流式结束或请求失败时：

- `isGenerating = false`
- 恢复输入并可自动 focus

媒体模式不走这套 `isGenerating` 逻辑。

## 气泡状态 UI

在 `ChatMessage` 上增加可选字段：

```ts
replyStatus?: 'streaming' | 'done'
```

| 阶段 | UI |
|------|-----|
| `streaming` | 正文末尾闪烁光标；气泡下方小字：「正在回复…」 |
| 首包前的空气泡 | 同样显示流式 UI（避免像卡住） |
| `done` | 去掉光标与「正在回复…」，显示「已完成」 |
| 约 1.5 秒后 | 清空 `replyStatus`（标记消失） |

不在历史消息上持久保留「已完成」。用户气泡无状态行。除现有 `finish` 的 Markdown 渲染外，不改渲染路径。

## 数据模型与归属

| 状态 | 位置 |
|------|------|
| `isGenerating: boolean` | `chat` store |
| 消息上的 `replyStatus` | `chatList` 中的 `ChatMessage` |
| 「已完成」定时器 | 由 store 的 `finishBotMessage` 管理；`clearChat` 时取消；避免泄漏 |

### 流程

```
发送 (chat|knowledge|internet)
  → 清空输入 / isGenerating=true / 助手气泡 replyStatus=streaming
  → POST → SSE add → updateBotMessage（保持 streaming）
  → SSE finish → finishBotMessage / replyStatus=done / isGenerating=false
  → 1.5s → 清空 replyStatus
```

### Store / SSE 改动要点

- `updateBotMessage`：流式过程中新建助手行时带上 `replyStatus: 'streaming'`。
- `finishBotMessage`：写入内容、设 `replyStatus: 'done'`、设 `isGenerating = false`，并调度约 1.5s 后清空该条 `replyStatus`。
- `useSSE` 的 `finish`：保持现有 Markdown 收尾；通过 store 解除生成中（并照旧清空 `botMsgId`）。
- `ChatInput` 文字模式 POST/网络失败：解锁（`isGenerating=false`）、清空 `replyStatus`、用 `ElMessage` 错误 toast，并把预创建助手气泡文案设为简短失败提示（如「回复失败，请重试」）。
- SSE `finish` 解析失败：调用 store 解锁（`isGenerating=false`），若有当前助手消息则清空其 `replyStatus`，已流出的正文保留。

## 错误与边界

| 情况 | 行为 |
|------|------|
| POST 抛错 / 网络失败 | 解锁 UI；`ElMessage` toast + 助手气泡错误文案；清空 `replyStatus` |
| SSE `finish` JSON 解析失败 | 解锁 UI；保留已流式正文 |
| 连点发送 | 控件已禁用，不会二次请求 |
| 生成中切模式 | 生成中禁用切换 |
| 定时器未结束时 `clearChat` | 取消 pending 定时器；重置 `isGenerating` |
| 媒体模式 | 不改动 |

## 预计改动文件

- `src/types/chat.ts` — `replyStatus`
- `src/stores/chat.ts` — `isGenerating`、完成/定时器相关
- `src/composables/useSSE.ts` — finish 与解锁对齐（走 store）
- `src/components/ChatInput.vue` — 乐观清空、生成中禁用
- `src/components/ChatMessages.vue` — 流式 / 完成 UI
- `src/components/ModeSelector.vue` — 生成中禁用模式按钮

## 验收（演示）

手工检查：

1. 发送后输入立刻清空；按钮为「生成中…」；输入锁定。
2. 助手气泡在首包前后均有光标 +「正在回复…」。
3. 结束后出现「已完成」约 1.5 秒后消失；输入解锁可再发。
4. 人为制造失败后 UI 能解锁并可再发。
5. 媒体模式行为与改前一致。

## 非目标提醒

不做停止/中断、不做媒体模式统一、不做完整状态机重写。
