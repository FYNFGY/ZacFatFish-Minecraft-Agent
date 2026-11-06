/**
 * Ph6 §2.8 — the chat side of the CR store: unread queue, cursor, ack and `Flag_User_Chat`.
 *
 * Everything in this file runs in the MCP server (Node), not in the mod and not in the agent:
 *
 *   mod --POST 57611 /chat_event--> daemon --> POST 57612 /internal/chat_event --> ChatHub.ingest()
 *
 * The mod pushes every line it sees the moment it sees it. Matching happens **here, before the message
 * is queued** ("缓存之前"), because the whole point of the flag is to be true while the agent is still
 * stuck inside a blocking tool call. The guard that turns that flag into a rejected tool call lives at
 * the single dispatch entry of this server (`reg()`), so no tool can forget it.
 *
 * The queue is memory only: restarting the MCP server loses the unread messages (the player asked for
 * exactly that, §7.7 "未读不持久化"), while restarting the client does not touch it.
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';

const REDELIVER_MS = 5_000;
const DROP_AFTER_MS = 60_000;
const SELF_RING = 200;
const MAX_UNREAD = 500;

/** Strips the §x formatting codes Minecraft allows inside every chat component. */
export function stripFormatting(text) {
  return String(text == null ? '' : text).replace(/\u00a7./g, '');
}

/**
 * Strips a leading `<name> ` decoration. The mod already does this (a player line arrives as
 * "<FYNyesterday> 鱼，回来吃饭"), but the queue must never depend on which mod build is installed: the
 * default rule anchors on the start of the message, so an undecorated text is a correctness requirement,
 * not cosmetics.
 */
export function stripSenderPrefix(text) {
  const t = String(text == null ? '' : text);
  if (t.startsWith('<')) {
    const end = t.indexOf('> ');
    if (end > 0 && end <= 40) return t.slice(end + 2);
  }
  return t;
}

export const DEFAULT_RULES = {
  enabled: true,
  matcher: '^鱼',
  exemptTools: [],
  mustReplyKeywords: ['鱼', '?', '？'],
  selfNames: [],
};

export class ChatHub {
  /**
   * @param {{workspace: string, log?: (msg: string) => void, selfNames?: string[]}} options
   */
  constructor(options) {
    this.workspace = options.workspace;
    this.log = options.log || ((m) => console.error(m));
    this.runtimeDir = path.join(this.workspace, 'runtime');
    this.rulesFile = path.join(this.runtimeDir, 'zff-chat-rules.json');
    this.flagFile = path.join(this.runtimeDir, 'zff-chat-flag.json');

    this.rules = { ...DEFAULT_RULES, ...(options.selfNames ? { selfNames: options.selfNames } : {}) };
    this.rulesMtime = -1;
    this.rulesLoadedAt = 0;

    /** seq -> {seq, ts, sender, text, mustReply, deliveredAt, repeats} */
    this.unread = new Map();
    this.inflight = new Map();
    /** seq -> {seq, ts, text, delivered} for lines we sent */
    this.self = [];
    this.selfSeq = 0;
    this.system = [];

    this.cursor = 0;
    this.lastAck = 0;
    this.flagUserChat = false;
    this.flagSetAt = 0;
    this.flagReason = '';
    this.lastIngestAt = 0;
    this.accepted = 0;
    this.dropped = 0;
    this.flaggedCount = 0;
    this.recentSent = [];

    this.publishFlag();
  }

  // ------------------------------------------------------------------ rules (hot reload)

