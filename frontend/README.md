# Spring AI Frontend Vue

<div align="center">

![Vue 3](https://img.shields.io/badge/Vue-3.5.22-brightgreen.svg)
![TypeScript](https://img.shields.io/badge/TypeScript-5.9-blue.svg)
![Vite](https://img.shields.io/badge/Vite-7.1-646CFF.svg)
![Element Plus](https://img.shields.io/badge/Element%20Plus-2.11-409EFF.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

**CRM 数据分析助手的 Web 前端**

</div>

---

## 项目定位

本仓库是 CRM 数据分析助手的 Web 前端。后端是独立运行的进程（`mcp-client`），不隶属于 CRM 系统，也不在 CRM 源码仓库中开发；前端只负责交互与展示。

主要能力：

- 自然语言提问，后端通过 Spring AI / Tool Calling 查询 CRM 数据
- 通过 SSE 接收流式回答
- 多会话管理，会话记忆由后端持久化（前端只保存当前会话 ID）
- 多轮记忆：同一 `sessionId` 下的历史消息由后端恢复
- 模型输出结构化追问时，渲染可点击的候选卡片

---

## 技术栈

以 `package.json` 实际依赖为准：

| 技术               | 版本   | 用途            |
| ------------------ | ------ | --------------- |
| Vue                | 3.5.x  | 视图框架        |
| TypeScript         | 5.9.x  | 类型系统        |
| Vite               | 7.1.x  | 开发服务器与构建 |
| Vue Router         | 4.6.x  | 路由（History 模式） |
| Pinia              | 3.0.x  | 状态管理        |
| Element Plus       | 2.11.x | UI 组件库       |
| Element Plus Icons | 2.3.x  | 图标            |
| Axios              | 1.13.x | HTTP 请求       |
| Marked             | 16.4.x | Markdown 渲染   |
| js-cookie          | 3.0.x  | Cookie 读取     |

开发工具：ESLint 9、Prettier 3、vue-tsc、vite-plugin-vue-devtools。

---

## 目录结构

```
src/
├── api/            # 后端接口封装
│   ├── chat.ts     # /chat/doChat、/rag/search、/internet/search、知识库上传
│   ├── media.ts    # 图像、TTS、ASR、视频接口
│   └── index.ts
├── components/     # 页面组件
│   ├── ChatHeader.vue      # 顶部标题
│   ├── ChatSidebar.vue     # 会话列表、新建/切换/删除、模式与知识库入口
│   ├── ChatMessages.vue    # 消息列表、Markdown 渲染、时间与状态条
│   ├── ChatInput.vue       # 输入框、发送、SSE 兜底解锁
│   ├── ClarifyCard.vue     # 追问候选卡片
│   ├── ModeSelector.vue    # 功能模式入口（受开关控制）
│   └── ...                 # ASR、图像、视频等组件（当前未在页面挂载）
├── composables/    # 组合式函数
│   ├── useSSE.ts           # SSE 连接与事件处理
│   ├── useScrollToBottom.ts
│   └── ...
├── config/
│   └── features.ts # 知识库 / 联网搜索入口开关
├── router/         # 单页路由（/ → ChatView）
├── stores/
│   ├── chat.ts     # 消息、userId/sessionId、生成态、模型选择
│   └── app.ts      # 侧边栏、主题等全局 UI 状态
├── types/          # ChatMessage、ClarifyData、请求/响应类型
├── utils/
│   ├── request.ts  # Axios 实例与拦截器
│   └── clarify.ts  # <clarify> 容错解析
├── views/
│   └── ChatView.vue # 主页面，初始化 userId/sessionId、SSE、历史恢复、视口适配
├── assets/styles/global.css
├── App.vue
└── main.ts
```

`public/` 放静态资源；`nginx.conf` 与两个 `Dockerfile` 用于部署；`docs/` 存放设计与计划文档。

---

## 快速开始

前置要求：Node.js `^20.19.0 || >=22.12.0`（见 `package.json` 的 `engines`）。

```bash
# 1. 安装依赖
npm install

# 2. 准备环境变量：复制 .env.example 为 .env（.env 已被 .gitignore 忽略）

# 3. 启动开发服务器
npm run dev
```

开发地址为 `http://localhost:9173/crmAgent/`（端口和 `base` 见 `vite.config.ts`）。

默认通过 Vite 代理访问后端：`/crmAgent-api/*` → `VITE_BACKEND_URL`（未配置时为 `http://localhost:9090`），转发时剥掉 `/crmAgent-api` 前缀。

生产构建：

```bash
npm run build
```

产物输出到 `dist/`。

---

## 配置项

配置来自项目根目录的 `.env`（本地私有，不提交）。`.env.example` 是模板；`.env.local` 可用于本机覆盖。

| 变量                          | 作用                                                                 |
| ----------------------------- | -------------------------------------------------------------------- |
| `VITE_BACKEND_URL`            | 仅开发服务器代理目标；`vite.config.ts` 默认 `http://localhost:9090`，`.env.example` 模板中写的是 9009，按实际后端端口填写 |
| `VITE_API_BASE_URL`           | Axios `baseURL` 和 SSE 地址前缀；当前值为 `/crmAgent-api`            |
| `VITE_WS_BASE_URL`            | 实时语音识别 WebSocket 地址，仅 `useRealtimeASR` 使用                |
| `VITE_ENABLE_KNOWLEDGE_MODE`  | 知识库模式入口开关，默认 `false`                                     |
| `VITE_ENABLE_INTERNET_MODE`   | 联网搜索模式入口开关，默认 `false`                                   |
| `VITE_ENABLE_DEV_TOOLS`       | 模板保留，当前代码未读取（Vue DevTools 插件在 `vite.config.ts` 中固定启用） |
| `VITE_ENABLE_CONSOLE_LOG`     | 模板保留，当前代码未读取                                             |

### `VITE_API_BASE_URL` 与 `/crmAgent-api` 前缀

前端所有 HTTP 请求和 SSE 连接都带同一个前缀：

- 开发环境：`vite.config.ts` 把 `/crmAgent-api` 代理到后端，并用 `rewrite` 去掉前缀，避免跨域。
- 生产环境：外层 nginx 需要暴露 `/crmAgent-api/`，转发到 `mcp-client` 时同样去掉该前缀；静态资源则挂在 `/crmAgent/`（对应 Vite 的 `base`）。

仓库内的 `nginx.conf` 是容器内直连版本，按 `/api/`、`/sse`、`/chat/` 等路径转发，**不包含** `/crmAgent-api` 前缀。如果部署环境沿用该前缀，需要在外层 nginx 或该文件中补充对应的 location 与 rewrite，否则请求会 404。

### 功能开关

`src/config/features.ts` 集中控制入口可见性。`VITE_ENABLE_KNOWLEDGE_MODE` 与 `VITE_ENABLE_INTERNET_MODE` 未显式设为 `true` 时，侧边栏不渲染对应按钮，`resolveMode` 也会把残留的旧模式回退为 `chat`。关闭只影响入口展示，接口代码保留。

---

## 开发与构建

脚本以 `package.json` 为准：

```bash
npm run dev        # 启动开发服务器
npm run build      # 并行执行类型检查与构建
npm run build-only # 只执行 vite build
npm run preview    # 预览 dist
npm run type-check # vue-tsc --build
npm run lint       # eslint . --fix --cache
npm run format     # prettier --write src/
```

---

## SSE 接入

实现位于 `src/composables/useSSE.ts`，由 `ChatView` 在挂载时建立连接。

### 连接与双键

```ts
new EventSource(`${VITE_API_BASE_URL}/sse/connect?userId=...&sessionId=...`)
```

- `userId`：匿名用户 ID，首次生成后存 `localStorage`，刷新不变。
- `sessionId`：前端生成的会话 ID，存 `sessionStorage`，按标签页隔离。
- 后端以 `userId + sessionId` 双键定位推送通道，避免同一用户多标签页互相串消息。
- 新建或切换会话时会先关闭旧连接再重建，防止重复接收和连接泄漏。

### 事件类型

| 事件     | 作用                                                                 |
| -------- | -------------------------------------------------------------------- |
| `add`    | 增量分片，追加到当前 `botMsgId` 对应的助手消息                        |
| `finish` | 生成结束，携带 `message`、`botMsgId`、`sessionId`，前端解析 Markdown 并提取追问 |
| `error`  | 连接异常，仅记录日志                                                 |

`finish` 会校验 `sessionId`，不匹配的直接忽略；字段缺失或 JSON 解析失败时也会解锁生成态，避免永久停在「思考中」。

### 断线与心跳

- 没有自定义重连逻辑，依赖浏览器 `EventSource` 的自动重连；`error` 事件不会主动关闭连接。
- 后端会周期性发送 SSE comment 心跳帧（形如 `: keep-alive`）。`EventSource` 不会把 comment 派发成事件，前端看不到，它的作用是让代理层不因空闲而断开长连接。
- 因此前端不能用心跳判断后端是否存活，只能依赖业务事件与 HTTP 请求结果。

### HTTP 返回后的兜底解锁

聊天 POST 要等后端跑完整个生成流程才返回。返回时前端仍是生成态，说明 `finish` 在链路中丢了（通道被判死、断网重连、后端重启等）。

`ChatInput.vue` 中的 `unlockIfFinishMissing` 会在 HTTP 返回后等待 1.5 秒：

- 若仍是生成态，调用 `unlockGeneration` 解锁，并提示「回答已生成但推送中断，请刷新查看」。
- 只解锁、不标记失败：回答通常已生成并写入会话记忆，刷新即可看到。
- 若 `finish` 稍后才到，`finishBotMessage` 仍会按 `botMsgId` 补全内容，不会被这次解锁挡住。

---

## 追问选项

模型通过 `<clarify>` 块输出追问，解析逻辑在 `src/utils/clarify.ts`，渲染在 `ClarifyCard.vue`。

### 为什么不用严格正则 + `JSON.parse`

模型输出不是稳定的协议，常见问题包括：

- 字段名漂移：`candidates` / `options` / `choices` / `items` / `list`
- JSON 损坏：中文引号、尾逗号、未闭合、混入说明文字
- 块被截断，缺少 `</clarify>`
- 候选项为空，或只有 `question`
- 用 Markdown 列表代替 JSON

旧实现只要 `JSON.parse` 失败就丢弃整块，界面只剩「请选择：」半句话。现在的原则是：**能还原就还原，还原不了也要给兜底卡片，绝不静默删块。**

### 容错与抢救顺序

1. 用宽松正则匹配 `<clarify>`：忽略大小写和属性，缺少结束标签时一直匹配到文末。
2. 先尝试直接解析；失败则修复常见问题（中文引号转英文、去掉尾逗号）。
3. 从含噪文本中截取最外层 `{...}` 再解析。
4. 找到候选数组后用括号配对提取；数组不合法或被截断时，逐个 `{...}` 对象抢救。
5. 从原文用正则还原 `type` / `question`。
6. 仍无候选时，解析 Markdown 列表行（`- 门店级别（STORE_LEVEL）`、`1. 门店级别：STORE_LEVEL` 等）。
7. 完全无法还原时生成 `fallback: true` 的兜底卡，提示用户直接补充说明。

此外，若没有 `<clarify>` 块，但回答以「请选择：」结尾并紧跟 Markdown 列表，也会尝试转换；为避免误伤正常回答，要求列表至少 2 项且每项不超过 40 字符。

一条消息里可能有多个 `<clarify>` 块，解析结果以数组返回并逐块渲染。用户点选后，`ChatView` 会把候选组织成自然中文再发送，避免把英文编码直接塞进用户气泡。

---

## 部署

### Docker

`Dockerfile`：多阶段构建，`node:20-alpine` 安装依赖并构建，再复制到 `nginx:alpine`，监听 80 端口。

```bash
docker build -t spring-ai-frontend:latest .
docker run -d --name spring-ai-frontend -p 80:80 spring-ai-frontend:latest
```

`Dockerfile.simple`：适用于本地已执行 `npm run build` 的场景，直接复制 `dist/` 到 nginx 镜像。

两个 Dockerfile 都会把 `nginx.conf` 复制为 `/etc/nginx/conf.d/default.conf`。

### nginx.conf

| location      | 作用                                                       |
| ------------- | ---------------------------------------------------------- |
| `/`           | 静态资源，`try_files` 回退到 `index.html`（History 路由）  |
| `/api/`       | 会话列表、历史消息、图像/音频/视频等接口                   |
| `/sse`        | SSE 长连接                                                 |
| `/chat/`      | 对话接口                                                   |
| `/rag/`       | 知识库接口                                                 |
| `/internet/`  | 联网搜索接口                                               |

`proxy_pass` 当前指向 `http://mcp-client:9009`，即容器网络中名为 `mcp-client` 的后端服务。实际部署时需保证服务名和端口与后端一致（开发默认端口是 9090）。

### SSE 相关配置

```nginx
proxy_http_version 1.1;
proxy_set_header Connection '';
proxy_buffering off;
proxy_cache off;
chunked_transfer_encoding off;
proxy_read_timeout 3600s;
```

- `proxy_buffering off`：关闭 nginx 缓冲后，后端写一帧就转发一帧。否则 nginx 会攒够缓冲区才下发，流式回答变成整段或分批出现。
- `proxy_http_version 1.1` + `Connection ''`：SSE 依赖 HTTP/1.1 的分块传输与长连接；默认的 HTTP/1.0 或 keep-alive 头处理会导致连接被提前关闭。
- `proxy_read_timeout 3600s`：生成可能持续很久，读超时不能沿用普通接口的 120 秒。
- `gzip_types` 不能包含 `text/event-stream`：压缩需要攒数据才能生效，会破坏逐帧下发；同时部分代理/浏览器对压缩后的 event-stream 处理不一致，容易出现整段延迟或断流。

静态资源 location 使用 `expires -1` 与 `no-cache`，保证 `index.html` 更新后能立即生效。

---

## 移动端适配

移动端浏览器工具栏和软键盘会改变可视视口，处理不当会把输入区挤出屏幕（曾出现消息区把输入框顶到看不见）。当前做法：

- **`100dvh`**：`100vh` 包含被浏览器工具栏遮挡的区域，`dvh` 跟随动态视口变化，工具栏收缩时页面仍完整可见。
- **`visualViewport`**：`ChatView` 监听 `resize` / `scroll`，把 `visualViewport.height` 和 `offsetTop` 写入 CSS 变量 `--app-vh` / `--app-top`，窄屏容器用它们定位；桌面端（>900px）清除变量，保持原来的 `95vh` 居中布局。
- **安全区**：`index.html` 设置 `viewport-fit=cover`，输入区用 `env(safe-area-inset-bottom)` 留出 iPhone home indicator 的空间。
- **Flex 约束**：`.main-content`、`.control-panel`、`.chat-messages` 都设置 `min-height: 0`，消息列表只在自己的容器内滚动；输入区 `flex: 0 0 auto`，不参与压缩。
- **输入框自增高上限**：桌面 `max-height: 120px`、窄屏 `88px`，超过后输入框内部滚动，不会无限撑高挤掉消息区。

---

## 常见问题

### 端口被占用？

修改 `vite.config.ts` 中的 `server.port`（当前为 9173）。

### 连不上后端？

1. 确认后端已启动（开发默认 9090）。
2. 检查 `.env` 中的 `VITE_BACKEND_URL`。
3. 若请求路径带 `/crmAgent-api`，确认代理或 nginx 已正确剥离前缀。

### 构建后刷新页面 404？

History 路由需要服务端回退到 `index.html`。使用仓库的 `nginx.conf`，或在其他服务器上配置等价的 `try_files`。

### 生产环境接口 404？

检查 `VITE_API_BASE_URL` 的前缀是否与 nginx 暴露的路径一致，并确认转发时去掉了该前缀。

---

## 贡献指南

1. Fork 本仓库
2. 创建特性分支（`git checkout -b feature/xxx`）
3. 提交更改（`git commit -m 'feat: ...'`）
4. 推送分支并开启 Pull Request

提交信息使用语义化前缀：`feat`、`fix`、`docs`、`style`、`refactor`、`perf`、`test`、`chore`。

---

## 许可证

本项目采用 MIT License。

---

## 相关链接

- 后端项目：[SpringAI-MCP-RAG-Dev](https://gitee.com/poison_liying/spring-ai-mcp-rag-dev)
- [Vue 3 文档](https://vuejs.org/)
- [Vite 文档](https://vitejs.dev/)
- [Element Plus 文档](https://element-plus.org/)
- [TypeScript 文档](https://www.typescriptlang.org/)
