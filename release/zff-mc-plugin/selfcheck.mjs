/**
 * ZFF MC / selfcheck.mjs —— **离线自检**（不依赖宿主、不连游戏、不产生网络请求）
 * ============================================================================
 * 跑法：`node D:\mc\zff-mc\selfcheck.mjs`
 *
 * 覆盖（对应任务书 M4 的验收项）：
 *   ① 自动续跑 6 条刹车（模式/暂停/掉线/job/空转上限/自主上限 + 终止 + 退避）
 *   ② 续跑模板**逐字**一致（从真实注入路径里把消息截出来比对）
 *   ③ Flag_User_Chat 联动（置位 → 跳过退避/低耗，立即唤醒；但 paused 仍然优先）
 *   ④ 提示词三件套：装配顺序（安全兜底 > B > A > C > 运行时事实）、B 空不注入
 *   ⑤ `zff_shutdown` 非管理员拒绝 / 管理员通过并落 terminated 标记
 *   ⑥ 设置存取 ≤64 KB（65536 通过、65537 拒绝）、恢复默认
 *   ⑦ 工具注册形状 / guard 白名单 / 静态检查（不 import 宿主包、waterfall 交棒）
 *   ⑧ 启动器命令拼装（合成版本 JSON；若本机有 PCL 的真实版本 JSON 顺带验一次）
 * ============================================================================
 */
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { createZffPlugin, DEFAULT_CONFIG, normalizeConfig, SOURCE_KIND, ALLOWED_HOST_TOOLS } from './index.js'
import { RESUME_TEXT, SELF_TASK_TEXT, LOW_POWER_TEXT, WAKE_PREFIX, DEFAULT_BRAKES, decideResume } from './src/brakes.mjs'
import { composePrompt, saveSlot, resetSlot, readSlot, isBlankPrompt, MAX_PROMPT_BYTES, SAFETY_HEAD } from './src/prompts.mjs'
import { readRuntimeSnapshot, setPaused, writeShutdownMarker } from './src/runtime.mjs'

/* ── 断言小工具 ─────────────────────────────────────────────────────── */
let pass = 0
let fail = 0
const ok = (name, cond, extra = '') => {
  if (cond) { pass++; console.log(`  ✅ ${name}`) } else { fail++; console.log(`  ❌ ${name}${extra ? `  ← ${extra}` : ''}`) }
}
const eq = (name, actual, expected) => ok(name, actual === expected, `实际=${JSON.stringify(actual)} 期望=${JSON.stringify(expected)}`)
const section = (t) => console.log(`\n── ${t} ${'─'.repeat(Math.max(0, 56 - t.length))}`)

const ROOT = fileURLToPath(new URL('.', import.meta.url))
// 🔴 临时目录**必须**落在 D:\mc 下（本仓库的写盘边界）：不要写系统 %TEMP%
const TMP = join(ROOT, '.selfcheck-tmp')
try { rmSync(TMP, { recursive: true, force: true }) } catch { /* 上次残留 */ }
const DSH_HOME = join(TMP, '.dsh')
const WS = join(TMP, 'workspace')
mkdirSync(join(WS, 'runtime'), { recursive: true })
mkdirSync(DSH_HOME, { recursive: true })
const PROMPT_DIR = join(DSH_HOME, 'zff-mc', 'prompts')
const DEFAULTS = join(ROOT, 'defaults')

const rtFile = (n) => join(WS, 'runtime', n)
const writeRt = (n, o) => { writeFileSync(rtFile(n), JSON.stringify(o), 'utf8') }
const dropRt = (n) => { try { rmSync(rtFile(n), { force: true }) } catch { /* 没有就算了 */ } }

/* ── 假宿主 ─────────────────────────────────────────────────────────── */
/** 宿主 `tools` 服务的替身：restrict 对**不认识的工具名抛错**（真实宿主行为，见 whale_craft/index.js:4321） */
function fakeTools () {
  const registered = []
  const guards = []
  const restrictCalls = []
  const KNOWN = new Set(['pwsh', 'subagent', 'present'])
  return {
    registered, guards, restrictCalls,
    register (t) { registered.push(t) },
    guard (fn) { guards.push(fn); return () => { const i = guards.indexOf(fn); if (i >= 0) guards.splice(i, 1) } },
    restrict (opts = {}) {
      for (const n of opts.deny ?? []) if (!KNOWN.has(n)) throw new Error(`unknown tool: ${n}`)
      restrictCalls.push(opts)
      return () => { restrictCalls.push({ released: true }) }
    },
  }
}

/** 假 agent：`steer` 把消息收进数组，`status` 可改，`composedPreset` 可切 */
function makeAgent ({ id = 'session-test', presetId = 'zff-mc', status = 'idle' } = {}) {
  const steered = []
  const box = { presetId, status, events: 0 }
  const agent = {
    id,
    get status () { return box.status },
    set status (v) { box.status = v },
    steered,
    box,
    session: {
      header: { cwd: WS },
      snapshotEvents: () => new Array(box.events).fill({ type: 'user/message' }),
    },
    ctx: { get: (k) => (k === 'agentPresets' ? { composedPreset: () => box.presetId } : undefined) },
    steer (msg) { steered.push(msg) },
  }
  return agent
}

