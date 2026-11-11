/**
 * 追问（clarify）解析工具。
 *
 * 模型输出的 <clarify> 块结构并不总是与提示词约定完全一致，常见异常包括：
 * - 字段名不同（options / choices / items / values / list）
 * - JSON 不合法（尾逗号、中文引号、未闭合、混入说明文字）
 * - 候选项为空数组，或只有 question
 * - 块被截断、未写 </clarify> 结束标签
 * - 用 markdown 列表代替 JSON
 *
 * 旧实现只要 JSON.parse 失败或缺少 candidates 就直接丢弃整块，
 * 结果界面上只剩“请选择：”这类半句话。这里统一做结构归一化 + 文本兜底还原，
 * 保证不会静默丢掉用户的可选项。
 */
import type { ClarifyCandidate, ClarifyData } from '@/types'

/** <clarify>…</clarify>；兼容大小写、属性、以及缺失结束标签（吞到文末） */
const BLOCK_PATTERN = /<clarify\b[^>]*>([\s\S]*?)(?:<\/clarify\s*>|$)/gi

/** 候选项数组可能出现的字段名 */
const CANDIDATE_KEYS = ['candidates', 'options', 'choices', 'items', 'values', 'list', 'candidateList']

const TYPE_KEYS = ['type', 'kind', 'category', 'mode']
const QUESTION_KEYS = ['question', 'title', 'prompt', 'ask']

const LABEL_KEYS = [
  'label',
  'name',
  'text',
  'title',
  'storeName',
  'store_name',
  'memberName',
  'member_name',
  'displayName',
  'display_name',
]

const CODE_KEYS = [
  'code',
  'value',
  'id',
  'key',
  'storeCode',
  'store_code',
  'memberId',
  'member_id',
  'memberNo',
  'member_no',
]

const META_KEYS = ['meta', 'desc', 'description', 'hint', 'sub', 'note', 'remark']

function pick(obj: Record<string, unknown>, keys: string[]): unknown {
  for (const key of keys) {
    const value = obj[key]
    if (value !== undefined && value !== null && value !== '') return value
  }
  return undefined
}

function stripFence(text: string): string {
  return text
    .trim()
    .replace(/^```[a-zA-Z0-9_-]*\s*/, '')
    .replace(/```\s*$/, '')
    .trim()
}

/** 修复常见的“近似 JSON”问题 */
function repairJson(text: string): string {
  return text
    .replace(/[\u201c\u201d\u300c\u300d\u300e\u300f]/g, '"')
    .replace(/,\s*([}\]])/g, '$1')
    .trim()
}

function tryJsonParse(text: string): unknown {
  try {
    return JSON.parse(repairJson(text))
  } catch {
    return undefined
  }
}

/** 从含噪文本里截取最外层 {…} 后解析 */
function parseOutermostObject(text: string): unknown {
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start < 0 || end <= start) return undefined
  return tryJsonParse(text.slice(start, end + 1))
}

/** 找到候选数组并整体解析；失败时逐个对象抢救 */
function extractArray(text: string): unknown[] {
  const keyPattern = new RegExp(`"?(${CANDIDATE_KEYS.join('|')})"?\\s*:\\s*\\[`, 'i')
  const keyMatch = keyPattern.exec(text)
  if (!keyMatch) return []
  const start = text.indexOf('[', keyMatch.index)
  if (start < 0) return []

  let depth = 0
  let inString = false
  let escaped = false
  for (let i = start; i < text.length; i++) {
    const char = text[i]
    if (inString) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') inString = false
      continue
    }
    if (char === '"') inString = true
    else if (char === '[') depth++
    else if (char === ']') {
      depth--
      if (depth === 0) {
        const parsed = tryJsonParse(text.slice(start, i + 1))
        if (Array.isArray(parsed)) return parsed
        return splitObjects(text.slice(start + 1, i))
      }
    }
  }
  // 数组没有闭合（被截断）：对剩余内容逐个对象抢救
  return splitObjects(text.slice(start + 1))
}

/** 把 `{…},{…}` 片段逐个解析，跳过坏对象 */
function splitObjects(text: string): unknown[] {
  const result: unknown[] = []
  let depth = 0
  let inString = false
  let escaped = false
  let start = -1
  for (let i = 0; i < text.length; i++) {
    const char = text[i]
    if (inString) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') inString = false
      continue
    }
    if (char === '"') inString = true
    else if (char === '{') {
      if (depth === 0) start = i
      depth++
    } else if (char === '}') {
      depth--
      if (depth === 0 && start >= 0) {
        const parsed = tryJsonParse(text.slice(start, i + 1))
        if (parsed !== undefined) result.push(parsed)
        start = -1
      }
    }
  }
  return result
}

