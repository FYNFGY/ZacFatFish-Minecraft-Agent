/**
 * ZFF MC / src/mcp-control.mjs —— **MCP 服务器的启停与状态**（2026-11-01 玩家需求 2/3/4/5/6）
 *
 * 玩家原话要点：
 *   2. 状态栏显示 MCP 实时状态、**从哪里被调起来（标明工作目录）**；一个按钮彻底关闭；
 *      另一个按钮在本工作目录**自动启动**（zip 或已解压文件夹都行；只有"图形化启动"才解压）。
 *   3. 关闭时把**所有相关进程**一起杀掉（server + daemon + 子进程），不能只杀一个。
 *   4. 启动后自动侦测装了桥接模组的客户端实例，用**登录账号名**标记。
 *   5. 开始游戏时 MCP 没起来 → 自动起；已有挂着的 MCP → 浮窗报告；两种情况都**不许**切到 ⏸。
 *   6. DSH 关掉后 server 自己退出（心跳看门狗，见 server.js `--owner-heartbeat`）。
 *
 * 这里只做"进程与文件"层的事，不碰 DSH 的会话/工具。
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, openSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import {
  DEFAULT_MCP_SERVER,
  extractZip,
  healthOk,
  inspectWorkspace,
  mcpBase,
  waitHealth,
} from './mcp-env.mjs'

const isWin = process.platform === 'win32'

/** `<workspace>/runtime/zff-mcp-server.json`（server 启动时自己写的） */
export function serverStateFile (workspace) {
  return join(String(workspace), 'runtime', 'zff-mcp-server.json')
}

export function readServerState (workspace) {
  try {
    const p = serverStateFile(workspace)
    if (!existsSync(p)) return null
    const j = JSON.parse(readFileSync(p, 'utf8').replace(/^\uFEFF/, ''))
    return j && typeof j === 'object' ? j : null
  } catch { return null }
}

/** 某个端口上正在监听的 pid 列表（Windows: netstat；其他: lsof） */
export function pidsOnPort (port) {
  try {
    const out = isWin
      ? execSyncText('netstat', ['-ano'])
      : execSyncText('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN'])
    const pids = new Set()
    for (const line of out.split(/\r?\n/)) {
      if (!line.includes(String(port))) continue
      if (isWin) {
        if (!/LISTENING/i.test(line)) continue
        const parts = line.trim().split(/\s+/)
        const pid = Number(parts[parts.length - 1])
        if (Number.isFinite(pid) && pid > 0) pids.add(pid)
      } else {
        const m = line.match(/\s(\d+)$/)
        if (m) pids.add(Number(m[1]))
      }
    }
    return [...pids]
  } catch { return [] }
}

function execSyncText (cmd, args) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', windowsHide: true, maxBuffer: 8 * 1024 * 1024 })
  return r?.stdout ?? ''
}

/** 一整棵进程树的 pid（Windows taskkill /T 仍可能漏掉脱离树/换父进程的子进程）。 */
export function killTree (pid) {
  const p = Number(pid)
  if (!Number.isFinite(p) || p <= 0) return false
  try {
    if (isWin) {
      spawnSync('taskkill', ['/PID', String(p), '/T', '/F'], { windowsHide: true, timeout: 10000 })
    } else {
      try { process.kill(-p, 'SIGKILL') } catch { /* not a group leader */ }
      try { process.kill(p, 'SIGKILL') } catch { /* already gone */ }
    }
    return true
  } catch { return false }
}

/** Windows: 一次拿到 pid/parent/name/commandLine，用来找脱离子进程与孤儿进程。 */
function processSnapshot () {
  if (!isWin) {
    try {
      const out = execSyncText('ps', ['-eo', 'pid=,ppid=,comm=,args='])
      return out.split(/\r?\n/).map((line) => {
        const m = line.trim().match(/^(\d+)\s+(\d+)\s+(\S+)\s*(.*)$/)
        return m ? { pid: Number(m[1]), parentPid: Number(m[2]), name: m[3], commandLine: m[4] } : null
      }).filter(Boolean)
    } catch { return [] }
  }
  try {
    const script = 'Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId,Name,CommandLine | ConvertTo-Json -Compress'
    const r = spawnSync('powershell', ['-NoProfile', '-NonInteractive', '-Command', script], {
      encoding: 'utf8',
      windowsHide: true,
      timeout: 15000,
      maxBuffer: 8 * 1024 * 1024,
    })
    const text = String(r?.stdout ?? '').replace(/^\uFEFF/, '').trim()
    if (!text) return []
    const parsed = JSON.parse(text)
    const arr = Array.isArray(parsed) ? parsed : [parsed]
    return arr.map((p) => ({
      pid: Number(p.ProcessId),
      parentPid: Number(p.ParentProcessId),
      name: String(p.Name ?? ''),
      commandLine: String(p.CommandLine ?? ''),
    })).filter((p) => Number.isFinite(p.pid) && p.pid > 0)
  } catch { return [] }
}