  /** Reads runtime/zff-chat-rules.json when its mtime changed; never throws. */
  reloadRules(force = false) {
    try {
      const st = fs.statSync(this.rulesFile);
      if (!force && st.mtimeMs === this.rulesMtime) return this.rules;
      const raw = fs.readFileSync(this.rulesFile, 'utf8');
      const parsed = JSON.parse(raw);
      const next = { ...DEFAULT_RULES };
      if (typeof parsed.enabled === 'boolean') next.enabled = parsed.enabled;
      if (typeof parsed.matcher === 'string') next.matcher = parsed.matcher;
      if (Array.isArray(parsed.exemptTools)) next.exemptTools = parsed.exemptTools.filter((x) => typeof x === 'string');
      if (Array.isArray(parsed.mustReplyKeywords)) {
        next.mustReplyKeywords = parsed.mustReplyKeywords.filter((x) => typeof x === 'string');
      }
      if (Array.isArray(parsed.selfNames)) next.selfNames = parsed.selfNames.filter((x) => typeof x === 'string');
      // Keep selfNames from the constructor if the file does not carry any.
      if (!parsed.selfNames && Array.isArray(this.rules.selfNames)) next.selfNames = this.rules.selfNames;
      this.rules = next;
      this.rulesMtime = st.mtimeMs;
      this.rulesLoadedAt = Date.now();
      this.compile();
      this.log(`[chat] rules loaded: enabled=${next.enabled} matcher=${JSON.stringify(next.matcher)}`);
    } catch (e) {
      if (e && e.code !== 'ENOENT' && force) this.log(`[chat] rules not loaded: ${e.message}`);
    }
    return this.rules;
  }

  compile() {
    const src = this.rules.matcher || '';
    if (!src) {
      this.matcherRe = null;
      return;
    }
    try {
      this.matcherRe = new RegExp(src, 'u');
    } catch (e) {
      this.log(`[chat] matcher is not a valid regex (${e.message}); the flag will never be set`);
      this.matcherRe = null;
    }
  }

  // ------------------------------------------------------------------ ingest

  /** Adds the bridge player names so a server echo of our own line is never treated as the player. */
  setSelfNames(names) {
    const list = (names || []).filter((x) => typeof x === 'string' && x.trim());
    if (!list.length) return;
    const merged = new Set([...(this.rules.selfNames || []), ...list]);
    this.rules.selfNames = [...merged];
  }

  isSelfName(sender) {
    if (!sender) return false;
    return (this.rules.selfNames || []).some((n) => n.toLowerCase() === String(sender).toLowerCase());
  }

  /** True when the line is an echo of something we sent ≤2 s ago (§7.3 self dedupe). */
  isEcho(sender, text) {
    const now = Date.now();
    this.recentSent = this.recentSent.filter((x) => now - x.at <= 2_000);
    if (!this.recentSent.length) return false;
    const idx = this.recentSent.findIndex((x) => x.text === text);
    if (idx < 0) return false;
    if (sender && !this.isSelfName(sender)) return false;
    this.recentSent.splice(idx, 1);
    return true;
  }

  /**
   * @param {Array<object>} events raw events pushed by the mod
   * @returns {{accepted: number, flagged: boolean, cursor: number}}
   */
  ingest(events) {
    this.reloadRules(false);
    let accepted = 0;
    let flaggedNow = false;
    for (const raw of Array.isArray(events) ? events : []) {
      if (!raw || typeof raw !== 'object') continue;
      const type = String(raw.type || 'chat');
      const text = stripSenderPrefix(stripFormatting(raw.text != null ? raw.text : ''));
      if (!text) continue;
      const sender = raw.sender != null ? String(raw.sender) : null;
      const isSelf = raw.self === true || raw.outgoing === true || this.isEcho(sender, text);
      this.lastIngestAt = Date.now();

      if (isSelf) {
        this.pushSelf({ text, ts: Number(raw.time) || Date.now(), delivered: true });
        continue;
      }
      if (type !== 'chat') {
        // System/action-bar lines are kept for debugging only; they are never "the player talking".
        this.system.push({ ts: Date.now(), type, sender, text });
        while (this.system.length > 50) this.system.shift();
        this.maybeFlag(type, text, sender);
        continue;
      }
      if (this.unread.size + this.inflight.size >= MAX_UNREAD) {
        const oldest = this.unread.keys().next();
        if (!oldest.done) {
          this.unread.delete(oldest.value);
          this.dropped++;
        }
      }
      const seq = ++this.cursor;
      const mustReply = this.matchesMustReply(text);
      this.unread.set(seq, {
        seq,
        ts: Number(raw.time) || Date.now(),
        sender,
        text,
        mustReply,
        deliveredAt: 0,
        repeats: 0,
      });
      accepted++;
      this.accepted++;
      if (this.maybeFlag(type, text, sender)) flaggedNow = true;
    }
    if (flaggedNow) this.publishFlag();
    return { accepted, flagged: flaggedNow, cursor: this.cursor };
  }