function normalizeCandidate(raw: unknown): ClarifyCandidate | null {
  if (typeof raw === 'string' || typeof raw === 'number') {
    const label = String(raw).trim()
    return label ? { code: label, label } : null
  }
  if (!raw || typeof raw !== 'object') return null
  const obj = raw as Record<string, unknown>
  const labelRaw = pick(obj, LABEL_KEYS)
  const codeRaw = pick(obj, CODE_KEYS)
  const metaRaw = pick(obj, META_KEYS)
  const label = labelRaw !== undefined ? String(labelRaw).trim() : codeRaw !== undefined ? String(codeRaw).trim() : ''
  if (!label) return null
  const candidate: ClarifyCandidate = { code: codeRaw !== undefined ? String(codeRaw) : label, label }
  if (metaRaw !== undefined) candidate.meta = String(metaRaw)
  return candidate
}

function normalizeCandidateList(raw: unknown): ClarifyCandidate[] {
  if (!raw) return []
  let list: unknown[] = []
  if (Array.isArray(raw)) list = raw
  else if (typeof raw === 'object') {
    // 兼容 { "STORE_LEVEL": "门店级别" } 这种键值对写法
    list = Object.entries(raw as Record<string, unknown>).map(([key, value]) =>
      typeof value === 'object' && value !== null ? { code: key, ...(value as object) } : { code: key, label: value },
    )
  }
  const seen = new Set<string>()
  const result: ClarifyCandidate[] = []
  for (const item of list) {
    const candidate = normalizeCandidate(item)
    if (!candidate) continue
    const dedupeKey = `${candidate.code}__${candidate.label}`
    if (seen.has(dedupeKey)) continue
    seen.add(dedupeKey)
    result.push(candidate)
  }
  return result
}

/** 把任意形态的 clarify JSON 归一化成 ClarifyData */
function normalizeClarify(parsed: unknown): ClarifyData | null {
  if (Array.isArray(parsed)) {
    const candidates = normalizeCandidateList(parsed)
    return candidates.length ? { type: 'ambiguity', candidates } : null
  }
  if (!parsed || typeof parsed !== 'object') return null
  const obj = parsed as Record<string, unknown>
  const typeRaw = pick(obj, TYPE_KEYS)
  const questionRaw = pick(obj, QUESTION_KEYS)

  let candidatesRaw: unknown
  for (const key of CANDIDATE_KEYS) {
    if (obj[key] !== undefined && obj[key] !== null) {
      candidatesRaw = obj[key]
      break
    }
  }
  const candidates = normalizeCandidateList(candidatesRaw)
  if (!candidates.length && questionRaw === undefined && typeRaw === undefined) return null
  return {
    type: typeRaw !== undefined ? String(typeRaw) : 'ambiguity',
    ...(questionRaw !== undefined ? { question: String(questionRaw) } : {}),
    candidates,
  }
}

/** JSON 已损坏时，从原文里用正则抢救 type / question */
function recoverMeta(text: string): { type?: string; question?: string } {
  const result: { type?: string; question?: string } = {}
  const typeMatch = /"?(?:type|kind|category|mode)"?\s*:\s*"?([A-Za-z_]+)"?/i.exec(text)
  if (typeMatch?.[1]) result.type = typeMatch[1]
  const questionMatch = /"?(?:question|title|prompt|ask)"?\s*:\s*"([^"]*)"/i.exec(text)
  if (questionMatch?.[1]) result.question = questionMatch[1]
  return result
}

/**
 * 从无法解析的块内容里尽量还原候选项：
 * 1) 逐个 JSON 对象抢救（能拿到 code/label 就用）
 * 2) markdown 列表行（- 门店级别 STORE_LEVEL / 1. 门店级别（STORE_LEVEL））
 */
function recoverCandidates(text: string): ClarifyCandidate[] {
  const cleaned = stripFence(text)
  const fromArray = normalizeCandidateList(extractArray(cleaned))
  if (fromArray.length) return fromArray
  const fromObjects = normalizeCandidateList(splitObjects(cleaned))
  if (fromObjects.length) return fromObjects

  const candidates: ClarifyCandidate[] = []
  for (const line of cleaned.split(/\r?\n/)) {
    const itemMatch = /^\s*(?:[-*+]|\d+[.)])\s+(.+?)\s*$/.exec(line)
    if (!itemMatch) continue
    const item = (itemMatch[1] ?? '').replace(/\*\*/g, '').trim()
    if (!item) continue
    const parenMatch = /^(.+?)[（(]([^()（）]+)[)）]\s*$/.exec(item)
    if (parenMatch) {
      const code = (parenMatch[2] ?? '').trim()
      const label = (parenMatch[1] ?? '').trim()
      if (label && code) candidates.push({ code, label })
      continue
    }
    const parts = item.split(/\s*[：:|｜]\s*/)
    if (parts.length >= 2) {
      const code = (parts[1] ?? '').trim()
      const label = (parts[0] ?? '').trim()
      if (label && code) candidates.push({ code, label })
      continue
    }
    const tokens = item.split(/\s+/)
    const lastToken = tokens[tokens.length - 1] ?? ''
    if (tokens.length >= 2 && /^[A-Za-z0-9_=:-]+$/.test(lastToken)) {
      candidates.push({ code: lastToken, label: tokens.slice(0, -1).join(' ') })
      continue
    }
    candidates.push({ code: item, label: item })
  }
  return normalizeCandidateList(candidates)
}