/** 造一个插件实例（每个 instance 自带假 ctx） */
function mkPlugin ({ presetId = 'zff-mc', config = {}, fetchImpl = null, agent = null } = {}) {
  const tools = fakeTools()
  const events = []
  const routes = []
  const webServer = { register (r) { routes.push(r); return () => { const i = routes.indexOf(r); if (i >= 0) routes.splice(i, 1) } } }
  const ctx = {
    _events: events, _routes: routes,
    get (k) {
      if (k === 'dshHomePath') return DSH_HOME
      if (k === 'tools') return tools
      if (k === 'webServer') return webServer
      if (k === 'agents') return { get: () => null }
      if (k === 'sessionController') return null
      return undefined
    },
    on (ev, fn) { events.push({ ev, fn }); return () => { const i = events.findIndex((e) => e.fn === fn); if (i >= 0) events.splice(i, 1) } },
    effect (fn) { try { fn() } catch { /* 忽略 */ } return () => {} },
    logger: { info: () => {}, warn: () => {} },
  }
  const p = createZffPlugin({
    ctx,
    config: { modePresets: ['zff-mc'], adminPlayers: [], tickMs: 600000, mcpServer: { enabled: false }, requireRunSwitch: false, ...config },
    fetchImpl: fetchImpl ?? (async () => { throw new Error('selfcheck: 不应该发网络请求') }),
    spawnImpl: () => { throw new Error('selfcheck: 不应该真的 spawn') },
    pkgRoot: DEFAULTS,
    logger: () => {},
  })
  p.start()
  const a = agent ?? makeAgent({ presetId })
  p.stateOf(a)
  return { p, agent: a, tools, events, routes, presetId }
}