  maybeFlag(type, text, sender) {
    if (!this.rules.enabled) return false;
    if (type !== 'chat') return false;
    if (!this.matcherRe) return false;
    if (this.isSelfName(sender)) return false;
    let hit = false;
    try {
      hit = this.matcherRe.test(text);
    } catch {
      hit = false;
    }
    if (!hit) return false;
    if (!this.flagUserChat) {
      this.flagUserChat = true;
      this.flagSetAt = Date.now();
      this.flagReason = `matched ${JSON.stringify(this.rules.matcher)}: ${text.slice(0, 80)}`;
      this.flaggedCount++;
      this.log(`[chat] Flag_User_Chat=1 (${this.flagReason})`);
    }
    return true;
  }

  matchesMustReply(text) {
    const words = this.rules.mustReplyKeywords || [];
    if (!words.length) return false;
    const t = String(text);
    return words.some((w) => w && t.includes(w));
  }

  pushSelf(entry) {
    const seq = ++this.selfSeq;
    this.self.push({ seq, ts: entry.ts || Date.now(), text: entry.text, delivered: entry.delivered !== false });
    while (this.self.length > SELF_RING) this.self.shift();
    return seq;
  }

  /** True when the same text was sent within `windowMs` (P7-19: one message must not be sent 2-3 times). */
  recentlySent(text, windowMs = 2_000) {
    const now = Date.now();
    this.recentSent = this.recentSent.filter((x) => now - x.at <= windowMs);
    return this.recentSent.some((x) => x.text === text);
  }

  /** Records a line the agent asked us to send, before the mod confirms it. */
  recordSent(items) {
    const out = [];
    for (const item of items) {
      const seq = this.pushSelf({ text: item.text, ts: Date.now(), delivered: item.delivered !== false });
      this.recentSent.push({ text: item.text, at: Date.now() });
      out.push({ seq, ts: Date.now(), text: item.text, delivered: item.delivered !== false });
    }
    return out;
  }

  // ------------------------------------------------------------------ drain / ack

  /**
   * `chat_turn` read side. Applies `ack` first, then hands out everything not yet delivered, plus
   * anything that was delivered but never acknowledged (the response may have been lost) after
   * {@link REDELIVER_MS}, and finally forgets unacknowledged messages older than {@link DROP_AFTER_MS}.
   */
  drain(options = {}) {
    const { ack, limit = 50, replying = false } = options;
    if (Number.isInteger(ack)) this.ack(ack);
    this.prune();
    const now = Date.now();
    const out = [];
    for (const [seq, msg] of this.unread) {
      if (out.length >= limit) break;
      this.unread.delete(seq);
      msg.deliveredAt = now;
      // P7-20：玩家已经回了话（replying=true）的消息不再反复要求回复。
      if (replying) msg.mustReply = false;
      this.inflight.set(seq, msg);
      out.push(this.publicMessage(msg, false));
    }
    // Re-deliver messages whose acknowledgement never came back (a lost tool response must not lose
    // the player's words). They are marked so the agent can tell a repeat from a new line.
    // P7-20: at most 2 repeats, then drop - an old line must not nag forever.
    for (const [seq, msg] of this.inflight) {
      if (out.length >= limit) break;
      if (msg.deliveredAt && now - msg.deliveredAt < REDELIVER_MS) continue;
      if ((msg.repeats || 0) >= 2) {
        this.inflight.delete(seq);
        this.dropped++;
        continue;
      }
      msg.repeats = (msg.repeats || 0) + 1;
      msg.deliveredAt = now;
      out.push(this.publicMessage(msg, true));
    }
    const mustReply = out.filter((m) => m.mustReply);
    return {
      received: out,
      self: this.self.slice(-20),
      mustReply,
      cursor: this.cursor,
      flagUserChat: this.flagUserChat,
      staleMs: this.lastIngestAt ? now - this.lastIngestAt : -1,
    };
  }