/**
 * 没有 <clarify> 块、但模型用“请选择：”+ markdown 列表给出选项时的兜底。
 * 只有列表项 ≥2 且每项足够短才转换，避免误伤正常回答。
 */
function extractMarkdownListClarify(
  markdown: string,
): { data: ClarifyData; markdown: string } | null {
  const lines = markdown.split(/\r?\n/)
  let leadIndex = -1
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = (lines[i] ?? '').trim()
    if (line === '') continue
    // 从末尾向前跳过列表项，最终定位到“请选择：”这类引导语
    if (/^(?:[-*+]|\d+[.)])\s+/.test(line)) continue
    if (/请(?:选择|指定|确认|明确)[^。！？\n]{0,10}[：:]\s*$/.test(line)) {
      leadIndex = i
    }
    break
  }
  if (leadIndex < 0) return null

  const items: string[] = []
  let lastItemIndex = leadIndex
  for (let i = leadIndex + 1; i < lines.length; i++) {
    const trimmed = (lines[i] ?? '').trim()
    if (trimmed === '') continue
    const itemMatch = /^(?:[-*+]|\d+[.)])\s+(.+)$/.exec(trimmed)
    if (!itemMatch) break
    const label = (itemMatch[1] ?? '').replace(/\*\*/g, '').trim()
    if (!label || label.length > 40) return null
    items.push(label)
    lastItemIndex = i
  }
  if (items.length < 2) return null

  const candidates = normalizeCandidateList(
    items.map((label) => {
      const parenMatch = /^(.+?)[（(]([^()（）]+)[)）]\s*$/.exec(label)
      if (!parenMatch) return { label, code: label }
      return { label: (parenMatch[1] ?? '').trim(), code: (parenMatch[2] ?? '').trim() }
    }),
  )
  if (!candidates.length) return null

  const remaining = [...lines.slice(0, leadIndex + 1), ...lines.slice(lastItemIndex + 1)]
  return {
    data: { type: 'ambiguity', candidates },
    markdown: remaining.join('\n').replace(/\n{3,}/g, '\n\n').trim(),
  }
}

export interface ClarifyParseOutput {
  /** 移除 clarify 标记后的 markdown 原文 */
  markdown: string
  /** 可渲染的追问卡片（可能包含无候选项的兜底卡） */
  clarifyList: ClarifyData[]
  clarifyData: ClarifyData | null
}

/**
 * 解析模型输出中的追问信息。
 * 无论结构是否合规都会返回可渲染结果：能还原选项就还原，还原不了给兜底卡。
 */
export function parseClarifyMarkdown(rawMarkdown: string): ClarifyParseOutput {
  const blocks: Array<{ inner: string; start: number; end: number }> = []
  BLOCK_PATTERN.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = BLOCK_PATTERN.exec(rawMarkdown)) !== null) {
    blocks.push({ inner: match[1] ?? '', start: match.index, end: match.index + match[0].length })
  }

  const clarifyList: ClarifyData[] = []
  for (const block of blocks) {
    const normalized = normalizeClarify(
      tryJsonParse(stripFence(block.inner)) ?? parseOutermostObject(block.inner),
    )
    if (normalized && normalized.candidates.length) {
      clarifyList.push(normalized)
      continue
    }
    // JSON 坏了但引导语/类型还能救回来
    const recoveredMeta = recoverMeta(block.inner)
    const base: ClarifyData = {
      type: normalized?.type ?? recoveredMeta.type ?? 'ambiguity',
      ...(normalized?.question ?? recoveredMeta.question
        ? { question: normalized?.question ?? recoveredMeta.question }
        : {}),
      candidates: [],
    }
    const recovered = recoverCandidates(block.inner)
    if (recovered.length) {
      clarifyList.push({ ...base, candidates: recovered })
      continue
    }
    // 完全无法还原：保留占位卡，避免只显示半句话
    clarifyList.push({ ...base, fallback: true })
  }

  // 移除所有标记块（倒序删除，避免下标错位）
  let markdown = rawMarkdown
  for (const block of [...blocks].sort((a, b) => b.start - a.start)) {
    markdown = markdown.slice(0, block.start) + markdown.slice(block.end)
  }
  markdown = markdown.replace(/\n{3,}/g, '\n\n').trim()

  // 没有标记块时，兼容“请选择：+ markdown 列表”的写法
  if (!blocks.length) {
    const listed = extractMarkdownListClarify(markdown)
    if (listed) {
      clarifyList.push(listed.data)
      markdown = listed.markdown
    }
  }

  // 仍然没有可渲染内容，但回答以“请选择：”收尾 → 明确提示用户补充说明
  if (!clarifyList.length && /请(?:选择|指定|确认|明确)[^。！？\n]{0,10}[：:]\s*$/.test(markdown)) {
    markdown += '\n\n（没有获取到可选项，请直接补充说明你想按哪种口径查询。）'
  }

  return { markdown, clarifyList, clarifyData: clarifyList[0] ?? null }
}
