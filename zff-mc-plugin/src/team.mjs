/**
 * ZFF MC / src/team.mjs —— **团队状态**（Ph7.4，玩家要求：随时看到 SR/AR/CR/ESRA/ESRC 在干什么）
 *
 * 数据只有三个来源，按可靠性排：
 *   1. **插件自己**：CR（主 Agent）的 status / 最近一次续跑决策 / 当前 MCP job
 *      → 不需要任何配合，永远准。
 *   2. **自报协议**：子代理（SR/AR/ESRC）开工/收工时写 CR store：
 *        store_set {store:"cr", key:"team.SR", value:{task:"一句话", state:"running", at:<ms>}}
 *      收工写 {state:"done", task:"结果"}。面板读 `<ws>/.mcp-agent/cr-store.json` 的 `team.*`。
 *      → 不写就显示"未上报"，**不会瞎猜**。
 *   3. **mod 看门狗**：ESRA 的告警在 `<ws>/runtime/env-inbox.json` 的 `alerts` 里（stuck 等）。
 *
 * 设计原则：宁可显示"未上报"，也不编造一个看起来像真的状态。
 */
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

/** 2026-11-02: AR/ESRA/ESRC removed; only CR + optional read-only SR remain. */
export const TEAM_ROLES = [
  { role: 'CR', label: '主代理', duty: '聊天 / 决策 / 派 job / 汇报' },
  { role: 'SR', label: '子代理 SR', duty: '只读分析：方案 / 截图 / 路线' },
]

/** 自报超过这么久还标着 running，就标成"可能已结束"（子代理崩了/忘了收工） */
const STALE_MS = 180_000

const readJson = (file) => {
  try {
    if (!existsSync(file)) return null
    return JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''))
  } catch { return null }
}

