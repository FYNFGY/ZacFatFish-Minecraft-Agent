/**
 * ZFF MC —— DSH 插件主体（Node / ESM）
 * ============================================================================
 * 这个插件做四件事（对应 Ph6 M4 的四条验收）：
 *   ① **自动续跑**：CR 每轮输出停止后注入续跑文案（刹车规则见 src/brakes.mjs）；
 *   ② **提示词三件套**：A/B/C 三份 `.md` 拼进每轮请求的消息最前面（`agent/pre-step`）；
 *   ③ **管理员下线** `zff_shutdown`：用只读 `chat_history` 复核 `adminPlayers`，通过才写 terminated；
 *   ④ **启动器**：给 `versions/<ver>` 上推两级拼出 java 启动命令（只拼不跑，除非显式 launch）。
 *
 * ── 三条"证据驱动"的硬决定（详见 docs/ph6/PH6-M0.5-PLUGIN-API.md）────────────────
 *   A. **没有 turn-end 钩子**（whale_craft 全仓库 0 命中）→ 用 `setInterval` 看 `agent.status`，
 *      `running → 非 running` 的那一跳算"本轮结束"。⚠️ 定时器回调里抛错是 uncaughtException，
 *      会把宿主带走 ⇒ 每个 tick 都 try/catch（whale_craft 踩过：watchdog.mjs:282）。
 *   B. **注入只用两条已验证通道**：`agent.steer(plugin 消息)`（空闲会起一轮）+ 改写
 *      `agent/pre-step` 的 `decision.messages`（本步立即生效）。**不挂** `system-prompt/assemble`
 *      （载荷形状未验证，见文档 §1 末尾）。
 *   C. **消息的 `source.kind` 必须是 `plugin:zff-mc`**：v4 会话格式拒收裸露的 `'plugin'`，
 *      而宿主 `createUserMessage` 对 source 原样透传、不会替你修（whale_craft/src/user-message.mjs:19-23）。
 *
 * ── 依赖约束 ─────────────────────────────────────────────────────────────
 *   本文件**不 import 任何 `@deepseek-ai/*`**（它们是 optional peer，装上别人的机器就解析不到，
 *   会在模块链接期把插件搞挂）。宿主服务一律 `ctx.get()` 现取 + try/catch。
 * ============================================================================
 */
