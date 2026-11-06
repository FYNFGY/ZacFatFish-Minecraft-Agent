/**
 * ZFF MC / src/settings.mjs —— 插件设置（**玩家可在设置页改的那些**）。
 *
 * 为什么要单独一个文件：插件的静态配置在 profile 的 `cordis.patch.yml` 里（按 id 覆盖 `zff-mc`
 * 的 `config`），那是**启动时**读的，改完要重启。而"游戏账号 / 管理员名单 / 续跑刹车参数"
 * 这类东西玩家会边用边调，必须**保存即生效**、且不需要碰 profile。所以：
 *
 *   生效值 = DEFAULT_CONFIG ← profile 的 cordis config ← `<DSH_HOME>/zff-mc/settings.json`
 *
 * 文件优先（后写覆盖），并且只覆盖文件里**明确出现**的键 —— 这样玩家删掉某项就回到默认/profile 值。
 * 设置页通过 `/api/zff-mc/settings` 读写这个文件，写完后插件把值 apply 到内存里的 cfg（立即生效）。
 *
 * ⚠️ 2026-11-01 按玩家要求**移除了启动器**：插件不再自己开 Minecraft。玩家自己开好游戏、进服，
 *    跟 AI 说一声，AI 用 `mcp__mc__*` 直接接手。这里只剩"账号 / 服务器"两个**事实**（写进提示词）。
 */
import fs from 'node:fs';
import path from 'node:path';

/** 设置文件位置（与提示词目录同级） */
export function settingsFile (homeDir) {
  return path.join(String(homeDir ?? ''), 'zff-mc', 'settings.json')
}

/** 只保留认识的键，避免玩家/前端把垃圾写进来 */
const RESUME_KEYS = [
  'enableResume', 'autoPauseFromChat',
  'idleStreakLimit', 'selfStreakLimit',
  'lowPowerIntervalMs', 'minIntervalMs', 'staleMaxMs',
  'chatWindowMs', 'tickMs',
]
/** 只剩"账号 / 服务器"两个事实（启动器已移除；版本目录/java/内存都随之消失） */
const CONNECTION_KEYS = ['playerName', 'server']

const isObj = (v) => !!v && typeof v === 'object' && !Array.isArray(v)
const numOr = (v, d) => (typeof v === 'number' && Number.isFinite(v) ? v : d)
const strOr = (v, d) => (typeof v === 'string' ? v : d)
const boolOr = (v, d) => (typeof v === 'boolean' ? v : d)
const arrStr = (v, d) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string' && x.trim()).map((x) => x.trim()) : d)

/** 读设置文件；不存在/坏掉都返回 `{}`（绝不抛） */
export function readSettings (homeDir) {
  try {
    const raw = fs.readFileSync(settingsFile(homeDir), 'utf8').replace(/^\uFEFF/, '')
    const parsed = JSON.parse(raw)
    return isObj(parsed) ? parsed : {}
  } catch { return {} }
}

/** 原子写（tmp + rename）；返回写入后的对象 */
export function writeSettings (homeDir, next) {
  const file = settingsFile(homeDir)
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const tmp = `${file}.tmp`
  fs.writeFileSync(tmp, JSON.stringify(next, null, 2) + '\n', 'utf8')
  JSON.parse(fs.readFileSync(tmp, 'utf8'))     // 读回来校验再替换
  fs.renameSync(tmp, file)
  return next
}

/**
 * 把设置文件合并进已经 normalize 过的 cfg（**原地改**，调用方持有同一个对象）。
 * 只覆盖文件里出现的键。
 */
