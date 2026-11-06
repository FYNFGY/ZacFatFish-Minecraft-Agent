/**
 * Ph7 M1-3 —— 中断引擎（Node 侧，MCP server 内）。
 * ============================================================================
 * 设计定稿见 docs/Ph7/agent-prompt7.md §6：
 *   · 五类中断：fall > damage > chat > hunger > inventory_full
 *   · 每种有冷却（从触发时刻算，清除不影响冷却）
 *   · neverInterrupt[type] 里的工具**不被打断**；标志照常被下一次工具调用清掉
 *   · 任何 MCP 工具调用开始时：清空所有挂起标志，该次调用照常执行，
 *     被清掉的信息通过 `cleared` 附在这次调用的结果里，AI 不丢信息
 *   · 长任务（waitJob / wait_events）每 250 ms 看一次挂起标志，命中就早退并取消 job
 * ============================================================================
 */
import fs from 'node:fs';
import path from 'node:path';

export const INTERRUPT_TYPES = ['fall', 'damage', 'chat', 'hunger', 'inventory_full'];

export const DEFAULT_CONFIG = {
  cooldowns: { chat: 10, damage: 5, hunger: 30, fall: 30, inventory_full: 30 },
  neverInterrupt: {
    chat: ['chat_turn'],
    // 2026-11-02 真机：溺水时 walk/up 是自救动作，绝不能因为"受伤"把自救也取消掉。
    damage: ['chat_turn', 'walk_to', 'walk', 'walk_dir', 'mlg_water', 'stop_all', 'tower', 'dig_shaft', 'dig_tunnel', 'branch_mine'],
    hunger: ['chat_turn', 'walk', 'walk_dir'],
    fall: ['chat_turn', 'mlg_water', 'walk', 'walk_dir'],
    inventory_full: ['chat_turn'],
  },
  priority: ['fall', 'damage', 'chat', 'hunger', 'inventory_full'],
};

const CONFIG_FILE = 'zff-interrupts.json';

function clampNumber(n, fallback) {
  const v = Number(n);
  if (!Number.isFinite(v) || v < 0) return fallback;
  return Math.min(3600, Math.round(v));
}

export function normalizeConfig(raw) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const out = { cooldowns: { ...DEFAULT_CONFIG.cooldowns }, neverInterrupt: {}, priority: [] };
  for (const type of INTERRUPT_TYPES) {
    out.cooldowns[type] = clampNumber(src.cooldowns?.[type], DEFAULT_CONFIG.cooldowns[type]);
    const list = src.neverInterrupt?.[type];
    out.neverInterrupt[type] = Array.isArray(list)
      ? [...new Set(list.filter((x) => typeof x === 'string' && x.trim()).map((x) => x.trim()))]
      : [...DEFAULT_CONFIG.neverInterrupt[type]];
  }
  const prio = Array.isArray(src.priority) ? src.priority.filter((x) => INTERRUPT_TYPES.includes(x)) : [];
  out.priority = [...new Set([...prio, ...DEFAULT_CONFIG.priority])];
  return out;
}

export class InterruptManager {
  /**
   * @param {{workspace:string, log?:(m:string)=>void, onFall?:(evt:object)=>void|Promise<void>}} opts
   */
  constructor({ workspace, log, onFall } = {}) {
    this.workspace = path.resolve(workspace || process.cwd());
    this.file = path.join(this.workspace, 'runtime', CONFIG_FILE);
    this.log = log || ((m) => console.error(m));
    this.onFall = onFall || (() => {});
    this.config = normalizeConfig(null);
    this.configMtime = -1;
    this.pending = new Map(); // type -> event (只保留每类最新一条)
    this.lastFired = new Map(); // type -> ms
    this.firedCount = 0;
    this.clearedCount = 0;
    this.lastEvent = null;
    this.lastConfigError = null;
    this.ensureConfigFile();
  }

  ensureConfigFile() {
    try {
      if (fs.existsSync(this.file)) return;
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.writeFileSync(this.file, JSON.stringify(DEFAULT_CONFIG, null, 2) + '\n', 'utf8');
    } catch (e) {
      this.lastConfigError = String(e?.message ?? e);
    }
  }

  /** mtime 热加载；文件坏了就保留上一份，不影响中断本身。 */
  reloadConfig(force = false) {
    try {
      const st = fs.statSync(this.file);
      if (!force && st.mtimeMs === this.configMtime) return this.config;
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      this.config = normalizeConfig(raw);
      this.configMtime = st.mtimeMs;
      this.lastConfigError = null;
      this.log(`[interrupts] config loaded cooldowns=${JSON.stringify(this.config.cooldowns)}`);
    } catch (e) {
      if (e?.code !== 'ENOENT') this.lastConfigError = String(e?.message ?? e);
    }
    return this.config;
  }

