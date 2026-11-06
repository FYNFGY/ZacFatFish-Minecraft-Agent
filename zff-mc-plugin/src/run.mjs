/**
 * ZFF MC / src/run.mjs —— **运行开关**（▶/⏸，由玩家亲手按）
 * ============================================================================
 * 2026-11-01 玩家定下的语义（原话）：
 *   「按钮 ▶ 按下变为 ⏸ 表示开始游戏，⏸ 按一下变为 ▶ 表示结束游戏」
 *   「轮询唤醒 AI Agent 的条件：当我点击开始游戏（会从三角变为 ⏸）这个状态」
 *   「当我关闭 DSH 客户端时状态应该复位」
 *
 * 所以这里只做一件事：把"玩家按了开始/停止"落成 `<workspace>/runtime/zff-run.json`：
 *
 *   { "state": "idle" | "running", "at": 1793…, "by": "ui|agent", "bootId": "…" }
 *
 * · `state=idle`    → 按钮显示 ▶，**不注入任何续跑**（玩家可以自由提问）
 * · `state=running` → 按钮显示 ⏸，满足其它条件时才续跑
 * · `bootId` 是**本次 DSH 启动**随机生成的 id：文件里的 bootId 与当前不同 ⇒ 视为上次启动的残留，
 *   一律复位成 idle 并重写 —— 这就是"关掉 DSH 之后状态复位"，而且不需要插件知道所有工作区。
 * ============================================================================
 */
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { runtimeDir } from './runtime.mjs'

/** 运行开关文件名（放在 `<workspace>/runtime/` 下，与 paused/shutdown 同级） */
export const RUN_FILE = 'zff-run.json'

/** 运行状态文件路径 */
export function runFilePath (workspace) {
  return join(runtimeDir(workspace), RUN_FILE)
}

const VALID = new Set(['idle', 'running'])

/**
 * 读运行状态。
 * @param {string} workspace
 * @param {string} bootId 本次 DSH 启动的 id（由插件生成）
 * @returns {{state:'idle'|'running', at:number, by:string|null, bootId:string, reset:boolean, file:string}}
 */
export function readRun (workspace, bootId, sessionId = null) {
  const file = runFilePath(workspace)
  const fallback = { state: 'idle', at: 0, by: null, bootId, sessionId: null, reset: false, file }
  if (!workspace) return fallback
  let parsed = null
  try {
    if (!existsSync(file)) return { ...fallback, reset: false }
    parsed = JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, ''))
  } catch {
    return fallback
  }
  const state = VALID.has(parsed?.state) ? parsed.state : 'idle'
  const sameBoot = typeof parsed?.bootId === 'string' && parsed.bootId === bootId
  if (!sameBoot) {
    // 上一次 DSH 启动留下的（或没有 bootId 的老文件）：复位成 idle
    const wrote = state === 'running'
    if (wrote) {
      try { writeRun(workspace, 'idle', 'boot-reset', bootId) } catch { /* 尽力 */ }
    }
    return { state: 'idle', at: Number(parsed?.at ?? 0) || 0, by: null, bootId, reset: true, file }
  }
  // 🔴 2026-11-01 需求 1：**按会话隔离**。以前 run 状态只按工作区存，于是"同一个工作区新开一个会话"
  //   会继承上一次的 running → 一进去就被续跑叫醒。现在文件里记 sessionId：
  //   只有**按过 ▶ 的那个会话**才算 running，其它会话一律 idle（不写文件、不打扰）。
  const boundSession = typeof parsed?.sessionId === 'string' && parsed.sessionId.trim() ? parsed.sessionId : null
  // 🔴 2026-11-01 真机第二次踩到：老文件没有 sessionId（升级前的），以前用 `!boundSession` 当"通配"，
  //   于是**任何**会话都继承 running —— 点进对话就被续跑叫醒。现在没有 sessionId 一律不认（=idle），
  //   只有"按 ▶ 的那个会话"才算 running。
  const sameSession = !!boundSession && !!sessionId && boundSession === sessionId
  if (!sameSession) {
    return { state: 'idle', at: Number(parsed?.at ?? 0) || 0, by: null, bootId, sessionId: boundSession, reset: false, file }
  }
  return {
    state,
    at: Number(parsed?.at ?? 0) || 0,
    by: typeof parsed?.by === 'string' ? parsed.by : null,
    bootId,
    sessionId: boundSession,
    reset: false,
    file,
  }
}

/**
 * 写运行状态（原子：tmp + rename）。
 * @param {string} workspace
 * @param {'idle'|'running'} state
 * @param {string} by 谁按的（ui / agent / boot-reset）
 * @param {string} bootId
 * @param {number} [now]
 */
export function writeRun (workspace, state, by, bootId, now = Date.now(), sessionId = null) {
  const s = VALID.has(state) ? state : 'idle'
  const file = runFilePath(workspace)
  mkdirSync(runtimeDir(workspace), { recursive: true })
  const tmp = `${file}.tmp`
  writeFileSync(tmp, JSON.stringify({ state: s, at: now, by: by ?? null, bootId, sessionId: sessionId ?? null }, null, 2) + '\n', 'utf8')
  JSON.parse(readFileSync(tmp, 'utf8'))     // 写回前校验
  renameSync(tmp, file)
  return { state: s, at: now, by: by ?? null, bootId, sessionId: sessionId ?? null, file }
}