export function applySettings (cfg, settings) {
  if (!isObj(settings)) return cfg
  if (!isObj(cfg.launcher)) cfg.launcher = {}
  if (isObj(settings.connection)) {
    for (const k of CONNECTION_KEYS) {
      if (k in settings.connection) cfg.launcher[k] = strOr(settings.connection[k], cfg.launcher[k] ?? '')
    }
  }
  // 老版本设置文件里可能还有 launcher 段：只认 playerName/server，版本目录之类的忽略
  if (isObj(settings.launcher)) {
    for (const k of CONNECTION_KEYS) {
      if (k in settings.launcher) cfg.launcher[k] = strOr(settings.launcher[k], cfg.launcher[k] ?? '')
    }
  }
  for (const k of RESUME_KEYS) {
    if (!(k in settings)) continue
    if (k === 'enableResume' || k === 'autoPauseFromChat') cfg[k] = boolOr(settings[k], cfg[k])
    else cfg[k] = numOr(settings[k], cfg[k])
  }
  if ('adminPlayers' in settings) cfg.adminPlayers = arrStr(settings.adminPlayers, cfg.adminPlayers)
  if ('modePresets' in settings) {
    const list = arrStr(settings.modePresets, cfg.modePresets)
    if (list.length) cfg.modePresets = list          // 空数组会让"什么都不是 ZFF 模式"，拒绝
  }
  return cfg
}

/**
 * 给设置页看的当前生效值（只挑玩家该调的，不外泄内部字段）。
 * @param extra `{ cwd }` —— 当前工作目录（展示给玩家看"我在哪儿干活"）
 */
export function settingsView (cfg, homeDir, extra = {}) {
  const cwd = typeof extra.cwd === 'string' && extra.cwd.trim() ? extra.cwd.trim() : ''
  return {
    ok: true,
    file: settingsFile(homeDir),
    workspace: cwd,
    connection: {
      playerName: String(cfg.launcher?.playerName ?? 'DSHDeepSeek'),
      server: String(cfg.launcher?.server ?? ''),
    },
    resume: {
      enableResume: cfg.enableResume !== false,
      autoPauseFromChat: cfg.autoPauseFromChat !== false,
      idleStreakLimit: Number(cfg.idleStreakLimit ?? 20),
      selfStreakLimit: Number(cfg.selfStreakLimit ?? 5),
      lowPowerIntervalMs: Number(cfg.lowPowerIntervalMs ?? 60000),
      minIntervalMs: Number(cfg.minIntervalMs ?? 3000),
      staleMaxMs: Number(cfg.staleMaxMs ?? 120000),
      tickMs: Number(cfg.tickMs ?? 2000),
    },
    adminPlayers: Array.isArray(cfg.adminPlayers) ? cfg.adminPlayers.slice() : [],
    modePresets: Array.isArray(cfg.modePresets) ? cfg.modePresets.slice() : ['zff-mc'],
  }
}

/** 把设置页 PUT 上来的 body 规整成可写入的设置对象（只收认识的键） */
export function sanitizeSettingsPatch (body) {
  const out = {}
  if (!isObj(body)) return out
  if (isObj(body.connection)) {
    const c = {}
    for (const k of CONNECTION_KEYS) if (k in body.connection) c[k] = String(body.connection[k] ?? '')
    if (Object.keys(c).length) out.connection = c
  }
  if (isObj(body.resume)) {
    for (const k of RESUME_KEYS) {
      if (!(k in body.resume)) continue
      const v = body.resume[k]
      if (k === 'enableResume' || k === 'autoPauseFromChat') out[k] = v === true
      else if (k === 'idleStreakLimit' || k === 'selfStreakLimit') out[k] = Math.max(1, Math.min(200, numOr(Number(v), 20)))
      else out[k] = Math.max(200, Math.min(3600_000, numOr(Number(v), 2000)))
    }
  }
  if ('adminPlayers' in body) out.adminPlayers = arrStr(body.adminPlayers, [])
  if ('modePresets' in body) {
    const list = arrStr(body.modePresets, [])
    if (list.length) out.modePresets = list
  }
  return out
}

/** 深合并（只两级，够用）：把 patch 叠到上一个设置对象上 */
export function mergeSettings (prev, patch) {
  const base = isObj(prev) ? prev : {}
  const next = { ...base }
  if (isObj(patch.connection)) next.connection = { ...(isObj(base.connection) ? base.connection : {}), ...patch.connection }
  for (const k of RESUME_KEYS) if (k in patch) next[k] = patch[k]
  if ('adminPlayers' in patch) next.adminPlayers = patch.adminPlayers
  if ('modePresets' in patch) next.modePresets = patch.modePresets
  return next
}
