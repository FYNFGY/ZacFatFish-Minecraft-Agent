/**
 * EmergencyEventList + InfoTxt — 2026-11-02 rewrite.
 *
 * Pull semantics chosen by the player:
 *   · every MCP tool response carries `infoTxt` and clears it;
 *   · `query_event` is a no-op tool that only pulls;
 *   · no event / no job completion -> `infoTxt: []`.
 *
 * The list is intentionally stateful in memory: the mod pushes raw events, the MCP
 * job poller appends terminal jobs, and buildAndClear() hands the current snapshot
 * to the next tool response.
 */

const now = () => Date.now();

export class EmergencyState {
  constructor ({ log = () => {} } = {}) {
    this.log = log;
    this.startedAt = now();
    /** level flags: recomputed from env each poll */
    this.hungry = false;
    this.fullInventory = false;
    this.monsters = [];
    /** armed prevents level flags from repeating while the condition persists */
    this.hungryArmed = true;
    this.fullArmed = true;
    /** event latches */
    this.userChat = null;
    this.dropHealth = null;
    this.ore = null;
    this.watchdog = null;
    this.fall = null;
    this.toolLow = null;
    this.combat = null;
    this.combatLines = [];
    this.hard = [];
    /** terminal job messages, appended after emergencies */
    this.jobMessages = [];
    /** job poller state */
    this.knownJobs = new Map();
    this.jobsSeen = false;
    this.stuckSeen = new Map();
  }

  /** Raw mod event: chat/damage/fall/hunger/inventory_full/ore/watchdog. */
  ingest (raw) {
    const type = String(raw?.type || raw?.kind || '').trim();
    if (!type) return;
    const detail = String(raw?.detail ?? raw?.message ?? type).slice(0, 300);
    const data = raw?.data && typeof raw.data === 'object' ? raw.data : {};
    if (type === 'chat') {
      this.userChat = { count: (this.userChat?.count ?? 0) + 1, at: now(), detail };
    } else if (type === 'damage') {
      this.dropHealth = {
        amount: Number(data.amount ?? 0),
        source: String(data.source ?? 'unknown'),
        health: Number(data.health ?? NaN),
        at: now(),
      };
    } else if (type === 'fall') {
      this.fall = { detail, at: now(), data };
    } else if (type === 'ore') {
      const list = Array.isArray(data.ores) ? data.ores : Array.isArray(raw?.ores) ? raw.ores : [];
      this.ore = { list, at: now() };
    } else if (type === 'watchdog') {
      this.watchdog = {
        jobId: String(data.jobId ?? data.taskId ?? '?'),
        tool: String(data.tool ?? '?'),
        mode: String(data.mode ?? '?'),
        windowMs: Number(data.windowMs ?? 0),
        at: now(),
      };
    } else if (type === 'hard') {
      this.hard.push({ detail, at: now() });
    } else if (type === 'tool_low') {
      this.toolLow = {
        tool: String(data.tool ?? '?'),
        remaining: Number(data.remaining ?? 0),
        max: Number(data.max ?? 0),
        at: now(),
      };
    } else if (type === 'combat') {
      this.combat = { detail, at: now() };
      this.combatLines.push({ detail, at: now() });
      if (this.combatLines.length > 8) this.combatLines.shift();
    }
    // hunger/inventory_full are level flags; env_read poll owns them.
  }

  setUserChat (count = 1) {
    this.userChat = { count: (this.userChat?.count ?? 0) + count, at: now(), detail: 'chat' };
  }

  /** env_read snapshot from the mod. */
  updateEnv (env) {
    if (!env || typeof env !== 'object' || env.ok === false) return;
    const food = Number(env.food);
    if (Number.isFinite(food)) {
      if (food < 6) {
        if (this.hungryArmed) {
          this.hungry = true;
          this.hungryArmed = false;
        }
      } else {
        this.hungryArmed = true;
      }
    }
    const inv = env.inventory;
    if (inv && typeof inv === 'object') {
      const used = Number(inv.used);
      const total = Number(inv.total ?? 36);
      if (used >= total) {
        if (this.fullArmed) {
          this.fullInventory = true;
          this.fullArmed = false;
        }
      } else {
        this.fullArmed = true;
      }
    }
    if (Array.isArray(env.monsters)) {
      this.monsters = env.monsters
        .filter((m) => m && m.type)
        .map((m) => ({ type: String(m.type), x: Number(m.x ?? 0), y: Number(m.y ?? 0), z: Number(m.z ?? 0) }))
        .slice(0, 16);
    }
    const alerts = Array.isArray(env.alerts) ? env.alerts : [];
    for (const a of alerts) {
      if (!a || a.interrupt !== true) continue;
      const kind = String(a.kind ?? 'hard');
      if (!this.hard.some((h) => h.kind === kind)) {
        this.hard.push({ kind, detail: String(a.message ?? kind), at: now() });
      }
    }
  }

