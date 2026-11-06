/**
 * ZFF MC / src/brakes.mjs —— **自动续跑的刹车规则**（纯函数，零依赖、可离线自检）
 * ============================================================================
 * 为什么要单独一个模块：M0.5 的结论是"**宿主没有 turn-end 钩子**"（见
 * `docs/ph6/PH6-M0.5-PLUGIN-API.md` §1），所以"这一轮结束了没有"由我们自己按
 * `agent.status` 轮询推断。推断一旦逻辑写错，症状是"IDLE 不动"或"刷屏式续跑"，
 * 两种都很难在真机上看出来 —— 因此把**判定**与**副作用**（注入、写文件、起进程）彻底分开：
 * 这里只有 `decideResume(state) → {action, reason, text}`，自检可以直接把 6 条刹车全跑一遍。
 *
 * 判定顺序（**顺序本身就是语义**，改顺序会改行为）：
 *   1. terminated            → 什么都不注入（管理员下线后永久生效）
 *   2. 不在 ZFF MC Mode       → 不续跑
 *   3. paused                → 不续跑（玩家说停）
 *   4. 游戏掉线（staleMs 太旧）→ 不续跑
 *   5. 有 job 在跑且未超时     → 不续跑（等 ar_run / job_status 回来）
 *   6. Flag_User_Chat 置位    → **立即唤醒**（跳过退避与低耗待机；1–5 仍然有效）
 *   7. 低耗待机中             → 每 lowPowerIntervalMs 只放一次
 *   8. 连续自主 ≥ M 仍无进展   → 降为低耗待机
 *   9. 连续空转 ≥ N           → 改注入"自主找任务"
 *  10. 距上次注入 < minIntervalMs → 退避
 *  11. 其余                   → 正常续跑
 * ============================================================================
 */

/** 续跑消息模板 —— **逐字固定，模型不允许改写**（M4 验收项）。改这里必须同步改 selfcheck。 */
export const RESUME_TEXT = '【自动续跑】继续当前任务，不要停下来输出总结，也不要提前收工。先 chat_turn 读取玩家消息；玩家消息只回复一次，之后继续任务是同一任务单元的推进，不会再次回复玩家，不要重复回复或每轮汇报。若有活就继续做，直到完成、被挡住或玩家明确叫停；禁止空转和重复话术。'

/** 连续空转达上限后改注入这条（仍然要求"有输出"，只是不再等指令）。 */
export const SELF_TASK_TEXT = '【自主找任务】你已经空转很多轮了：别再等指令，自己挑一件**安全、可逆、小**的事做（整理背包 / 看一看周围 / 修工具 / 补一条记忆），做完汇报一句。'

/** 低耗待机：每 60 s 只允许一次 `chat_turn {}`（听一句、答一句，不做长任务）。 */
export const LOW_POWER_TEXT = '【低耗待机】只调用一次 `chat_turn {}`：有玩家消息就回，没有就回一句"待机中"，然后停手。'

/** 被 Flag_User_Chat 叫醒时在续跑文案前加的一句（让人一眼看出是"被打断后立刻回来"）。 */
export const WAKE_PREFIX = '【玩家刚说话】先 `chat_turn` 读完并只回复一次（≤ 2 行），然后直接继续手上的任务；后续自动续跑不会再回应这条玩家消息，不要重复回复或重新总结。\n'

/** 默认刹车参数（插件配置的同名项可以覆盖；`index.js` 的 DEFAULT_CONFIG 引用这里） */
export const DEFAULT_BRAKES = {
  /** 不在这些 preset 里 → 不续跑。与 presets/zff-mc.patch.yml 的 `config.id` 对应 */
  modePresets: ['zff-mc'],
  /** 连续空转 N 轮 → 改注入"自主找任务" */
  idleStreakLimit: 20,
  /** 连续"自主找任务" M 轮仍无进展 → 降为低耗待机 */
  selfStreakLimit: 5,
  /** 低耗待机的最小注入间隔（ms） */
  lowPowerIntervalMs: 60_000,
  /** 两次注入之间的最小间隔（ms）：防抖，避免 tick 抖动导致连投 */
  minIntervalMs: 4_000,
  /** env_read.staleMs 超过这个值就算掉线（ms） */
  staleMaxMs: 15_000,
}