function isNodeProcess (p) {
  return /^node(\.exe)?$/i.test(String(p?.name ?? ''))
}

function commandHasWorkspace (p, ws) {
  if (!ws) return false
  const cmd = String(p?.commandLine ?? '').toLowerCase()
  const target = ws.toLowerCase()
  return cmd.includes(target)
}

/**
 * 收集本次 stop 应该杀的 pid：
 *   state.pid / state.daemonPid + 两个端口的 owner + 后代 + 当前 workspace 的 node 进程。
 * 关键点：端口 owner 若明确属于**另一个 workspace**，不杀，返回 conflict。
 */
function collectStopTargets (ws, port, daemonPort) {
  const state = ws ? readServerState(ws) : null
  const seeds = new Set()
  const details = new Map()
  const conflict = []
  if (state?.pid) seeds.add(Number(state.pid))
  if (state?.daemonPid) seeds.add(Number(state.daemonPid))
  for (const pid of pidsOnPort(port)) seeds.add(pid)
  for (const pid of pidsOnPort(daemonPort)) seeds.add(pid)

  const all = processSnapshot()
  const byPid = new Map(all.map((p) => [p.pid, p]))
  const targetWorkspace = String(ws ?? '').toLowerCase()

  // 端口/state 的 owner 若属于别的 workspace，不能误杀。
  for (const pid of [...seeds]) {
    const p = byPid.get(pid)
    if (!p) continue
    const cmd = String(p.commandLine ?? '');
    const m = cmd.match(/--workspace\s+("[^"]+"|\S+)/i)
    if (m) {
      const ownerWs = m[1].replace(/^"|"$/g, '').replace(/\/+$/, '').toLowerCase()
      if (targetWorkspace && ownerWs && ownerWs !== targetWorkspace) {
        conflict.push({ pid, workspace: m[1], name: p.name })
        seeds.delete(pid)
      }
    }
    details.set(pid, p)
  }

  // 当前 workspace 的 node 进程：补上 state 丢失时脱树的 server/daemon。
  for (const p of all) {
    if (!isNodeProcess(p)) continue
    if (!commandHasWorkspace(p, ws)) continue
    seeds.add(p.pid)
    details.set(p.pid, p)
  }

  // 递归后代（按 ParentProcessId），从已知 seed 展开。
  let changed = true
  while (changed) {
    changed = false
    for (const p of all) {
      if (seeds.has(p.pid)) continue
      if (seeds.has(p.parentPid)) {
        seeds.add(p.pid)
        details.set(p.pid, p)
        changed = true
      }
    }
  }

  // 不杀自己/宿主；去掉无效 pid。
  seeds.delete(process.pid)
  for (const pid of [...seeds]) {
    if (!Number.isFinite(pid) || pid <= 0) seeds.delete(pid)
  }
  return { seeds: [...seeds], details, state, conflict, all }
}

function removeStateIfOwned (ws, pid) {
  if (!ws) return false
  try {
    const p = serverStateFile(ws)
    if (!existsSync(p)) return false
    const cur = JSON.parse(readFileSync(p, 'utf8').replace(/^\uFEFF/, ''))
    if (pid == null || Number(cur?.pid) === Number(pid) || Number(cur?.pid) === Number(process.pid)) {
      rmSync(p, { force: true })
      return true
    }
  } catch { /* 尽力 */ }
  return false
}

/**
 * 同步兜底：插件 stop()/进程退出时不能 await，只能立即 taskkill。
 * 返回 {ok, killed, leftover, conflicts, stateRemoved}。
 */
export function stopMcpSync ({ workspace, mcp = DEFAULT_MCP_SERVER, log = () => {} } = {}) {
  const ws = String(workspace ?? '').trim()
  const port = Number(mcp?.port ?? DEFAULT_MCP_SERVER.port)
  const daemonPort = Number(mcp?.daemonPort ?? DEFAULT_MCP_SERVER.daemonPort)
  if (!ws) return { ok: false, error: 'no_workspace', killed: [], leftover: [] }
  const { seeds, details, state, conflict } = collectStopTargets(ws, port, daemonPort)
  const killed = []
  for (const pid of seeds) {
    log(`[stop-sync] 杀掉 pid=${pid}（${details.get(pid)?.name ?? '?'}）`)
    killTree(pid)
    killed.push(pid)
  }
  const leftover = [...pidsOnPort(port), ...pidsOnPort(daemonPort)]
  const stateRemoved = removeStateIfOwned(ws, state?.pid)
  return { ok: leftover.length === 0 && conflict.length === 0, killed, leftover, conflicts: conflict, stateRemoved }
}


/**
 * 当前 MCP 服务器的状态（需求 2）。
 * @returns {Promise<{running:boolean, healthy:boolean, port:number, daemonPort:number,
 *   pid:number|null, workspace:string|null, startedBy:string|null, startedAt:number|null,
 *   stateFile:string, daemon:{running:boolean, port:number}, instances:number,
 *   conflict:'none'|'other_workspace'|'hung'|'unknown'}>}
 */
export async function mcpStatus ({ workspace, mcp = DEFAULT_MCP_SERVER, fetchImpl = globalThis.fetch } = {}) {
  const ws = String(workspace ?? '').trim()
  const base = mcpBase(mcp)
  const daemonPort = Number(mcp?.daemonPort ?? DEFAULT_MCP_SERVER.daemonPort)
  const state = ws ? readServerState(ws) : null
  const healthy = await healthOk(base, fetchImpl, 1500)
  const portPids = pidsOnPort(mcp?.port ?? DEFAULT_MCP_SERVER.port)
  const running = healthy || portPids.length > 0
  let daemonRunning = false
  try {
    const r = await fetchImpl(`http://127.0.0.1:${daemonPort}/health`, { signal: AbortSignal.timeout(1200) })
    daemonRunning = r?.status === 200
  } catch { daemonRunning = false }
  let conflict = 'none'
  if (running && !healthy) conflict = 'hung'
  else if (healthy && state && ws && String(state.workspace ?? '').toLowerCase() !== ws.toLowerCase()) conflict = 'other_workspace'
  else if (healthy && !state) conflict = 'unknown'
  let instances = 0
  if (healthy) {
    try {
      const r = await fetchImpl(`${base}/instances`, { signal: AbortSignal.timeout(2000) })
      if (r?.status === 200) {
        const j = await r.json()
        instances = Array.isArray(j?.instances) ? j.instances.length : 0
      }
    } catch { /* 不影响状态判定 */ }
  }
  // P7-9: the mod's env mirror carries chatPush counters. If the daemon was down, `failed` keeps
  // growing while the player's messages never arrive - surface it instead of hiding it in a file.
  let chatPush = null
  if (ws) {
    try {
      const env = JSON.parse(readFileSync(join(ws, 'runtime', 'env-inbox.json'), 'utf8').replace(/^\uFEFF/, ''))
      if (env && env.chatPush) chatPush = env.chatPush
    } catch { /* mirror is optional */ }
  }
  return {
    running,
    healthy,
    port: Number(mcp?.port ?? DEFAULT_MCP_SERVER.port),
    daemonPort,
    pid: Number(state?.pid) || portPids[0] || null,
    workspace: state?.workspace ?? null,
    startedBy: state?.startedBy ?? null,
    startedAt: Number(state?.startedAt) || null,
    instanceWorkspace: state?.workspace ?? null,
    stateFile: ws ? serverStateFile(ws) : '',
    daemon: { running: daemonRunning, port: daemonPort },
    instances,
    conflict,
    portPids,
    chatPush,
    chatPushWarning: !!(chatPush && (Number(chatPush.failed) > 0 || Number(chatPush.dropped) > 0)),
  }
}

/**
 * 彻底关闭（需求 3）：state pid/daemon + 两个端口 owner + 后代 + 当前 workspace 的 node。
 * 返回 ok 只在端口和进程都空时为 true；leftover 带命令行，方便面板显示“到底谁还活着”。
 */
export async function stopMcp ({ workspace, mcp = DEFAULT_MCP_SERVER, fetchImpl = globalThis.fetch, log = () => {} } = {}) {
  const ws = String(workspace ?? '').trim()
  if (!ws) return { ok: false, error: 'no_workspace', killed: [], leftover: [] }
  const port = Number(mcp?.port ?? DEFAULT_MCP_SERVER.port)
  const daemonPort = Number(mcp?.daemonPort ?? DEFAULT_MCP_SERVER.daemonPort)
  const first = collectStopTargets(ws, port, daemonPort)
  if (first.conflict.length) {
    return {
      ok: false,
      error: 'other_workspace',
      conflict: first.conflict,
      killed: [],
      leftover: [],
      status: await mcpStatus({ workspace: ws, mcp, fetchImpl }),
    }
  }

  const killed = []
  let last = first
  // 两轮：第一轮整树；端口/进程若在 6s 内又被拉起来（supervisor/重连），第二轮按新快照再杀。
  for (let round = 0; round < 2; round++) {
    const { seeds, details } = last
    for (const pid of seeds) {
      if (!Number.isFinite(pid) || pid <= 0 || pid === process.pid) continue
      log(`[stop] 杀掉 pid=${pid}（${details.get(pid)?.name ?? '?'}）`)
      killTree(pid)
      killed.push(pid)
    }
    const deadline = Date.now() + 6000
    let ports = []
    while (Date.now() < deadline) {
      ports = [...pidsOnPort(port), ...pidsOnPort(daemonPort)]
      if (!ports.length) break
      await new Promise((r) => setTimeout(r, 250))
    }
    if (!ports.length) break
    last = collectStopTargets(ws, port, daemonPort)
    if (last.conflict.length) break
  }

  // 最终快照：端口 + 仍存活的已知 pid。
  const livePids = new Set()
  for (const p of processSnapshot()) {
    if (pidsOnPort(port).includes(p.pid) || pidsOnPort(daemonPort).includes(p.pid)) livePids.add(p.pid)
  }
  const leftover = [...new Set([...pidsOnPort(port), ...pidsOnPort(daemonPort), ...livePids])]
  const all = processSnapshot()
  const leftoverDetails = leftover.map((pid) => {
    const p = all.find((x) => x.pid === pid)
    return { pid, name: p?.name ?? null, commandLine: p?.commandLine ?? null }
  })
  const stateRemoved = removeStateIfOwned(ws, first.state?.pid)
  const st = await mcpStatus({ workspace: ws, mcp, fetchImpl })
  return {
    ok: leftover.length === 0 && !st.running && !st.healthy && !last.conflict.length,
    killed: [...new Set(killed)],
    leftover,
    leftoverDetails,
    conflict: last.conflict,
    stateRemoved,
    status: st,
  }
}

/**
 * 在**当前工作目录**启动（需求 2/5）：
 *   - 已有 `zff-mcp-server/src/server.js` → 直接用；
 *   - 只有 `ZFF MCP Server.zip` → **解压到本工作目录**再用；
 *   - 都没有 → 报 `no_zip`（只有"图形化启动"才会解压，这点和玩家要求一致）。
 */
export async function startMcp ({
  workspace,
  mcp = DEFAULT_MCP_SERVER,
  spawnImpl = spawn,
  fetchImpl = globalThis.fetch,
  log = () => {},
  startedBy = 'ui',
} = {}) {
  const ws = String(workspace ?? '').trim()
  if (!ws) return { ok: false, error: 'no_workspace' }
  const before = await mcpStatus({ workspace: ws, mcp, fetchImpl })
  if (before.healthy && before.conflict === 'none') return { ok: true, action: 'already', status: before }
  if (before.running && !before.healthy) return { ok: false, error: 'hung', detail: `端口 ${before.port} 被 pid=${before.pid ?? '?'} 占着但 /health 不来`, status: before }
  if (before.healthy && before.conflict !== 'none') {
    return { ok: false, error: before.conflict, detail: `已有 MCP server 在跑（workspace=${before.workspace ?? '?'}）`, status: before }
  }

  // 1) 找或解压
  const insp = inspectWorkspace(ws, mcp)
  if (!insp.serverJs) {
    if (!insp.zip) return { ok: false, error: 'no_zip', detail: `本目录既没有 ${mcp?.dirName ?? DEFAULT_MCP_SERVER.dirName} 也没有 ${mcp?.zipName ?? DEFAULT_MCP_SERVER.zipName}` }
    // inspectWorkspace 在 zip-only 模式下不返回目标目录，这里自己算（和 ensureServer 用同一个约定）
    const destDir = insp.dir || join(ws, String(mcp?.dirName ?? DEFAULT_MCP_SERVER.dirName))
    log(`解压 ${insp.zip} → ${destDir}`)
    const res = extractZip(insp.zip, destDir)
    if (res?.ok === false) return { ok: false, error: 'extract_failed', detail: res.error ?? '解压失败' }
    const again = inspectWorkspace(ws, mcp)
    if (!again.serverJs) return { ok: false, error: 'extract_failed', detail: '解压后仍找不到 src/server.js' }
    insp.serverJs = again.serverJs
    insp.dir = again.dir
  }

  // 2) 起 server（带心跳看门狗：DSH 关掉 30 s 后自己退出）
  const port = Number(mcp?.port ?? DEFAULT_MCP_SERVER.port)
  const nodePath = String(mcp?.nodePath ?? '').trim() || process.execPath
  const serverRoot = join(insp.dir || join(ws, 'zff-mcp-server'))
  const outLog = join(ws, 'runtime', 'zff-mcp-server.out.log')
  const errLog = join(ws, 'runtime', 'zff-mcp-server.err.log')
  try { mkdirSync(join(ws, 'runtime'), { recursive: true }) } catch { /* 尽力 */ }
  const openLog = (f) => { try { return openSync(f, 'a') } catch { return 'ignore' } }
  const args = ['src/server.js', '--http', '--port', String(port), '--workspace', ws, '--owner-heartbeat', '--started-by', startedBy,
    '--exit-without-instance', String(Number(mcp?.exitWithoutInstanceSeconds ?? 60))]
  let child = null
  try {
    child = spawnImpl(nodePath, args, {
      cwd: serverRoot,
      detached: true,
      windowsHide: true,
      stdio: ['ignore', openLog(outLog), openLog(errLog)],
      env: { ...process.env, MC_INTERNAL_PORT: String(mcp?.internalPort ?? DEFAULT_MCP_SERVER.internalPort) },
    })
    child?.unref?.()
  } catch (e) {
    return { ok: false, error: 'spawn_failed', detail: String(e?.message ?? e) }
  }
  const up = await waitHealth(mcpBase(mcp), fetchImpl, Number(mcp?.startTimeoutMs ?? DEFAULT_MCP_SERVER.startTimeoutMs))
  if (!up) {
    killTree(child?.pid)
    return { ok: false, error: 'start_failed', detail: `启动后 ${mcp?.startTimeoutMs ?? 15000}ms 内 /health 未就绪`, pid: child?.pid }
  }
  // 3) 起聊天守护进程（mod 往 57611 推 chat_event）
  let daemon = { ok: false, action: 'skipped' }
  try {
    const { ensureDaemon } = await import('./mcp-env.mjs')
    daemon = await ensureDaemon({ workspace: ws, mcp, fetchImpl, spawnImpl, log })
  } catch (e) { daemon = { ok: false, action: 'failed', detail: String(e?.message ?? e) } }
  const after = await mcpStatus({ workspace: ws, mcp, fetchImpl })
  log(`MCP 已启动（pid=${child?.pid ?? '?'}，port=${port}，workspace=${ws}）`)
  return { ok: true, action: 'started', pid: child?.pid, daemon, status: after }
}

/** 心跳（需求 6）：插件每次 tick 打一次；server 30 s 收不到就自杀 */
export async function sendHeartbeat ({ workspace, mcp = DEFAULT_MCP_SERVER, fetchImpl = globalThis.fetch } = {}) {
  const ws = String(workspace ?? '').trim()
  if (!ws) return false
  try {
    const token = readFileSync(join(ws, 'runtime', 'mcp-internal-token'), 'utf8').trim()
    const internalPort = Number(mcp?.internalPort ?? DEFAULT_MCP_SERVER.internalPort)
    const res = await fetchImpl(`http://127.0.0.1:${internalPort}/internal/heartbeat`, {
      method: 'POST',
      headers: { 'x-zff-internal': token },
      signal: AbortSignal.timeout(2000),
    })
    return res?.status === 200
  } catch { return false }
}