  /** jobs_list snapshot: detect terminal transitions and stuck metric growth. */
  updateJobs (jobs) {
    if (!Array.isArray(jobs)) return;
    const live = new Set();
    for (const j of jobs) {
      if (!j) continue;
      // mod's Job.toJson uses `jobId`; older fixtures used `id`.
      const id = String(j.jobId || j.id || '');
      if (!id) continue;
      live.add(id);
      const state = String(j.state || '');
      const tool = String(j.tool || '?');
      const prev = this.knownJobs.get(id);
      const terminal = ['done', 'failed', 'cancelled'].includes(state);
      if (!this.jobsSeen) {
        // First snapshot after startup: report only jobs created after this server
        // started; older terminal jobs are history, not news.
        const createdAt = Number(j.createdAt || 0);
        if (!createdAt || createdAt < this.startedAt) {
          this.knownJobs.set(id, { state, tool });
          continue;
        }
        if (terminal) {
          const err = j.error ? ` ${String(j.error).slice(0, 80)}` : '';
          this.jobMessages.push(`JOB: ${tool} ${id} ${state}${err}`);
        }
        this.knownJobs.set(id, { state, tool });
        continue;
      }
      if (terminal && (!prev || prev.state !== state)) {
        const err = j.error ? ` ${String(j.error).slice(0, 80)}` : '';
        this.jobMessages.push(`JOB: ${tool} ${id} ${state}${err}`);
      }
      this.knownJobs.set(id, { state, tool });
      const stuck = Number(j.metrics?.stuck ?? 0);
      const last = this.stuckSeen.get(id) ?? 0;
      if (stuck > last) {
        this.stuckSeen.set(id, stuck);
        this.watchdog = {
          jobId: id,
          tool,
          mode: String(j.metrics?.stuckMode ?? '?'),
          windowMs: Number(j.metrics?.stuckWindowMs ?? 0),
          at: now(),
        };
      }
    }
    // Forget very old jobs so the map does not grow forever.
    if (this.knownJobs.size > 300) {
      for (const id of [...this.knownJobs.keys()].slice(0, this.knownJobs.size - 300)) this.knownJobs.delete(id);
    }
    this.jobsSeen = true;
  }

  /** Build the short lines and clear all flags (levels included, per player's choice). */
  buildAndClear () {
    const lines = [];
    if (this.fall) lines.push(`FALL: ${this.fall.detail}`);
    if (this.toolLow) {
      const max = this.toolLow.max > 0 ? `/${this.toolLow.max}` : '';
      lines.push(`TOOL: ${this.toolLow.tool} 耐久耗尽 (${this.toolLow.remaining}${max})`);
    }
    for (const c of this.combatLines) lines.push(`COMBAT: ${c.detail}`);
    if (this.userChat) lines.push(`CHAT: ${this.userChat.count} unread`);
    if (this.dropHealth) {
      const h = Number.isFinite(this.dropHealth.health) ? ` ${this.dropHealth.health}/20` : '';
      const amt = this.dropHealth.amount > 0 ? `-${this.dropHealth.amount}` : 'damage';
      lines.push(`HP ${amt} (${this.dropHealth.source})${h}`);
    }
    if (this.monsters.length) {
      const list = this.monsters.map((m) => `${m.type}(${m.x},${m.y},${m.z})`).join(', ');
      lines.push(`MONSTERS: ${list}`);
    }
    if (this.watchdog) {
      const w = this.watchdog.windowMs ? ` ${Math.round(this.watchdog.windowMs / 100) / 10}s` : '';
      lines.push(`WATCHDOG: ${this.watchdog.tool} ${this.watchdog.jobId} no move${w}`);
    }
    for (const h of this.hard) lines.push(`HARD: ${h.detail}`);
    if (this.hungry) lines.push(`HUNGRY: food<6`);
    if (this.fullInventory) lines.push(`FULL: inventory 36/36`);
    if (this.ore && this.ore.list.length) {
      const list = this.ore.list.map((o) => `${o.id}(${o.x ?? 0},${o.y ?? 0},${o.z ?? 0})`).join(', ');
      lines.push(`ORE: ${list}`);
    }
    for (const m of this.jobMessages) lines.push(m);
    // clear everything handed to the caller
    this.hungry = false;
    this.fullInventory = false;
    this.monsters = [];
    this.userChat = null;
    this.dropHealth = null;
    this.ore = null;
    this.watchdog = null;
    this.fall = null;
    this.toolLow = null;
    this.combat = null;
    this.combatLines = [];
    this.hard = [];
    this.jobMessages = [];
    return lines;
  }

  stats () {
    return {
      hungry: this.hungry,
      fullInventory: this.fullInventory,
      monsters: this.monsters.length,
      userChat: this.userChat,
      dropHealth: this.dropHealth,
      ore: this.ore,
      watchdog: this.watchdog,
      fall: this.fall,
      toolLow: this.toolLow,
      hard: this.hard,
      jobs: this.jobMessages.length,
    };
  }
}
