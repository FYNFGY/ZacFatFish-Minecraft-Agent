/**
 * ZFF MC / src/mcp-env.mjs —— **MCP 环境自举**（Ph7 功能①）
 * ============================================================================
 * 玩家的要求（原话）：
 *   「用户可以在任何工作目录下开启新对话…插件能自动地在工作目录下完成 ZFF MCP Server 的解压。
 *     如果用户直接启动对话，且在工作目录下没有 ZFF MCP Server 的压缩包或目录，则 Agent 会发消息，
 *     用户需要手动下载并把 zip 放置在该目录下。这个时候 Agent 不应该被唤醒。」
 *
 * 所以这里只管三件事：
 *   1. **看**：工作目录里有没有 `zff-mcp-server/`（解压好的）或 `ZFF MCP Server.zip`；
 *   2. **解压 + 起**：有 zip 就解到 `zff-mcp-server/`，然后 spawn `node src/server.js --http --port …`；
 *   3. **报**：什么都没有就返回 `missing`，由 index.js 去"告诉玩家、且不续跑"（不在这里喷日志/唤醒）。
 *
 * 约束：本模块**不抛异常**（返回 `{ok:false, reason, detail}`），因为它的调用点在每 2 s 的 tick 里。
 * ============================================================================
 */
import { existsSync, mkdirSync, openSync, readdirSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { spawn as nodeSpawn, spawnSync } from 'node:child_process'

/**
 * 🔴 2026-11-02 真机（D:\MC Agent Play）：插件同一 tick 里对多个 agent 调 refreshEnv，
 * `ensureServer`/`ensureDaemon` 都是"看到不健康就 spawn"，于是同一个端口被连起了 3 个 server
 * 和 3 个 daemon；只有第一个绑上端口，其余 EADDRINUSE 退出，但 DSH 的 mc-control 可能已经
 * 连到"将被换掉"的那个会话 → 全部 `unknown MCP session`。
 *
 * 这里做 **single-flight**：同一 workspace+port 同时只允许一个 ensure 在跑，后来者复用同一个
 * Promise；跑完无论成功失败都释放，下一次 tick 再重新探测。
 */
const ensureServerLocks = new Map()
const ensureDaemonLocks = new Map()

export function ensureServer (options = {}) {
  const port = Number(options.mcp?.port ?? DEFAULT_MCP_SERVER.port) || DEFAULT_MCP_SERVER.port
  // 端口是全局资源：即使两个会话的 workspace 不同，也不能同时起两个 server 抢 57610。
  const key = String(port)
  const running = ensureServerLocks.get(key)
  if (running) return running
  const p = ensureServerImpl(options).finally(() => { ensureServerLocks.delete(key) })
  ensureServerLocks.set(key, p)
  return p
}

export function ensureDaemon (options = {}) {
  const port = Number(options.mcp?.daemonPort ?? DEFAULT_MCP_SERVER.daemonPort) || DEFAULT_MCP_SERVER.daemonPort
  // 同上：57611 只能有一个 daemon，按端口 single-flight。
  const key = String(port)
  const running = ensureDaemonLocks.get(key)
  if (running) return running
  const p = ensureDaemonImpl(options).finally(() => { ensureDaemonLocks.delete(key) })
  ensureDaemonLocks.set(key, p)
  return p
}

/** 默认值（profile 里 `zff-mc` 的 `config.mcpServer` 可以逐项覆盖） */
export const DEFAULT_MCP_SERVER = {
  port: 57610,
  /** 聊天守护进程（mc-daemon.mjs）的端口：mod 往这里推 chat_event */
  daemonPort: 57611,
  /** 服务端内部 ingest 端口（daemon 把聊天转发到这里，需要 token） */
  internalPort: 57612,
  /** 空 = `http://127.0.0.1:<port>` */
  url: '',
  /** 工作目录下解压后的目录名 */
  dirName: 'zff-mcp-server',
  /** 工作目录下的压缩包名（也可自动认出 `*.zip` 里名字像 ZFF/MCP 的） */
  zipName: 'ZFF MCP Server.zip',
  /** true = 缺 server 时自动解压并启动 */
  autostart: true,
  /** node 可执行文件；空 = 用当前进程的 execPath */
  nodePath: '',
  /** 等 /health 变绿的上限 */
  startTimeoutMs: 15000,
}

/** `http://127.0.0.1:<port>`（`url` 非空则用它，去掉尾部斜杠） */
export function mcpBase (mcp = DEFAULT_MCP_SERVER) {
  const explicit = String(mcp?.url ?? '').trim()
  if (explicit) return explicit.replace(/\/+$/, '')
  const port = Number(mcp?.port ?? DEFAULT_MCP_SERVER.port)
  return `http://127.0.0.1:${Number.isFinite(port) && port > 0 ? port : DEFAULT_MCP_SERVER.port}`
}

/** `GET /health` 是否是 200。超短超时：调用点每 2 s 一次。 */
export async function healthOk (base, fetchImpl = globalThis.fetch, timeoutMs = 1500) {
  try {
    const res = await fetchImpl(`${base}/health`, { signal: AbortSignal.timeout(timeoutMs) })
    return res?.status === 200
  } catch { return false }
}

/**
 * 工作目录里有什么？
 * @returns {{mode:'dir'|'zip'|'missing', dir:string, zip:string|null, serverJs:string|null, candidates:string[]}}
 */
export function inspectWorkspace (workspace, mcp = DEFAULT_MCP_SERVER) {
  const ws = String(workspace ?? '').trim()
  const out = { mode: 'missing', dir: '', zip: null, serverJs: null, candidates: [] }
  if (!ws) return out
  const dirName = String(mcp?.dirName ?? DEFAULT_MCP_SERVER.dirName)
  const zipName = String(mcp?.zipName ?? DEFAULT_MCP_SERVER.zipName)

  // ① 已解压目录：优先配置名，其次常见的 `mcp-server`（开发期就是这个名字）
  for (const name of [dirName, 'mcp-server']) {
    const dir = join(ws, name)
    const serverJs = join(dir, 'src', 'server.js')
    if (existsSync(serverJs)) {
      out.mode = 'dir'
      out.dir = dir
      out.serverJs = serverJs
      return out
    }
  }
  // ② 压缩包：配置名优先，其次工作目录根上的候选
  const zips = []
  const configured = join(ws, zipName)
  if (existsSync(configured)) zips.push(configured)
  try {
    for (const name of readdirSync(ws)) {
      if (!/\.zip$/i.test(name)) continue
      if (!/zff|mcp/i.test(name)) continue
      const full = join(ws, name)
      if (!zips.includes(full)) zips.push(full)
    }
  } catch { /* 目录读不了：当没有 */ }
  out.candidates = zips
  if (zips.length) {
    out.mode = 'zip'
    out.zip = zips[0]
  }
  return out
}

/** 解压 zip 到 dest。优先 bsdtar（Win10+ 自带），退回 PowerShell Expand-Archive。 */
export function extractZip (zipPath, destDir, { spawnSyncImpl = spawnSync } = {}) {
  const zip = resolve(String(zipPath ?? ''))
  const dest = resolve(String(destDir ?? ''))
  if (!existsSync(zip)) return { ok: false, method: null, detail: `压缩包不存在：${zip}` }
  try { mkdirSync(dest, { recursive: true }) } catch (e) { return { ok: false, method: null, detail: `建目录失败：${e?.message ?? e}` } }

  const attempts = [
    ['tar', ['-xf', zip, '-C', dest]],
    ['powershell', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', `Expand-Archive -LiteralPath '${zip.replace(/'/g, "''")}' -DestinationPath '${dest.replace(/'/g, "''")}' -Force`]],
  ]
  const errors = []
  for (const [cmd, args] of attempts) {
    try {
      const r = spawnSyncImpl(cmd, args, { windowsHide: true, timeout: 120000 })
      if (r && r.status === 0) return { ok: true, method: cmd, detail: dest }
      errors.push(`${cmd}: exit=${r?.status ?? 'null'} ${String(r?.stderr ?? '').slice(0, 200)}`)
    } catch (e) {
      errors.push(`${cmd}: ${e?.message ?? e}`)
    }
  }
  return { ok: false, method: null, detail: errors.join(' | ') }
}

/** 等 /health 变绿 */
export async function waitHealth (base, fetchImpl, timeoutMs) {
  const deadline = Date.now() + Math.max(1000, Number(timeoutMs) || 15000)
  while (Date.now() < deadline) {
    if (await healthOk(base, fetchImpl, 1200)) return true
    await new Promise((r) => setTimeout(r, 400))
  }
  return false
}

/**
 * 保证工作目录里有一个**活着的** MCP server。
 * @returns {Promise<{ok:boolean, action:'already'|'started'|'extracted-started'|'missing'|'extract_failed'|'start_failed'|'disabled', base:string, detail?:string, pid?:number}>}
 */
export async function ensureServerImpl ({
  workspace,
  mcp = DEFAULT_MCP_SERVER,
  fetchImpl = globalThis.fetch,
  spawnImpl = nodeSpawn,
  log = () => {},
} = {}) {
  const ws = String(workspace ?? '').trim()
  const base = mcpBase(mcp)
  if (!ws) return { ok: false, action: 'missing', base, detail: '没有工作目录' }
  if (await healthOk(base, fetchImpl)) return { ok: true, action: 'already', base }

  const seen = inspectWorkspace(ws, mcp)
  if (seen.mode === 'missing') {
    return { ok: false, action: 'missing', base, detail: `工作目录里既没有 ${mcp?.dirName ?? 'zff-mcp-server'}/ 也没有 zip：${ws}` }
  }
  if (mcp?.autostart === false) {
    return { ok: false, action: 'disabled', base, detail: 'mcpServer.autostart=false，且 server 没在跑' }
  }

  let serverJs = seen.serverJs
  let action = 'started'
  // 2026-11-03: a new release zip next to an old extracted directory used to be ignored forever.
  // If the zip is newer than the extracted server.js, refresh the directory before spawning.
  if (seen.mode === 'dir' && serverJs) {
    const zip = join(ws, String(mcp?.zipName ?? DEFAULT_MCP_SERVER.zipName))
    try {
      if (existsSync(zip) && statSync(zip).mtimeMs > statSync(serverJs).mtimeMs) {
        const ex = extractZip(zip, resolve(serverJs, '..', '..'))
        if (ex.ok) {
          action = 'updated-started'
          log(`检测到更新的 ${zip}，已覆盖解压目录 ${resolve(serverJs, '..', '..')}`)
        } else {
          log(`更新 ${zip} 失败（继续用旧目录）：${ex.detail}`)
        }
      }
    } catch { /* mtime/extract errors must not stop startup */ }
  }
  if (!serverJs) {
    // 只有 zip：解压到 <ws>/<dirName>
    const dir = join(ws, String(mcp?.dirName ?? DEFAULT_MCP_SERVER.dirName))
    const ex = extractZip(seen.zip, dir)
    if (!ex.ok) return { ok: false, action: 'extract_failed', base, detail: ex.detail }
    serverJs = join(dir, 'src', 'server.js')
    if (!existsSync(serverJs)) {
      return { ok: false, action: 'extract_failed', base, detail: `解压后仍找不到 src/server.js：${serverJs}` }
    }
    action = 'extracted-started'
    log(`已解压 ZFF MCP Server → ${dir}`)
  }

  // 起进程：日志写进 <ws>/runtime/zff-mcp-server.{out,err}.log
  const runtime = join(ws, 'runtime')
  try { mkdirSync(runtime, { recursive: true }) } catch { /* 尽力 */ }
  const outLog = join(runtime, 'zff-mcp-server.out.log')
  const errLog = join(runtime, 'zff-mcp-server.err.log')
  const nodePath = String(mcp?.nodePath ?? '').trim() || process.execPath
  const port = Number(mcp?.port ?? DEFAULT_MCP_SERVER.port) || DEFAULT_MCP_SERVER.port
  let child = null
  try {
    child = spawnImpl(nodePath, ['src/server.js', '--http', '--port', String(port), '--workspace', ws], {
      cwd: resolve(serverJs, '..', '..'),
      detached: true,
      stdio: ['ignore', fsOpenAppend(outLog), fsOpenAppend(errLog)],
      windowsHide: true,
    })
    child?.unref?.()
  } catch (e) {
    return { ok: false, action: 'start_failed', base, detail: String(e?.message ?? e) }
  }
  log(`已启动 ZFF MCP Server（pid=${child?.pid ?? '?'}，port=${port}，workspace=${ws}）`)
  const up = await waitHealth(base, fetchImpl, mcp?.startTimeoutMs ?? DEFAULT_MCP_SERVER.startTimeoutMs)
  if (!up) return { ok: false, action: 'start_failed', base, detail: `启动后 ${mcp?.startTimeoutMs ?? 15000}ms 内 /health 未就绪`, pid: child?.pid }
  return { ok: true, action, base, pid: child?.pid }
}

/**
 * 保证**聊天守护进程**（`mc-daemon.mjs`，默认 57611）在跑。
 *
 * 🔴 2026-11-01 玩家实测：mod 把聊天推 `http://127.0.0.1:57611/chat_event`，但这个守护进程
 *    以前**没有任何人启动**（`start-mcp.ps1` 和插件都只起 `server.js`）→ 每条推送都是
 *    `ConnectException` → 服务端 `accepted:0` → “玩家说话硬中断”整条链路等于不存在。
 *    所以插件在起完 server 之后必须顺手把它拉起来。
 *
 * @returns {Promise<{ok:boolean, action:'already'|'started'|'failed'|'disabled', port:number, detail?:string, pid?:number}>}
 */
export async function ensureDaemonImpl ({
  workspace,
  serverJs,
  mcp = DEFAULT_MCP_SERVER,
  fetchImpl = globalThis.fetch,
  spawnImpl = nodeSpawn,
  log = () => {},
} = {}) {
  const ws = String(workspace ?? '').trim()
  const port = Number(mcp?.daemonPort ?? DEFAULT_MCP_SERVER.daemonPort) || DEFAULT_MCP_SERVER.daemonPort
  const base = `http://127.0.0.1:${port}`
  const healthy = async () => {
    try {
      const res = await fetchImpl(`${base}/health`, { signal: AbortSignal.timeout(1200) })
      return res?.status === 200
    } catch { return false }
  }
  if (mcp?.autostart === false) return { ok: false, action: 'disabled', port, detail: 'mcpServer.autostart=false' }
  if (await healthy()) return { ok: true, action: 'already', port }
  if (!ws) return { ok: false, action: 'failed', port, detail: '没有工作目录，无法定位 mc-daemon.mjs' }
  // 没传 serverJs 就自己找（<ws>/zff-mcp-server/src/server.js 或 <ws>/mcp-server/src/server.js）
  const effectiveServerJs = serverJs || inspectWorkspace(ws, mcp).serverJs
  if (!effectiveServerJs) return { ok: false, action: 'failed', port, detail: '工作目录里找不到 MCP server（无法定位 mc-daemon.mjs）' }

  const daemonJs = join(resolve(effectiveServerJs, '..', '..'), 'src', 'mc-daemon.mjs')
  if (!existsSync(daemonJs)) return { ok: false, action: 'failed', port, detail: `找不到 ${daemonJs}` }

  const runtime = join(ws, 'runtime')
  try { mkdirSync(runtime, { recursive: true }) } catch { /* 尽力 */ }
  const outLog = join(runtime, 'mc-daemon.out.log')
  const errLog = join(runtime, 'mc-daemon.err.log')
  const nodePath = String(mcp?.nodePath ?? '').trim() || process.execPath
  const serverPort = Number(mcp?.port ?? DEFAULT_MCP_SERVER.port) || DEFAULT_MCP_SERVER.port
  let child = null
  try {
    child = spawnImpl(nodePath, ['src/mc-daemon.mjs'], {
      cwd: resolve(daemonJs, '..', '..'),
      detached: true,
      stdio: ['ignore', fsOpenAppend(outLog), fsOpenAppend(errLog)],
      windowsHide: true,
      env: {
        ...process.env,
        MCP_URL: `http://127.0.0.1:${serverPort}/mcp`,
        PORT: String(port),
        MC_WORKSPACE: ws,
      },
    })
    child?.unref?.()
  } catch (e) {
    return { ok: false, action: 'failed', port, detail: String(e?.message ?? e) }
  }
  // 等它起来（最多 6 s）
  const deadline = Date.now() + 6000
  while (Date.now() < deadline) {
    if (await healthy()) {
      log(`已启动聊天守护进程 mc-daemon（pid=${child?.pid ?? '?'}，port=${port}）`)
      return { ok: true, action: 'started', port, pid: child?.pid }
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  return { ok: false, action: 'failed', port, detail: 'mc-daemon 启动后 /health 未就绪', pid: child?.pid }
}


/** 打开一个可追加的日志 fd；失败退回 'ignore'（不能让"写日志失败"拖垮启动） */
function fsOpenAppend (file) {
  try { return openSync(file, 'a') } catch { return 'ignore' }
}

/** 读 `/instances`（带 username）。失败不抛。 */
export async function fetchInstances (base, fetchImpl = globalThis.fetch, timeoutMs = 2500, includeExpired = false) {
  try {
    const url = `${base}/instances${includeExpired ? '?expired=1' : ''}`
    const res = await fetchImpl(url, { signal: AbortSignal.timeout(timeoutMs) })
    if (res?.status !== 200) return { ok: false, instances: [], error: `HTTP ${res?.status}` }
    const j = await res.json()
    if (!j || j.ok !== true || !Array.isArray(j.instances)) return { ok: false, instances: [], error: '响应格式不对' }
    return { ok: true, instances: j.instances, dir: j.dir ?? null, workspace: j.workspace ?? null }
  } catch (e) {
    return { ok: false, instances: [], error: String(e?.message ?? e) }
  }
}

/**
 * 按**配置的离线账号**挑实例（Ph7 功能②）。
 * @param {Array} instances `/instances` 的数组
 * @param {string} playerName 配置里的离线名
 * @returns {{ok:boolean, instance?:object, reason?:'no_account'|'no_instance'|'ambiguous'|'unknown_username', matches:Array}}
 */
export function chooseInstance (instances, playerName) {
  const want = String(playerName ?? '').trim()
  const list = Array.isArray(instances) ? instances.filter((i) => i && i.port) : []
  if (!want) return { ok: false, reason: 'no_account', matches: [], detail: '没有配置 launcher.playerName（离线账号）' }
  if (!list.length) return { ok: false, reason: 'no_instance', matches: [], detail: '没有任何存活的游戏实例' }
  const same = list.filter((i) => String(i.username ?? '').trim().toLowerCase() === want.toLowerCase())
  if (same.length === 1) return { ok: true, instance: same[0], matches: same }
  if (same.length > 1) return { ok: false, reason: 'ambiguous', matches: same, detail: `${same.length} 个实例都叫 ${want}` }
  const unknown = list.filter((i) => !String(i.username ?? '').trim())
  if (unknown.length) {
    return { ok: false, reason: 'unknown_username', matches: unknown, detail: `有 ${unknown.length} 个实例还没上报游戏名（没进世界？），无法确认是不是 ${want}` }
  }
  return { ok: false, reason: 'no_instance', matches: [], detail: `没有叫 ${want} 的实例（现存：${list.map((i) => i.username || '?').join(', ')}）` }
}

/** 给提示词/报错用：把 blocked 原因翻译成一句中文 */
export function explainBlocked (blocked) {
  const kind = String(blocked?.kind ?? blocked ?? '')
  const detail = String(blocked?.detail ?? '')
  switch (kind) {
    case 'no_workspace': return '当前会话没有工作目录，插件无法定位 MCP 环境'
    case 'no_server': return `工作目录里没有 ZFF MCP Server（zip 或目录都缺）。请把「ZFF MCP Server.zip」放到工作目录根下，然后再说一句话让我重试。${detail ? `（${detail}）` : ''}`
    case 'extract_failed': return `ZFF MCP Server 解压失败：${detail}`
    case 'start_failed': return `ZFF MCP Server 启动失败：${detail}`
    case 'no_account': return '没有配置离线账号（设置 → 游戏 → 你的游戏名），无法选择要接管的实例'
    case 'no_instance': return `没有找到要接管的游戏实例：${detail}。如果是刚重启客户端，等它进世界（通常 1–2 分钟）即可；也可以确认实例目录里有没有新的注册文件。`
    case 'ambiguous': return `有多个同名实例，无法确定接管哪一个：${detail}`
    case 'unknown_username': return `有实例还没进世界（拿不到游戏名），无法确认身份：${detail}`
    case 'instances_unreachable': return `MCP server 在跑，但读不到实例列表：${detail}`
    case 'port_conflict': return `端口被别的工作目录的 MCP server 占着：${detail}。先停掉它，或让插件自己起本目录的。`
    case 'server_workspace_mismatch': return `端口上的 MCP server 属于**别的工作目录**，不能接管：${detail}（先停掉那个 server，或让插件自己起本目录的）`
    default: return detail || `环境异常：${kind}`
  }
}