  publicMessage(msg, repeat) {
    const o = { seq: msg.seq, ts: msg.ts, sender: msg.sender, text: msg.text, mustReply: msg.mustReply };
    if (repeat) o.repeat = true;
    return o;
  }

  /** Everything up to and including {@code cursor} is now read. */
  ack(cursor) {
    if (!Number.isInteger(cursor)) return 0;
    let removed = 0;
    for (const seq of this.inflight.keys()) {
      if (seq <= cursor) {
        this.inflight.delete(seq);
        removed++;
      }
    }
    this.lastAck = Math.max(this.lastAck, cursor);
    return removed;
  }

  prune() {
    const now = Date.now();
    for (const [seq, msg] of this.inflight) {
      if (msg.deliveredAt && now - msg.deliveredAt > DROP_AFTER_MS) {
        this.inflight.delete(seq);
        this.dropped++;
      }
    }
    while (this.self.length > SELF_RING) this.self.shift();
  }

  // ------------------------------------------------------------------ flag

  /** `chat_turn` with drain!==false clears the flag; a pure send must never clear it (§2.8.5). */
  clearFlag(reason) {
    if (!this.flagUserChat) return false;
    this.flagUserChat = false;
    this.flagSetAt = 0;
    this.flagReason = `cleared by ${reason || 'chat_turn'}`;
    this.publishFlag();
    this.log(`[chat] Flag_User_Chat=0 (${this.flagReason})`);
    return true;
  }

  /** Written for the plugin: it decides whether to wake the agent up immediately (§2.8.8). */
  publishFlag() {
    const body = {
      flagUserChat: this.flagUserChat,
      at: this.flagUserChat ? this.flagSetAt : Date.now(),
      reason: this.flagReason,
      cursor: this.cursor,
      updatedAt: Date.now(),
    };
    try {
      fs.mkdirSync(this.runtimeDir, { recursive: true });
      const tmp = `${this.flagFile}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(body, null, 2), 'utf8');
      fs.renameSync(tmp, this.flagFile);
    } catch {
      /* the flag file is an out-of-band hint; losing it must not affect the queue */
    }
  }

  /** `exemptTools` are never interrupted (default empty; the mod's hard safety is not affected). */
  isExempt(toolName) {
    const list = this.rules.exemptTools || [];
    return list.includes(toolName);
  }

  /** The neutral error of §2.8.7: not a command failure, so it must not be counted as one. */
  guardError(toolName) {
    return {
      ok: false,
      error: 'user_chat_pending',
      message: '玩家刚说话，请先 chat_turn 读取并回复',
      tool: toolName,
      flagUserChat: true,
      sinceMs: this.flagSetAt ? Date.now() - this.flagSetAt : 0,
    };
  }

  stats() {
    return {
      cursor: this.cursor,
      lastAck: this.lastAck,
      unread: this.unread.size,
      inflight: this.inflight.size,
      self: this.self.length,
      accepted: this.accepted,
      dropped: this.dropped,
      flagged: this.flaggedCount,
      flagUserChat: this.flagUserChat,
      flagAgeMs: this.flagSetAt ? Date.now() - this.flagSetAt : 0,
      rules: {
        enabled: this.rules.enabled,
        matcher: this.rules.matcher,
        exemptTools: this.rules.exemptTools,
        mustReplyKeywords: this.rules.mustReplyKeywords,
        loadedAt: this.rulesLoadedAt,
      },
      lastIngestAt: this.lastIngestAt,
    };
  }

  /** Rule file the plugin writes; created with the defaults so it is discoverable. */
  async ensureRulesFile() {
    try {
      await fsp.mkdir(this.runtimeDir, { recursive: true });
      await fsp.access(this.rulesFile);
    } catch {
      try {
        await fsp.writeFile(this.rulesFile, JSON.stringify(DEFAULT_RULES, null, 2), 'utf8');
      } catch {
        /* not fatal: the built-in defaults still work */
      }
    }
    this.reloadRules(true);
  }
}