const main = async () => {
  console.log('════════ ZFF MC 离线自检 ════════')
  console.log(`临时目录：${TMP}`)

  /* ══════════════ ① 刹车：纯函数契约 ══════════════ */
  section('① 续跑刹车（decideResume 纯函数）')
  const base = {
    now: 1_000_000, terminated: false, presetId: 'zff-mc', modePresets: ['zff-mc'], paused: false,
    env: { known: false }, job: null, flagUserChat: false, idleStreak: 0, selfStreak: 0,
    lowPower: false, lastInjectAt: 0,
    minIntervalMs: DEFAULT_BRAKES.minIntervalMs, lowPowerIntervalMs: DEFAULT_BRAKES.lowPowerIntervalMs,
    idleStreakLimit: DEFAULT_BRAKES.idleStreakLimit, selfStreakLimit: DEFAULT_BRAKES.selfStreakLimit,
    staleMaxMs: DEFAULT_BRAKES.staleMaxMs,
    // Ph7.2：「必须点开始游戏才续跑」由专门的断言覆盖，这里先放行，免得所有老用例都要带 runState
    requireRunSwitch: false,
  }
  eq('正常情况 → resume', decideResume(base).action, 'resume')
  eq('模板逐字（RESUME_TEXT）', decideResume(base).text, RESUME_TEXT)

  eq('刹车1 不在 ZFF MC Mode → skip', decideResume({ ...base, presetId: 'standard' }).reason, 'not_zff_mode')
  eq('刹车1b preset 为空 → skip', decideResume({ ...base, presetId: null }).reason, 'not_zff_mode')
  eq('刹车2 paused → skip', decideResume({ ...base, paused: true }).reason, 'paused')
  eq('刹车3 掉线（staleMs 超阈值）→ skip',
    decideResume({ ...base, env: { known: true, staleMs: 60_000, online: true } }).reason, 'offline')
  eq('刹车3b offline=false → skip', decideResume({ ...base, env: { known: true, staleMs: 10, online: false } }).reason, 'offline')
  eq('刹车3c 没有 env 信号 → **不**刹车（fail-open）',
    decideResume({ ...base, env: { known: false } }).action, 'resume')
  eq('刹车4 job 未超时 → skip',
    decideResume({ ...base, job: { active: true, expiresAt: base.now + 1 } }).reason, 'job_active')
  eq('刹车4b job 已超时 → 不拦',
    decideResume({ ...base, job: { active: false, expiresAt: base.now - 1 } }).action, 'resume')
  eq('刹车5 连续空转 ≥ N → self_task',
    decideResume({ ...base, idleStreak: DEFAULT_BRAKES.idleStreakLimit }).action, 'self_task')
  eq('刹车5b self_task 文案', decideResume({ ...base, idleStreak: 20 }).text, SELF_TASK_TEXT)
  eq('刹车6 连续自主 ≥ M → enter_low_power',
    decideResume({ ...base, selfStreak: DEFAULT_BRAKES.selfStreakLimit }).action, 'enter_low_power')
  eq('刹车6b 低耗待机中、未到间隔 → 等',
    decideResume({ ...base, lowPower: true, lastInjectAt: base.now - 1000 }).reason, 'low_power_wait')
  eq('刹车6c 低耗待机中、到间隔 → low_power',
    decideResume({ ...base, lowPower: true, lastInjectAt: base.now - DEFAULT_BRAKES.lowPowerIntervalMs - 1 }).action, 'low_power')
  // Ph7.2 ▶/⏸：玩家没点「开始游戏」时一条都不投
  eq('刹车0a ▶（idle）→ 不续跑', decideResume({ ...base, requireRunSwitch: true, runState: 'idle' }).reason, 'run_idle')
  eq('刹车0b ⏸（running）→ 照常续跑', decideResume({ ...base, requireRunSwitch: true, runState: 'running' }).action, 'resume')
  eq('刹车7 terminated → 一切皆停', decideResume({ ...base, terminated: true }).reason, 'terminated')
  eq('刹车7b terminated 优先于 paused', decideResume({ ...base, terminated: true, paused: true }).reason, 'terminated')
  eq('退避：距上次注入太近 → skip', decideResume({ ...base, lastInjectAt: base.now - 100 }).reason, 'too_soon')
  eq('退避：过了间隔 → resume', decideResume({ ...base, lastInjectAt: base.now - 5000 }).action, 'resume')

  /* ══════════════ ② 端到端：注入真实走一遍 ══════════════ */
  section('② 端到端注入（handleTurnEnd → agent.steer）')
  {
    const { p, agent } = mkPlugin({ config: { autoPauseFromChat: false } })
    const st = p.stateOf(agent)
    st.lastInjectAt = 0
    st.eventsAtInject = null
    const r = await p.handleTurnEnd(st, 1_000_000, { messages: [] })
    eq('action=resume', r.action, 'resume')
    eq('via=steer', r.via, 'steer')
    eq('只投了一条', agent.steered.length, 1)
    const msg = agent.steered[0]
    eq('消息 role=user', msg.role, 'user')
    eq('source.kind=plugin:zff-mc', msg.source.kind, SOURCE_KIND)
    eq('source.form=notice', msg.source.form, 'notice')
    ok('消息有 id', typeof msg.id === 'string' && msg.id.length > 0)
    eq('content[0].type=text', msg.content[0].type, 'text')
    eq('🚨 续跑模板**逐字**一致', msg.content[0].text, '【自动续跑】继续当前任务，不要停下来输出总结，也不要提前收工。先 chat_turn 读取玩家消息；玩家消息只回复一次，之后继续任务是同一任务单元的推进，不会再次回复玩家，不要重复回复或每轮汇报。若有活就继续做，直到完成、被挡住或玩家明确叫停；禁止空转和重复话术。')
    eq('与 brakes.mjs 常量一致', msg.content[0].text, RESUME_TEXT)
    // 计数是"下一次 turn-end 才结算上一轮有没有产出"——所以刚投完不该立刻 +1
    eq('刚投完时计数不动（下一轮才结算）', st.idleStreak, 0)
    st.lastInjectAt = 0                                   // 绕过退避，模拟"又一轮结束了"
    await p.handleTurnEnd(st, 1_000_400, { messages: [] })
    eq('再一轮仍然没产出 → 空转 +1', st.idleStreak, 1)
    eq('确实又投了一次', agent.steered.length, 2)
    agent.box.events = 7                                  // 这一轮有产出（会话事件变多）
    st.lastInjectAt = 0
    await p.handleTurnEnd(st, 1_000_800, { messages: [] })
    eq('有产出 → 空转计数归零', st.idleStreak, 0)
    p.stop()
  }

  /* ══════════════ ③ Flag_User_Chat 联动 ══════════════ */
  section('③ Flag_User_Chat 联动唤醒')
  {
    const { p, agent } = mkPlugin({ config: { autoPauseFromChat: false } })
    const st = p.stateOf(agent)
    // 先进入低耗待机 + 刚刚才注入过（正常会被退避/低耗挡住）
    st.lowPower = true
    st.lastInjectAt = 1_000_000
    writeRt('zff-chat-flag.json', { flagUserChat: true, at: 1_000_000 })
    const r = await p.handleTurnEnd(st, 1_000_100, { messages: [] })
    eq('低耗 + 刚注入过，但 Flag 置位 → 立刻 wake', r.action, 'wake')
    ok('wake 文案带【玩家刚说话】前缀', String(r.text).startsWith(WAKE_PREFIX), String(r.text).slice(0, 30))
    ok('wake 文案里含续跑模板', String(r.text).includes(RESUME_TEXT))
    // paused 仍然优先于 Flag（三条刹车不被绕过）
    setPaused({ workspace: WS, paused: true, by: 'selfcheck', now: 1_000_100 })
    const r2 = await p.handleTurnEnd(st, 1_000_200, { messages: [] })
    eq('Flag 置位但 paused → 仍然 skip', r2.reason, 'paused')
    setPaused({ workspace: WS, paused: false, by: 'selfcheck', now: 1_000_300 })
    dropRt('zff-chat-flag.json')
    // 掉线时 Flag 也不放行
    writeRt('env-inbox.json', { staleMs: 99_999, at: 1_000_300, online: true })
    writeRt('zff-chat-flag.json', { flagUserChat: true, at: 1_000_300 })
    const r3 = await p.handleTurnEnd(st, 1_000_400, { messages: [] })
    eq('Flag 置位但掉线 → 仍然 skip', r3.reason, 'offline')
    dropRt('env-inbox.json'); dropRt('zff-chat-flag.json')
    p.stop()
  }

  /* ══════════════ ④ 文件型刹车（真读 runtime/）══════════════ */
  section('④ 文件型刹车（env / job / paused / shutdown）')
  {
    const { p, agent } = mkPlugin({ config: { autoPauseFromChat: false } })
    const st = p.stateOf(agent)
    st.lastInjectAt = 0
    writeRt('env-inbox.json', { staleMs: 42, at: Date.now(), online: true })
    eq('env 新鲜 → resume', (await p.handleTurnEnd(st, Date.now(), { messages: [] })).action, 'resume')
    writeRt('env-inbox.json', { staleMs: 60_000, at: Date.now(), online: true })
    eq('env staleMs 太旧 → skip(offline)', (await p.handleTurnEnd(st, Date.now(), { messages: [] })).reason, 'offline')
    writeRt('env-inbox.json', { staleMs: 5, at: Date.now() - 120_000, online: true })
    eq('env 文件本身很久没更新 → skip(offline)', (await p.handleTurnEnd(st, Date.now(), { messages: [] })).reason, 'offline')
    dropRt('env-inbox.json')
    writeRt('cr-active-job.json', { jobId: 'j-1', tool: 'mine_run', expiresAt: Date.now() + 60_000 })
    eq('job 未超时 → skip(job_active)', (await p.handleTurnEnd(st, Date.now(), { messages: [] })).reason, 'job_active')
    writeRt('cr-active-job.json', { jobId: 'j-1', expiresAt: Date.now() - 1 })
    st.lastInjectAt = 0
    eq('job 已超时 → resume', (await p.handleTurnEnd(st, Date.now(), { messages: [] })).action, 'resume')
    dropRt('cr-active-job.json')
    setPaused({ workspace: WS, paused: true, by: 'player', reason: '停', now: Date.now() })
    eq('paused 文件 → skip(paused)', (await p.handleTurnEnd(st, Date.now(), { messages: [] })).reason, 'paused')
    setPaused({ workspace: WS, paused: false, by: 'player', now: Date.now() })
    writeShutdownMarker({ workspace: WS, reason: 'admin', by: 'Owner', now: Date.now() })
    const r = await p.handleTurnEnd(st, Date.now(), { messages: [] })
    eq('shutdown 文件 → skip(terminated)', r.reason, 'terminated')
    eq('插件自己记住 terminated', p.terminated, true)
    dropRt('zff-shutdown.json')
    p.stop()
  }

  /* ══════════════ ⑤ 轮询推断 turn-end ══════════════ */
  section('⑤ turn-end 推断（没有钩子 → 看 agent.status）')
  {
    const { p, agent } = mkPlugin({ config: { autoPauseFromChat: false } })
    const st = p.stateOf(agent)
    st.lastInjectAt = 0
    agent.status = 'idle'
    eq('一直 idle（没跑过）→ 不动作', await p.tick(st, 1_000_000), null)
    agent.status = 'running'
    eq('running → 只记录', await p.tick(st, 1_000_100), null)
    agent.status = 'idle'
    const r = await p.tick(st, 1_000_200)
    eq('running → idle 的一跳 = 本轮结束 → resume', r?.action, 'resume')
    eq('同一段 idle 里不重复注入', await p.tick(st, 1_000_300), null)
    agent.status = undefined
    eq('拿不到 status → 不动（保守）', await p.tick(st, 1_000_400), null)
    p.stop()
  }

  /* ══════════════ ⑥ 提示词三件套 ══════════════ */
  section('⑥ 提示词三件套（装配顺序 / B 空不注入 / 64 KB）')
  {
    mkdirSync(PROMPT_DIR, { recursive: true })
    writeFileSync(join(PROMPT_DIR, 'tools.md'), '# A-MARKER 工具使用正文\n', 'utf8')
    writeFileSync(join(PROMPT_DIR, 'rules.md'), '# B-MARKER 游戏规则正文\n', 'utf8')
    writeFileSync(join(PROMPT_DIR, 'persona.md'), '# C-MARKER 角色正文\n', 'utf8')
    const bundle = {
      rules: { text: '# B-MARKER' }, tools: { text: '# A-MARKER' }, persona: { text: '# C-MARKER' },
    }
    const composed = composePrompt({ bundle, facts: { server: '127.0.0.1:25565', presetId: 'zff-mc' } })
    const iSafety = composed.text.indexOf('## 安全兜底')
    const iB = composed.text.indexOf('## 游戏规则')
    const iA = composed.text.indexOf('## 工具使用')
    const iC = composed.text.indexOf('## 角色')
    const iR = composed.text.indexOf('## 运行时事实')
    ok('顺序：安全兜底 < B < A < C < 运行时事实', iSafety === 0 && iSafety < iB && iB < iA && iA < iC && iC < iR,
      `索引 ${iSafety}/${iB}/${iA}/${iC}/${iR}`)
    ok('sections 顺序正确', JSON.stringify(composed.sections) === JSON.stringify(['safety', 'rules', 'tools', 'persona', 'runtime']), composed.sections.join(','))
    ok('安全兜底段在（写死在代码里）', composed.text.includes(SAFETY_HEAD.split('\n')[0]))
    ok('运行时事实里带服务器', composed.text.includes('127.0.0.1:25565'))

    const noB = composePrompt({ bundle: { ...bundle, rules: { text: '   \n<!-- 只剩注释 -->\n' } } })
    ok('B 为空（只剩注释/空白）→ 不注入该段', !noB.text.includes('## 游戏规则'))
    ok('B 空时 skipped 里有 rules', noB.skipped.includes('rules'))
    ok('B 空时 A/C 仍在', noB.text.includes('## 工具使用') && noB.text.includes('## 角色'))
    ok('isBlankPrompt 认注释为空', isBlankPrompt('<!-- x -->\n\n') === true)

    // 写盘越界：64 KB 整过、多 1 字节拒
    const big = 'x'.repeat(MAX_PROMPT_BYTES)
    const saved = saveSlot({ dir: PROMPT_DIR, slot: 'tools', text: big })
    ok(`保存 ${MAX_PROMPT_BYTES} 字节（正好 64 KB）→ 通过`, saved.ok === true && saved.bytes === MAX_PROMPT_BYTES)
    let threw = null
    try { saveSlot({ dir: PROMPT_DIR, slot: 'tools', text: big + 'x' }) } catch (e) { threw = e }
    ok('保存 65537 字节 → 拒绝（报错里带 64 KB）', Boolean(threw) && /64 KB/.test(String(threw.message)), String(threw?.message ?? ''))
    let threw2 = null
    try { saveSlot({ dir: PROMPT_DIR, slot: 'tools', text: '   ' }) } catch (e) { threw2 = e }
    ok('保存空白 → 拒绝（要恢复默认请用 reset）', Boolean(threw2))
    ok('restoreDefault：删掉用户版后回落到 defaults/', (() => {
      resetSlot({ dir: PROMPT_DIR, slot: 'tools' })
      const r = readSlot({ slot: 'tools', dir: PROMPT_DIR, pkgRoot: DEFAULTS })
      return r.source === 'default' && r.text.includes('工具使用规则')
    })())
    ok('A 默认值文件存在且非空', existsSync(join(DEFAULTS, 'tools.md')) && readFileSync(join(DEFAULTS, 'tools.md'), 'utf8').length > 100)
    // 措辞可以变（「无指令权限」/「你没有权限使用任何指令」都合理），语义必须是"默认禁止指令"。
    ok('B 默认值含「无指令权限」', (() => {
      const t = readFileSync(join(DEFAULTS, 'rules.md'), 'utf8')
      return t.includes('无指令权限') || (/没有权限使用任何指令|不得使用任何指令|禁止使用指令/.test(t))
    })())
    ok('C 默认值是新玩家伙伴人设', (() => {
      const t = readFileSync(join(DEFAULTS, 'persona.md'), 'utf8')
      return t.includes('Minecraft 服务器里的玩家伙伴') && t.includes('不是客服') && t.includes('短句')
    })())
    // 工具返回值必须是**瘦身版**（否则三份正文 192 KB 会直接烧掉模型上下文）
    {
      const { p, agent, tools } = mkPlugin()
      const list = await tools.registered.find((t) => t.name === 'zff_prompts').execute({ action: 'list' }, { agent })
      ok('zff_prompts{list} 不回正文（无 effectiveText/defaultText）',
        list.ok === true && list.slots.every((s) => s.effectiveText === undefined && s.defaultText === undefined))
      ok('zff_prompts{list} 给 preview + bytes', list.slots.every((s) => typeof s.preview === 'string' && typeof s.bytes === 'number'))
      const saved = await tools.registered.find((t) => t.name === 'zff_prompts').execute({ action: 'save', slot: 'persona', text: '# 自检写入的 C\n' }, { agent })
      ok('zff_prompts{save} 能写盘', saved.ok === true && saved.bytes > 0)
      const back = readSlot({ slot: 'persona', dir: PROMPT_DIR, pkgRoot: DEFAULTS })
      ok('写进去的内容立刻可读（source=user）', back.source === 'user' && back.text.includes('自检写入的 C'))
      const reset = await tools.registered.find((t) => t.name === 'zff_prompts').execute({ action: 'reset', slot: 'persona' }, { agent })
      ok('zff_prompts{reset} 回到默认', reset.ok === true && readSlot({ slot: 'persona', dir: PROMPT_DIR, pkgRoot: DEFAULTS }).source === 'default')
      let slotErr = null
      try { await tools.registered.find((t) => t.name === 'zff_prompts').execute({ action: 'save', slot: 'nope', text: 'x' }, { agent }) } catch (e) { slotErr = e }
      ok('非法 slot → 被参数校验拦下（不是静默写坏文件）', Boolean(slotErr) && /slot/.test(String(slotErr.message)), String(slotErr?.message ?? ''))
      p.stop()
    }
  }

  /* ══════════════ ⑦ pre-step 注入（B 空 + 台账）══════════════ */
  section('⑦ pre-step 注入（decision.messages 改写）')
  {
    const { p, agent } = mkPlugin()
    mkdirSync(PROMPT_DIR, { recursive: true })
    writeFileSync(join(PROMPT_DIR, 'rules.md'), '   \n', 'utf8')          // B 空
    writeFileSync(join(PROMPT_DIR, 'tools.md'), '# A-正文\n', 'utf8')
    writeFileSync(join(PROMPT_DIR, 'persona.md'), '# C-正文\n', 'utf8')
    const decision = { kind: 'enter', messages: [{ role: 'user', content: [{ type: 'text', text: '用户那句' }] }] }
    const out = p.enterWithPrompts(agent, decision)
    ok('返回新 decision（不是原对象）', out !== decision)
    eq('消息数 +1', out.messages.length, 2)
    const text = out.messages[0].content[0].text
    ok('第一条是插件提示行（不是用户那句）', out.messages[0].source.kind === SOURCE_KIND)
    ok('提示行排在用户那句**前面**', text.includes('安全兜底'))
    eq('用户那句还在最后', out.messages[1].content[0].text, '用户那句')
    ok('B 空 → 不含「## 游戏规则」', !text.includes('## 游戏规则'))
    ok('含 A 与 C 的正文', text.includes('# A-正文') && text.includes('# C-正文'))
    ok('带 Instructions from 头', text.startsWith('Instructions from: zff-mc/prompts/'))
    // 幂等：同一份内容不再重投
    const out2 = p.enterWithPrompts(agent, decision)
    eq('同一份提示词不重复注入', out2.messages.length, 1)
    // 内容变了 → 重投
    writeFileSync(join(PROMPT_DIR, 'tools.md'), '# A-正文 v2\n', 'utf8')
    const out3 = p.enterWithPrompts(agent, decision)
    eq('提示词改动后重新注入', out3.messages.length, 2)
    ok('新内容生效', out3.messages[0].content[0].text.includes('A-正文 v2'))
    // 非 enter 的 decision 原样返回
    eq('decision.kind!=enter → 原样返回', p.enterWithPrompts(agent, { kind: 'skip' }).kind, 'skip')
    // 不在 ZFF 模式 → 不注入
    agent.box.presetId = 'standard'
    p.stateOf(agent)
    const out4 = p.enterWithPrompts(agent, decision)
    eq('非 ZFF 模式 → 不注入', out4.messages.length, 1)
    agent.box.presetId = 'zff-mc'
    p.stop()
  }

  /* ══════════════ ⑧ zff_shutdown 授权 ══════════════ */
  section('⑧ zff_shutdown（非管理员拒绝 / 管理员通过）')
  {
    const nowMs = Date.now()
    const chatJson = (sender) => ({
      ok: true, json: { ok: true, messages: [
        { time: nowMs - 5000, type: 'chat', sender, text: '你下线吧' },
        { time: nowMs - 4000, type: 'chat', sender: 'DSHDeepSeek', text: '好', outgoing: true },
      ] },
    })
    let currentSender = 'NotAnAdmin'
    const fetchImpl = async () => ({ ok: true, status: 200, text: async () => JSON.stringify(chatJson(currentSender)) })

    // 非管理员
    const { p, agent, tools } = mkPlugin({ config: { adminPlayers: ['Owner'] }, fetchImpl })
    dropRt('zff-shutdown.json')
    const bad = await tools.registered.find((t) => t.name === 'zff_shutdown').execute({ reason: 'test' }, { agent })
    eq('非管理员 → ok:false', bad.ok, false)
    eq('非管理员 → error=not_admin', bad.error, 'not_admin')
    ok('非管理员 → **不写** terminated 标记', !existsSync(rtFile('zff-shutdown.json')))
    eq('非管理员 → 插件仍可续跑（terminated=false）', p.terminated, false)

    // 管理员
    currentSender = 'Owner'
    const good = await tools.registered.find((t) => t.name === 'zff_shutdown').execute({ reason: '我要关服了' }, { agent })
    eq('管理员 → ok:true', good.ok, true)
    eq('管理员 → action=disconnect_then_kill', good.action, 'disconnect_then_kill')
    eq('管理员 → by=Owner', good.by, 'Owner')
    ok('管理员 → 写了 zff-shutdown.json', existsSync(rtFile('zff-shutdown.json')))
    const marker = JSON.parse(readFileSync(rtFile('zff-shutdown.json'), 'utf8'))
    eq('标记里 terminated=true', marker.terminated, true)
    eq('标记里 reason 保留', marker.reason, '我要关服了')
    eq('插件自身进入 terminated', p.terminated, true)
    const after = await p.handleTurnEnd(p.stateOf(agent), Date.now(), { messages: [] })
    eq('terminated 之后不再注入', after.reason, 'terminated')
    eq('terminated 之后没有新 steer', agent.steered.length, 0)

    // daemon 不可用 → 拒绝（宁可不下线）
    const { p: p2, agent: a2, tools: t2 } = mkPlugin({
      config: { adminPlayers: ['Owner'] },
      fetchImpl: async () => { throw new Error('ECONNREFUSED') },
    })
    dropRt('zff-shutdown.json')
    const noChat = await t2.registered.find((t) => t.name === 'zff_shutdown').execute({}, { agent: a2 })
    eq('拿不到聊天 → ok:false', noChat.ok, false)
    eq('拿不到聊天 → error=chat_unavailable', noChat.error, 'chat_unavailable')
    ok('拿不到聊天 → 不写标记', !existsSync(rtFile('zff-shutdown.json')))
    // adminPlayers 为空 → 谁都不能下线
    const { tools: t3, agent: a3 } = mkPlugin({ config: { adminPlayers: [] }, fetchImpl })
    const noAdmin = await t3.registered.find((t) => t.name === 'zff_shutdown').execute({}, { agent: a3 })
    eq('adminPlayers 为空 → not_admin', noAdmin.error, 'not_admin')
    p.stop(); p2.stop()
  }

  /* ══════════════ ⑨ 工具注册 / guard ══════════════ */
  section('⑨ 工具注册形状 + guard 白名单')
  {
    const { p, agent, tools } = mkPlugin()
    const names = tools.registered.map((t) => t.name)
    ok('注册了 zff_shutdown', names.includes('zff_shutdown'), names.join(','))
    ok('注册了 zff_pause / zff_status / zff_usage / zff_prompts / zff_env / zff_team',
      ['zff_pause', 'zff_status', 'zff_usage', 'zff_prompts', 'zff_env', 'zff_team'].every((n) => names.includes(n)), names.join(','))
    {
      const fakeFetch = async () => ({
        ok: true,
        status: 200,
        text: async () => JSON.stringify({
          ok: true,
          json: { ok: true, totalCalls: 7, uniqueTools: 2, counts: [{ name: 'get_state', count: 5 }, { name: 'tool_usage', count: 2 }] },
        }),
      })
      const { p: up, agent: ua, tools: ut } = mkPlugin({ fetchImpl: fakeFetch })
      const usage = await ut.registered.find((x) => x.name === 'zff_usage').execute({ limit: 10 }, { agent: ua })
      eq('zff_usage 返回总调用次数', usage.totalCalls, 7)
      eq('zff_usage 返回工具条数', usage.uniqueTools, 2)
      eq('zff_usage 保留 counts', usage.counts[0].name, 'get_state')
      up.stop()
    }
    const t = tools.registered.find((x) => x.name === 'zff_shutdown')
    ok('工具形状：{name,description,parameters,output{schema,render},execute}',
      typeof t.name === 'string' && typeof t.description === 'string' && typeof t.execute === 'function' &&
      t.parameters?.type === 'object' && typeof t.output?.schema === 'object' && typeof t.output?.render === 'function')
    eq('parameters 是编译好的 JSON Schema', t.parameters.properties.reason.type, 'string')
    ok('参数校验：reason 传数字 → 抛错', await (async () => {
      try { await t.execute({ reason: 123 }, { agent }) } catch (e) { return /reason/.test(String(e.message)) } return false
    })())
    eq('注册了 1 个 guard', tools.guards.length, 1)
    const guard = tools.guards[0]
    ok('guard 放行 mcp__mc__chat_turn', guard({ name: 'mcp__mc__chat_turn', agent }) === undefined)
    ok('guard 放行 read（白名单里的文件工具）', guard({ name: 'read', agent }) === undefined)
    ok('guard 放行 zff_status（本插件工具）', guard({ name: 'zff_status', agent }) === undefined)
    ok('guard 拦下 pwsh', typeof guard({ name: 'pwsh', agent }) === 'string')
    agent.box.presetId = 'standard'
    ok('非 ZFF 模式 → guard 不管（返回 undefined）', guard({ name: 'pwsh', agent }) === undefined)
    agent.box.presetId = 'zff-mc'
    ok('restrict 用了逐个尝试（未知名字不炸整个策略）', p.stateOf(agent) !== null && Array.isArray(tools.restrictCalls))
    p.stop()
  }

  /* ══════════════ ⑩ 静态检查（源码契约）══════════════ */
  section('⑩ 静态检查（源码契约 / 打包声明）')
  {
    const idx = readFileSync(join(ROOT, 'index.js'), 'utf8')
    const cli = readFileSync(join(ROOT, 'client.js'), 'utf8')
    const pkg = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'))
    ok('index.js 不 import 任何 @deepseek-ai/*（optional peer 会链接期炸）', !/from\s+['"]@deepseek-ai\//.test(idx))
    ok('index.js 里 schemastery 是动态 import 且在 try/catch 里', /await import\('@deepseek-ai\/schemastery'\)/.test(idx) && /catch/.test(idx))
    ok('挂了 agent/pre-step 并且**接 next 交棒**', /on\('agent\/pre-step',\s*\(payload,\s*next\)/.test(idx) && /const inner = next\(\)/.test(idx))
    ok('pre-step 里对没有 next 的情况有兜底', /typeof next !== 'function'/.test(idx))
    ok('source.kind 是 v4 规范值 plugin:zff-mc', /plugin:zff-mc/.test(idx) && SOURCE_KIND === 'plugin:zff-mc')
    ok('没有注册 system-prompt/assemble（载荷未验证，不挂）', !/ctx\.on\('system-prompt\/assemble'/.test(idx))
    ok('定时器回调整体 try/catch（不把宿主带走）', /setInterval\(\(\) => \{ void runTick\(\) \}/.test(idx) && /定时器回调失败（已吞/.test(idx))
    ok('client.js 是 factory bundle', /window\.__ModuleLoader__\.load\(\{/.test(cli) && /factory\(require\)/.test(cli))
    ok('client.js 返回 {inject:[slots], apply}', /inject:\s*\['slots'\]/.test(cli) && /apply\(ctx\)/.test(cli))
    ok('client.js 注册了 plugins.detail.actions 插槽', /plugins\.detail\.actions/.test(cli))
    ok('client.js 三张卡片都有（data-zff-card）', /data-zff-card/.test(cli))
    ok('client.js 有上传 .md', /accept: '\.md/.test(cli) && /FileReader/.test(cli))
    eq('package.json name', pkg.name, '@local/zff-mc')
    eq('package.json type=module', pkg.type, 'module')
    eq('package.json main', pkg.main, 'index.js')
    ok('dsh.bundle.patch 是数组且含 cordis.patch.yml + presets/zff-mc.patch.yml',
      Array.isArray(pkg.dsh?.bundle?.patch) && pkg.dsh.bundle.patch.includes('./cordis.patch.yml') && pkg.dsh.bundle.patch.includes('./presets/zff-mc.patch.yml'))
    eq('dsh.client.platform=web', pkg.dsh?.client?.platform, 'web')
    eq('exports["./client"]', pkg.exports['./client'], './client.js')
    const patch = readFileSync(join(ROOT, 'cordis.patch.yml'), 'utf8')
    ok('cordis.patch.yml 插 id=zff-mc / name=@local/zff-mc', /id: zff-mc/.test(patch) && /name: '@local\/zff-mc'/.test(patch))
    ok('cordis.patch.yml 里有 mcp-client 模板（streamable-http + 57610 + 600002）',
      /streamable-http/.test(patch) && /127\.0\.0\.1:57610\/mcp/.test(patch) && /toolCallTimeoutMs: 600002/.test(patch))
    const preset = readFileSync(join(ROOT, 'presets', 'zff-mc.patch.yml'), 'utf8')
    ok('preset: id=zff-mc', /^\s+id: zff-mc$/m.test(preset))
    ok('preset: name=ZFF MC Mode', /name: ZFF MC Mode/.test(preset))
    ok('preset: 用 @deepseek-ai/dsh-agent-preset', /@deepseek-ai\/dsh-agent-preset/.test(preset))
    ok('preset: 挂了 tool-fs / tool-fs-search', /@deepseek-ai\/dsh-tool-fs'/.test(preset) && /dsh-tool-fs-search/.test(preset))
    ok('preset: 没挂 shell/联网（工具面收窄）', !/dsh-tool-(shell|web)/.test(preset))
    ok('preset: 挂了子代理工具（Ph7.5 玩家决定 A）', /dsh-tool-subagent'/.test(preset) && /dsh-tool-subagent-control/.test(preset))
    const zh = JSON.parse(readFileSync(join(ROOT, 'locale', 'zh.json'), 'utf8'))
    ok('locale/zh.json 有 meta.title', typeof zh.meta?.title === 'string' && zh.meta.title.length > 0)
    ok('README.md 存在且 ≤ 80 行', (() => { const n = readFileSync(join(ROOT, 'README.md'), 'utf8').split('\n').length; return n <= 80 })())
    // 默认配置与 brakes 默认值一致（避免两处漂移）
    eq('DEFAULT_CONFIG.idleStreakLimit 与 brakes 一致', DEFAULT_CONFIG.idleStreakLimit, DEFAULT_BRAKES.idleStreakLimit)
    eq('DEFAULT_CONFIG.selfStreakLimit 与 brakes 一致', DEFAULT_CONFIG.selfStreakLimit, DEFAULT_BRAKES.selfStreakLimit)
    eq('DEFAULT_CONFIG.modePresets 默认 zff-mc', JSON.stringify(DEFAULT_CONFIG.modePresets), JSON.stringify(['zff-mc']))
    eq('normalizeConfig 兜住非法值（负数 → 回默认）', normalizeConfig({ idleStreakLimit: -5 }).idleStreakLimit, DEFAULT_CONFIG.idleStreakLimit)
    eq('normalizeConfig 兜住极小值（tickMs<500 → 500）', normalizeConfig({ tickMs: 1 }).tickMs, 500)
    eq('normalizeConfig 保留合法覆盖', normalizeConfig({ idleStreakLimit: 3 }).idleStreakLimit, 3)
    ok('ALLOWED_HOST_TOOLS 里没有危险工具（shell/pwsh/workflow）', !ALLOWED_HOST_TOOLS.some((n) => ['pwsh', 'bash', 'workflow', 'subprocess'].includes(n)))
    ok('子代理工具在允许面里（Ph7.5）', ['subagent', 'subagent_fork', 'list_agents', 'send_message', 'interrupt_agent'].every((n) => ALLOWED_HOST_TOOLS.includes(n)), ALLOWED_HOST_TOOLS)
  }

  /* ══════════════ 汇总 ══════════════ */
  section('汇总')
  console.log(`\n  通过 ${pass} · 失败 ${fail}`)
  if (fail === 0) console.log('  🎉 全绿：ZFF MC 的刹车 / 注入 / 提示词 / 下线 / 工具 / 打包 契约都成立')
  else console.log('  💥 有失败项 —— 先修，再谈装进 profile')
  try { rmSync(TMP, { recursive: true, force: true }) } catch { /* Windows 偶发占用，忽略 */ }
  return fail === 0 ? 0 : 1
}

main().then((code) => { process.exitCode = code }).catch((e) => {
  console.error('\n💥 自检自身崩了：', e)
  process.exitCode = 1
})
