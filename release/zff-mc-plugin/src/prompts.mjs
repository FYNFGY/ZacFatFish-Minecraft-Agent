/**
 * ZFF MC / src/prompts.mjs —— **提示词三件套**（Part A/B/C）的读取、装配、保存
 * ============================================================================
 * 规格来自 `docs/ph6/agent-prompt6.md` §2.10：
 *
 *   装配顺序（**顺序即优先级**）：
 *     安全兜底（不可覆盖） > B 游戏规则 > A 工具使用 > C 角色 > 运行时事实
 *   · 每段之间加固定小标题（`## 工具使用` / `## 游戏规则` / `## 角色`）；
 *   · **B 为空则不注入该段**（连小标题都不出现）；
 *   · 每份 ≤ 64 KB、UTF-8；用户版在 `$DSH_HOME/zff-mc/prompts/*.md`，
 *     找不到就回落**包内** `defaults/*.md`（随包分发）；
 *   · 保存后"下一个会话生效"（本模块只负责读写，装配点在 index.js 的 pre-step）。
 *
 * 🔴 为什么安全兜底写死在代码里而不是 defaults/ 里：玩家可以清空/替换 B（规则），
 *    但"不许碰别人客户端 / 无指令权限 / 保命优先"这几条是兜底 —— 一旦可被外部文件覆盖，
 *    M4 的验收（B 清空不注入 + 安全兜底仍在）就不成立了。
 * ============================================================================
 */