/** 把毫秒差写成 `m:ss` / `h:mm:ss` */
function dur (ms) {
  const s = Math.max(0, Math.floor(Number(ms) / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = s % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}` : `${m}:${String(ss).padStart(2, '0')}`
}

/**
 * 组装团队面板数据。
 * @param {{workspace:string|null, now?:number, cr?:{status?:string|null, presetId?:string|null, lastDecision?:object|null}}} o
 * @returns {{ok:true, at:number, workspace:string|null, rows:Array, notes:string[]}}
 */
export function buildTeam ({ workspace, now = Date.now(), cr = {} } = {}) {
  const ws = workspace && String(workspace).trim() ? String(workspace).trim() : null
  const notes = []
  const store = ws ? readJson(join(ws, '.mcp-agent', 'cr-store.json')) : null
  const env = ws ? readJson(join(ws, 'runtime', 'env-inbox.json')) : null
  const activeJob = ws ? readJson(join(ws, 'runtime', 'cr-active-job.json')) : null

  const entries = store && typeof store === 'object' && store.entries && typeof store.entries === 'object'
    ? store.entries
    : (store && typeof store === 'object' ? store : {})

  /** 时间戳是否可信：不许早于 1 天前、不许是未来（agent 自填的 `at` 实测有 2025-12-30 这种） */
  const plausibleAt = (x) => {
    const n = Number(x)
    if (!Number.isFinite(n) || n <= 0) return null
    if (n < now - 24 * 3600_000) return null
    if (n > now + 5 * 60_000) return null
    return n
  }

  /**
   * 读一条自报。CR store 的真实形状是 `{ value: {...}, tags, updatedAt, version }` —— 2026-11-01 实测踩过：
   * 直接读 `v.task` 永远是 undefined，`state` 兜底成 'running'，于是面板**恒显示"执行中"**。
   * 所以这里必须解 `.value`；时间优先用 store 自己盖的 `updatedAt`（比 agent 自填的 `at` 可信）。
   */
  const selfReport = (role) => {
    const raw = entries[`team.${role}`]
    if (!raw || typeof raw !== 'object') return null
    const v = raw.value && typeof raw.value === 'object' ? raw.value : raw
    const state = typeof v.state === 'string' && v.state.trim() ? v.state.trim() : 'unknown'
    const storeAt = plausibleAt(raw.updatedAt ?? v.updatedAt)
    const selfAt = plausibleAt(v.at)
    return {
      task: typeof v.task === 'string' && v.task.trim() ? v.task.trim() : null,
      state,
      at: selfAt ?? storeAt,
      atSource: selfAt ? 'self' : (storeAt ? 'store' : null),
      note: typeof v.note === 'string' ? v.note : null,
    }
  }

  const rows = []
  for (const meta of TEAM_ROLES) {
    const row = { role: meta.role, label: meta.label, duty: meta.duty, state: 'unknown', task: null, since: null, sinceMs: null, source: null, note: null }

    if (meta.role === 'CR') {
      row.source = 'plugin'
      const st = typeof cr.status === 'string' ? cr.status : null
      row.state = st === 'running' ? 'running' : st === 'idle' ? 'idle' : 'unknown'
      const d = cr.lastDecision && typeof cr.lastDecision === 'object' ? cr.lastDecision : null
      if (d && d.reason) row.note = `最近决策：${d.action ?? '?'}（${d.reason}）`
      if (activeJob && (activeJob.active === true || activeJob.jobId)) {
        row.task = `${activeJob.tool ?? 'job'}（${activeJob.jobId ?? '?'}）`
        row.state = 'running'
        row.source = 'plugin+job'
        if (activeJob.at) { row.since = activeJob.at; row.sinceMs = now - Number(activeJob.at) }
      }
    } else if (meta.role === 'ESRA') {
      row.source = 'mod'
      const alerts = Array.isArray(env?.alerts) ? env.alerts : []
      const recent = alerts
        .filter((a) => a && typeof a === 'object')
        .map((a) => ({ kind: String(a.kind ?? a.type ?? '?'), at: Number(a.at ?? 0) || null, text: String(a.text ?? a.message ?? '') }))
        .filter((a) => !a.at || now - a.at <= 60_000)
      if (recent.length) {
        const a = recent[recent.length - 1]
        row.state = 'alert'
        row.task = `${a.kind}${a.text ? `：${a.text}` : ''}`
        row.since = a.at
        row.sinceMs = a.at ? now - a.at : null
      } else if (env) {
        row.state = 'ok'
        row.task = '无告警'
      }
    } else {
      // SR / AR / ESRC：自报
      const rep = selfReport(meta.role)
      if (!rep) {
        row.source = 'none'
        row.state = 'unreported'
        row.task = null
      } else {
        row.source = 'self-report'
        row.task = rep.task
        row.state = rep.state
        // 只有"正在跑"才谈"持续多久"；done/failed 显示时长没有意义（玩家会以为它还在跑）
        if (rep.state === 'running') {
          row.since = rep.at
          row.sinceMs = rep.at ? now - rep.at : null
          if (!rep.at) row.note = [row.note, '缺可信时间戳（子代理自填的 at 不可靠，已忽略）'].filter(Boolean).join(' · ')
        } else if (rep.at) {
          row.since = rep.at
          row.sinceMs = null
          row.sinceText = '—'
        }
        if (rep.note) row.note = [row.note, rep.note].filter(Boolean).join(' · ')
        if (rep.state === 'running' && rep.at && now - rep.at > STALE_MS) {
          row.state = 'stale'
          row.note = [row.note, `自报已超 ${dur(now - rep.at)}，可能已结束但没写 done`].filter(Boolean).join(' · ')
        }
      }
    }

    if (row.sinceMs !== null && row.sinceMs !== undefined) row.sinceText = dur(row.sinceMs)
    rows.push(row)
  }

  if (!ws) notes.push('没有工作目录：读不到 CR store / env-inbox')
  else {
    if (!store) notes.push(`没有找到 ${join(ws, '.mcp-agent', 'cr-store.json')}（子代理还没上报过）`)
    if (!env) notes.push('没有找到 runtime/env-inbox.json（mod 还没推过环境）')
  }

  return { ok: true, at: now, workspace: ws, rows, notes }
}

/** 给面板渲染用的一句话摘要（一行） */
export function summarizeRow (row) {
  const map = {
    running: '执行中', idle: '空闲', ok: '正常', alert: '告警', unreported: '未上报', stale: '可能已结束', unknown: '未知',
  }
  const state = map[row?.state] ?? row?.state ?? '未知'
  const task = row?.task ? row.task : (row?.state === 'unreported' ? '（还没上报）' : '—')
  return `${state} · ${task}`
}