/**
 * @typedef {object} ResumeState
 * @property {number} now                         当前时间（ms）
 * @property {boolean} terminated                 已终止（管理员下线/标记文件存在）
 * @property {string|null} presetId               当前会话的 preset id
 * @property {string[]} modePresets               哪些 preset 算 ZFF MC 模式
 * @property {boolean} paused                     玩家已叫停
 * @property {{known:boolean, stale?:boolean, staleMs?:number|null}} env  游戏存活信号
 * @property {{active:boolean, expiresAt?:number, id?:string}|null} job   正在跑的任务
 * @property {boolean} flagUserChat                Flag_User_Chat 是否置位
 * @property {number} idleStreak                  连续空转计数
 * @property {number} selfStreak                  连续自主找任务计数
 * @property {boolean} lowPower                   是否处于低耗待机
 * @property {number} lastInjectAt                上次注入时间（0 = 从未）
 * @property {number} [minIntervalMs]
 * @property {number} [lowPowerIntervalMs]
 * @property {number} [idleStreakLimit]
 * @property {number} [selfStreakLimit]
 */

/**
 * 判定这一轮到底要不要注入、注入什么。
 * @param {ResumeState} s
 * @returns {{action: 'skip'|'resume'|'wake'|'self_task'|'enter_low_power'|'low_power', reason: string, text: string|null}}
 */
export function decideResume (s) {
  const now = Number(s?.now ?? Date.now())
  const minIntervalMs = num(s?.minIntervalMs, DEFAULT_BRAKES.minIntervalMs)
  const lowPowerIntervalMs = num(s?.lowPowerIntervalMs, DEFAULT_BRAKES.lowPowerIntervalMs)
  const idleLimit = num(s?.idleStreakLimit, DEFAULT_BRAKES.idleStreakLimit)
  const selfLimit = num(s?.selfStreakLimit, DEFAULT_BRAKES.selfStreakLimit)
  const lastInjectAt = num(s?.lastInjectAt, 0)

  // 1. 终止：一切注入的最终否决
  if (s?.terminated === true) return skip('terminated')

  // 2. 模式：不在 ZFF MC Mode 里，一条都不投（普通会话不许冒出 MC 提示词）
  const presetId = typeof s?.presetId === 'string' ? s.presetId : null
  const modePresets = Array.isArray(s?.modePresets) && s.modePresets.length
    ? s.modePresets.map(String)
    : DEFAULT_BRAKES.modePresets
  if (!presetId || !modePresets.includes(presetId)) return skip('not_zff_mode')

  // 2.5 **运行开关**（Ph7.2）：玩家必须亲手点「开始游戏」（▶ → ⏸）才会续跑。
  //     `requireRunSwitch === false` 只给离线自检/特殊部署用。
  if (s?.requireRunSwitch !== false && s?.runState !== 'running') return skip('run_idle')

  // 3. 玩家叫停
  if (s?.paused === true) return skip('paused')

  // 3.5 Ph7：环境不可用 —— 缺 MCP Server / 没有要接管的实例 / 有同名实例。
  //     规格要求"Agent 应该停止输出并报告"，所以这里是**硬刹车**：一条续跑都不投，
  //     由插件注入的那条提示负责把原因告诉玩家（见 index.js refreshEnv）。
  if (s?.envBlocked) return skip('env_blocked:' + String(s.envBlocked.kind ?? 'unknown'))

  // 4. 掉线：staleMs 太旧 = 客户端没在推环境；`known:false` 表示没有信号，**不刹车**（fail-open）
  const env = s?.env ?? { known: false }
  if (env?.known === true) {
    if (env.online === false) return skip('offline')
    const staleMs = env.staleMs
    if (typeof staleMs === 'number' && Number.isFinite(staleMs) && staleMs > num(s?.staleMaxMs, DEFAULT_BRAKES.staleMaxMs)) {
      return skip('offline')
    }
    if (env.stale === true) return skip('offline')
  }

  // 5. 有 job 在跑且未超时：让它先跑完（超时的 job 不再阻塞，否则一次卡死会永久停摆）
  const job = s?.job
  if (job && job.active === true) {
    const expiresAt = num(job.expiresAt, 0)
    if (expiresAt > now) return skip('job_active')
  }

  // 6. 玩家刚说话：立即唤醒。跳过退避与低耗待机（上面 1–5 已经过了）
  if (s?.flagUserChat === true) {
    return { action: 'wake', reason: 'flag_user_chat', text: WAKE_PREFIX + RESUME_TEXT }
  }

  // 7. 低耗待机中：只按低耗节奏放行
  if (s?.lowPower === true) {
    if (now - lastInjectAt < lowPowerIntervalMs) return skip('low_power_wait')
    return { action: 'low_power', reason: 'low_power_tick', text: LOW_POWER_TEXT }
  }

  // 8. 连续自主仍无进展 → 降为低耗待机（先判 8 再判 9：M 用尽就不该继续"自主找任务"）
  if (num(s?.selfStreak, 0) >= selfLimit) {
    return { action: 'enter_low_power', reason: 'self_streak_exhausted', text: LOW_POWER_TEXT }
  }

  // 9. 连续空转达上限 → 改注入"自主找任务"
  if (num(s?.idleStreak, 0) >= idleLimit) {
    return { action: 'self_task', reason: 'idle_streak_limit', text: SELF_TASK_TEXT }
  }

  // 10. 退避
  if (lastInjectAt > 0 && now - lastInjectAt < minIntervalMs) return skip('too_soon')

  // 11. 正常续跑
  return { action: 'resume', reason: 'turn_end', text: RESUME_TEXT }
}

