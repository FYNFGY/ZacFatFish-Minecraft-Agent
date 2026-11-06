/**
 * ZFF MC / src/chat.mjs —— 通过 **daemon（127.0.0.1:57611）** 只读 `chat_history`
 * ============================================================================
 * 为什么要绕 daemon 而不是直连 MCP：
 *   · 直连 `http://127.0.0.1:57610/mcp` 需要 MCP SDK（streamable-http 会话），多一个依赖；
 *   · daemon 已经是一个常驻会话，接口就一行：
 *       `POST http://127.0.0.1:57611/call {"tool":"chat_history","args":{"limit":40},"timeoutMs":5000}`
 *       → `{ok, ms, isError, json, text}`      （见 `mcp-server/src/mc-daemon.mjs:3,76-92`）
 *   · 插件只做**只读复核**（管理员下线 / 玩家叫停），从不在这里发消息。
 *
 * `chat_history` 的返回形状（真机证据 `evidence/chat-history-after-send.json`）：
 *   `{"ok":true,"messages":[{"time":<ms>,"type":"chat"|"system","sender":"名字","text":"…","outgoing":true}]}`
 * ============================================================================
 */

/** 默认 daemon 地址（与本仓库 `start-all.ps1` 一致） */
export const DEFAULT_DAEMON_URL = 'http://127.0.0.1:57611'

/** 管理员复核窗口：最近 60 s 内说过话（M4 规格） */
export const ADMIN_WINDOW_MS = 60_000

/** 默认"叫停"正则：玩家说停/别动/休息/别干了/下班 → 暂停续跑 */
export const DEFAULT_PAUSE_REGEX = '停|别动|别干|别做|休息|下班|挂机|不用你'

const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 调一次 daemon 的工具（**只读用途**）。
 * @param {{baseUrl?:string, tool:string, args?:object, timeoutMs?:number, fetchImpl?:Function}} o
 * @returns {Promise<{ok:boolean, ms?:number, json:any, text?:string, error?:string}>}
 */
