/**
 * ZFF MC / src/runtime.mjs —— `<workspace>/runtime/*.json` 的读写（**唯一的运行时事实来源**）
 * ============================================================================
 * 插件与 MCP server / CR（模型）之间**不共享内存**，只共享这几个小 JSON 文件：
 *
 *   runtime/env-inbox.json      ← MCP server（M1）写：`{"staleMs":120,"at":<ms>,"online":true}`
 *   runtime/cr-active-job.json  → CR（模型）写：`{"jobId":"j-1","tool":"mine_run","expiresAt":<ms>}`
 *   runtime/zff-chat-flag.json  ← MCP server 写：`{"flagUserChat":true,"at":<ms>}`
 *   runtime/zff-paused.json     ← 插件/CR 写：`{"paused":true,"at":<ms>,"by":"chat"}`
 *   runtime/zff-shutdown.json   ← 插件写：`{"terminated":true,"at":<ms>,"reason":"…","by":"<玩家>"}`
 *
 * 🔴 三条铁律：
 *   ① **任何读取都不能抛**（文件可能半截、可能被别的进程正在写）→ 一律 try/catch + 返回 `known:false`；
 *   ② **任何写入都原子**（tmp + rename），否则 MCP server 会读到半截 JSON；
 *   ③ 文件**不存在**不等于"坏"：是"还没人写" —— 判据要能区分 `known:false` 与 `known:true`。
 * ============================================================================
 */
import { existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/** `<workspace>/runtime` */
export function runtimeDir (workspace) {
  return join(String(workspace ?? ''), 'runtime')
}

/** 运行时文件名常量（别处引用这里，避免手打字符串打错） */
export const RUNTIME_FILES = {
  env: 'env-inbox.json',
  job: 'cr-active-job.json',
  flag: 'zff-chat-flag.json',
  paused: 'zff-paused.json',
  shutdown: 'zff-shutdown.json',
}

/** 读 JSON：不存在/坏掉都返回 null（**不抛**） */
export function readJsonSafe (file) {
  try {
    if (!existsSync(file)) return null
    const text = readFileSync(file, 'utf8').replace(/^\uFEFF/, '').trim()
    if (!text) return null
    const v = JSON.parse(text)
    return v && typeof v === 'object' ? v : null
  } catch {
    return null
  }
}

/** 原子写 JSON：先写 `<file>.tmp-<pid>` 再 rename */
export function writeJsonAtomic (file, value) {
  const dir = dirname(file)
  mkdirSync(dir, { recursive: true })
  const tmp = `${file}.tmp-${process.pid}-${Math.random().toString(36).slice(2, 8)}`
  writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n', 'utf8')
  try {
    renameSync(tmp, file)
  } catch (e) {
    try { unlinkSync(tmp) } catch { /* 尽力清理 */ }
    throw e
  }
  return { ok: true, file }
}

const num = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 读一个 workspace 的运行时快照。
 * @param {{workspace:string, now:number, staleMaxMs?:number}} o
 * @returns {{
 *   workspace:string, dir:string, at:number,
 *   env:{known:boolean, online:boolean|null, staleMs:number|null, at:number|null, stale:boolean},
 *   job:{active:boolean, expiresAt:number|null, id:string|null, tool:string|null},
 *   flag:{flagUserChat:boolean, at:number|null},
 *   paused:{paused:boolean, at:number|null, by:string|null, reason:string|null},
 *   shutdown:{terminated:boolean, at:number|null, by:string|null, reason:string|null},
 *   errors:string[]
 * }}
 */
export function readRuntimeSnapshot ({ workspace, now = Date.now(), staleMaxMs = 15_000 }) {
  const dir = runtimeDir(workspace)
  const errors = []
  const snap = {
    workspace: String(workspace ?? ''),
    dir,
    at: now,
    env: { known: false, online: null, staleMs: null, at: null, stale: false },
    job: { active: false, expiresAt: null, id: null, tool: null },
    flag: { flagUserChat: false, at: null },
    paused: { paused: false, at: null, by: null, reason: null },
    shutdown: { terminated: false, at: null, by: null, reason: null },
    errors,
  }

  // ① 环境心跳 —— M1 之后才有；没有就 known:false（不刹车，见 brakes.mjs 第 4 条）
  const env = readJsonSafe(join(dir, RUNTIME_FILES.env))
  if (env) {
    const inner = (env.env && typeof env.env === 'object') ? env.env : env
    const staleMs = num(inner.staleMs)
    const at = num(inner.at) ?? num(inner.ts) ?? num(env.at) ?? num(env.ts)
    const online = typeof inner.online === 'boolean' ? inner.online : (typeof env.online === 'boolean' ? env.online : null)
    // "文件在，但很久没更新" 也算掉线（daemon 死了不会再刷新它）
    const fileStale = at !== null && now - at > staleMaxMs
    snap.env = {
      known: true,
      online,
      staleMs,
      at,
      stale: fileStale || (online === false),
    }
  }

  // ② CR 正在跑的任务
  const job = readJsonSafe(join(dir, RUNTIME_FILES.job))
  if (job) {
    const expiresAt = num(job.expiresAt) ?? num(job.expires_at) ?? num(job.expires) ?? null
    const active = expiresAt !== null && expiresAt > now
    snap.job = {
      active,
      expiresAt,
      id: job.jobId !== undefined ? String(job.jobId) : (job.id !== undefined ? String(job.id) : null),
      tool: job.tool !== undefined ? String(job.tool) : null,
    }
    if (expiresAt === null) errors.push('cr-active-job.json 没有 expiresAt（按"没有 job"处理）')
  }

  // ③ Flag_User_Chat
  const flag = readJsonSafe(join(dir, RUNTIME_FILES.flag))
  if (flag) {
    const v = flag.flagUserChat !== undefined ? flag.flagUserChat : flag.flag
    snap.flag = { flagUserChat: v === true, at: num(flag.at) ?? num(flag.ts) }
  }

  // ④ 暂停标记
  const paused = readJsonSafe(join(dir, RUNTIME_FILES.paused))
  if (paused) {
    snap.paused = {
      paused: paused.paused === true,
      at: num(paused.at) ?? num(paused.ts),
      by: paused.by !== undefined ? String(paused.by) : null,
      reason: paused.reason !== undefined ? String(paused.reason) : null,
    }
  }

  // ⑤ 终止标记（管理员下线后**永久**生效，除非手动删文件）
  const shutdown = readJsonSafe(join(dir, RUNTIME_FILES.shutdown))
  if (shutdown) {
    snap.shutdown = {
      terminated: shutdown.terminated === true,
      at: num(shutdown.at) ?? num(shutdown.ts),
      by: shutdown.by !== undefined ? String(shutdown.by) : null,
      reason: shutdown.reason !== undefined ? String(shutdown.reason) : null,
    }
  }

  return snap
}

/** 写/清暂停标记（`paused=false` 时也写一条，便于排查"谁解除的"） */
export function setPaused ({ workspace, paused, by = 'plugin', reason = null, now = Date.now() }) {
  const file = join(runtimeDir(workspace), RUNTIME_FILES.paused)
  return writeJsonAtomic(file, { paused: paused === true, at: now, by, reason })
}

/** 写终止标记（管理员下线）。写入后本插件**不再注入任何东西**。 */
export function writeShutdownMarker ({ workspace, reason = null, by = null, now = Date.now() }) {
  const file = join(runtimeDir(workspace), RUNTIME_FILES.shutdown)
  return writeJsonAtomic(file, { terminated: true, at: now, reason, by })
}

/** 清掉终止标记（调试用；正式流程不给模型暴露这个动作） */
export function clearShutdownMarker ({ workspace }) {
  const file = join(runtimeDir(workspace), RUNTIME_FILES.shutdown)
  try { if (existsSync(file)) unlinkSync(file); return { ok: true, removed: true } } catch (e) { return { ok: false, error: String(e?.message ?? e) } }
}