/** 是否属于"会往下计数"的注入动作（自检与状态页用同一口径） */
export function isInjectingAction (action) {
  return action === 'resume' || action === 'wake' || action === 'self_task' ||
    action === 'low_power' || action === 'enter_low_power'
}

/**
 * 一次注入之后结算计数（"有没有进展"由调用方按会话事件数判断）。
 * @param {{idleStreak:number, selfStreak:number, lowPower:boolean}} st 会被就地修改
 * @param {{action:string, progressed:boolean}} r
 * @returns {{idleStreak:number, selfStreak:number, lowPower:boolean}}
 */
export function settleStreaks (st, r) {
  const action = r?.action ?? 'skip'
  const progressed = r?.progressed === true
  if (progressed) {
    st.idleStreak = 0
    st.selfStreak = 0
    st.lowPower = false
    return st
  }
  switch (action) {
    case 'resume':
    case 'wake':
      st.idleStreak = num(st.idleStreak, 0) + 1
      break
    case 'self_task':
      st.selfStreak = num(st.selfStreak, 0) + 1
      break
    case 'enter_low_power':
      st.lowPower = true
      st.idleStreak = 0
      st.selfStreak = 0
      break
    default:
      break
  }
  return st
}

/** 空转计数达到什么程度时状态页应该报警 */
export function streakLevel (st, brakes = DEFAULT_BRAKES) {
  const idleLimit = num(brakes?.idleStreakLimit, DEFAULT_BRAKES.idleStreakLimit)
  const selfLimit = num(brakes?.selfStreakLimit, DEFAULT_BRAKES.selfStreakLimit)
  if (st?.lowPower === true) return 'low_power'
  if (num(st?.selfStreak, 0) >= selfLimit) return 'low_power_pending'
  if (num(st?.idleStreak, 0) >= idleLimit) return 'self_task'
  return 'normal'
}

function skip (reason) {
  return { action: 'skip', reason, text: null }
}

function num (v, dflt) {
  const n = Number(v)
  return Number.isFinite(n) ? n : dflt
}