export async function callDaemonTool ({ baseUrl, tool, args = {}, timeoutMs = 5000, fetchImpl = globalThis.fetch } = {}) {
  const url = `${String(baseUrl || DEFAULT_DAEMON_URL).replace(/\/+$/, '')}/call`
  if (typeof fetchImpl !== 'function') return { ok: false, json: null, error: 'no_fetch' }
  const ac = new AbortController()
  const timer = setTimeout(() => { try { ac.abort() } catch { /* 已结束 */ } }, Math.max(500, timeoutMs))
  timer.unref?.()
  try {
    const res = await fetchImpl(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'application/json' },
      body: JSON.stringify({ tool, args, timeoutMs }),
      signal: ac.signal,
    })
    const text = await res.text()
    let json = null
    try { json = text ? JSON.parse(text) : null } catch { json = null }
    if (!res.ok) return { ok: false, json, text, error: `http_${res.status}` }
    if (!json || json.ok !== true) return { ok: false, json, text, error: String(json?.error ?? 'daemon_error') }
    return { ok: true, ms: num(json.ms) ?? undefined, json: json.json, text: json.text }
  } catch (e) {
    return { ok: false, json: null, error: String(e?.name === 'AbortError' ? 'timeout' : (e?.message ?? e)) }
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 把 `chat_history` 的各种可能形状归一成 `[{time,type,sender,text,outgoing}]`。
 * 宽容一点：`messages` 可能在外面也可能在 `json.messages` / `json.data.messages`。
 */
export function normalizeChatMessages (payload) {
  const list = Array.isArray(payload)
    ? payload
    : (Array.isArray(payload?.messages) ? payload.messages
      : (Array.isArray(payload?.data?.messages) ? payload.data.messages
        : (Array.isArray(payload?.json?.messages) ? payload.json.messages : [])))
  const out = []
  for (const m of list) {
    if (!m || typeof m !== 'object') continue
    out.push({
      time: num(m.time) ?? num(m.ts) ?? num(m.at) ?? 0,
      type: String(m.type ?? 'chat'),
      sender: m.sender !== undefined && m.sender !== null ? String(m.sender) : (m.who !== undefined ? String(m.who) : null),
      text: String(m.text ?? m.message ?? ''),
      outgoing: m.outgoing === true || m.self === true,
    })
  }
  return out
}

/**
 * 只保留**玩家说的话**（`type==='chat'` 且不是自己发的），并且只看最近 `windowMs`。
 * @param {{messages:Array, now:number, windowMs?:number}} o
 */
export function recentPlayerChats ({ messages, now = Date.now(), windowMs = ADMIN_WINDOW_MS }) {
  const from = now - Math.max(0, Number(windowMs) || 0)
  return (Array.isArray(messages) ? messages : [])
    .filter((m) => m && m.type === 'chat' && m.outgoing !== true)
    .filter((m) => !m.time || m.time >= from)   // 没有时间戳的消息也算"最近"（宁可放过，不可错杀）
    .sort((a, b) => (a.time ?? 0) - (b.time ?? 0))
}

/**
 * 最近窗口内，**有没有管理员说过话**（管理员下线工具的唯一授权依据）。
 * @param {{messages:Array, admins:string[], now:number, windowMs?:number}} o
 * @returns {{found:boolean, sender:string|null, at:number|null, text:string|null, checked:number}}
 */
export function findAdminSender ({ messages, admins, now = Date.now(), windowMs = ADMIN_WINDOW_MS }) {
  const adminSet = new Set((Array.isArray(admins) ? admins : []).map((a) => String(a ?? '').trim().toLowerCase()).filter(Boolean))
  const recent = recentPlayerChats({ messages, now, windowMs })
  if (!adminSet.size) return { found: false, sender: null, at: null, text: null, checked: recent.length }
  // 从最新往旧找：谁最后说话谁负责
  for (let i = recent.length - 1; i >= 0; i--) {
    const m = recent[i]
    const who = String(m.sender ?? '').trim().toLowerCase()
    if (who && adminSet.has(who)) return { found: true, sender: m.sender, at: m.time || null, text: m.text, checked: recent.length }
  }
  return { found: false, sender: null, at: null, text: null, checked: recent.length }
}

/**
 * 玩家"叫停 / 解除叫停"的判定（**纯函数**，插件在每次 turn-end 读一次聊天就够）。
 *
 * 规则（M4 规格）：玩家说"停/别动/休息/别干了" → `paused=true`；
 * **玩家再发言**（任何一条比"叫停"更新的玩家消息）→ 解除。
 *
 * @param {{messages:Array, now:number, windowMs?:number, pauseRegex?:string, pausedAt?:number|null}} o
 * @returns {{action:'pause'|'resume'|'none', at:number|null, text:string|null, sender:string|null, reason?:string}}
 */
export function detectPauseVote ({ messages, now = Date.now(), windowMs = ADMIN_WINDOW_MS, pauseRegex = DEFAULT_PAUSE_REGEX, pausedAt = null }) {
  const recent = recentPlayerChats({ messages, now, windowMs })
  if (!recent.length) return { action: 'none', at: null, text: null, sender: null }
  let re = null
  try { re = new RegExp(pauseRegex) } catch { re = null }
  if (!re) return { action: 'none', at: null, text: null, sender: null, reason: 'bad_regex' }
  const last = recent[recent.length - 1]
  // 最新一条命中"叫停" → 暂停
  if (re.test(String(last.text ?? ''))) {
    return { action: 'pause', at: last.time || now, text: last.text, sender: last.sender }
  }
  // 已经暂停，且有一条**比暂停时刻更新**的玩家消息 → 解除（"玩家再发言 → 解除"）
  if (pausedAt !== null && pausedAt !== undefined) {
    const newer = recent.find((m) => (m.time || 0) > Number(pausedAt))
    if (newer) return { action: 'resume', at: newer.time || now, text: newer.text, sender: newer.sender }
  }
  return { action: 'none', at: null, text: null, sender: recent[recent.length - 1]?.sender ?? null }
}