import { spawn as nodeSpawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { appendFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  DEFAULT_BRAKES,
  decideResume, settleStreaks, streakLevel, isInjectingAction,
} from './src/brakes.mjs'
import {
  PROMPT_SLOTS, slotMeta, isBlankPrompt,
  composePrompt, readBundle, readSlot,
  promptsSnapshot, saveSlot, resetSlot, userPromptDir,
} from './src/prompts.mjs'
import { readRuntimeSnapshot, setPaused, runtimeDir } from './src/runtime.mjs'
import {
  DEFAULT_DAEMON_URL, DEFAULT_PAUSE_REGEX, callDaemonTool, detectPauseVote, normalizeChatMessages,
} from './src/chat.mjs'
import { verifyAdminAndMark } from './src/shutdown.mjs'
import { applySettings, mergeSettings, readSettings, sanitizeSettingsPatch, settingsView, writeSettings } from './src/settings.mjs'
import { DEFAULT_MCP_SERVER, chooseInstance, ensureDaemon, ensureServer, explainBlocked, fetchInstances, healthOk, mcpBase } from './src/mcp-env.mjs'
import { makeTool } from './src/tool-def.mjs'
import { readRun, writeRun } from './src/run.mjs'
import { buildTeam } from './src/team.mjs'
import { mcpStatus, sendHeartbeat, startMcp, stopMcp, stopMcpSync } from './src/mcp-control.mjs'

/** 插件名（宿主事件/日志里用它；`source.kind` 用 `plugin:zff-mc`） */
export const name = 'zff-mc'

/** 需要宿主就绪的服务：工具注册 + HTTP 路由（设置页的数据面） */
export const inject = ['tools', 'webServer']

/** `source.kind` 的 v4 规范值 —— 改这里必须同时改 selfcheck 的断言 */
export const SOURCE_KIND = 'plugin:zff-mc'

/** ZFF MC Mode 里**额外**允许的宿主工具（文件/待办；其余靠 `mcp__mc__*`） */
/**
 * ZFF MC Mode 里允许的**宿主工具**。
 * 2026-11-01（玩家决定 A）：加入子代理五件套 —— CR 必须能真的 spawn / 管理 SR / AR / ESRC，
 * 否则架构里的子代理只是纸上谈兵（团队面板永远只有 CR 和 ESRA 两行）。
 *   subagent / subagent_fork                     —— 派活（SR / AR / ESRC）
 *   list_agents / send_message / interrupt_agent —— 管理（CR 抢占 AR 用 interrupt_agent）
 */
export const ALLOWED_HOST_TOOLS = [
  'read', 'write', 'edit', 'glob', 'grep', 'read_image', 'todo_write',
  'subagent', 'subagent_fork', 'list_agents', 'send_message', 'interrupt_agent',
]

/**
 * Ph7.2：玩家按 ▶（= 结束游戏）之后，**只读**的游戏工具仍然放行 —— 这样玩家问
 * 「我在哪 / 现在几点 / 背包里有什么」时 AI 还能看；任何会改变世界的工具一律拒绝。
 * 名字都是 mcp__mc__ 后面的部分。
 */
export const READONLY_GAME_TOOLS = [
  // —— 读状态 ——
  'get_state', 'env_read', 'bridge_info', 'mc_instances',
  'block_query', 'entity_query', 'collision_query', 'scan_area', 'scan_connected', 'scan_surface',
  'light_map', 'inventory_slots', 'hotbar', 'item_info', 'read_book', 'read_sign',
  'container_read', 'villager_trades', 'jobs_list', 'job_status', 'path_status', 'tool_usage',
  'damage_log', 'ghosts', 'ore_log', 'recipe_lookup', 'zff_chat_stats', 'zff_status',
  'screenshot', 'screenshot_gui', 'query_event', 'wait_events',
  'query_env_info', 'query_layer', 'query_layer_foot', 'reachable_synthese', 'gui_describe',
  'combat_status',
  // —— 聊天与记账：**不改世界**，但"结束游戏"后仍必须可用 ——
  // 🔴 2026-11-01 P0-1：以前只放行"只读"，于是 ▶ 时连 chat_turn / store_set 都被拒：
  //    玩家按停后 agent 变哑巴、子代理收尾写不了 done（面板永远烂尾成 running）。
  'chat_turn', 'chat', 'chat_history',
  'store_get', 'store_set', 'store_list', 'store_delete',
  'memory_read', 'memory_write', 'memory_list',
]

/**
 * ZFF MC Mode 里**想隐藏**的宿主工具（只是可见性，安全靠 guard 兜底）。
 * 名字可能是"这个部署里不存在"的 —— 宿主 `restrict({deny})` 对不认识的工具名**会抛错**
 * （whale_craft/index.js:4321 的注释），所以 `applyToolPolicy()` 逐个试、失败就跳过。
 */
export const HIDE_HOST_TOOLS = [
  'pwsh', 'bash', 'subprocess', 'workflow',
  'present', 'web_fetch', 'web_search', 'skill', 'schedule_create', 'schedule_list',
  'schedule_delete', 'schedule_update', 'list_mcp_resources', 'read_mcp_resource',
]

/** 平台默认的 DSH 家目录（宿主 `ctx.get('dshHomePath')` 拿不到时兜底） */
function defaultDshHome () {
  try { return join(homedir(), '.dsh') } catch { return '.dsh' }
}

/**
 * 读宿主的 DSH 家目录。
 *
 * 🔴 2026-11-01 真机事故：`ctx.get('dshHomePath')` 返回的是**函数**（不是字符串），直接丢给
 *    `join()` 就抛 `ERR_INVALID_ARG_TYPE: The "path" argument must be of type string.
 *    Received function dshHomePath`。这个异常发生在 `apply()` 里 → cordis 判定
 *    「entry did not activate」→ Electron 的 web boot 当成致命错误 → **DSH 启动失败**。
 *    所以：函数就调一次、只接受非空字符串，否则回落默认值。
 */
function readDshHome (ctx) {
  try {
    let v = ctx?.get?.('dshHomePath')
    if (typeof v === 'function') {
      try { v = v() } catch { v = null }
    }
    if (typeof v === 'string' && v.trim()) return v.trim()
  } catch { /* 宿主没有这个服务：回落 */ }
  return defaultDshHome()
}

/** 默认配置（与 schemastery 的 Config 默认值一致；改一处要一起改） */
export const DEFAULT_CONFIG = {
  /** 哪些 preset 算 ZFF MC 模式（= presets/zff-mc.patch.yml 的 config.id） */
  modePresets: ['zff-mc'],
  /** 管理员玩家名（游戏名，大小写不敏感）。空 = 谁都不能让 AI 下线 */
  adminPlayers: [],
  /** 总计开关：关掉后完全不注入（刹车 0） */
  enableResume: true,
  /** Ph7.2：必须由玩家点「开始游戏」（▶→⏸）才续跑；自检用 false 绕过 */
  requireRunSwitch: true,
  /** 从聊天里读"玩家叫停 / 再发言解除" */
  autoPauseFromChat: true,
  /** "叫停"正则 */
  pauseRegex: DEFAULT_PAUSE_REGEX,
  /** 轮询间隔（ms）：没有 turn-end 钩子，靠它看 agent.status */
  tickMs: 2000,
  /** 连续空转 N 轮 → 改注入"自主找任务" */
  idleStreakLimit: DEFAULT_BRAKES.idleStreakLimit,
  /** 连续自主 M 轮仍无进展 → 低耗待机 */
  selfStreakLimit: DEFAULT_BRAKES.selfStreakLimit,
  /** 低耗待机间隔（ms） */
  lowPowerIntervalMs: DEFAULT_BRAKES.lowPowerIntervalMs,
  /** 两次注入的最小间隔（ms） */
  minIntervalMs: DEFAULT_BRAKES.minIntervalMs,
  /** env_inbox.staleMs 超过它就算掉线（ms） */
  staleMaxMs: DEFAULT_BRAKES.staleMaxMs,
  /** 聊天复核窗口（ms） */
  chatWindowMs: 60_000,
  /** daemon（daemon 的 /call 接口） */
  daemonUrl: DEFAULT_DAEMON_URL,
  /** 工作区（不填就用会话 header.cwd） */
  workspaceDir: null,
  /** 提示词目录（不填就用 `$DSH_HOME/zff-mc/prompts`） */
  promptDir: null,
  /** 游戏工具名前缀（MCP server 注册出来就是 `mcp__mc__*`） */
  mcpToolPrefix: 'mcp__mc__',
  /** 额外放行的宿主工具名 */
  allowOtherTools: [],
  /** 启动器默认值 */
  launcher: {
    versionDir: '',
    javaPath: 'java',
    playerName: 'DSHDeepSeek',
    server: '127.0.0.1:25565',
    memoryMb: 4096,
    jvmArgs: '',
  },
  /**
   * Ph7 功能①：工作目录里的 MCP 环境（zip / 解压目录 / 自动启动）。
   * 见 src/mcp-env.mjs 顶部的规格说明。
   */
  mcpServer: { ...DEFAULT_MCP_SERVER },
}

/** 规范化配置（深层默认值；用户传 null/undefined 都当没传） */
export function normalizeConfig (raw) {
  const c = raw && typeof raw === 'object' ? raw : {}
  const arr = (v, d) => (Array.isArray(v) ? v.map(String) : [...d])
  const numOr = (v, d) => (Number.isFinite(Number(v)) && Number(v) > 0 ? Number(v) : d)
  const strOr = (v, d) => (typeof v === 'string' && v.trim() ? v.trim() : d)
  return {
    modePresets: arr(c.modePresets, DEFAULT_CONFIG.modePresets),
    adminPlayers: arr(c.adminPlayers, DEFAULT_CONFIG.adminPlayers),
    enableResume: c.enableResume !== false,
    requireRunSwitch: c.requireRunSwitch !== false,
    autoPauseFromChat: c.autoPauseFromChat !== false,
    pauseRegex: strOr(c.pauseRegex, DEFAULT_CONFIG.pauseRegex),
    tickMs: Math.max(500, numOr(c.tickMs, DEFAULT_CONFIG.tickMs)),
    idleStreakLimit: Math.max(1, Math.floor(numOr(c.idleStreakLimit, DEFAULT_CONFIG.idleStreakLimit))),
    selfStreakLimit: Math.max(1, Math.floor(numOr(c.selfStreakLimit, DEFAULT_CONFIG.selfStreakLimit))),
    lowPowerIntervalMs: numOr(c.lowPowerIntervalMs, DEFAULT_CONFIG.lowPowerIntervalMs),
    minIntervalMs: numOr(c.minIntervalMs, DEFAULT_CONFIG.minIntervalMs),
    staleMaxMs: numOr(c.staleMaxMs, DEFAULT_CONFIG.staleMaxMs),
    chatWindowMs: numOr(c.chatWindowMs, DEFAULT_CONFIG.chatWindowMs),
    daemonUrl: strOr(c.daemonUrl, DEFAULT_CONFIG.daemonUrl),
    workspaceDir: typeof c.workspaceDir === 'string' && c.workspaceDir.trim() ? c.workspaceDir.trim() : null,
    promptDir: typeof c.promptDir === 'string' && c.promptDir.trim() ? c.promptDir.trim() : null,
    mcpToolPrefix: strOr(c.mcpToolPrefix, DEFAULT_CONFIG.mcpToolPrefix),
    allowOtherTools: arr(c.allowOtherTools, DEFAULT_CONFIG.allowOtherTools),
    launcher: { ...DEFAULT_CONFIG.launcher, ...(c.launcher && typeof c.launcher === 'object' ? c.launcher : {}) },
    mcpServer: { ...DEFAULT_CONFIG.mcpServer, ...(c.mcpServer && typeof c.mcpServer === 'object' ? c.mcpServer : {}) },
  }
}

/* ── Config（schemastery 可选：拿不到就不导出，cordis 容忍 undefined）────────────
 * 见 whale_craft/index.js:129-135：**绝不能**退化成一个普通对象，
 * cordis 会去调 `Config['~standard'].validate` 而当场炸。 */
let z = null
try { z = (await import('@deepseek-ai/schemastery')).default ?? null } catch { /* 宿主没喂 ⇒ 不要 Config */ }
export const Config = z
  ? z.object({
      modePresets: z.array(z.string()).default(DEFAULT_CONFIG.modePresets),
      adminPlayers: z.array(z.string()).default(DEFAULT_CONFIG.adminPlayers),
      enableResume: z.boolean().default(true),
      autoPauseFromChat: z.boolean().default(true),
      pauseRegex: z.string().default(DEFAULT_PAUSE_REGEX),
      tickMs: z.number().default(DEFAULT_CONFIG.tickMs),
      idleStreakLimit: z.number().default(DEFAULT_CONFIG.idleStreakLimit),
      selfStreakLimit: z.number().default(DEFAULT_CONFIG.selfStreakLimit),
      lowPowerIntervalMs: z.number().default(DEFAULT_CONFIG.lowPowerIntervalMs),
      minIntervalMs: z.number().default(DEFAULT_CONFIG.minIntervalMs),
      staleMaxMs: z.number().default(DEFAULT_CONFIG.staleMaxMs),
      chatWindowMs: z.number().default(DEFAULT_CONFIG.chatWindowMs),
      daemonUrl: z.string().default(DEFAULT_CONFIG.daemonUrl),
      workspaceDir: z.string(),
      promptDir: z.string(),
      mcpToolPrefix: z.string().default(DEFAULT_CONFIG.mcpToolPrefix),
      allowOtherTools: z.array(z.string()).default(DEFAULT_CONFIG.allowOtherTools),
      launcher: z.object({
        versionDir: z.string().default(''),
        javaPath: z.string().default('java'),
        playerName: z.string().default('DSHDeepSeek'),
        server: z.string().default('127.0.0.1:25565'),
        memoryMb: z.number().default(4096),
        jvmArgs: z.string().default(''),
      }),
    })
  : undefined

/* ══════════════════════════════════════════════════════════════════════════
 * 插件实例
 * ══════════════════════════════════════════════════════════════════════════ */

/**
 * 造一个插件实例（**不自启定时器**，方便自检拿假 ctx 直接驱动）。
 * @param {{
 *   ctx:any, config?:object,
 *   now?:()=>number,
 *   fetchImpl?:Function,
 *   spawnImpl?:Function,
 *   logger?:(msg:string)=>void,
 *   pkgRoot?:string,
 * }} o
 */
export function createZffPlugin ({ ctx, config = {}, now = () => Date.now(), fetchImpl = globalThis.fetch, spawnImpl = nodeSpawn, logger = null, pkgRoot = null } = {}) {
  const cfg = normalizeConfig(config)
  const homeDir = readDshHome(ctx)
  // 玩家在设置页改的东西（启动器/刹车/管理员/关键词）存在 <DSH_HOME>/zff-mc/settings.json，
  // 优先于 profile 里的静态 config —— 保存即生效，不需要重启、也不需要碰 profile。
  applySettings(cfg, readSettings(homeDir))
  /** 本次 DSH 启动的 id：运行开关（▶/⏸）用它判断"关掉 DSH 后复位" */
  const BOOT_ID = randomUUID()

  /**
   * MCP server 配置快照（模块级：面板路由 / tick 看门狗 / ▶ 门禁 / 心跳都要用）。
   * 🔴 2026-11-01 真机：以前它只声明在 refreshEnv 里，新加的 /api/zff-mc/mcp 等代码一用就
   *   mcp is not defined —— 整个「启动 MCP」按钮直接报错。
   */
  const mcp = cfg.mcpServer ?? {}

  /** 全局心跳：DSH 活着就一直打，30 s 收不到 server 自己退出（需求 6） */
  let heartbeatWorkspace = null
  const heartbeatTimer = setInterval(() => {
    const ws = heartbeatWorkspace || readLastWs()
    if (!ws) return
    try { sendHeartbeat({ workspace: ws, mcp: cfg.mcpServer ?? {}, fetchImpl }).catch(() => {}) } catch { /* 尽力 */ }
  }, 5000)
  heartbeatTimer.unref?.()

  /** 会话 id（DSH 的 agent.id 就是 session id） */
  const sessionIdOf = (agent) => {
    const id = agent?.id ?? agent?.session?.id ?? null
    return id == null ? null : String(id)
  }

  /**
   * 读某个会话的 ▶/⏸ 状态（文件在 <workspace>/runtime/zff-run.json）。
   * 🔴 2026-11-01 需求 1：**按会话读**。以前只按工作区，导致"同一工作区新开一个对话"直接继承
   *   上一次的 running → 点进去就被续跑叫醒。现在只有按过 ▶ 的那个会话才是 running。
   */
  const runStateOf = (agent) => {
    const ws = workspaceOf(agent)
    if (!ws) return { state: 'idle', at: 0, file: null, sessionId: null }
    try { return readRun(ws, BOOT_ID, sessionIdOf(agent)) } catch { return { state: 'idle', at: 0, file: null, sessionId: null } }
  }

  const zffHome = join(homeDir, 'zff-mc')
  const promptDir = cfg.promptDir ?? userPromptDir(homeDir)
  const defaultsRoot = pkgRoot ?? fileURLToPath(new URL('./defaults/', import.meta.url))
  const logFile = join(zffHome, 'logs', 'zff-mc.log')
  /** 最近一次用到的工作目录：没有会话时全局心跳也要知道往哪打（需求 6） */
  const lastWsFile = join(zffHome, 'mcp-workspace.json')
  const readLastWs = () => { try { return JSON.parse(readFileSync(lastWsFile, 'utf8')).workspace ?? null } catch { return null } }
  const writeLastWs = (ws) => { try { mkdirSync(zffHome, { recursive: true }); writeFileSync(lastWsFile, JSON.stringify({ workspace: ws, at: Date.now() }), 'utf8') } catch { /* 尽力 */ } }

  /** @type {Map<string, any>} agentId → 每会话状态 */
  const agents = new Map()
  /** 待投递的插件提示行（steer 不可用/失败时的降落伞，在 pre-step 里送出去） */
  const pendingNotices = []
  /** 每个 agent 的提示词台账（已投的指纹） */
  const promptLedger = new WeakMap()
  /** 每个 agent 的 restrict disposer（"黏"的限制，切模式要手动撤销） */
  const restrictByAgent = new WeakMap()
  /** 同一个 disposer 的强引用列表（WeakMap **不可遍历**，stop() 用它收尾） */
  const restrictReleases = new Set()
  /** 每个 agent 上次的工具策略档位（幂等） */
  const policyKind = new WeakMap()
  /** 聊天缓存（避免每次 turn-end 都打一次 daemon） */
  let chatCache = { at: 0, ok: false, messages: [], error: null }

  /** 最近一次发给 MCP server 的心跳（需求 6：server 30 s 收不到就自杀） */
  let lastHeartbeatAt = 0
  /** 最近一次 MCP 状态快照（需求 2/5：面板 + ▶ 门禁 + 看门狗共用） */
  let lastMcp = { at: 0, status: null }

  let terminated = false
  let started = false
  let timer = null
  let routeDisposer = null
  let guardDisposer = null
  /** 诊断：最近一次决策 / 最近一次注入 */
  const diagnostics = { lastDecision: null, lastInject: null, injected: 0, skipped: 0, errors: [] }

  /* ── 日志（**绝不抛**）────────────────────────────────────────────── */
  let logRolled = false
  const log = (msg) => {
    const line = `[${new Date(now()).toISOString()}] ${msg}`
    try { logger?.(line) } catch { /* 日志失败不影响主流程 */ }
    try {
      try { mkdirSync(join(zffHome, 'logs'), { recursive: true }) } catch { /* 已存在 */ }
      // 超过 4 MB 就**从头开始**（一次会话只滚一次，避免把日志写成无限大）
      try {
        if (!logRolled && existsSync(logFile) && statSync(logFile).size > 4 * 1024 * 1024) {
          logRolled = true
          writeFileSync(logFile, `[${new Date(now()).toISOString()}] [rolled] 上一个 4 MB 已丢弃\n`, 'utf8')
        }
      } catch { /* 滚不动就算了 */ }
      appendFileSync(logFile, line + '\n', 'utf8')
    } catch { /* 磁盘写不了也不能带崩插件 */ }
  }
  const pushError = (where, e) => {
    const text = `${where}: ${e?.message ?? e}`
    diagnostics.errors.push({ at: now(), text })
    if (diagnostics.errors.length > 30) diagnostics.errors.shift()
    log(`⚠️ ${text}`)
  }

  /* ── 宿主服务现取（绝不缓存到判据里）──────────────────────────────── */
  const service = (key) => { try { return ctx?.get?.(key) ?? null } catch { return null } }

  /** 会话工作区：优先 agent 的 header.cwd，其次配置兜底 */
  /**
   * 这个会话是不是**子代理**（SR / AR / ESRC）？
   * DSH 的子代理是独立会话，header 带 parentSessionId；插件只该伺候根会话（CR）。
   * 字段拿不到时返回 false（宁可多管，也不要把 CR 误判成子代理）。
   */
  const isChildSession = (agent) => {
    try {
      const h = agent?.session?.header ?? {}
      if (typeof h.parentSessionId === 'string' && h.parentSessionId.trim()) return true
      if (h.parent && typeof h.parent === 'object' && h.parent.id) return true
      // 宿主版本不同，父会话字段可能直接挂在 agent 上（P7-6 真机：header 里没有）。
      if (typeof agent?.parentSessionId === 'string' && agent.parentSessionId.trim()) return true
      if (typeof agent?.parentId === 'string' && agent.parentId.trim()) return true
      if (agent?.parent && typeof agent.parent === 'object' && agent.parent.id) return true
      return false
    } catch { return false }
  }
  const workspaceOf = (agent) => {
    // 🔴 2026-11-01（真机）：**显式配置优先**。插件读的 `runtime/*.json` 必须与 MCP server
    //   （`start-mcp.ps1` 的 `--workspace`）写的是同一个目录；而会话 `header.cwd` 可能是另一个
    //   工作区（实测这台机器上是 `D:\mc`，server 侧是 `D:\mc\MCP Development`）。
    //   两边不一致时，"玩家说话立即唤醒 / 暂停 / 掉线"三条刹车会**静默失效**，还不报错。
    if (cfg.workspaceDir) return cfg.workspaceDir
    const cwd = agent?.session?.header?.cwd
    if (typeof cwd === 'string' && cwd.trim()) return cwd.trim()
    return null
  }

  /**
   * 当前会话的 preset id —— **每次现场问**（服务可能晚就绪；whale_craft/index.js:4139-4149 的教训）。
   * @returns {string|null}
   */
  const presetIdOf = (agent) => {
    if (!agent?.ctx) return null
    try {
      let svc = service('agentPresets')
      if (!svc) { try { svc = agent.ctx.get?.('agentPresets') ?? null } catch { svc = null } }
      const id = svc?.composedPreset?.(agent.ctx)
      return typeof id === 'string' && id ? id : null
    } catch { return null }
  }

  const isZffMode = (agent) => {
    const id = presetIdOf(agent)
    return Boolean(id && cfg.modePresets.includes(id))
  }

  const statusOf = (agent) => {
    const s = agent?.status
    return typeof s === 'string' ? s : null
  }

  /** 会话事件数（"这一轮有没有产出"的代理指标；拿不到返回 null） */
  const eventCountOf = (agent) => {
    try {
      const ev = agent?.session?.snapshotEvents?.()
      return Array.isArray(ev) ? ev.length : null
    } catch { return null }
  }

  /* ── 聊天（只读）──────────────────────────────────────────────────── */
  /**
   * 读最近聊天（带 2.5 s 缓存；失败缓存 10 s，避免 daemon 没起时每 2 s 打一次）。
   * @returns {Promise<{ok:boolean, messages:Array, error:string|null}>}
   */
  const readChat = async (t = now(), { force = false } = {}) => {
    const ttl = chatCache.ok ? 2500 : 10_000
    if (!force && chatCache.at && t - chatCache.at < ttl) return chatCache
    const res = await callDaemonTool({
      baseUrl: cfg.daemonUrl, tool: 'chat_history', args: { limit: 80 }, timeoutMs: 4000, fetchImpl,
    })
    chatCache = res.ok
      ? { at: t, ok: true, messages: normalizeChatMessages(res.json), error: null }
      : { at: t, ok: false, messages: [], error: String(res.error ?? 'chat_unavailable') }
    return chatCache
  }

  /* ── 消息构造（**不 import 宿主包**）────────────────────────────────── */
  /**
   * 造一条"插件提示行"用的 UserMessage（形状逐字段对齐宿主 `createUserMessage`：
   * `role` / `content` / `source` / `id`，见 whale_craft/src/user-message.mjs:39-44）。
   * `source.kind` 必须是 v4 规范值 `plugin:zff-mc`。
   */
  const makeNoticeMessage = ({ text, summary, id }) => ({
    role: 'user',
    content: [{ type: 'text', text: String(text ?? '') }],
    source: { kind: SOURCE_KIND, form: 'notice', summary: String(summary ?? 'ZFF MC').slice(0, 120) },
    id: id ?? `zff-${Math.random().toString(36).slice(2, 10)}-${Date.now().toString(36)}`,
  })

  /* ── 注入：steer 优先，失败落 pendingNotices ───────────────────────── */
  /**
   * 注入一段文本。**只在"一轮结束/空闲"时调用**（steer 的语义是"空闲起一轮"）。
   * @returns {{via:'steer'|'pending'|'prompt'|'none', ok:boolean, error?:string}}
   */
  const inject = (agent, text, summary) => {
    const message = makeNoticeMessage({ text, summary })
    // ① 首选 agent.steer —— 空闲会起一轮（宿主文档原话，见 watchdog.mjs:614-617）
    if (agent && typeof agent.steer === 'function') {
      try {
        agent.steer(message)
        return { via: 'steer', ok: true }
      } catch (e) {
        pushError('agent.steer 注入失败（降级到 prompt / pending）', e)
      }
    }
    // ② 次选 sessionController.prompt（**用户来源**消息；只有拿不到 steer 时才走这条）
    const sc = service('sessionController')
    if (sc && typeof sc.prompt === 'function' && agent?.id) {
      try {
        const p = sc.prompt({
          requestId: `zff-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          sessionId: agent.id,
          mode: 'steer',
          content: [{ type: 'text', text }],
        }, new AbortController().signal)
        if (p && typeof p.catch === 'function') p.catch((e) => pushError('sessionController.prompt 异步失败', e))
        return { via: 'prompt', ok: true }
      } catch (e) {
        pushError('sessionController.prompt 注入失败', e)
      }
    }
    // ③ 兜底：留给下一个 pre-step（与用户那句同批送出）
    pendingNotices.push({ text, summary, at: now() })
    if (pendingNotices.length > 8) pendingNotices.shift()
    return { via: 'pending', ok: true }
  }

  /* ── Ph7 环境自举：工作目录里的 MCP Server / 游戏实例 ───────────────── */
  /** agent → 最近一次环境快照（给提示词用，不进 st） */
  const envByAgent = new WeakMap()
  /** 每个 agent 的上次检查时间（tick 每 2 s 跑一次，别每次都比 health） */
  const envCheckedAt = new Map()
  const ENV_CHECK_MS = 3000

  /**
   * 检查/自举「工作目录里的 MCP 环境」，并按**配置的离线账号**选实例。
   * 结果写 `st.env`；状态从"可用"变"不可用"时注入一条提示（让模型转告玩家），然后停下。
   * 绝不抛：调用点在 tick 里。
   */
  const refreshEnv = async (st, t = now()) => {
    const agent = st?.agent
    if (isChildSession(agent)) { st.env = { at: t, workspace: workspaceOf(agent), server: null, instance: null, blocked: null, child: true }; return st.env }
    const ws = workspaceOf(agent)
    const prev = st?.env ?? null
    try { st.run = runStateOf(agent) } catch { st.run = { state: 'idle', at: 0 } }
    // 关掉环境自举（`mcpServer.enabled:false`）时完全不动：离线自检靠它不碰网络、不 spawn
    if (cfg.mcpServer?.enabled === false) {
      st.env = { at: t, workspace: ws, server: null, instance: null, blocked: null, disabled: true }
      envByAgent.set(agent, st.env)
      return st.env
    }
    if (!ws) {
      st.env = { at: t, workspace: null, server: null, instance: null, blocked: { kind: 'no_workspace' } }
      envByAgent.set(agent, st.env)
      return st.env
    }
    if (prev && t - Number(prev.at ?? 0) < ENV_CHECK_MS) return prev
    const base = mcpBase(mcp)
    let server = { base, healthy: false, action: null, detail: null }
    let blocked = null
    try {
      if (await healthOk(base, fetchImpl)) {
        server.healthy = true
        server.action = 'already'
      } else {
        const r = await ensureServer({ workspace: ws, mcp, fetchImpl, spawnImpl, log })
        server = { base, healthy: r.ok, action: r.action, detail: r.detail ?? null }
        if (!r.ok) {
          const kind = r.action === 'port_conflict' ? 'port_conflict'
            : r.action === 'missing' ? 'no_server'
            : r.action === 'extract_failed' ? 'extract_failed'
              : r.action === 'disabled' ? 'no_server'
                : 'start_failed'
          blocked = { kind, detail: r.detail ?? '' }
        }
      }
      let instance = null
      if (server.healthy) {
        // 聊天守护进程（57611）：mod 把 chat_event 推到这里；没人起它 = 玩家说话永远收不到（P0-3）
        try {
          server.daemon = await ensureDaemon({ workspace: ws, mcp, fetchImpl, spawnImpl, log })
          if (server.daemon?.action === 'started') log('聊天守护进程已就绪（57611）')
        } catch (e) { pushError('启动聊天守护进程失败', e) }
        const li = await fetchInstances(base, fetchImpl)
        if (!li.ok) {
          blocked = { kind: 'instances_unreachable', detail: li.error }
        } else if (li.workspace && ws && li.workspace.toLowerCase() !== ws.toLowerCase()) {
          // 同一个端口上跑的是**别的会话/工作目录**的 server：它的 runtime/ 不是这个会话的，
          // 继续用它会让两边的 flag/paused 互相串台（Ph7 真机就是这个坑）。
          blocked = { kind: 'server_workspace_mismatch', detail: 'server=' + li.workspace + '，本会话=' + ws }
        } else {
          const pick = chooseInstance(li.instances, cfg.launcher?.playerName)
          if (pick.ok) instance = pick.instance
          else {
            // P2-3 / P7-10：报错要带上下文（端口/用户名/在不在世界），并且把"刚过期"的实例
            // 也列出来——玩家就能分清"客户端没开"还是"开着但心跳停了"。
            const seen = li.instances.map((i) => `${i.username ?? '?'}@${i.port}${i.inWorld ? '(world)' : '(menu)'}`).join('、')
            let recent = ''
            if (pick.reason === 'no_instance') {
              try {
                const old = await fetchInstances(base, fetchImpl, 1500, true)
                const expired = (old.instances || []).filter((i) => i && !i.live).slice(0, 4)
                if (expired.length) {
                  recent = '；最近见过但已过期的实例：' + expired.map((i) => {
                    const sec = Math.max(0, Math.round(Number(i.ageMs ?? 0) / 1000))
                    return `${i.username ?? '?'}@${i.port}（最后心跳 ${sec}s 前）`
                  }).join('、')
                }
              } catch { /* 只是补充信息 */ }
            }
            blocked = {
              kind: pick.reason,
              detail: `${pick.detail ?? ''}${seen ? `；现存实例：${seen}` : ''}${recent}${li.dir ? `；实例目录：${li.dir}` : ''}`,
              matches: pick.matches?.length ?? 0,
            }
          }
        }
      }
      st.env = { at: t, workspace: ws, server, instance, blocked, checkedAt: t }
    } catch (e) {
      pushError('环境自举失败（已吞）', e)
      st.env = { at: t, workspace: ws, server, instance: null, blocked: { kind: 'start_failed', detail: String(e?.message ?? e) }, checkedAt: t }
    }
    envByAgent.set(agent, st.env)
    // Ph7.2：玩家先点了 ▶、游戏后进的世界 —— 进世界这一刻补一次唤醒（只一次）
    try {
      if (st.pendingStart === true && st.env?.instance?.inWorld && (st.run?.state ?? 'idle') === 'running') {
        st.pendingStart = false
        inject(agent, '[ZFF MC] 客户端已进世界，开始干活：先 mcp__mc__get_state，再 chat_turn。', 'ZFF MC：客户端已进世界')
        log('检测到客户端进世界：补发一次开始唤醒')
      }
    } catch (e) { pushError('补发开始唤醒失败', e) }
    // Ph7.2：本会话一个 mcp__mc__* 都没有（mc-control 启动时没连上 57610）
    //   → 把开关置回 ▶（不续跑）+ 提示玩家重启 DSH。只有"明确数到 0"才动，拿不到就不猜。
    try {
      const n = isZffMode(agent) ? countGameTools(agent) : null
      if (n === 0 && (st.run?.state ?? 'idle') === 'running') {
        writeRun(ws, 'idle', 'no-mcp-tools', BOOT_ID, t)
        const wasRunning = (st.run?.state ?? 'idle') === 'running'
        st.run = { state: 'idle', at: t, by: 'no-mcp-tools' }
        if (wasRunning) {
          inject(agent, '[ZFF MC] 本会话没有注册任何游戏工具（mcp__mc__*）：如果工具报 `unknown MCP session`，说明 MCP server 被换过，DSH 还拿着旧会话，**只能完全退出 DSH 再打开**（重启客户端没用）。请把这句话原样告诉玩家，然后停下等玩家处理。', 'ZFF MC：缺少游戏工具')
        }
        st.env = { ...(st.env || {}), sessionHint: 'unknown_mcp_session_if_tool_errors' }
        log('检测到 0 个 mcp__mc__*：运行开关已置回 ▶，并提示玩家重启 DSH')
      }
    } catch (e) { pushError('检测游戏工具数量失败（忽略）', e) }
    const prevKind = prev?.blocked?.kind ?? null
    const nextKind = st.env.blocked?.kind ?? null
    if (nextKind && nextKind !== prevKind) {
      // 2026-11-01 实测：新会话第一次 tick 时 prevKind=null → 这里会注入一条"环境不可用"，
      // 玩家看到的就是"点进对话就被唤醒"。现在：没按 ▶ 就**只写日志，不注入**。
      log(`环境阻塞（${nextKind}）：${explainBlocked(st.env.blocked)}`)
      if ((st.run?.state ?? 'idle') === 'running') {
        const text = `[ZFF MC] 现在不能操作游戏：${explainBlocked(st.env.blocked)}\n`
          + '请把原因原样告诉玩家，然后**停下等玩家处理**；不要尝试调用任何游戏工具，也不要继续旧计划。'
        try { inject(agent, text, 'ZFF MC：环境不可用') } catch (e) { pushError('环境提示注入失败', e) }
      }
    } else if (!nextKind && prevKind) {
      if ((st.run?.state ?? 'idle') === 'running') { log('环境已恢复：可以接管游戏了') } else { log('环境已恢复（未开始游戏，不注入提示）') }
    }
    return st.env
  }
  /* ── 续跑：一次 turn-end 的完整判定 ─────────────────────────────────── */
  /**
   * 处理"一轮结束"。**自检直接调这个**，不需要真宿主。
   * @param {any} st      会话状态（agents 里的那个对象）
   * @param {number} t
   * @param {{messages?:Array}} [opts] 可选：注入聊天（自检用；不给就读 daemon）
   * @returns {Promise<{action:string, reason:string, text:string|null, via?:string}>}
   */
  const handleTurnEnd = async (st, t = now(), opts = {}) => {
    // 只有真的有一轮结束过，后面才允许 tick 继续注入续跑（否则"刚打开对话"就会被叫醒）
    st.turnEndSeen = true
    const agent = st?.agent
    const decideBase = {
      now: t,
      terminated: terminated === true,
      presetId: presetIdOf(agent),
      modePresets: cfg.modePresets,
      paused: false,
      env: { known: false },
      job: null,
      flagUserChat: false,
      idleStreak: st?.idleStreak ?? 0,
      selfStreak: st?.selfStreak ?? 0,
      lowPower: st?.lowPower === true,
      lastInjectAt: st?.lastInjectAt ?? 0,
      minIntervalMs: cfg.minIntervalMs,
      lowPowerIntervalMs: cfg.lowPowerIntervalMs,
      idleStreakLimit: cfg.idleStreakLimit,
      selfStreakLimit: cfg.selfStreakLimit,
      staleMaxMs: cfg.staleMaxMs,
      envBlocked: st?.env?.blocked ?? null,
      runState: st?.run?.state ?? runStateOf(agent).state,
      requireRunSwitch: cfg.requireRunSwitch !== false,
    }
    if (isChildSession(st?.agent)) return finish(st, { action: 'skip', reason: 'child_session', text: null }, t)
    if (!cfg.enableResume) return finish(st, { action: 'skip', reason: 'resume_disabled', text: null }, t)
    if (terminated) return finish(st, { action: 'skip', reason: 'terminated', text: null }, t)

    const ws = workspaceOf(agent)
    const rt = ws
      ? readRuntimeSnapshot({ workspace: ws, now: t, staleMaxMs: cfg.staleMaxMs })
      : { env: { known: false }, job: { active: false }, flag: { flagUserChat: false }, paused: { paused: false, at: null }, shutdown: { terminated: false }, errors: ['no_workspace'] }
    if (rt.shutdown?.terminated === true && !terminated) {
      terminated = true
      log('读到 runtime/zff-shutdown.json：本会话已终止，之后不再注入任何东西')
    }
    decideBase.terminated = terminated
    decideBase.env = rt.env
    decideBase.job = rt.job
    decideBase.flagUserChat = rt.flag?.flagUserChat === true

    // 暂停：文件标记 + （可选）从聊天里读"叫停/再发言"
    let paused = rt.paused?.paused === true
    if (cfg.autoPauseFromChat && ws) {
      try {
        const chat = opts.messages ? { ok: true, messages: opts.messages } : await readChat(t)
        if (chat.ok) {
          const vote = detectPauseVote({
            messages: chat.messages, now: t, windowMs: cfg.chatWindowMs,
            pauseRegex: cfg.pauseRegex, pausedAt: paused ? (rt.paused?.at ?? 0) : null,
          })
          if (vote.action === 'pause' && !paused) {
            setPaused({ workspace: ws, paused: true, by: vote.sender ?? 'chat', reason: vote.text, now: t })
            paused = true
            log(`玩家叫停（${vote.sender ?? '?'}：「${String(vote.text ?? '').slice(0, 40)}」）→ 暂停续跑`)
          } else if (vote.action === 'resume' && paused) {
            setPaused({ workspace: ws, paused: false, by: vote.sender ?? 'chat', reason: '玩家再发言', now: t })
            paused = false
            log(`玩家再发言（${vote.sender ?? '?'}）→ 解除暂停`)
          }
        }
      } catch (e) { pushError('读聊天判定暂停失败（按文件里的暂停状态继续）', e) }
    }
    decideBase.paused = paused

    // 先把**上一次注入**的结果结算掉（"有没有进展"），再决定这一次 —— 否则判据永远慢一拍：
    // 上一轮明明有产出，却因为计数还是旧值而误判成"空转"。
    const cur = eventCountOf(agent)
    if (st.eventsAtInject !== null && st.eventsAtInject !== undefined) {
      const progressed = cur === null ? true : cur > st.eventsAtInject
      settleStreaks(st, { action: st.lastInjectAction ?? 'skip', progressed })
    }
    decideBase.idleStreak = st.idleStreak ?? 0
    decideBase.selfStreak = st.selfStreak ?? 0
    decideBase.lowPower = st.lowPower === true

    // 2026-11-01 实测：点进对话的一瞬间被自动唤醒 —— 因为 tick 只看 idle 就注入。
    // 现在要求这个会话至少结束过一轮（玩家点过 ▶ 之后 AI 真的干过活），否则一律不动。
    if (st.turnEndSeen !== true) {
      return finish(st, { action: 'skip', reason: 'no_turn_yet', text: null }, t)
    }
    const d = decideResume(decideBase)
    if (!isInjectingAction(d.action)) return finish(st, d, t)

    const r = inject(agent, d.text, `ZFF MC：${d.action}`)
    diagnostics.injected++
    diagnostics.lastInject = { at: t, action: d.action, via: r.via, ok: r.ok }
    st.eventsAtInject = cur
    st.lastInjectAction = d.action
    st.lastInjectAt = t
    st.lastDecision = { ...d, at: t, via: r.via, ok: r.ok }
    st.turnEnds = (st.turnEnds ?? 0) + 1
    if (d.action === 'enter_low_power') st.lowPower = true
    log(`注入 ${d.action}（${d.reason}；via=${r.via}；idle=${st.idleStreak} self=${st.selfStreak} lowPower=${st.lowPower}）`)
    return { action: d.action, reason: d.reason, text: d.text, via: r.via }
  }

  const finish = (st, d, t) => {
    diagnostics.skipped++
    diagnostics.lastDecision = { ...d, at: t }
    if (st) {
      st.lastDecision = { ...d, at: t }
      // 同一个原因连续跳过时只记第一次（否则每 2 s 一行日志，把日志刷爆）
      if (st.lastSkipReason !== d.reason) {
        st.lastSkipReason = d.reason
        log(`跳过续跑：${d.reason}`)
      }
    } else {
      log(`跳过续跑：${d.reason}`)
    }
    return { action: d.action, reason: d.reason, text: d.text }
  }

  /* ── 轮询（替代不存在的 turn-end 钩子）────────────────────────────── */
  /**
   * 一次 tick：`running → 非 running` 的那一跳算"本轮结束"。
   * @param {any} st
   * @param {number} t
   * @returns {Promise<object|null>} 有决策就返回决策，否则 null
   */
  const tick = async (st, t = now()) => {
    // 记住工作目录：全局心跳在没有会话时也要能用
    if (workspaceOf(st?.agent)) { heartbeatWorkspace = workspaceOf(st.agent); writeLastWs(heartbeatWorkspace) }
    // 需求 6：DSH 活着就每 5 s 给 MCP server 打一次心跳
    if (t - lastHeartbeatAt > 5000) {
      lastHeartbeatAt = t
      try { await sendHeartbeat({ workspace: workspaceOf(st?.agent), mcp, fetchImpl }) } catch { /* 尽力 */ }
    }
    // 需求 5：游戏进行中 MCP 被意外关闭 → 立刻切回 ▶（暂停）
    if (t - (lastMcp.at ?? 0) > 3000) {
      try {
        const status = await mcpStatus({ workspace: workspaceOf(st?.agent), mcp, fetchImpl })
        lastMcp = { at: t, status }
        if (st?.run?.state === 'running' && !status.healthy) {
          const ws = workspaceOf(st.agent)
          try { writeRun(ws, 'idle', 'mcp-lost', BOOT_ID, t, sessionIdOf(st.agent)) } catch { /* 尽力 */ }
          st.run = { state: 'idle', at: t, by: 'mcp-lost', reason: status.conflict }
          log('MCP 不可用：运行开关已自动切回 ▶（暂停）')
          inject(st.agent, '[ZFF MC] MCP 服务器断开了（' + (status.conflict || 'unknown') + '），已自动暂停。恢复后请让玩家重新点「开始游戏」。', 'ZFF MC：MCP 断开，已暂停')
        }
      } catch (e) { pushError('MCP 状态探测失败', e) }
    }
    try {
      if (terminated || !st?.agent) return null
      // Ph7：每轮先看环境（缺 server / 缺实例 / 重名），阻塞时下面的续跑会被刹车挡住
      if (isZffMode(st.agent)) await refreshEnv(st, t)
      const status = statusOf(st.agent)
      if (status === 'running') { st.wasRunning = true; st.idleSince = 0; return null }
      if (status === null) return null          // 拿不到状态就别自作聪明
      if (st.wasRunning) {
        st.wasRunning = false
        st.idleSince = t
        return await handleTurnEnd(st, t)
      }
      return null
    } catch (e) {
      pushError('tick 失败（已吞，进程不受影响）', e)
      return null
    }
  }

  /* ── 提示词：装配 + 台账 ──────────────────────────────────────────── */
  /**
   * 装配本轮的提示词包。
   * @param {any} agent
   * @returns {{fp:string, text:string, sections:string[], skipped:string[], sources:object}}
   */
  const buildPromptBundle = (agent) => {
    const ws = workspaceOf(agent)
    const bundle = readBundle({ dir: promptDir, pkgRoot: defaultsRoot })
    const facts = {
      playerName: cfg.launcher?.playerName,
      server: cfg.launcher?.server,   // 账号/服务器只是"事实"，不再用于启动（启动器已移除）
      presetId: presetIdOf(agent),
      workspace: ws,
      runtimeDir: ws ? runtimeDir(ws) : null,
      promptDir,
      runSwitch: (stateOf(agent)?.run?.state ?? runStateOf(agent).state) === 'running' ? '⏸ 进行中（允许续跑）' : '▶ 已结束（不要续跑，玩家可以自由提问）',
      mcpEnv: envByAgent.get(agent)?.blocked
        ? '不可用：' + explainBlocked(envByAgent.get(agent).blocked)
        : 'ok',
      instance: envByAgent.get(agent)?.instance
        ? 'port ' + envByAgent.get(agent).instance.port + '（' + (envByAgent.get(agent).instance.username || '?') + '）'
        : null,
    }
    const composed = composePrompt({ bundle, facts })
    const fp = hashString(composed.text)
    return {
      fp,
      text: composed.text,
      sections: composed.sections,
      skipped: composed.skipped,
      sources: Object.fromEntries(PROMPT_SLOTS.map((s) => [s.slot, bundle[s.slot]?.source ?? 'missing'])),
    }
  }

  /**
   * `agent/pre-step` 的改写：把提示词 + 待投递提示行塞到本步消息**最前面**。
   * 🔴 这是 cordis waterfall：**必须交棒**（`next()`），且**无论成败都不能否决整条链**
   * （whale_craft 0.1.4 的事故：不交棒 → 宿主 `decision.kind` 崩 → 任意会话每轮都失败）。
   */
  const enterWithPrompts = (agent, decision) => {
    try {
      if (!decision || decision.kind !== 'enter') return decision
      if (isChildSession(agent)) return decision   // 子代理有自己的提示词与生命周期，别塞续跑文案
      const msgs = []
      // ① 待投递的续跑/事件提示行（steer 不可用时的降落伞）
      if (pendingNotices.length) {
        for (const n of pendingNotices.splice(0, pendingNotices.length)) {
          msgs.push(makeNoticeMessage({ text: n.text, summary: n.summary }))
        }
      }
      // ② 提示词三件套（每种模式/每次改动只投一次）
      if (!terminated && isZffMode(agent)) {
        const built = buildPromptBundle(agent)
        const ledger = promptLedgerOf(agent)
        if (ledger.fp !== built.fp || !ledger.delivered) {
          const truncNote = PROMPT_SLOTS
            .filter((s) => readSlot({ slot: s.slot, dir: promptDir, pkgRoot: defaultsRoot }).truncated === true)
            .map((s) => s.slot)
          const head = `Instructions from: zff-mc/prompts/{rules,tools,persona}.md`
          const note = truncNote.length ? `\n\n（提示：这些槽位超过 64 KB 已被截断：${truncNote.join(', ')}）` : ''
          msgs.push(makeNoticeMessage({
            text: `${head}\n\n${built.text}${note}`,
            summary: 'ZFF MC 提示词（A 工具 / B 规则 / C 角色）',
          }))
          ledger.fp = built.fp
          ledger.delivered = true
          ledger.sources = built.sources
          ledger.sections = built.sections
          ledger.skipped = built.skipped
          log(`提示词已注入本步消息（${built.sections.join(' → ')}${built.skipped.length ? `；跳过空段：${built.skipped.join(',')}` : ''}）`)
        }
      } else if (!isZffMode(agent)) {
        promptLedger.delete(agent)     // 切出模式 ⇒ 下次进来重投
      }
      if (!msgs.length) return decision
      const rest = (decision.messages ?? []).filter((m) => !isOurNotice(m))
      return { ...decision, messages: [...msgs, ...rest] }
    } catch (e) {
      pushError('pre-step 提示词注入失败（不打断本轮，按原样继续）', e)
      return decision
    }
  }

  const promptLedgerOf = (agent) => {
    let l = promptLedger.get(agent)
    if (!l) { l = { fp: null, delivered: false, sources: {}, sections: [], skipped: [] }; promptLedger.set(agent, l) }
    return l
  }

  const isOurNotice = (m) => String(m?.source?.kind ?? '') === SOURCE_KIND

  /* ── 工具策略（可见性 + guard 硬拒）────────────────────────────────── */
  const toolAllowedInMode = (toolName, argentAgent) => {
    const n = String(toolName ?? '')
    if (!n) return false
    if (n.startsWith(cfg.mcpToolPrefix)) {
      // ▶（idle）= 结束游戏：只读工具放行（玩家仍能问"我在哪"），会改变世界的全部拒绝。
      // 这就是"⏸ 按一下变 ▶ = 结束游戏"的硬约束。
      if (cfg.requireRunSwitch === false) return true
      if (runStateOf(argentAgent).state === 'running') return true
      const short = n.slice(cfg.mcpToolPrefix.length)
      return READONLY_GAME_TOOLS.includes(short)
    }
    if (n.startsWith('zff_')) return true
    if (ALLOWED_HOST_TOOLS.includes(n)) return true
    return cfg.allowOtherTools.includes(n)
  }

  /** 取 scoped tools（restrict 必须用 session scope 的服务，否则宿主拒绝） */
  const scopedTools = (agent) => {
    const candidates = [
      () => service('tools'),
      () => agent?.ctx?.get?.('tools'),
      () => agent?.ctx?.tools,
    ]
    for (const pick of candidates) {
      try {
        const t = pick()
        if (t && typeof t.restrict === 'function') return t
      } catch { /* 该 scope 没 inject 时属性访问会抛 */ }
    }
    return null
  }

  /**
   * 数一下本会话**实际注册**的游戏工具（mcp__mc__*）。
   * 宿主没有公开的 list API，所以这里挨个试常见的形状；拿不到就返回 null（**不猜**）。
   * Ph7.2 用它实现：缺工具时把开关置回 ▶ 并提示玩家重启 DSH。
   */
  const countGameTools = (agent) => {
    const t = scopedTools(agent)
    if (!t) return null
    for (const key of ['list', 'names', 'all', 'tools']) {
      try {
        const v = t[key]
        const arr = typeof v === 'function' ? v.call(t) : v
        if (Array.isArray(arr)) {
          return arr.filter((x) => String(x?.name ?? x).startsWith(cfg.mcpToolPrefix)).length
        }
      } catch { /* 换下一种形状 */ }
    }
    return null
  }
  /**
   * 按现场判据套工具策略。ZFF MC Mode：隐藏一堆宿主工具（逐个试，宿主对不认识的工具名会抛错）；
   * 其它模式：释放限制（`restrict` 是"黏"的，不撤销会一直留着）。
   */
  const applyToolPolicy = (agent) => {
    if (!agent?.ctx) return
    const kind = isZffMode(agent) ? 'zff' : 'other'
    if (policyKind.get(agent) === kind) return
    const prev = restrictByAgent.get(agent)
    if (prev) {
      restrictByAgent.delete(agent)
      restrictReleases.delete(prev)
      try { prev() } catch (e) { pushError('撤销上一轮工具限制失败（guard 仍会兜底）', e) }
    }
    policyKind.set(agent, kind)
    const tools = scopedTools(agent)
    if (!tools) { log('拿不到 scoped tools，跳过可见性限制（guard 仍会硬拒）'); return }
    if (kind === 'other') return
    const disposers = []
    let denied = 0
    for (const n of HIDE_HOST_TOOLS) {
      try {
        const d = tools.restrict({ deny: [n] })
        if (typeof d === 'function') disposers.push(d)
        denied++
      } catch { /* 本部署没有这个工具名 → 跳过（宿主对未知名字抛错） */ }
    }
    if (disposers.length) {
      const release = () => { for (const d of disposers.splice(0)) { try { d() } catch { /* 尽力 */ } } }
      restrictByAgent.set(agent, release)
      restrictReleases.add(release)
      log(`已隐藏 ${denied} 个宿主工具（ZFF MC Mode；其余靠 guard 硬拒）`)
    }
  }

  /* ── 工具：注册 ───────────────────────────────────────────────────── */
  const pickAgent = (exec) => {
    const a = exec?.agent
    if (a && typeof a === 'object') return a
    // 没有 exec.agent（比如从设置页/HTTP 来的调用）：拿最近活跃的那个
    let newest = null
    for (const st of agents.values()) if (!newest || (st.seenAt ?? 0) > (newest.seenAt ?? 0)) newest = st
    return newest?.agent ?? null
  }

  /** P7-6：HTTP 层按会话 id 取 agent；子代理/另一个会话不会再把 ▶ 状态读串。 */
  const agentById = (id) => {
    const key = id == null ? '' : String(id).trim()
    if (!key) return null
    return agents.get(key)?.agent ?? null
  }
  const agentFromRequest = (req, url, body) => {
    const fromQuery = url?.searchParams?.get?.('session')
    const fromBody = body?.sessionId
    return agentById(fromQuery) || agentById(fromBody) || pickAgent(null)
  }

  const tools = {
    zff_shutdown: makeTool({
      name: 'zff_shutdown',
      description: '【管理员下线】玩家（必须是配置里的 adminPlayers 之一）要求你下线时调用。\n'
        + '插件会用**只读的 chat_history** 复核最近 60 s 内说话的玩家在不在 adminPlayers 里：\n'
        + '· 不在 → 返回 {ok:false, error:"not_admin"}，**什么都不会发生**；\n'
        + '· 在   → 返回 {ok:true, action:"disconnect_then_kill"} 并写 runtime/zff-shutdown.json（terminated=true），'
        + '之后插件不再续跑；真正的断连/杀进程由 MCP server 侧执行。\n'
        + '⚠️ 这是不可撤销的动作，只在玩家明确说"下线 / 关掉 / 退出"时用。',
      parameters: {
        reason: { type: 'string', description: '为什么下线（会写进 runtime/zff-shutdown.json，便于复盘）' },
      },
      timeoutMs: 20_000,
      render: (args, v) => (v?.ok
        ? `✅ 已记录下线请求（授权人：${v.by}；动作：${v.action}）→ ${v.file}\n现在停止一切续跑；MCP server 侧会断连并在 10 s 未退出时杀进程。`
        : `❌ 拒绝下线：${v?.error}${v?.detail ? `（${v.detail}）` : ''}`),
      execute: async (args, exec) => {
        const agent = pickAgent(exec)
        const ws = workspaceOf(agent)
        const t = now()
        const r = await verifyAdminAndMark({
          workspace: ws,
          admins: cfg.adminPlayers,
          now: t,
          daemonUrl: cfg.daemonUrl,
          reason: args?.reason ? String(args.reason) : null,
          windowMs: cfg.chatWindowMs,
          fetchImpl,
        })
        if (!r.ok) {
          log(`拒绝 zff_shutdown：${r.error}（${r.detail ?? ''}）`)
          return { ok: false, error: r.error, detail: r.detail ?? null, action: null, admins: cfg.adminPlayers }
        }
        terminated = true
        for (const st of agents.values()) { st.wasRunning = false; st.lastInjectAt = 0 }
        log(`管理员下线已授权：by=${r.by} reason=${args?.reason ?? ''} → ${r.file}`)
        return { ok: true, action: r.action, by: r.by, at: r.at, file: r.file, terminated: true }
      },
    }),

    zff_pause: makeTool({
      name: 'zff_pause',
      description: '【暂停/恢复续跑】玩家说"停/别动/休息/别干了"时置 paused=true；玩家再开口时置 false。\n'
        + '写 `runtime/zff-paused.json`。插件自己也会从聊天里读（配置 autoPauseFromChat）。',
      parameters: {
        paused: { type: 'boolean', required: true, description: 'true=暂停续跑，false=恢复' },
        reason: { type: 'string', description: '原因（写进文件，便于复盘）' },
      },
      timeoutMs: 5000,
      render: (args, v) => (v?.ok ? `已${v.paused ? '暂停' : '恢复'}续跑 → ${v.file}` : `❌ ${v?.error}`),
      execute: async (args, exec) => {
        const agent = pickAgent(exec)
        const ws = workspaceOf(agent)
        if (!ws) return { ok: false, error: 'no_workspace' }
        const w = setPaused({ workspace: ws, paused: args?.paused === true, by: 'agent', reason: args?.reason ?? null, now: now() })
        log(`zff_pause：paused=${args?.paused === true}（${args?.reason ?? ''}）`)
        return { ok: true, paused: args?.paused === true, file: w.file }
      },
    }),

    zff_status: makeTool({
      name: 'zff_status',
      description: '【状态】看这个会话的 ZFF MC 状态：模式、是否终止/暂停、续跑计数、最近一次决策、提示词来源。排查"为什么不动"时先看它。',
      parameters: {},
      timeoutMs: 5000,
      render: (args, v) => JSON.stringify(v, null, 2),
      execute: async (args, exec) => {
        const agent = pickAgent(exec)
        const st = agent ? stateOf(agent) : null
        const ws = workspaceOf(agent)
        const rt = ws ? readRuntimeSnapshot({ workspace: ws, now: now(), staleMaxMs: cfg.staleMaxMs }) : null
        const built = agent && isZffMode(agent) ? buildPromptBundle(agent) : null
        return {
          ok: true,
          plugin: name,
          version: PLUGIN_VERSION,
          presetId: agent ? presetIdOf(agent) : null,
          modePresets: cfg.modePresets,
          isZffMode: agent ? isZffMode(agent) : false,
          terminated,
          paused: rt?.paused ?? null,
          resume: st ? {
            idleStreak: st.idleStreak, selfStreak: st.selfStreak, lowPower: st.lowPower === true,
            lastInjectAt: st.lastInjectAt || null, turnEnds: st.turnEnds ?? 0,
            level: streakLevel(st, cfg), lastDecision: st.lastDecision ?? null,
          } : null,
          runtime: rt ? { env: rt.env, job: rt.job, flag: rt.flag, shutdown: rt.shutdown, dir: rt.dir } : null,
          // 本会话实际注册了多少游戏工具（null = 宿主没暴露，未知）
          mcpGameTools: agent ? countGameTools(agent) : null,
          // ▶/⏸ 运行开关（玩家是否点了「开始游戏」）
          run: (() => {
            const r = agent ? runStateOf(agent) : { state: 'idle', at: 0, by: null }
            return { state: r.state, at: r.at, by: r.by ?? null, label: r.state === 'running' ? '⏸ 进行中（会续跑）' : '▶ 已结束（不续跑）' }
          })(),
          // Ph7：工作目录里的 MCP 环境 + 选中的实例 + 阻塞原因（排查"为什么不动"第一现场）
          env: st?.env ? {
            workspace: st.env.workspace,
            server: st.env.server,
            instance: st.env.instance ? { port: st.env.instance.port, username: st.env.instance.username, gameDir: st.env.instance.gameDir, inWorld: st.env.instance.inWorld } : null,
            blocked: st.env.blocked,
            blockedText: st.env.blocked ? explainBlocked(st.env.blocked) : null,
            checkedAt: st.env.checkedAt ?? st.env.at ?? null,
          } : null,
          prompts: { dir: promptDir, files: Object.fromEntries(PROMPT_SLOTS.map((s) => {
            const r = readSlot({ slot: s.slot, dir: promptDir, pkgRoot: defaultsRoot })
            return [s.slot, { source: r.source, bytes: r.bytes, blank: isBlankPrompt(r.text) }]
          })) },
          promptSections: built ? built.sections : null,
          diagnostics: { ...diagnostics, errors: diagnostics.errors.slice(-10) },
        }
      },
    }),

    zff_usage: makeTool({
      name: 'zff_usage',
      description: '【工具统计】查看 MCP 每个工具的累计调用次数（按次数降序）。数据由 MCP Server 在 workspace/runtime/tool-usage.json 持久化。',
      parameters: {
        limit: { type: 'integer', description: '最多返回多少个工具（1-1000，默认 100）' },
      },
      timeoutMs: 10000,
      render: (args, v) => {
        if (v?.ok === false) return `❌ ${v.error ?? 'usage_unavailable'}`
        const rows = Array.isArray(v?.counts) ? v.counts : []
        return [`MCP 工具调用统计：总计 ${v?.totalCalls ?? 0} 次，${v?.uniqueTools ?? rows.length} 个工具`, ...rows.map((r) => `${String(r.name).padEnd(28)} ${r.count}`)].join('\n')
      },
      execute: async (args, exec) => {
        const r = await callDaemonTool({
          baseUrl: cfg.daemonUrl,
          tool: 'tool_usage',
          args: { limit: Number(args?.limit) || 100 },
          timeoutMs: 8000,
          fetchImpl,
        })
        if (!r.ok) return { ok: false, error: r.error ?? 'usage_unavailable', detail: r.text ?? null }
        return { ok: true, ...(r.json && typeof r.json === 'object' ? r.json : {}) }
      },
    }),

    zff_env: makeTool({
      name: 'zff_env',
      description: '【环境】重新检查工作目录里的 ZFF MCP Server 与游戏实例（会按需解压/启动），并按配置的离线账号选实例。\n'
        + '玩家刚把 zip 放进工作目录、或刚开好游戏之后，调它一次就能刷新状态；返回 ok=false 时把 reason 原样告诉玩家。',
      parameters: {},
      timeoutMs: 30000,
      render: (args, v) => (v?.ok
        ? `✅ 环境就绪：server=${v.server?.base ?? '?'} 实例 port=${v.instance?.port ?? '?'}（${v.instance?.username ?? '?'}）`
        : `❌ 不能操作游戏：${v?.text ?? v?.reason ?? 'unknown'}`),
      execute: async (args, exec) => {
        const agent = pickAgent(exec)
        const st = agent ? stateOf(agent) : null
        if (!st) return { ok: false, reason: 'no_state' }
        st.env = null                      // 强制重扫
        envCheckedAt.delete(agent)
        const env = await refreshEnv(st, now())
        if (env?.blocked) return { ok: false, reason: env.blocked.kind, text: explainBlocked(env.blocked), env }
        return { ok: true, server: env?.server, instance: env?.instance }
      },
    }),
    /**
     * Ph7.4：团队面板的数据源（面板和这个工具吃同一份 JSON）。
     * 子代理（SR/AR/ESRC）靠自报：`store_set {store:"cr", key:"team.SR", value:{task,state,at}}`。
     */
    zff_team: makeTool({
      name: 'zff_team',
      description: '【团队】看 CR / SR 各自**当前在干什么**（一行摘要）。\n'
        + '· CR 由插件直接读（status + 最近决策 + 当前 job）；\n'
        + '· SR 只认它自己写的 CR store `team.SR`，没写就显示 unreported（不猜）。\n'
        + '玩家问"队友在干嘛/现在谁在跑"时调它，把 rows 翻成人话回答。',
      parameters: {},
      timeoutMs: 5000,
      render: (args, v) => (v?.ok
        ? v.rows.map((r) => `${r.role.padEnd(5)} ${String(r.state).padEnd(11)} ${r.task ?? '—'}${r.sinceText ? `  (${r.sinceText})` : ''}`).join('\n')
        : `❌ ${v?.error ?? '读不到团队状态'}`),
      execute: async (args, exec) => {
        const agent = pickAgent(exec)
        const st = agent ? stateOf(agent) : null
        return buildTeam({
          workspace: workspaceOf(agent),
          now: now(),
          cr: {
            status: statusOf(agent),
            presetId: agent ? presetIdOf(agent) : null,
            lastDecision: st?.lastDecision ?? null,
          },
        })
      },
    }),
    zff_prompts: makeTool({
      name: 'zff_prompts',
      description: '【提示词三件套 A/B/C】读或改 `$DSH_HOME/zff-mc/prompts/{tools,rules,persona}.md`。\n'
        + 'action：list（默认，看三份的来源/大小/生效文本）· save（要 slot + text，≤64 KB）· reset（回到包内默认）。\n'
        + '保存后**下一个会话**生效（本会话已注入的那份不会中途换掉）。',
      parameters: {
        action: { type: 'string', enum: ['list', 'save', 'reset'], description: 'list / save / reset' },
        slot: { type: 'string', enum: ['tools', 'rules', 'persona'], description: 'A=tools / B=rules / C=persona' },
        text: { type: 'string', description: 'action=save 时的正文（UTF-8，≤64 KB）' },
      },
      timeoutMs: 10_000,
      render: (args, v) => (v?.ok === false ? `❌ ${v.error}` : JSON.stringify(v, null, 2)),
      execute: async (args) => {
        const action = String(args?.action ?? 'list')
        try {
          if (action === 'save') {
            if (!slotMeta(args?.slot)) return { ok: false, error: 'slot 必须是 tools/rules/persona' }
            const r = saveSlot({ dir: promptDir, slot: args.slot, text: String(args?.text ?? '') })
            log(`zff_prompts：已保存 ${r.slot}（${r.bytes} 字节）`)
            return { ok: true, action, ...r, note: '下一个会话生效' }
          }
          if (action === 'reset') {
            if (!slotMeta(args?.slot)) return { ok: false, error: 'slot 必须是 tools/rules/persona' }
            const r = resetSlot({ dir: promptDir, slot: args.slot })
            log(`zff_prompts：已恢复默认 ${r.slot}`)
            return { ok: true, action, ...r }
          }
          const snap = promptsSnapshot({ dir: promptDir, pkgRoot: defaultsRoot, brief: true })
          return { ok: true, action: 'list', ...snap }
        } catch (e) {
          return { ok: false, error: String(e?.message ?? e) }
        }
      },
    }),

  }

  /* ── HTTP：设置页的数据面 ─────────────────────────────────────────── */
  const sendJson = (res, code, obj) => {
    try {
      const body = JSON.stringify(obj)
      res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body), 'cache-control': 'no-store' })
      res.end(body)
    } catch { try { res.end() } catch { /* 对端已断 */ } }
  }

  const readBody = (req) => new Promise((resolve) => {
    let data = ''
    try {
      req.on('data', (c) => { data += c; if (data.length > 2_000_000) data = data.slice(0, 2_000_000) })
      req.on('end', () => resolve(data))
      req.on('error', () => resolve(''))
    } catch { resolve('') }
  })

  /** 回环 + 同源栅栏（与 whale_craft 的 `/api/mc` 同一套思路，非认证层） */
  const trustedRequest = (req) => {
    const host = String(req?.headers?.host ?? '').toLowerCase()
    const bare = host.replace(/^\[|\]$/g, '').replace(/:\d+$/, '')
    const loopback = bare === 'localhost' || bare === '::1' || /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(bare)
    if (!loopback) return false
    if (req?.headers?.['sec-fetch-site'] === 'cross-site') return false
    const origin = req?.headers?.origin
    if (!origin) return true
    try { return new URL(origin).host.toLowerCase() === host } catch { return false }
  }

  /**
   * `/api/zff-mc/*`：
   *   GET    /prompts            三份快照（默认值 / 用户版 / 生效值）
   *   PUT    /prompts {slot,text} 保存
   *   DELETE /prompts?slot=      恢复默认
   *   GET    /status             状态
   *   POST   /pause {paused}     暂停/恢复
   */
  const handleApi = async (req, res) => {
    const url = new URL(String(req?.url ?? '/'), 'http://127.0.0.1')
    const path = url.pathname.replace(/\/+$/, '') || '/'
    if (!trustedRequest(req)) { res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' }); res.end('forbidden'); return }
    const agent = pickAgent(null)
    try {
      if (path === '/api/zff-mc/prompts' && req.method === 'GET') {
        return sendJson(res, 200, { ok: true, ...promptsSnapshot({ dir: promptDir, pkgRoot: defaultsRoot }) })
      }
      if (path === '/api/zff-mc/prompts' && req.method === 'PUT') {
        const body = JSON.parse(await readBody(req) || '{}')
        const r = saveSlot({ dir: promptDir, slot: body?.slot, text: String(body?.text ?? '') })
        return sendJson(res, 200, { ok: true, ...r, note: '下一个会话生效' })
      }
      if (path === '/api/zff-mc/prompts' && req.method === 'DELETE') {
        const r = resetSlot({ dir: promptDir, slot: url.searchParams.get('slot') })
        return sendJson(res, 200, { ok: true, ...r })
      }
      if (path === '/api/zff-mc/status' && req.method === 'GET') {
        const st = agent ? stateOf(agent) : null
        const ws = workspaceOf(agent)
        return sendJson(res, 200, {
          ok: true, plugin: name, version: PLUGIN_VERSION, terminated,
          presetId: agent ? presetIdOf(agent) : null, isZffMode: agent ? isZffMode(agent) : false,
          resume: st ? { idleStreak: st.idleStreak, selfStreak: st.selfStreak, lowPower: st.lowPower === true, level: streakLevel(st, cfg), lastDecision: st.lastDecision ?? null } : null,
          runtime: ws ? readRuntimeSnapshot({ workspace: ws, now: now(), staleMaxMs: cfg.staleMaxMs }) : null,
          diagnostics: { ...diagnostics, errors: diagnostics.errors.slice(-10) },
        })
      }
      // ── 需求 2/3/4：MCP 服务器状态 + 启停 + 已连接的桥接实例 ──
      if (path === '/api/zff-mc/mcp' && req.method === 'GET') {
        const agent = pickAgent(null)
        const ws = workspaceOf(agent)
        const status = await mcpStatus({ workspace: ws, mcp, fetchImpl })
        let instances = []
        if (status.healthy) {
          try {
            const li = await fetchInstances(mcpBase(mcp), fetchImpl, 2000)
            instances = (li.instances ?? []).map((i) => ({ port: i.port, username: i.username ?? null, account: i.account ?? null, inWorld: i.inWorld === true, gameDir: i.gameDir ?? null }))
          } catch { /* 保留空数组 */ }
        }
        return sendJson(res, 200, { ok: true, status, instances, gameTools: agent ? countGameTools(agent) : null })
      }
      if (path === '/api/zff-mc/mcp' && req.method === 'POST') {
        const body = JSON.parse(await readBody(req) || '{}')
        const action = String(body?.action ?? '')
        const agent = pickAgent(null)
        const ws = workspaceOf(agent)
        if (!ws) return sendJson(res, 200, { ok: false, error: 'no_workspace' })
        if (action === 'start') {
          const r = await startMcp({ workspace: ws, mcp, fetchImpl, spawnImpl, log, startedBy: 'ui' })
          if (r.ok) { heartbeatWorkspace = ws; writeLastWs(ws) }
          log(r.ok ? 'MCP 已由面板启动' : ('MCP 启动失败：' + (r.error ?? '?') + ' ' + (r.detail ?? '')))
          return sendJson(res, 200, r)
        }
        if (action === 'stop') {
          const r = await stopMcp({ workspace: ws, mcp, fetchImpl, log })
          log(r.ok ? ('MCP 已彻底关闭（killed=' + r.killed.join(',') + '）') : 'MCP 关闭后仍有残留进程')
          if (r.ok) {
            // 关掉 MCP 就等于结束游戏：运行开关回到 ▶
            try { writeRun(ws, 'idle', 'mcp-stop', BOOT_ID, now(), sessionIdOf(agent)) } catch { /* 尽力 */ }
          }
          return sendJson(res, 200, r)
        }
        return sendJson(res, 200, { ok: false, error: 'bad_action' })
      }
      // ▶/⏸ 运行开关（玩家点「开始游戏」/「进行中」）：GET 读状态，POST 切换
      if (path === '/api/zff-mc/usage' && req.method === 'GET') {
        const r = await callDaemonTool({
          baseUrl: cfg.daemonUrl,
          tool: 'tool_usage',
          args: { limit: 200 },
          timeoutMs: 5000,
          fetchImpl,
        })
        return sendJson(res, r.ok ? 200 : 503, r.ok
          ? { ok: true, ...(r.json && typeof r.json === 'object' ? r.json : {}) }
          : { ok: false, error: r.error ?? 'usage_unavailable', detail: r.text ?? null })
      }
      if (path === '/api/zff-mc/team' && req.method === 'GET') {
        const agent = pickAgent(null)
        const ws = workspaceOf(agent)
        const st = agent ? stateOf(agent) : null
        return sendJson(res, 200, buildTeam({
          workspace: ws,
          now: now(),
          cr: {
            status: statusOf(agent),
            presetId: agent ? presetIdOf(agent) : null,
            lastDecision: st?.lastDecision ?? null,
          },
        }))
      }
      if (path === '/api/zff-mc/run' && req.method === 'GET') {
        const agent = agentFromRequest(req, url, null)
        const ws = workspaceOf(agent) || workspaceOf(pickAgent(null))
        if (!ws) return sendJson(res, 200, { ok: false, error: 'no_workspace' })
        const sid = sessionIdOf(agent)
        const r = readRun(ws, BOOT_ID, sid)
        const env = agent?.id && agents.get(String(agent.id))?.env
          ? agents.get(String(agent.id)).env
          : (agents.size ? [...agents.values()][0]?.env ?? null : null)
        return sendJson(res, 200, {
          ok: true,
          state: r.state,
          at: r.at,
          by: r.by,
          reset: r.reset === true,
          sessionId: sid,
          game: env ? { server: env.server, instance: env.instance, blocked: env.blocked } : null,
        })
      }
      if (path === '/api/zff-mc/run' && req.method === 'POST') {
        const body = JSON.parse(await readBody(req) || '{}')
        const next = body?.state === 'running' ? 'running' : 'idle'
        const agent0 = agentFromRequest(req, url, body)
        const ws = workspaceOf(agent0)
        if (!ws) return sendJson(res, 200, { ok: false, error: 'no_workspace' })
        // ── 需求 5：开始游戏前 MCP 必须是好的 ─────────────────────────────
        //   · 没起 → **自动起**（本工作目录；有 zip 就解压，有解压好的就用）
        //   · 起不来 / 有挂着的 server / 别的工作区的 server → 返回错误，**保持 ▶**
        let mcpGate = null
        if (next === 'running') {
          try {
            const st = await mcpStatus({ workspace: ws, mcp, fetchImpl })
            lastMcp = { at: now(), status: st }
            if (!st.healthy) {
              if (st.running && st.conflict === 'hung') {
                mcpGate = { ok: false, error: 'hung', detail: `端口 ${st.port} 被 pid=${st.pid ?? '?'} 占着但 /health 不响应`, status: st }
              } else {
                const r = await startMcp({ workspace: ws, mcp, fetchImpl, spawnImpl, log, startedBy: 'ui' })
                if (r.ok) mcpGate = { ok: true, started: true, status: r.status }
                else mcpGate = { ok: false, error: r.error, detail: r.detail, status: r.status ?? st }
              }
            } else if (st.conflict === 'other_workspace') {
              mcpGate = { ok: false, error: 'other_workspace', detail: `已有 MCP server 在跑，但它的工作目录是 ${st.workspace ?? '?'}（不是 ${ws}）`, status: st }
            } else {
              mcpGate = { ok: true, status: st }
            }
          } catch (e) {
            mcpGate = { ok: false, error: 'probe_failed', detail: String(e?.message ?? e) }
          }
          if (!mcpGate.ok) {
            // 不写 running：保持 ▶，把原因交给面板弹窗
            log('▶ 被拒绝：' + mcpGate.error + ' ' + (mcpGate.detail ?? ''))
            return sendJson(res, 200, { ok: false, error: mcpGate.error, detail: mcpGate.detail ?? null, state: 'idle', mcp: mcpGate.status ?? null })
          }
        }
        const w = writeRun(ws, next, 'ui', BOOT_ID, now(), sessionIdOf(agent0))
        // 点 ▶ = 玩家明确要它干活：顺手清掉可能残留的"暂停"（比如上一轮 CI 自己在聊天里叫停过），
        // 否则会出现"按了开始却不动"的怪状态。
        if (next === 'running') {
          try { setPaused({ workspace: ws, paused: false, by: 'ui', reason: '玩家点了开始游戏', now: now() }) } catch { /* 尽力 */ }
        }
        log(next === 'running' ? '▶ 玩家点了「开始游戏」：续跑开关打开' : '⏸ 玩家点了「进行中」：结束游戏，停止续跑')
        // 🔴 关键：注入=唤醒。所以
        //   ▶：**应该**唤醒（这就是"开始游戏"这个动作本身）——但要等游戏真的进世界再叫；
        //   ⏸：**绝不能在它已经停下时注入**，否则"想暂停反而把它叫醒"（2026-11-01 实测踩到）。
        //       只有它此刻确实在跑（status=running）时，才把"停手"这句话塞进这一轮。
        // P7-6：只对**按 ▶ 的那个会话**注入，子代理/其它 ZFF 会话不再被叫醒（日志里重复两条的来源）。
        if (agent0 && isZffMode(agent0) && !isChildSession(agent0)) {
          const st = stateOf(agent0)
          if (next === 'running') {
            const inst = st.env?.instance
            if (inst?.inWorld) {
              try {
                inject(agent0, '[ZFF MC] 玩家点了 ▶ 开始游戏：先 mcp__mc__get_state 确认在线，再 chat_turn 看有没有玩家消息。玩家消息只回复一次；之后继续执行同一任务，不要因为自动续跑再次回复，也不要停下来输出总结。直到完成、被挡住或玩家明确叫停。', 'ZFF MC：玩家点了开始游戏')
              } catch (e) { pushError('开始提示注入失败', e) }
            } else {
              st.pendingStart = true
              log('▶ 已打开开关，但游戏还没进世界：进世界后再唤醒它')
            }
          } else {
            if (statusOf(agent0) !== 'running') {
              // 已经停着的就什么都别发 —— 发一句"停手"只会把它重新叫醒
              st.pendingStart = false
            } else {
              try {
                inject(agent0, '[ZFF MC] 玩家按了 ⏸（结束游戏）：立刻停手，不要再调用任何游戏工具，用一句话汇报当前状态即可。', 'ZFF MC：玩家结束游戏')
              } catch (e) { pushError('停手提示注入失败', e) }
            }
          }
        }
        return sendJson(res, 200, { ok: true, state: w.state, at: w.at, sessionId: sessionIdOf(agent0), file: w.file, mcp: mcpGate?.status ?? lastMcp.status ?? null })
      }
      if (path === '/api/zff-mc/pause' && req.method === 'POST') {
        const body = JSON.parse(await readBody(req) || '{}')
        const ws = workspaceOf(agent)
        if (!ws) return sendJson(res, 200, { ok: false, error: 'no_workspace' })
        const w = setPaused({ workspace: ws, paused: body?.paused === true, by: 'web', reason: body?.reason ?? null, now: now() })
        return sendJson(res, 200, { ok: true, paused: body?.paused === true, file: w.file })
      }
      // 客户端门控用：哪些 preset 算 ZFF MC 模式（含启动器是否已配置），极小、不需要 sessionId
      if (path === '/api/zff-mc/presets' && req.method === 'GET') {
        return sendJson(res, 200, {
          ok: true,
          modePresets: Array.isArray(cfg.modePresets) ? cfg.modePresets : ['zff-mc'],
        })
      }
      // 设置页：游戏账号 / 续跑刹车 / 管理员 / 聊天规则。保存即生效（写 settings.json 并 apply 到内存）。
      if (path === '/api/zff-mc/settings' && req.method === 'GET') {
        const cwd = workspaceOf(pickAgent(null)) ?? process.cwd()
        return sendJson(res, 200, settingsView(cfg, homeDir, { cwd }))
      }
      if (path === '/api/zff-mc/settings' && req.method === 'PUT') {
        const body = JSON.parse(await readBody(req) || '{}')
        const patch = sanitizeSettingsPatch(body)
        const merged = mergeSettings(readSettings(homeDir), patch)
        writeSettings(homeDir, merged)
        applySettings(cfg, merged)                  // 立即生效（不含需要重启的字段：无）
        const cwd = workspaceOf(pickAgent(null)) ?? process.cwd()
        return sendJson(res, 200, { ok: true, ...settingsView(cfg, homeDir, { cwd }), patched: Object.keys(patch) })
      }
      // mustReply 关键词 + 聊天中断正则：写 MCP server 的 runtime/zff-chat-rules.json（它热加载）
      if (path === '/api/zff-mc/chat-rules' && req.method === 'GET') {
        const ws = workspaceOf(pickAgent(null))
        const file = ws ? join(ws, 'runtime', 'zff-chat-rules.json') : null
        let current = null
        try { current = file ? JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, '')) : null } catch { current = null }
        return sendJson(res, 200, { ok: true, file, rules: current })
      }
      if (path === '/api/zff-mc/chat-rules' && req.method === 'PUT') {
        const ws = workspaceOf(pickAgent(null))
        if (!ws) return sendJson(res, 200, { ok: false, error: 'no_workspace' })
        const body = JSON.parse(await readBody(req) || '{}')
        const file = join(ws, 'runtime', 'zff-chat-rules.json')
        let prev = {}
        try { prev = JSON.parse(readFileSync(file, 'utf8').replace(/^\uFEFF/, '')) } catch { prev = {} }
        const next = { ...prev }
        if (typeof body?.matcher === 'string') next.matcher = body.matcher
        if (typeof body?.enabled === 'boolean') next.enabled = body.enabled
        if (Array.isArray(body?.mustReplyKeywords)) next.mustReplyKeywords = body.mustReplyKeywords.filter((x) => typeof x === 'string')
        if (Array.isArray(body?.exemptTools)) next.exemptTools = body.exemptTools.filter((x) => typeof x === 'string')
        mkdirSync(join(ws, 'runtime'), { recursive: true })
        writeFileSync(file, JSON.stringify(next, null, 2) + '\n', 'utf8')
        return sendJson(res, 200, { ok: true, file, rules: next, note: 'MCP server 会在下一次 ingest 时热加载' })
      }
      return sendJson(res, 404, { ok: false, error: `unknown endpoint ${req.method} ${path}` })
    } catch (e) {
      return sendJson(res, 200, { ok: false, error: String(e?.message ?? e) })
    }
  }

  /* ── 事件绑定 ─────────────────────────────────────────────────────── */
  const stateOf = (agent) => {
    const id = String(agent?.id ?? 'anonymous')
    let st = agents.get(id)
    if (!st) {
      st = {
        id, agent, wasRunning: false, idleSince: 0, turnEnds: 0,
        idleStreak: 0, selfStreak: 0, lowPower: false,
        lastInjectAt: 0, eventsAtInject: null, lastDecision: null, seenAt: now(),
      }
      agents.set(id, st)
    }
    st.agent = agent
    st.seenAt = now()
    return st
  }

  const onAgent = (agent) => {
    if (!agent?.id) return null
    const st = stateOf(agent)
    try { applyToolPolicy(agent) } catch (e) { pushError('套用工具策略失败', e) }
    return st
  }

  const installEvents = () => {
    const offs = []
    const on = (ev, fn) => { try { const off = ctx.on(ev, fn); if (typeof off === 'function') offs.push(off) } catch { /* 宿主没有这个事件 */ } }
    on('agent/created', (payload) => { const a = payload?.agent; if (a) onAgent(a) })
    on('agent/session-start', (payload) => { const a = payload?.agent; if (a) onAgent(a) })
    // 模式切换：两个**位置参数**（whale_craft/index.js:4575 的证据）
    on('agent-preset/selected', (sessionId /* , presetId */) => {
      const a = safeAgentById(sessionId)
      if (a) onAgent(a)
    })
    // 每步请求之前：提示词注入点（waterfall，**必须交棒**）
    on('agent/pre-step', (payload, next) => {
      if (typeof next !== 'function') {
        try { return enterWithPrompts(payload?.agent, payload) } catch { return undefined }
      }
      const inner = next()
      const wrap = (d) => enterWithPrompts(payload?.agent, d)
      if (inner && typeof inner.then === 'function') return inner.then(wrap)
      return wrap(inner)
    })
    return () => { for (const off of offs) { try { off?.() } catch { /* 尽力 */ } } }
  }

  const safeAgentById = (sessionId) => {
    const id = String(sessionId ?? '')
    if (!id) return null
    const known = agents.get(id)?.agent
    if (known) return known
    try { return service('agents')?.get?.(id) ?? null } catch { return null }
  }

  /* ── 生命周期 ─────────────────────────────────────────────────────── */
  const runTick = async () => {
    try {
      const t = now()
      for (const st of [...agents.values()]) {
        try { await tick(st, t) } catch (e) { pushError(`tick(${st?.id}) 失败`, e) }
      }
    } catch (e) { pushError('定时器回调失败（已吞，进程不受影响）', e) }
  }

  const start = () => {
    if (started) return api
    started = true
    // ① 工具注册
    try {
      const reg = service('tools')
      if (reg && typeof reg.register === 'function') {
        for (const tool of Object.values(tools)) reg.register(tool)
        log(`已注册 ${Object.keys(tools).length} 个工具：${Object.keys(tools).join(', ')}`)
      } else {
        log('⚠️ 拿不到 tools 服务：工具不会注册（插件其余功能照常）')
      }
    } catch (e) { pushError('注册工具失败', e) }

    // ② 工具守卫：ZFF MC Mode 里只放行 mcp__mc__* + 文件/待办 + 本插件工具
    try {
      const reg = service('tools')
      if (reg && typeof reg.guard === 'function') {
        guardDisposer = reg.guard((exec) => {
          try {
            const agent = exec?.agent
            if (!agent || !isZffMode(agent)) return undefined
            const n = String(exec?.name ?? '')
            if (toolAllowedInMode(n, agent)) return undefined
            if (n.startsWith(cfg.mcpToolPrefix)) {
              return `游戏已结束（玩家按的是 ▶）：只能查看状态，不能操作世界。要动手请让玩家点「开始游戏」变成 ⏸。`
            }
            return `ZFF MC Mode 里只能调游戏工具（${cfg.mcpToolPrefix}*）和少量文件/待办工具；「${n}」不在允许面里。`
          } catch { return undefined }
        })
      }
    } catch (e) { pushError('注册工具守卫失败（不可见性限制仍然生效）', e) }

    // ③ HTTP 路由（设置页数据面）
    try {
      const ws = service('webServer')
      if (ws && typeof ws.register === 'function') {
        const route = { kind: 'prefix', path: '/api/zff-mc', handler: (req, res) => { handleApi(req, res) } }
        routeDisposer = ws.register(route)
        log('已注册 /api/zff-mc 路由（设置页）')
      } else {
        log('⚠️ 拿不到 webServer 服务：设置页无法读写提示词（可用 zff_prompts 工具代替）')
      }
    } catch (e) { pushError('注册 HTTP 路由失败', e) }

    // ③.5 开机预启动：DSH 的 mc-control 是在 Host 启动时连接的，而插件 tick 要等会话/agent
    //      才会 refreshEnv。如果 server 在 mc-control 放弃重连之后才起来，mcp__mc__* 永远不会出现
    //      （2026-11-02 真机：DSH 20:31 起，server 20:38 才起 → unknown tool "mcp__mc__get_state"）。
    //      所以只要配置了固定 workspaceDir，插件一加载就先拉 server/daemon，抢在 mc-control 重试窗口内。
    try {
      const preWs = String(cfg.workspaceDir ?? '').trim()
      if (preWs && cfg.mcpServer?.enabled !== false && cfg.mcpServer?.autostart !== false) {
        void ensureServer({ workspace: preWs, mcp, fetchImpl, spawnImpl, log })
          .then((r) => {
            log(`预启动 MCP：${r.ok ? r.action : r.action + '（' + (r.detail ?? '') + '）'}`)
            if (r.ok) return ensureDaemon({ workspace: preWs, mcp, fetchImpl, spawnImpl, log })
            return null
          })
          .then((d) => { if (d) log(`预启动 daemon：${d.ok ? d.action : 'failed'}`) })
          .catch((e) => pushError('预启动 MCP/daemon 失败（已吞）', e))
      }
    } catch (e) { pushError('预启动 MCP 调度失败（已吞）', e) }

    // ④ 事件
    try { api._offEvents = installEvents() } catch (e) { pushError('安装事件监听失败', e) }

    // ⑤ 轮询（没有 turn-end 钩子，只能自己看 agent.status）
    try {
      timer = setInterval(() => { void runTick() }, cfg.tickMs)
      timer.unref?.()
    } catch (e) { pushError('起定时器失败（自动续跑不会工作）', e) }

    log(`ZFF MC 已就绪（modePresets=${cfg.modePresets.join('/')}；tick=${cfg.tickMs}ms；promptDir=${promptDir}）`)
    return api
  }

  const stop = () => {
    started = false
    if (timer) { try { clearInterval(timer) } catch { /* 已清 */ } timer = null }
    try { api._offEvents?.() } catch { /* 尽力 */ }
    try { routeDisposer?.() } catch { /* 尽力 */ }
    try { guardDisposer?.() } catch { /* 尽力 */ }
    for (const release of [...restrictReleases]) { try { release() } catch { /* 尽力 */ } }
    restrictReleases.clear()
    // 2026-11-04: 宿主关闭/插件 stop 时同步杀掉本 workspace 的 MCP server/daemon。
    // stop() 不能 await，所以用 stopMcpSync 做最后兜底；面板“关闭 MCP”仍走 stopMcp 异步。
    try {
      const ws = heartbeatWorkspace || readLastWs()
      if (ws) {
        const r = stopMcpSync({ workspace: ws, mcp: cfg.mcpServer ?? {}, log })
        log(r.ok ? '关闭插件：已清理 MCP/daemon' : `关闭插件：MCP 仍有残留（${JSON.stringify(r.leftover)}）`)
      }
    } catch (e) { pushError('插件 stop 清理 MCP 失败（已吞）', e) }
    log('ZFF MC 已停止')
  }

  const api = {
    name,
    version: PLUGIN_VERSION,
    config: cfg,
    home: zffHome,
    promptDir,
    defaultsRoot,
    agents,
    diagnostics,
    tools,
    // 自检/调试入口（**不要**在运行时乱调）
    start,
    stop,
    stateOf,
    tick,
    handleTurnEnd,
    inject,
    enterWithPrompts,
    buildPromptBundle,
    applyToolPolicy,
    isZffMode,
    presetIdOf,
    workspaceOf,
    handleApi,
    get terminated () { return terminated },
    setTerminated (v) { terminated = v === true },
    get pendingNotices () { return pendingNotices },
    get timerId () { return timer ? true : false },
  }
  return api
}

/** 插件版本（读包内 package.json；读不到给 unknown） */
const PLUGIN_VERSION = (() => {
  try {
    return JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version ?? 'unknown'
  } catch { return 'unknown' }
})()

/** 稳定的短指纹（djb2 → base36）：用来判断"提示词内容变了没有" */
export function hashString (s) {
  let h = 5381
  const str = String(s ?? '')
  for (let i = 0; i < str.length; i++) h = ((h << 5) + h + str.charCodeAt(i)) >>> 0
  return h.toString(36) + ':' + str.length.toString(36)
}

/* ══════════════════════════════════════════════════════════════════════════
 * 宿主入口
 * ══════════════════════════════════════════════════════════════════════════ */

/**
 * cordis 插件入口。`config` 由宿主按 `Config` schema 传进来（拿不到 schemastery 时就是原样透传）。
 * @param {any} ctx
 * @param {object} [rawConfig]
 */
export function apply (ctx, rawConfig) {
  // 🔴 2026-11-01 真机事故：`createZffPlugin` 里任何一次抛错（当时是 `ctx.get('dshHomePath')`
  //    返回函数导致 join() 抛 TypeError）都会让 cordis 判「entry did not activate」，
  //    而 Electron 的 web boot 把"有 entry 没激活"当成**致命错误** → DSH 起不来。
  //    第三方插件绝不该有这种权力：这里兜住一切，失败就退化成空插件，宿主照常启动。
  try {
    const plugin = createZffPlugin({ ctx, config: rawConfig ?? {}, logger: (m) => { try { ctx?.logger?.info?.(m) } catch { /* 宿主 logger 不可用 */ } } })
    plugin.start()
    try { ctx?.effect?.(() => () => plugin.stop()) } catch { /* 老宿主没有 effect */ }
    return plugin
  } catch (e) {
    const detail = e && e.stack ? e.stack : String(e)
    try { ctx?.logger?.error?.(`[zff-mc] 插件启动失败，已降级为空插件（不影响 DSH）：${detail}`) } catch { /* ignore */ }
    try { console.error('[zff-mc] 插件启动失败，已降级为空插件：' + detail) } catch { /* ignore */ }
    return { name: 'zff-mc', start () {}, stop () {} }
  }
}