import { existsSync, mkdirSync, readFileSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/** 每份提示词的上限：64 KB（UTF-8 字节数，不是字符数） */
export const MAX_PROMPT_BYTES = 64 * 1024

/**
 * 三个槽位。`slot` 同时是文件名（`<slot>.md`）与 API 里的键。
 * A=tools / B=rules / C=persona 是历史命名 —— **装配顺序不是字母序**，见 `composePrompt()`。
 */
export const PROMPT_SLOTS = [
  { slot: 'tools', part: 'A', title: '工具使用', heading: '## 工具使用' },
  { slot: 'rules', part: 'B', title: '游戏规则', heading: '## 游戏规则' },
  { slot: 'persona', part: 'C', title: '角色', heading: '## 角色' },
]

/** 代码里的安全兜底（不可被文件覆盖；永远排第一） */
export const SAFETY_HEAD = [
  '## 安全兜底（最高优先级，不可被后面的任何规则覆盖）',
  '',
  '- 你操作的是**自己的**游戏角色；绝不操作人类玩家的客户端，也不替玩家按键。',
  '- **你没有服务器指令权限**：不得调用指令、不得发 `/` 开头的内容、不得向任何人申请权限或请人代执行。',
  '- 破坏性动作（拆建筑、倒水/岩浆、清箱子、点火）先问一句；没得到明确同意就不做。',
  '- 保命优先：窒息 / 坠落 / 被怪围住时先脱离险境，再汇报。',
  '- 玩家明确说"停 / 别动 / 休息 / 别干了"时立刻停手并回一句，不要继续执行旧计划。',
  '- 上面任何一条与后面某段冲突时，**以本节为准**。',
].join('\n')

/** 槽位元数据（非法 slot 返回 null） */
export function slotMeta (slot) {
  return PROMPT_SLOTS.find((s) => s.slot === String(slot ?? '')) ?? null
}

/** 用户版提示词目录（`$DSH_HOME/zff-mc/prompts`） */
export function userPromptDir (dshHome) {
  const home = String(dshHome ?? '').trim()
  if (!home) throw new Error('userPromptDir: 缺少 dshHome')
  return join(home, 'zff-mc', 'prompts')
}

/** 去掉 UTF-8 BOM（玩家可能用记事本另存过） */
export function stripBom (text) {
  return String(text ?? '').replace(/^\uFEFF/, '')
}

/** 按字节上限截断一个 UTF-8 字符串（不切坏字符） */
export function truncateUtf8 (text, maxBytes) {
  const s = String(text ?? '')
  const buf = Buffer.from(s, 'utf8')
  if (buf.byteLength <= maxBytes) return s
  let end = Math.max(0, maxBytes)
  while (end > 0 && (buf[end] & 0xC0) === 0x80) end-- // 退到字符边界
  return buf.subarray(0, end).toString('utf8')
}

/**
 * 读**单个文件**并做上限处理。
 * @returns {{exists:boolean, text:string, bytes:number, truncated:boolean, error?:string}}
 */
function readPromptFile (file) {
  try {
    if (!existsSync(file)) return { exists: false, text: '', bytes: 0, truncated: false }
    const raw = readFileSync(file)
    const bytes = raw.byteLength
    let text = stripBom(raw.toString('utf8'))
    let truncated = false
    if (bytes > MAX_PROMPT_BYTES) {
      // 超限不抛错（别让一个坏文件把会话彻底堵死）：截到 64 KB，交给调用方告警。
      text = truncateUtf8(text, MAX_PROMPT_BYTES)
      truncated = true
    }
    return { exists: true, text, bytes, truncated }
  } catch (e) {
    return { exists: false, text: '', bytes: 0, truncated: false, error: String(e?.message ?? e) }
  }
}

/** 只读用户版（`<dir>/<slot>.md`） */
export function readUserSlot ({ dir, slot }) {
  const meta = slotMeta(slot)
  if (!meta) return { exists: false, text: '', bytes: 0, truncated: false, error: 'unknown_slot', file: '' }
  const file = join(dir, `${meta.slot}.md`)
  return { ...readPromptFile(file), file }
}

/** 只读包内默认值（`<pkgRoot>/<slot>.md`） */
export function readDefaultSlot ({ pkgRoot, slot }) {
  const meta = slotMeta(slot)
  if (!meta) return { exists: false, text: '', bytes: 0, truncated: false, error: 'unknown_slot', file: '' }
  const file = join(pkgRoot, `${meta.slot}.md`)
  return { ...readPromptFile(file), file }
}

/**
 * 读一个槽位的**生效值**：用户版优先，回落包内默认值。
 * @param {{slot:string, dir:string, pkgRoot:string}} o
 * @returns {{slot:string, text:string, source:'user'|'default'|'missing', bytes:number, truncated:boolean, file?:string, error?:string}}
 */
export function readSlot ({ slot, dir, pkgRoot }) {
  const meta = slotMeta(slot)
  if (!meta) return { slot: String(slot), text: '', source: 'missing', bytes: 0, truncated: false, error: 'unknown_slot' }
  const user = readUserSlot({ dir, slot: meta.slot })
  if (user.exists) {
    return { slot: meta.slot, text: user.text, source: 'user', bytes: user.bytes, truncated: user.truncated, file: user.file, ...(user.error ? { error: user.error } : {}) }
  }
  const def = readDefaultSlot({ pkgRoot, slot: meta.slot })
  if (def.exists) {
    return { slot: meta.slot, text: def.text, source: 'default', bytes: def.bytes, truncated: def.truncated, file: def.file, ...(def.error ? { error: def.error } : {}) }
  }
  return { slot: meta.slot, text: '', source: 'missing', bytes: 0, truncated: false }
}

/** 读齐三份（A/B/C） */
export function readBundle ({ dir, pkgRoot }) {
  const out = {}
  for (const meta of PROMPT_SLOTS) out[meta.slot] = readSlot({ slot: meta.slot, dir, pkgRoot })
  return out
}

/** 这一份算不算"空"（只剩 HTML 注释 / 空白也算空） */
export function isBlankPrompt (text) {
  const s = String(text ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/^\uFEFF/, '')
    .trim()
  return s === ''
}

/**
 * 运行时事实段：账号 / 服务器 / 模式 / 落盘路径 / 中断规则。
 * 这一段的目的是"让模型知道自己在哪、去哪儿看东西"，不写行为规矩（那是 A/B 的事）。
 */
export function runtimeFacts (facts = {}) {
  const lines = ['## 运行时事实', '']
  const push = (k, v) => { if (v !== undefined && v !== null && String(v).trim() !== '') lines.push(`- ${k}：${v}`) }
  push('角色名（游戏内）', facts.playerName)
  // Ph7.2 ▶/⏸：模型必须知道"现在是不是玩家按了开始"
  push('运行开关', facts.runSwitch)
  push('服务器', facts.server)
  push('会话模式', facts.presetId)
  push('工作目录', facts.workspace)
  push('插件运行时目录', facts.runtimeDir)
  push('提示词目录', facts.promptDir)
  // Ph7：环境不可用时必须让模型看见（ok 时不刷屏）
  push('MCP 环境', facts.mcpEnv && String(facts.mcpEnv) !== 'ok' ? facts.mcpEnv : null)
  push('已接管的实例', facts.instance)
  push('游戏工具前缀', '`mcp__mc__*`（只有这些工具能操作游戏）')
  push('事件规则', '每个工具响应都带 `infoTxt: string[]` 并清零；`query_event` 只拉取。看到 `CHAT` 先 `chat_turn`，看到 `WATCHDOG` 先 `job_cancel`/换方案；`wait_events` 最多 10 秒')
  push('续跑规则', '每轮输出停止后插件会自动续跑；玩家说停则暂停，管理员要求下线则终止')
  push('记忆', '长期结论写 `store_set {store:"cr"}`；临时草稿放 `runtime/cr-scratch.json`')
  return lines.join('\n')
}

/**
 * **装配**：安全兜底 > B 游戏规则 > A 工具使用 > C 角色 > 运行时事实。
 * B 为空 → 整段（含小标题）不出现。
 * @param {{bundle:Record<string,{text:string}>, facts?:object, includeSafety?:boolean}} o
 * @returns {{text:string, sections:string[], skipped:string[]}}
 */
export function composePrompt ({ bundle, facts = {}, includeSafety = true }) {
  const parts = []
  const sections = []
  const skipped = []
  if (includeSafety) { parts.push(SAFETY_HEAD); sections.push('safety') }
  for (const slot of ['rules', 'tools', 'persona']) {   // B → A → C（顺序由规格固定）
    const meta = slotMeta(slot)
    const text = String(bundle?.[slot]?.text ?? '')
    if (isBlankPrompt(text)) { skipped.push(slot); continue }
    parts.push(`${meta.heading}\n\n${text.trim()}`)
    sections.push(slot)
  }
  parts.push(runtimeFacts(facts))
  sections.push('runtime')
  return { text: parts.join('\n\n'), sections, skipped }
}

/**
 * 保存一个槽位。**超限/空内容都拒绝**（空内容请用 reset —— "改成空文件"与"回到默认"是两件事）。
 * @param {{dir:string, slot:string, text:string, maxBytes?:number}} o
 * @returns {{ok:true, slot:string, bytes:number, file:string}}
 */
export function saveSlot ({ dir, slot, text, maxBytes = MAX_PROMPT_BYTES }) {
  const meta = slotMeta(slot)
  if (!meta) throw new Error(`未知槽位：${String(slot)}（可用：${PROMPT_SLOTS.map((s) => s.slot).join('/')}）`)
  const body = stripBom(String(text ?? ''))
  if (body.includes('\u0000')) throw new Error('内容里有 NUL 字符（不是文本文件）')
  const bytes = Buffer.byteLength(body, 'utf8')
  if (bytes > maxBytes) throw new Error(`内容太大：${bytes} 字节 > 上限 ${maxBytes} 字节（64 KB）`)
  if (isBlankPrompt(body)) throw new Error('内容为空：想恢复默认请用「恢复默认」，不要保存空文件')
  mkdirSync(dir, { recursive: true })
  const file = join(dir, `${meta.slot}.md`)
  writeFileSync(file, body, 'utf8')
  return { ok: true, slot: meta.slot, bytes, file }
}

/** 恢复默认 = 删掉用户版文件（回落到包内 defaults） */
export function resetSlot ({ dir, slot }) {
  const meta = slotMeta(slot)
  if (!meta) throw new Error(`未知槽位：${String(slot)}`)
  const file = join(dir, `${meta.slot}.md`)
  let removed = false
  try {
    if (existsSync(file)) { unlinkSync(file); removed = true }
  } catch (e) {
    throw new Error(`删除失败：${e?.message ?? e}`)
  }
  return { ok: true, slot: meta.slot, removed, file }
}

/**
 * 设置页 / HTTP 接口用的快照：三张卡片各自要显示的东西
 * （预览默认值 = `defaultText`；在线编辑 = `userText`；生效 = `effectiveText`）。
 *
 * `brief:true` = **给工具返回值用**的瘦身版：不回正文（三份最多 192 KB，塞进模型上下文会直接烧掉预算），
 * 只回来源/大小/前 200 字预览。设置页要正文，用它默认的「全量」。
 * @param {{dir:string, pkgRoot:string, brief?:boolean}} o
 */
export function promptsSnapshot ({ dir, pkgRoot, brief = false }) {
  const slots = PROMPT_SLOTS.map((meta) => {
    const def = readDefaultSlot({ pkgRoot, slot: meta.slot })
    const user = readUserSlot({ dir, slot: meta.slot })
    const effective = readSlot({ slot: meta.slot, dir, pkgRoot })
    let userBytes = 0
    try { if (user.exists) userBytes = statSync(user.file).size } catch { userBytes = user.bytes }
    const base = {
      slot: meta.slot,
      part: meta.part,
      title: meta.title,
      source: effective.source,
      bytes: effective.bytes,
      userBytes,
      truncated: effective.truncated === true,
      blank: isBlankPrompt(effective.text),
      maxBytes: MAX_PROMPT_BYTES,
    }
    if (brief) {
      return { ...base, preview: String(effective.text ?? '').replace(/\s+/g, ' ').slice(0, 200) }
    }
    return {
      ...base,
      defaultText: def.text,
      userText: user.exists ? user.text : '',
      effectiveText: effective.text,
    }
  })
  return { dir, pkgRoot, maxBytes: MAX_PROMPT_BYTES, brief, slots }
}