  /** 冷却是否还压着（从触发时刻算）。 */
  inCooldown(type, now = Date.now()) {
    this.reloadConfig(false);
    const last = this.lastFired.get(type) || 0;
    return now - last < (this.config.cooldowns[type] ?? 0) * 1000;
  }

  /**
   * 收一条事件。返 {accepted, reason?, type?, event?}。
   * 同一类在冷却内直接丢弃（§6.2）。
   */
  ingest(raw) {
    this.reloadConfig(false);
    const type = String(raw?.type || raw?.kind || '').trim();
    if (!INTERRUPT_TYPES.includes(type)) return { accepted: false, reason: 'unknown_type', type };
    // 2026-11-02 真机：普通地形里走下一个 3 格台阶就会 fallDistance≈2.7，按 2.5 判定会把
    // 每一次正常下坡都当成"坠落急救"并 stop_all。阈值提高到 5 格（约 2.5 颗心的伤害）才算危险。
    if (type === 'fall' && Number.isFinite(Number(raw?.data?.fallDistance)) && Number(raw.data.fallDistance) < 5.0) {
      return { accepted: false, reason: 'minor_fall', type, fallDistance: Number(raw.data.fallDistance) };
    }
    const now = Date.now();
    if (this.inCooldown(type, now)) return { accepted: false, reason: 'cooldown', type, retryInMs: (this.config.cooldowns[type] * 1000) - (now - (this.lastFired.get(type) || 0)) };
    const detail = raw?.detail != null ? String(raw.detail) : (raw?.message != null ? String(raw.message) : type);
    const event = {
      type,
      detail: detail.slice(0, 500),
      at: now,
      data: raw?.data && typeof raw.data === 'object' ? raw.data : undefined,
    };
    this.pending.set(type, event);
    this.lastFired.set(type, now);
    this.firedCount++;
    this.lastEvent = event;
    this.log(`[interrupts] ${type}: ${event.detail}`);
    if (type === 'fall') {
      try { Promise.resolve(this.onFall(event)).catch((e) => this.log(`[interrupts] onFall failed: ${e?.message ?? e}`)); } catch (e) { this.log(`[interrupts] onFall failed: ${e?.message ?? e}`); }
    }
    return { accepted: true, type, event };
  }

  /** 按配置优先级排序挂起事件。 */
  pendingList() {
    const prio = this.config.priority;
    return [...this.pending.values()].sort((a, b) => prio.indexOf(a.type) - prio.indexOf(b.type));
  }

  /** 该工具是否被配置为"不打断"。 */
  isNeverInterrupt(type, tool) {
    const list = this.config.neverInterrupt[type] || [];
    return list.includes(tool) || list.includes('*');
  }

  /**
   * 工具调用开始：**清空所有挂起标志**，返回被清掉的事件 + 本次要报告的中断。
   * 规则：neverInterrupt 的只进 cleared，不算 interrupt；interrupt 取最高优先级那条。
   */
  beginCall(tool, now = Date.now()) {
    this.reloadConfig(false);
    const cleared = this.pendingList().map((e) => ({ type: e.type, detail: e.detail, at: e.at }));
    this.pending.clear();
    if (cleared.length) this.clearedCount += cleared.length;
    let interrupt = null;
    for (const e of this.pendingList()) { /* pending 已清空，保留结构 */ }
    // cleared 已按优先级排序；取第一条不豁免的作为 interrupt。
    for (const e of cleared) {
      if (!this.isNeverInterrupt(e.type, tool)) {
        interrupt = { ...e, sinceMs: Math.max(0, now - e.at) };
        break;
      }
    }
    return { cleared, interrupt, tool, at: now };
  }

  /** 长任务轮询用：只查看、不清除。 */
  peek(tool) {
    this.reloadConfig(false);
    for (const e of this.pendingList()) {
      if (this.isNeverInterrupt(e.type, tool)) continue;
      return { type: e.type, detail: e.detail, at: e.at, sinceMs: Math.max(0, Date.now() - e.at), data: e.data };
    }
    return null;
  }

  /** 长任务命中后真正取走它（其它类型保留，等下一次工具调用清）。 */
  take(tool) {
    const hit = this.peek(tool);
    if (!hit) return null;
    this.pending.delete(hit.type);
    this.clearedCount++;
    return hit;
  }

  stats() {
    return {
      ok: true,
      pending: this.pendingList(),
      cooldowns: this.config.cooldowns,
      neverInterrupt: this.config.neverInterrupt,
      priority: this.config.priority,
      lastFired: Object.fromEntries(this.lastFired),
      fired: this.firedCount,
      cleared: this.clearedCount,
      lastEvent: this.lastEvent,
      file: this.file,
      configError: this.lastConfigError,
    };
  }
}
