#!/usr/bin/env node
/**
 * MCP server for the mcpbridge NeoForge client mod.
 *
 * Transport: streamable-http on 127.0.0.1:57610/mcp (preferred) or stdio.
 * The bridge itself is loopback HTTP + Bearer token; the token is read from the
 * instance registration file and is never logged or returned by a tool.
 */
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { z } from 'zod';
import { ChatHub } from './lib/chat-hub.mjs';
import { CrStore, readModStore } from './lib/store.mjs';
import { InterruptManager } from './lib/interrupts.mjs';
import { EmergencyState } from './lib/emergency.mjs';
import { sanitizeChatText } from './lib/chat-sanitize.mjs';
import { synthesize as synthesizeReachability } from './lib/reachability.mjs';
import { ToolUsage } from './lib/tool-usage.mjs';

const VERSION = '0.1.0';
const PROTOCOL = 1;
const EXPIRE_MS = 20_000;
const MAX_TOOL_TEXT = 30_000;
/** Ph6 §2.8: internal ingest endpoint the daemon forwards chat events to. */
const INTERNAL_PORT = Number(process.env.MC_INTERNAL_PORT || 57612);
const ENV_MIRROR_MS = 500;
/** 2026-11-01 需求 6：DSH 插件的心跳（0 = 还没收到过） */
let lastHeartbeatMs = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseArgs(argv) {
  const out = { http: false, port: 57610, instanceFile: null, tokenFile: null, stateDir: null, workspace: process.env.MC_WORKSPACE || process.cwd() };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--stdio': out.http = false; break;
      case '--http': out.http = true; break;
      case '--port': out.port = Number(next()); break;
      case '--state-dir': out.stateDir = next(); break;
      case '--instance': out.instanceFile = next(); break;
      case '--token-file': out.tokenFile = next(); break;
      case '--workspace': out.workspace = next(); break;
      // 2026-11-01 需求 6：DSH 关掉后 server 自己退出。插件用 --owner-heartbeat 启动它，
      // 每 5 s 打一次 /internal/heartbeat；超过 30 s 没心跳就 exit(0)。
      case '--owner-heartbeat': out.ownerHeartbeat = true; break;
      case '--heartbeat-timeout': out.heartbeatTimeoutMs = Number(next()); break;
      // 2026-11-04: 客户端关闭后退出。插件启动时传 60；0/缺省表示不启用。
      case '--exit-without-instance': out.exitWithoutInstanceSeconds = Number(next()); break;
      case '--started-by': out.startedBy = next(); break;
      case '--help': out.help = true; break;
      default: throw new Error('unknown argument: ' + a);
    }
  }
  return out;
}

function defaultStateDir() {
  if (process.env.MC_STATE_DIR) return path.resolve(process.env.MC_STATE_DIR);
  if (process.platform === 'win32') {
    const local = process.env.LOCALAPPDATA || path.join(os.homedir(), 'AppData', 'Local');
    return path.join(local, 'state', 'headlessmc-mcp');
  }
  if (process.platform === 'darwin') {
    return path.join(os.homedir(), 'Library', 'Application Support', 'headlessmc-mcp');
  }
  const xdg = process.env.XDG_STATE_HOME || path.join(os.homedir(), '.local', 'state');
  return path.join(xdg, 'headlessmc-mcp');
}

function instancesDir(config) {
  const base = config.stateDir ? path.resolve(config.stateDir) : defaultStateDir();
  return path.basename(base) === 'instances' ? base : path.join(base, 'instances');
}

class BridgeError extends Error {
  constructor(code, message, partial = false) {
    super(message);
    this.code = code;
    this.partial = partial;
  }
}

function codeOf(e) {
  if (e instanceof BridgeError) return e.code;
  if (e && (e.name === 'AbortError' || e.name === 'TimeoutError')) return 'timeout';
  return 'internal';
}

function sanitizeInstance(inst, file) {
  return {
    file: file ? path.basename(file) : undefined,
    path: file,
    port: inst.port,
    bridgeVersion: inst.bridgeVersion,
    loader: inst.loader,
    mcVersion: inst.mcVersion,
    gameDir: inst.gameDir,
    started: inst.started,
    heartbeatAt: inst.heartbeatAt,
    ageMs: typeof inst.heartbeatAt === 'number' ? Date.now() - inst.heartbeatAt : null,
    live: typeof inst.heartbeatAt === 'number' && Date.now() - inst.heartbeatAt <= EXPIRE_MS,
    token: undefined,
  };
}

class BridgeStore {
  constructor(config) {
    this.config = config;
    this.dir = instancesDir(config);
    this.active = null;
    this.lastDiscover = 0;
    // P1-11: jobs started through MCP are tracked so a client abort / session close can cancel them.
    this.activeJobs = new Map();
    // Once attached (mc_attach / --instance) the store must not wander to another client:
    // with several game windows open the newest heartbeat would win on every re-discovery.
    this.pinned = false;
  }

  /** Cancels every job that MCP started and that has not reached a terminal state yet. */
  async cancelActiveJobs(reason) {
    const ids = [...this.activeJobs.keys()];
    this.activeJobs.clear();
    let cancelled = 0;
    for (const id of ids) {
      try {
        await this.cancel(id);
        cancelled++;
      } catch { /* best effort: the job may already be gone */ }
    }
    if (ids.length) console.error(`[cancel-active] ${reason}: ${cancelled}/${ids.length} job(s) cancelled`);
    return { reason, tracked: ids.length, cancelled, jobIds: ids };
  }

  async listInstances(includeExpired = false) {
    let names = [];
    try { names = await fsp.readdir(this.dir); } catch { return []; }
    const out = [];
    for (const name of names) {
      if (!name.endsWith('.json')) continue;
      const file = path.join(this.dir, name);
      try {
        const raw = await fsp.readFile(file, 'utf8');
        const inst = JSON.parse(raw);
        // Ph6 M3: the registration `protocol` is the *mod's* capability number, not ours, so it must
        // never be an exact-match gate: a client with a newer protocol still attaches (and reports
        // backend=server only when the two halves agree). Gating on equality is what silently made a
        // 0.2.0 client invisible to a 0.1.0 MCP server.
        if (!Number.isInteger(inst.protocol) || inst.protocol < 1 || !Number.isInteger(inst.port) || !inst.token) continue;
        const view = sanitizeInstance(inst, file);
        if (!includeExpired && !view.live) continue;
        out.push({ ...view, token: inst.token });
      } catch {
        // A partially written registration is ignored; the heartbeat will rewrite it.
      }
    }
    out.sort((a, b) => (b.heartbeatAt || 0) - (a.heartbeatAt || 0));
    return out;
  }

  /**
   * Ph7: instance list **with the in-game player name**.
   *
   * The registration file does not carry the username (the mod cannot know it before login), so the
   * only honest source is the bridge itself: `GET /info` returns `player` once the client is in a
   * world. A dead/slow bridge yields `username: null` — callers must treat that as "unknown", never
   * as "matches nothing".
   */
  async describeInstances(includeExpired = false) {
    const list = await this.listInstances(includeExpired);
    const out = [];
    for (const view of list) {
      let username = null;
    let account = null;
      let inWorld = null;
      try {
        const infoRes = await fetch(`http://127.0.0.1:${view.port}/info`, {
          headers: { Authorization: `Bearer ${view.token}` },
          signal: AbortSignal.timeout(1200),
        });
        if (infoRes.ok) {
          const info = await infoRes.json();
          if (typeof info?.player === 'string' && info.player.trim()) username = info.player.trim();
      if (typeof info?.account === 'string' && info.account.trim()) account = info.account.trim();
          if (typeof info?.inWorld === 'boolean') inWorld = info.inWorld;
        }
      } catch { /* bridge gone between listing and probing */ }
      const { token, ...rest } = view;
      out.push({ ...rest, username, account, inWorld });
    }
    return out;
  }

  async probe(inst) {
    const base = `http://127.0.0.1:${inst.port}`;
    const health = await fetch(`${base}/health`, { signal: AbortSignal.timeout(1500) });
    if (health.status !== 200) throw new BridgeError('disconnected', 'bridge health check failed');
    const infoRes = await fetch(`${base}/info`, {
      headers: { Authorization: `Bearer ${inst.token}` },
      signal: AbortSignal.timeout(1500),
    });
    if (infoRes.status === 401) throw new BridgeError('no_permission', 'bridge rejected the bearer token');
    if (infoRes.status !== 200) throw new BridgeError('disconnected', 'bridge info request failed');
    const info = await infoRes.json();
    return { ...inst, info, base };
  }

  async ensure(force = false) {
    if (!force && this.pinned && this.active) {
      return this.active;
    }
    // Without an explicit attach, stay with the instance already chosen while its registration
    // is still fresh: re-sorting by heartbeat would otherwise hop between two open game windows.
    if (!force && this.active && this.active.path) {
      try {
        const raw = JSON.parse(await fsp.readFile(this.active.path, 'utf8'));
        if (typeof raw.heartbeatAt === 'number' && Date.now() - raw.heartbeatAt <= EXPIRE_MS) {
          this.active.heartbeatAt = raw.heartbeatAt;
          return this.active;
        }
      } catch {
        // registration disappeared; fall through to discovery
      }
    }
    if (!force && this.active && this.active.heartbeatAt && Date.now() - this.active.heartbeatAt < EXPIRE_MS) {
      return this.active;
    }
    if (!force && Date.now() - this.lastDiscover < 1_000) {
      throw new BridgeError('not_loaded', 'no live Minecraft bridge registration');
    }
    this.lastDiscover = Date.now();
    const live = await this.listInstances(false);
    if (!live.length) throw new BridgeError('not_loaded', 'no live Minecraft bridge registration; start the client and wait for the heartbeat');
    let lastError = null;
    const probed = [];
    for (const inst of live) {
      try {
        probed.push(await this.probe(inst));
      } catch (e) {
        lastError = e;
      }
    }
    if (probed.length) {
      // A client sitting at the main menu can have a valid heartbeat too. Prefer one
      // that is actually in a world, then the newest heartbeat.
      probed.sort((a, b) => {
        const aw = a.info && a.info.inWorld ? 1 : 0;
        const bw = b.info && b.info.inWorld ? 1 : 0;
        if (aw !== bw) return bw - aw;
        return (b.heartbeatAt || 0) - (a.heartbeatAt || 0);
      });
      this.active = probed[0];
      return this.active;
    }
    throw lastError instanceof BridgeError ? lastError : new BridgeError('disconnected', 'no bridge answered on the registered ports');
  }

  async attach({ instanceFile, port, tokenFile } = {}) {
    let inst = null;
    if (instanceFile) {
      const file = path.resolve(instanceFile);
      const raw = await fsp.readFile(file, 'utf8');
      inst = JSON.parse(raw);
      inst._file = file;
    } else if (port) {
      if (!tokenFile) throw new BridgeError('no_permission', 'manual port attach needs --token-file / tokenFile; tokens are never accepted inline');
      const token = (await fsp.readFile(path.resolve(tokenFile), 'utf8')).trim();
      if (!token) throw new BridgeError('no_permission', 'token file is empty');
      inst = { protocol: PROTOCOL, port: Number(port), token, heartbeatAt: Date.now() };
    } else {
      throw new BridgeError('internal', 'mc_attach needs instanceFile or port+tokenFile');
    }
    if (!Number.isInteger(inst.port) || inst.port < 1 || inst.port > 65535 || !inst.token) {
      throw new BridgeError('internal', 'invalid instance registration');
    }
    const probed = await this.probe({ ...inst, heartbeatAt: inst.heartbeatAt || Date.now() });
    this.active = probed;
    this.pinned = true;
    return probed;
  }

  async rpc(tool, args, timeoutSeconds) {
    let inst;
    try {
      inst = await this.ensure();
    } catch (e) {
      throw e;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), Math.max(2_000, Math.min(600, timeoutSeconds || 60) * 1000 + 2_000));
    try {
      const res = await fetch(`${inst.base}/rpc`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${inst.token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ tool, args: args || {}, timeoutSeconds: timeoutSeconds || 0 }),
        signal: controller.signal,
      });
      const text = await res.text();
      let json = null;
      try { json = text ? JSON.parse(text) : null; } catch { json = null; }
      if (res.status === 401) throw new BridgeError('no_permission', 'bridge rejected the bearer token');
      if (!res.ok) throw new BridgeError('disconnected', `bridge returned HTTP ${res.status}`);
      if (!json || json.ok === false) {
        throw new BridgeError(json && json.error ? json.error : 'internal', (json && json.message) || 'bridge tool failed', !!(json && json.partial));
      }
      if (json.jobId) {
        this.activeJobs.set(json.jobId, { tool, at: Date.now() });
        return { jobId: json.jobId, state: json.state, tool: json.tool, job: json.job };
      }
      return json.result !== undefined ? json.result : json;
    } catch (e) {
      if (e instanceof BridgeError) throw e;
      if (e && (e.name === 'AbortError' || e.name === 'TimeoutError')) throw new BridgeError('timeout', `bridge call ${tool} timed out`, true);
      this.active = null;
      throw new BridgeError('disconnected', `bridge call ${tool} failed: ${e.message}`);
    } finally {
      clearTimeout(timer);
    }
  }

  /** The game directory of the attached client: where the mod store lives (§2.4). */
  async gameDir() {
    try {
      const inst = await this.ensure();
      return (inst && (inst.gameDir || (inst.info && inst.info.gameDir))) || null;
    } catch {
      return null;
    }
  }

  async info() {
    const inst = await this.ensure();
    return { ...inst.info, port: inst.port, instanceFile: inst._file || null };
  }

  async job(jobId) {
    const inst = await this.ensure();
    const res = await fetch(`${inst.base}/jobs/${encodeURIComponent(jobId)}`, {
      headers: { Authorization: `Bearer ${inst.token}` },
      signal: AbortSignal.timeout(10_000),
    });
    if (res.status === 404) throw new BridgeError('internal', 'unknown job id');
    if (res.status !== 200) throw new BridgeError('disconnected', `bridge returned HTTP ${res.status}`);
    const json = await res.json();
    const job = json.job || json;
    // Stop tracking jobs that finished, and forget entries older than 30 minutes (a stale id would
    // otherwise be re-cancelled for ever).
    if (job && (job.state === 'done' || job.state === 'failed' || job.state === 'cancelled')) {
      this.activeJobs.delete(jobId);
    }
    const cutoff = Date.now() - 30 * 60_000;
    for (const [id, meta] of this.activeJobs) {
      if (meta.at < cutoff) this.activeJobs.delete(id);
    }
    return job;
  }

  async cancel(jobId) {
    this.activeJobs.delete(jobId);
    const inst = await this.ensure();
    const res = await fetch(`${inst.base}/jobs/${encodeURIComponent(jobId)}/cancel`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${inst.token}` },
      signal: AbortSignal.timeout(15_000),
    });
    if (res.status === 404) throw new BridgeError('internal', 'unknown job id');
    if (res.status !== 200) throw new BridgeError('disconnected', `bridge returned HTTP ${res.status}`);
    const json = await res.json();
    if (json.job) return json.job;
    return json;
  }
}

class MemoryStore {
  constructor(workspace) {
    this.dir = path.join(path.resolve(workspace), '.mcp-agent');
  }

  async ensure() {
    await fsp.mkdir(this.dir, { recursive: true });
    const readme = path.join(this.dir, 'README.md');
    try { await fsp.access(readme); } catch {
      await fsp.writeFile(readme, [
        '# MCP memory index',
        '',
        'This directory is the persistent memory for the Minecraft control agent.',
        'It is intentionally not named AGENTS.md: DSH injects AGENTS.md as workspace instructions.',
        '',
        'Use `memory_write` to record goals, coordinates, verified facts and open questions.',
        'Use `memory_read` after a client restart to resume. Keep each file below 256 KiB.',
        '',
        '## Topics',
        '',
      ].join('\n'), 'utf8');
    }
  }

  safe(name) {
    const raw = String(name || '').trim().replaceAll('\\', '/');
    if (!raw || raw.startsWith('/') || raw.includes('..') || raw.includes('\0')) {
      throw new BridgeError('internal', 'invalid memory file name');
    }
    const parts = raw.split('/').filter(Boolean);
    if (!parts.length || parts.some((p) => !/^[A-Za-z0-9._-]+$/.test(p))) {
      throw new BridgeError('internal', 'memory names use letters, digits, dot, underscore, dash and /');
    }
    const full = path.resolve(this.dir, ...parts);
    if (!full.startsWith(this.dir + path.sep)) throw new BridgeError('internal', 'memory path escapes the memory directory');
    return full;
  }

  async list() {
    await this.ensure();
    const out = [];
    const walk = async (dir, prefix) => {
      let entries = [];
      try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
      for (const e of entries) {
        if (out.length >= 200) return;
        const abs = path.join(dir, e.name);
        const rel = prefix ? `${prefix}/${e.name}` : e.name;
        if (e.isDirectory()) await walk(abs, rel);
        else if (e.isFile()) {
          const st = await fsp.stat(abs);
          out.push({ name: rel, bytes: st.size, modified: st.mtimeMs });
        }
      }
    };
    await walk(this.dir, '');
    out.sort((a, b) => a.name.localeCompare(b.name));
    return out;
  }

  async read(name) {
    await this.ensure();
    const full = this.safe(name);
    let st;
    try { st = await fsp.stat(full); } catch { throw new BridgeError('internal', 'memory file not found: ' + name); }
    let content = await fsp.readFile(full, 'utf8');
    const bytes = Buffer.byteLength(content, 'utf8');
    let truncated = false;
    if (content.length > MAX_TOOL_TEXT - 2_000) {
      content = content.slice(0, MAX_TOOL_TEXT - 2_000);
      truncated = true;
    }
    return { name, path: full, bytes, truncated, content };
  }

  async write(name, content, mode = 'replace') {
    await this.ensure();
    const full = this.safe(name);
    const text = String(content ?? '');
    if (Buffer.byteLength(text, 'utf8') > 256 * 1024) throw new BridgeError('internal', 'memory content exceeds 256 KiB');
    await fsp.mkdir(path.dirname(full), { recursive: true });
    let finalText = text;
    if (mode === 'append') {
      let old = '';
      try { old = await fsp.readFile(full, 'utf8'); } catch { /* new file */ }
      finalText = old + text;
    }
    const tmp = full + '.tmp-' + crypto.randomBytes(4).toString('hex');
    await fsp.writeFile(tmp, finalText, 'utf8');
    await fsp.rename(tmp, full);
    await this.addIndex(name).catch(() => {});
    return { name, path: full, bytes: Buffer.byteLength(finalText, 'utf8'), mode };
  }

  async addIndex(name) {
    if (name === 'README.md') return;
    const readme = path.join(this.dir, 'README.md');
    let text = await fsp.readFile(readme, 'utf8');
    const line = `- [${name}](${name})`;
    if (!text.includes(line)) {
      text = text.trimEnd() + '\n' + line + '\n';
      await fsp.writeFile(readme, text, 'utf8');
    }
  }
}

function capText(text) {
  if (Buffer.byteLength(text, 'utf8') <= MAX_TOOL_TEXT) return text;
  const cut = text.slice(0, MAX_TOOL_TEXT - 200);
  return cut + '\n... [truncated by MCP server at 30 KB]';
}

function contentOf(value) {
  if (value && value.__mcpContent) return value.content;
  const text = value === undefined ? 'null' : typeof value === 'string' ? value : JSON.stringify(value);
  return [{ type: 'text', text: capText(text) }];
}

function errorContent(e) {
  const code = codeOf(e);
  const body = { ok: false, error: code, message: e && e.message ? e.message : String(e) };
  if (e && e.partial) body.partial = true;
  return { isError: true, content: [{ type: 'text', text: JSON.stringify(body) }] };
}

function createServer(store, memory, ctx) {
  const hub = ctx.hub;
  const cr = ctx.cr;
  const interrupts = ctx.interrupts;
  const emergency = ctx.emergency;
  const usage = new ToolUsage({
    file: path.join(ctx.workspace, 'runtime', 'tool-usage.json'),
    log: (m) => console.error(m),
  });
  const server = new McpServer(
    { name: 'minecraft-control', version: VERSION },
    {
      instructions: [
        'This server controls a real, headed Minecraft client through a loopback bridge.',
        'Coordinates are integer block coordinates unless a field says otherwise.',
        'Long operations return a jobId immediately; poll with job_status or path_status and cancel with job_cancel/path_cancel.',
        'If no bridge is registered, ask the user to start the client; never try to launch a headless client.',
        'Never print or request the bridge bearer token: it is only read from the instance file or a token file.',
        'Record durable goals and verified coordinates in memory_write; memory lives in <workspace>/.mcp-agent.',
      ].join(' '),
    },
  );

  const reg = (name, description, inputSchema, handler) => {
    server.registerTool(name, { description, inputSchema }, async (args, extra) => {
      usage.record(name);
      // Ph7 M1-3 §6.4: every tool call starts by draining the interrupt flags. The call itself is
      // never blocked; the drained events ride along in `cleared`, so the agent loses no information.
      // (The old Flag_User_Chat hard guard is gone: a chat line now interrupts long calls through the
      // same engine, instead of rejecting every unrelated tool.)
      const begin = interrupts.beginCall(name);
      // Job pollers are the agent's "am I done?" call. If an interrupt fired while the job ran, cancelling
      // it here is what makes an async 64-block mine / 60-block path react within one poll instead of
      // waiting for the job's own timeout.
      if (begin.interrupt && (name === 'job_status' || name === 'path_status') && args && args.jobId) {
        // Do NOT kill a job that is itself a self-rescue action (swimming up, walk, mlg_water). The
        // interrupt is still reported via `cleared`/`interrupt` on the normal status result.
        let jobTool = null;
        try { const j = await store.job(args.jobId); jobTool = j && j.tool ? String(j.tool) : null; } catch { /* unknown */ }
        const protectedJob = jobTool && interrupts.isNeverInterrupt(begin.interrupt.type, jobTool);
        if (!protectedJob) {
          let cancelled = false;
          try { await store.cancel(args.jobId); cancelled = true; } catch { /* job already gone */ }
          return {
            content: contentOf({
              ok: false,
              error: 'interrupted',
              interrupt: begin.interrupt,
              jobId: args.jobId,
              cancelled,
              cleared: begin.cleared,
            }),
          };
        }
        // fall through: run the normal job_status handler and attach the interrupt to its result
      }
      // P1-11: when the MCP client aborts this request (user pressed stop), cancel every job that
      // this server started and that is still running - an aborted call must not leave the game
      // digging/walking in the background.
      const signal = extra && extra.signal;
      const onAbort = () => {
        store.cancelActiveJobs(`client aborted ${name}`)
          .catch((e) => console.error('[cancel-active] failed:', e && e.message));
      };
      if (signal) {
        if (signal.aborted) onAbort();
        else signal.addEventListener('abort', onAbort, { once: true });
      }
      try {
        const value = await handler(args || {}, extra || {});
        // 2026-11-02 rewrite: EVERY tool response carries the current infoTxt and
        // clears it (the player's pull semantics). Emergency lines come first,
        // terminal-job lines are appended at the end by EmergencyState.
        const infoTxt = emergency ? emergency.buildAndClear() : [];
        if (value && typeof value === 'object' && !Array.isArray(value) && !value.__mcpContent) {
          const withMeta = { ...value, infoTxt };
          if (begin.cleared.length) withMeta.cleared = begin.cleared;
          if (begin.interrupt && withMeta.interrupt == null) withMeta.interrupt = begin.interrupt;
          return { content: contentOf(withMeta) };
        }
        if (value && typeof value === 'object' && value.__mcpContent) {
          // binary payload (screenshot): keep the image, append the info text
          const out = contentOf(value);
          if (infoTxt.length) out.content = [...(out.content || []), { type: 'text', text: JSON.stringify({ infoTxt }) }];
          return out;
        }
        if (infoTxt.length) return { content: contentOf({ ok: true, result: value, infoTxt }) };
        return { content: contentOf(value) };
      } catch (e) {
        // Never swallow the pull: an error response must also hand infoTxt to the CR.
        const infoTxt = emergency ? emergency.buildAndClear() : [];
        const err = errorContent(e);
        if (infoTxt.length) err.content = [...(err.content || []), { type: 'text', text: JSON.stringify({ infoTxt }) }];
        return err;
      } finally {
        if (signal) {
          try { signal.removeEventListener('abort', onAbort); } catch { /* ignore */ }
        }
      }
    });
  };

  let lastCompact = null;
  const inventoryCounts = (inv) => {
    const m = new Map();
    for (const it of (inv?.items || [])) m.set(it.id, (m.get(it.id) || 0) + it.count);
    return m;
  };
  const deltaOf = (prev, cur) => {
    if (!prev) return null;
    const d = {};
    for (const k of ['health', 'food', 'air', 'xpLevel', 'blockLight', 'fluidAtFeet']) {
      if (prev[k] !== cur[k]) d[k] = { from: prev[k], to: cur[k] };
    }
    if (prev.usedSlots !== cur.usedSlots) d.usedSlots = { from: prev.usedSlots, to: cur.usedSlots };
    const changes = [];
    const ids = new Set([...prev.counts.keys(), ...cur.counts.keys()]);
    for (const id of ids) {
      const before = prev.counts.get(id) || 0;
      const after = cur.counts.get(id) || 0;
      if (before !== after) changes.push({ id, delta: after - before, now: after });
    }
    if (changes.length) d.inventory = changes;
    return Object.keys(d).length ? d : null;
  };

  /**
   * Polls a job until it reaches a terminal state (or the deadline). Used by tools that post-process.
   * Ph7 M1-3: every 250 ms it also checks the interrupt engine; a hit cancels **this** job only and
   * returns immediately, so a `collect_drops` call no longer sits out its full 60 s timeout.
   */
  const waitJob = async (jobId, seconds, store, tool = 'wait_job') => {
    const deadline = Date.now() + Math.max(5, Math.min(600, seconds)) * 1000;
    let last = null;
    while (Date.now() < deadline) {
      const hit = interrupts.peek(tool);
      if (hit) {
        let cancelled = false;
        try { await store.cancel(jobId); cancelled = true; } catch { /* the job may already be gone */ }
        return {
          ok: false,
          error: 'interrupted',
          interrupt: hit,
          jobId,
          cancelled,
        };
      }
      try {
        last = await store.job(jobId);
      } catch (e) {
        return last || { jobId, state: 'unknown', error: e.message };
      }
      if (last && ['done', 'failed', 'cancelled'].includes(last.state)) return last;
      await new Promise((r) => setTimeout(r, 250));
    }
    return last || { jobId, state: 'timeout' };
  };
  const t = z.number().int().min(1).max(600).optional();
  const coord = z.number().int();
  const optCoord = z.number().int().optional();

  // Ph8: server-side terrain helpers. They use the existing block_query RPC (max 64 per call)
  // instead of adding another mod-side scanner; this keeps the vanilla+bridge surface small.
  async function queryBlocks (positions, timeoutSeconds = 5) {
    const out = [];
    for (let i = 0; i < positions.length; i += 64) {
      const chunk = positions.slice(i, i + 64);
      const r = await store.rpc('block_query', { positions: chunk }, timeoutSeconds);
      if (Array.isArray(r?.blocks)) out.push(...r.blocks);
    }
    return out;
  }

  function blockFlags (b) {
    const air = b?.air === true;
    const loaded = b?.loaded !== false;
    const collision = Array.isArray(b?.collision) ? b.collision : [];
    const full = collision.some((a) => Array.isArray(a) && a.length >= 6
      && Number(a[0]) === 0 && Number(a[1]) === 0 && Number(a[2]) === 0
      && Number(a[3]) === 1 && Number(a[4]) === 1 && Number(a[5]) === 1);
    return {
      id: String(b?.id ?? (loaded ? 'minecraft:air' : 'minecraft:unloaded')),
      air,
      full,
      nonFull: !air && !full,
      loaded,
    };
  }

  async function playerBlock () {
    const st = await store.rpc('get_state', {}, 5);
    const p = st?.player;
    if (!p) throw new Error('no player');
    return { x: Math.floor(Number(p.x)), y: Math.floor(Number(p.y)), z: Math.floor(Number(p.z)) };
  }

  async function layerSnapshot (y) {
    const p = await playerBlock();
    const positions = [];
    for (let dz = -7; dz <= 7; dz++) {
      for (let dx = -7; dx <= 7; dx++) positions.push([p.x + dx, y, p.z + dz]);
    }
    const blocks = await queryBlocks(positions, 5);
    const by = new Map(blocks.map((b) => [`${b.pos?.[0]},${b.pos?.[1]},${b.pos?.[2]}`, b]));
    const ids = [];
    const air = [];
    const nonFull = [];
    const unloaded = [];
    for (let dz = -7; dz <= 7; dz++) {
      const idRow = [];
      const airRow = [];
      const nfRow = [];
      for (let dx = -7; dx <= 7; dx++) {
        const b = by.get(`${p.x + dx},${y},${p.z + dz}`);
        const f = blockFlags(b || {});
        idRow.push(f.id);
        airRow.push(f.air);
        nfRow.push(f.nonFull);
        if (!f.loaded) unloaded.push([p.x + dx, y, p.z + dz]);
      }
      ids.push(idRow);
      air.push(airRow);
      nonFull.push(nfRow);
    }
    return {
      ok: true,
      y,
      center: [p.x, y, p.z],
      origin: [p.x - 7, p.z - 7],
      rows: 15,
      cols: 15,
      ids,
      air,
      nonFull,
      unloaded,
    };
  }

  async function reachabilitySnapshot () {
    const p = await playerBlock();
    const positions = [];
    for (let dy = -2; dy <= 3; dy++) {
      for (let dz = -7; dz <= 7; dz++) {
        for (let dx = -7; dx <= 7; dx++) positions.push([p.x + dx, p.y + dy, p.z + dz]);
      }
    }
    const blocks = await queryBlocks(positions, 5);
    const map = new Map(blocks.map((b) => [`${b.pos?.[0]},${b.pos?.[1]},${b.pos?.[2]}`, blockFlags(b)]));
    const blockAt = (x, y, z) => map.get(`${x},${y},${z}`) || {
      id: 'minecraft:unloaded', air: false, full: true, nonFull: false, loaded: false,
    };
    return synthesizeReachability({ px: p.x, pz: p.z, feetY: p.y, blockAt });
  }

  reg('get_state', '读取玩家/世界/背包快照。compact=true（默认）只保留关键字段以加快响应；compact=false 返回完整数据。', {
    compact: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const full = await store.rpc('get_state', {}, a.timeoutSeconds || 5);
    if (a.compact === false || !full || !full.player) return full;
    const p = full.player;
    const w = full.world || {};
    const compact = {
      ok: true,
      player: {
        name: p.name, x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch,
        blockX: p.blockX, blockY: p.blockY, blockZ: p.blockZ,
        health: p.health, maxHealth: p.maxHealth, food: p.food, saturation: p.saturation,
        xpLevel: p.xpLevel, xpProgress: p.xpProgress,
        onGround: p.onGround, inWater: p.inWater, underWater: p.underWater,
        // Ph6 ISSUE-2: sleeping is a *server* state, so it has to survive the compact projection or the
        // agent cannot tell "in bed" from "standing next to a bed".
        sleeping: p.sleeping, bedPos: p.bedPos, sleepTimer: p.sleepTimer,
        air: p.air, maxAir: p.maxAir,
        blockLight: p.blockLight, skyLight: p.skyLight, headBlockLight: p.headBlockLight,
        fluidAtFeet: p.fluidAtFeet, fluidAtFeetSource: p.fluidAtFeetSource,
        dimension: p.dimension, selectedSlot: p.selectedSlot, mainHand: p.mainHand,
        gameMode: p.gameMode, alive: p.alive,
        toolStatus: p.toolStatus ? {
          mainHand: p.toolStatus.mainHand,
          bestPickaxe: p.toolStatus.bestPickaxe,
          pickaxes: p.toolStatus.pickaxes,
          lowTools: p.toolStatus.lowTools,
          tools: p.toolStatus.tools,
        } : undefined,
        inventory: p.inventory ? {
          usedSlots: p.inventory.usedSlots,
          freeSlots: p.inventory.freeSlots,
          items: (p.inventory.items || []).slice(0, 18),
          hotbar: (p.inventory.hotbar || []).slice(0, 9),
        } : undefined,
      },
      world: {
        dimension: w.dimension, timeOfDay: w.timeOfDay, day: w.day,
        isDay: w.isDay, moonPhase: w.moonPhase,
        time: w.time, weather: w.weather,
        raining: w.raining, thundering: w.thundering, difficulty: w.difficulty,
        playerCount: w.playerCount, server: w.server,
      },
      screen: full.screen,
      damage: (full.damage || []).slice(0, 3),
      ghostCount: full.ghostCount,
      ghostEvents: (full.ghostEvents || []).slice(0, 3),
      bridge: full.bridge,
    };
    const cur = {
      health: p.health, food: p.food, air: p.air, xpLevel: p.xpLevel,
      blockLight: p.blockLight, fluidAtFeet: p.fluidAtFeet,
      usedSlots: p.inventory ? p.inventory.usedSlots : 0,
      counts: inventoryCounts(p.inventory),
    };
    const delta = deltaOf(lastCompact, cur);
    if (delta) compact.delta = delta;
    lastCompact = cur;
    return compact;
  });

  reg('scan_area', '【调试/X-ray，勿用于游戏决策】按球/立方体暴力扫描方块（能看穿墙）。正常探索请用 scan_connected。radius 1..32；返回 truncated/bytes。', {
    center: z.array(z.number().int()).length(3).optional(),
    x: optCoord, y: optCoord, z: optCoord,
    radius: z.number().int().min(1).max(32).optional(),
    shape: z.enum(['sphere', 'cube']).optional(),
    filter: z.string().optional(),
    includeAir: z.boolean().optional(),
    includeEntities: z.boolean().optional(),
    includeProperties: z.boolean().optional(),
    includeCollision: z.boolean().optional(),
    xray: z.boolean().optional(),
    maxBlocks: z.number().int().min(1).max(6000).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('scan_area', a, a.timeoutSeconds || 3));

  reg('scan_connected', '连通空间扫描（P1-1）：从玩家所在空腔做 flood fill，只返回能走到的空腔表面方块；门/栅栏门/活板门不阻断区域，实心墙后面的东西看不见。radius 1..64。', {
    center: z.array(z.number().int()).length(3).optional(),
    radius: z.number().int().min(1).max(64).optional(),
    heightRadius: z.number().int().min(1).max(32).optional(),
    maxSurface: z.number().int().min(32).max(8000).optional(),
    maxVisitedCells: z.number().int().min(1000).max(2000000).optional(),
    timeBudgetMs: z.number().int().min(50).max(5000).optional(),
    includeEntities: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('scan_connected', a, a.timeoutSeconds || 5));

  reg('scan_surface', '表面扫描：从眼睛对可达开放空间做 flood fill，只返回贴着开放空间的方块（surface/heightMap/pits/doors/containers）。mode=connected|los|fov。includeNonFull（默认 true）把门/活板门/栅栏门/床/告示牌也放进 surface（ISSUE-8）。', {
    center: z.array(z.number().int()).length(3).optional(),
    radius: z.number().int().min(1).max(64).optional(),
    mode: z.enum(['connected', 'los', 'fov']).optional(),
    includeNonFull: z.boolean().optional(),
    fovDegrees: z.number().int().min(10).max(180).optional(),
    faceSamples: z.number().int().min(1).max(9).optional(),
    maxRaySteps: z.number().int().min(8).max(256).optional(),
    includeEntities: z.boolean().optional(),
    maxSurface: z.number().int().min(32).max(8000).optional(),
    maxVisitedCells: z.number().int().min(1000).max(2000000).optional(),
    timeBudgetMs: z.number().int().min(50).max(5000).optional(),
    heightRadius: z.number().int().min(1).max(32).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('scan_surface', a, a.timeoutSeconds || 4));

  reg('batch', '把一串 mine/place/use 操作打包成任务顺序执行，中途不查询状态；整批完成后一次性回读校验（verifiedOk/unverified）。', {
    ops: z.array(z.object({
      op: z.enum(['place', 'mine', 'use']),
      x: z.number().int(), y: z.number().int(), z: z.number().int(),
      face: z.string().optional(),
      item: z.string().optional(),
    })).min(1).max(2048),
    timeoutSeconds: t,
  }, async (a) => store.rpc('batch', a, 3));

  reg('watch_damage', '等待下一次生命值下降，返回伤害量、原因（fall / mob:<type> / fire / drown / starve 等）和位置。', {
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('watch_damage', a, 3));

  reg('damage_log', '最近的受伤记录（默认 10 条，含原因、伤害量、前后血量、位置）。', {
    limit: z.number().int().min(1).max(40).optional(),
  }, async (a) => store.rpc('damage_log', a, 3));

  reg('mlg_water', 'MLG 落地水：从边缘走出去，在离地 3 格内把水放在脚下，落地后报告下落高度。需要快捷栏有水桶。', {
    yaw: z.number().optional(),
    maxTicks: z.number().int().min(20).max(600).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('mlg_water', a, a.timeoutSeconds || 30));
  reg('select_slot', '选择快捷栏槽位（0-8），切换手持物品。', {
    slot: z.number().int().min(0).max(8),
  }, async (a) => store.rpc('select_slot', a, 5));
  reg('dump_junk', '前往附近丢垃圾：整组丢出后立刻跑开，避免掉落物被吸回来。target=想要的空格数。', {
    target: z.number().int().min(1).max(20).optional(),
    run: z.number().int().min(3).max(12).optional(),
    all: z.boolean().optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('dump_junk', a, a.timeoutSeconds || 120));
  reg('drop_junk', '[DEPRECATED since Ph4: use delete_item (server-synced, verified)] 背包满时丢弃垃圾方块（泥土/砂砾/安山岩等），直到有 target 个空位。', {
    target: z.number().int().min(1).max(20).optional(),
  }, async (a) => store.rpc('drop_junk', a, 5));
  reg('delete_item', '服务端同步删除物品（快捷栏 SWAP + DROP_ALL_ITEMS + 校验；本地背包不被修改）。给 item 或 slot(0..35)，run 为丢完跑开距离。', {
    item: z.string().optional(),
    slot: z.number().int().min(0).max(35).optional(),
    run: z.number().int().min(0).max(12).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('delete_item', a, a.timeoutSeconds || 60));
  reg('ore_log', '矿石探针流：每次挖掉一个方块都会探测它的 6 个邻居，这里返回最近发现的矿石坐标。', {
    limit: z.number().int().min(1).max(100).optional(),
  }, async (a) => store.rpc('ore_log', a, 5));
  reg('ghosts', '幽灵方块/客户端-服务端不同步事件（position_jump / mined_revert / invisible_wall）。check=true 时现场检测卡在空气里的情况。', {
    limit: z.number().int().min(1).max(100).optional(),
    check: z.boolean().optional(),
  }, async (a) => store.rpc('ghosts', a, 5));
  reg('walk_to', '短距离直线行走：朝 (x,z) 移动、自动跳 1 格台阶、无进展立即报 no_progress。', {
    x: z.number(), z: z.number(),
    range: z.number().min(0.5).max(16).optional(),
    maxTicks: z.number().int().min(10).max(1200).optional(),
    allowBreak: z.boolean().optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('walk_to', a, a.timeoutSeconds || 60));
  reg('tower', '跳起并在脚下放方块，连续搭高 blocks 格；执行前先检查 h+2 格竖直列。', {
    blocks: z.number().int().min(1).max(64).optional(),
    item: z.string().optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const h = a.blocks == null ? 1 : a.blocks;
    const p = await playerBlock();
    const positions = [];
    for (let dy = 0; dy <= h + 1; dy++) positions.push([p.x, p.y + dy, p.z]);
    const blocks = await queryBlocks(positions, 5);
    const by = new Map(blocks.map((b) => [`${b.pos?.[0]},${b.pos?.[1]},${b.pos?.[2]}`, b]));
    const column = [];
    const blocked = [];
    for (let dy = 0; dy <= h + 1; dy++) {
      const f = blockFlags(by.get(`${p.x},${p.y + dy},${p.z}`) || {});
      const cell = { dy, pos: [p.x, p.y + dy, p.z], id: f.id, air: f.air, nonFull: f.nonFull, loaded: f.loaded };
      column.push(cell);
      if (!f.air || !f.loaded) blocked.push(cell);
    }
    if (blocked.length) {
      return {
        ok: false,
        error: 'tower_blocked',
        h,
        from: [p.x, p.y, p.z],
        column,
        blocked,
        message: `tower column is blocked at dy=${blocked[0].dy} (${blocked[0].id}); clear it first`,
      };
    }
    return store.rpc('tower', a, a.timeoutSeconds || 5);
  });

  reg('fish', '只抛一次鱼竿：找水、抛竿、等咬钩、收竿；不会自动连钓。timeoutSeconds 为等待咬钩时间。', {
    timeoutSeconds: z.number().int().min(10).max(180).optional(),
  }, async (a) => store.rpc('fish', a, (a.timeoutSeconds || 90) + 10));

  reg('combat_attack', '内建 AutoAim 的单目标战斗：近战按冷却攻击，远程用弓/弩/三叉戟蓄力后释放。只打 Enemy（除非 weapon=force）。', {
    entityId: z.number().int(),
    weapon: z.string().optional(),
    timeoutSeconds: z.number().int().min(2).max(60).optional(),
  }, async (a) => store.rpc('combat_attack', a, (a.timeoutSeconds || 20) + 5));

  reg('combat_auto', '扫描最近的敌对生物，并为最近的至多 N 个目标启动 combat_attack（内建 AutoAim）。', {
    maxTargets: z.number().int().min(1).max(4).optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const env = await store.rpc('env_read', {}, 3);
    const monsters = (Array.isArray(env?.monsters) ? env.monsters : [])
      .filter((m) => m && Number.isFinite(Number(m.id)))
      .sort((x, y) => Math.hypot(Number(x.x), Number(x.z)) - Math.hypot(Number(y.x), Number(y.z)))
      .slice(0, a.maxTargets || 2);
    const started = [];
    for (const m of monsters) {
      try {
        const r = await store.rpc('combat_attack', { entityId: Number(m.id), weapon: 'auto', timeoutSeconds: 20 }, 5);
        started.push({ entityId: m.id, type: m.type, jobId: r?.jobId ?? null });
      } catch (e) {
        started.push({ entityId: m.id, type: m.type, error: e?.code ?? String(e?.message ?? e) });
      }
    }
    return { ok: true, started, seen: monsters.length, autoaim: true };
  });

  reg('kill_target_close', '16 格内索敌，自动走向目标并近战击杀；有盾牌时在攻击间隙举盾。返回 health/armor/attacks/hits/outcome。', {
    weapon: z.string().optional(),
    timeoutSeconds: z.number().int().min(10).max(600).optional(),
  }, async (a) => store.rpc('kill_target_close', a, (a.timeoutSeconds || 120) + 10));

  reg('kill_target_far', '64 格内索敌，自动用弓/弩/三叉戟瞄准并射击直到击杀；返回 health/armor/attacks/hits/outcome。', {
    weapon: z.string().optional(),
    aimOffset: z.number().optional(),
    timeoutSeconds: z.number().int().min(10).max(600).optional(),
  }, async (a) => store.rpc('kill_target_far', a, (a.timeoutSeconds || 120) + 10));

  reg('escape', '敌对生物超过阈值时逃跑，直到 20x20 范围安全；返回 health/armor/outcome。', {
    threshold: z.number().int().min(1).max(30).optional(),
    timeoutSeconds: z.number().int().min(10).max(600).optional(),
  }, async (a) => store.rpc('escape', a, (a.timeoutSeconds || 120) + 10));

  reg('self_protect', '开启/关闭自卫：被非玩家敌对生物伤害时中断其他 job，并反击/举盾/逃跑。', {
    enabled: z.boolean(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('self_protect', { enabled: a.enabled }, a.timeoutSeconds || 3));

  reg('bow_probe', '诊断用：读取主手/副手/箭数/使用状态/服务端弹药物。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('bow_probe', {}, a.timeoutSeconds || 3));

  reg('combat_status', '战斗能力：内建 AutoAim 永远启用；战斗逻辑全部内置在 Bridge 模组中。', {
    timeoutSeconds: t,
  }, async () => ({
    ok: true,
    autoaim: { enabled: true, builtIn: true, canDisable: false },
    combatModes: ['off', 'defend', 'aggressive'],
  }));

  reg('walk', '按世界朝向走一小段（真实客户端移动），可 jump 跳下、sprint 疾跑、up 上浮/爬台阶。', {
    yaw: z.number().optional(),
    ticks: z.number().int().min(1).max(400).optional(),
    jump: z.boolean().optional(),
    sprint: z.boolean().optional(),
    up: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('walk', a, 3));

  reg('craft', '一键合成：默认用配方书放置（3x3 自动找附近工作台），配方没解锁时自动改用 JEI 式逐格放料；manual 可强制走 JEI 式。', {
    item: z.string(),
    count: z.number().int().min(1).max(64).optional(),
    table: z.array(z.number().int()).length(3).optional(),
    manual: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('craft', a, 3));

  // Ph6 §2.8.4: a long block is sliced into 2 s calls so the flag can be checked *between* slices.
  // Hitting the flag returns the neutral error and does NOT cancel the underlying job: the events are
  // still there when the agent comes back, and the player's message must not cost the agent its work.
  reg('loot_chest', '打开一个箱子/容器，把前 27 个槽的整叠物品全部 shift-click 拿走后关闭，返回拿到的物品列表。', {
    x: coord, y: coord, z: coord,
    timeoutSeconds: t,
  }, async (a) => store.rpc('loot_chest', a, 3));

  reg('auto_respawn', '查看/设置死亡自动复活（默认开启）；返回死亡次数和最后一次死亡信息。', {
    enabled: z.boolean().optional(),
  }, async (a) => store.rpc('auto_respawn', a, 3));

  reg('parallel', '并行执行 2–4 个短查询（如 scan_area/get_state/entity_query），用于边走边观察；返回每个调用的结果。', {
    calls: z.array(z.object({ tool: z.string(), args: z.record(z.any()).optional() })).min(2).max(4),
  }, async (a) => {
    const results = await Promise.all(a.calls.map(async (call) => {
      try {
        const result = await store.rpc(call.tool, call.args || {}, 10);
        return { tool: call.tool, ok: true, result };
      } catch (e) {
        return { tool: call.tool, ok: false, error: e && e.message ? e.message : String(e) };
      }
    }));
    return { ok: true, results };
  });

  reg('block_query', '读取指定方块的状态、碰撞箱和交互箱；positions 为 [x,y,z] 列表，最多 64 个。', {
    positions: z.array(z.array(z.number().int()).length(3)).max(64).optional(),
    x: optCoord, y: optCoord, z: optCoord,
    timeoutSeconds: t,
  }, async (a) => store.rpc('block_query', a, a.timeoutSeconds || 3));

  reg('query_layer', '以玩家为中心读取 xOz 平面 15x15x1 的全部方块；y 为绝对方块高度。', {
    y: coord,
    timeoutSeconds: t,
  }, async (a) => layerSnapshot(a.y));

  reg('query_layer_foot', '读取玩家脚部高度（player.blockY）的 15x15x1 平面。', {
    timeoutSeconds: t,
  }, async () => {
    const p = await playerBlock();
    return layerSnapshot(p.y);
  });

  reg('reachable_synthese', '以玩家为中心扫描 15x15x4 的可达性，返回 2D 标记网格（o/+/-/=/*/l/u/d/t/%/&）。', {
    timeoutSeconds: t,
  }, async () => reachabilitySnapshot());

  reg('entity_query', '查询客户端已跟踪的实体，含掉落物、生物和其他玩家。半径 >48 会自动改成分环扫描；空结果带 note（ISSUE-12）。', {
    radius: z.number().int().min(1).max(128).optional(),
    type: z.string().optional(),
    name: z.string().optional(),
    limit: z.number().int().min(1).max(200).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('entity_query', a, a.timeoutSeconds || 3));

  reg('collision_query', '读取指定方块的碰撞体积和交互体积 AABB 列表。', {
    positions: z.array(z.array(z.number().int()).length(3)).max(64).optional(),
    x: optCoord, y: optCoord, z: optCoord,
    timeoutSeconds: t,
  }, async (a) => store.rpc('collision_query', a, a.timeoutSeconds || 3));

  reg('chat', '发送一条聊天或 /命令（只发不听）。要听玩家消息必须用 chat_turn；两者不要对同一句话重复调用。不会把 bridge token 放进聊天。', {
    message: z.string().max(256).optional(),
    limit: z.number().int().min(1).max(200).optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const message = sanitizeChatText(a.message);
    // Silent sanitization: never tell the agent that characters were removed. If nothing is
    // left, treat it as a no-op instead of surfacing an "illegal characters" error.
    if (!message) return { ok: true, sent: false, infoTxt: [] };
    return store.rpc('chat', { ...a, message }, a.timeoutSeconds || 2);
  });

  reg('chat_history', '只读最近聊天记录。', {
    limit: z.number().int().min(1).max(200).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('chat_history', a, a.timeoutSeconds || 2));

  reg('command', '发送服务器命令（可带或不带前导斜杠）。返回 known 表示命令是否在当前客户端命令树中。', {
    command: z.string().min(1).max(256),
    timeoutSeconds: t,
  }, async (a) => {
    const command = sanitizeChatText(a.command, { command: true }).replace(/^\/+/, '');
    // Same silent policy as chat: an all-illegal command becomes a no-op.
    if (!command) return { ok: true, sent: false, infoTxt: [] };
    return store.rpc('command', { ...a, command }, a.timeoutSeconds || 2);
  });

  reg('recipe_lookup', '按输出/输入物品查询运行时配方（RecipeManager + JEI 虚拟配方兜底）。不硬编码配方。', {
    query: z.string().min(1).max(200),
    kind: z.enum(['output', 'input', 'any']).optional(),
    limit: z.number().int().min(1).max(80).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('recipe_lookup', a, a.timeoutSeconds || 5));

  reg('path_to', '异步 Baritone 寻路到 (x,y,z)；range>0 时使用 GoalNear。立即返回 jobId；用 path_status/path_cancel。', {
    x: coord, y: coord, z: coord,
    range: z.number().int().min(0).max(64).optional(),
    allowBreak: z.boolean().optional(),
    allowPlace: z.boolean().optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('path_to', a, a.timeoutSeconds || 300));

  reg('set_pathing_policy', 'Set whether pathing may break/place blocks (P1-10; default both false). No args returns the current policy.', {
    break: z.boolean().optional(),
    place: z.boolean().optional(),
  }, async (a) => store.rpc('set_pathing_policy', a, 5));

  reg('path_open', '带开门寻路的 path_to：走到目标点的同时自动打开挡住路线的门/栅栏门/活板门（村庄农田场景）。', {
    x: coord, y: coord, z: coord,
    range: z.number().int().min(0).max(64).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('path_open', a, a.timeoutSeconds || 300));

  // Ph9: when a path job fails, include the current foot layer so the agent can see why.
  const PATH_FAILURE_TOOLS = new Set(['path_to', 'path_open', 'path_open_ex', 'walk_to', 'walk']);
  async function enrichPathFailure(job) {
    if (!job || job.state !== 'failed' || !PATH_FAILURE_TOOLS.has(job.tool)) return job;
    try {
      const p = await playerBlock();
      job.layerFoot = await layerSnapshot(p.y);
    } catch (e) {
      job.layerFoot = { ok: false, error: String(e?.message || e) };
    }
    return job;
  }

  reg('path_status', '查询 path_to 任务状态；包含 distanceLeft/currentGoal/etaTicks；失败时附 layerFoot（query_layer_foot 结果）。', {
    jobId: z.string().min(1),
  }, async (a) => enrichPathFailure(await store.rpc('path_status', a, 5)));

  reg('path_cancel', '取消 path_to 任务并立即停止 Baritone。', {
    jobId: z.string().min(1),
  }, async (a) => store.rpc('path_cancel', a, 10));

  reg('mine_block', '异步挖掘单个方块或 positions 列表；progress 带 blocksMined/total。立即返回 jobId。', {
    x: optCoord, y: optCoord, z: optCoord,
    positions: z.array(z.array(z.number().int()).length(3)).max(64).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('mine_block', a, a.timeoutSeconds || 60));

  reg('place_block', '异步放置方块到 (x,y,z)；可指定 face 或 item。立即返回 jobId。', {
    x: coord, y: coord, z: coord,
    face: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional(),
    item: z.string().optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('place_block', a, a.timeoutSeconds || 60));

  reg('use_block', '右键使用门/按钮/拉杆等方块；返回使用前后的方块状态。mode=block|item_on|use_item（营火/烘干架用 item_on）；sneak=true 蹲下右键（ISSUE-11/13）。', {
    x: coord, y: coord, z: coord,
    face: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional(),
    mode: z.enum(['block', 'item_on', 'use_item']).optional(),
    hand: z.enum(['main', 'empty']).optional(),
    sneak: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('use_block', a, a.timeoutSeconds || 5));

  reg('entity_interact', '右键/攻击实体；action=trade 打开村民交易菜单。', {
    entityId: z.number().int().optional(),
    uuid: z.string().optional(),
    action: z.enum(['use', 'attack', 'trade']).optional(),
    itemSlot: z.number().int().min(0).max(8).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('entity_interact', a, a.timeoutSeconds || 10));

  reg('stop_all', '全局停止：取消所有 job、停 Baritone、清空调度器、关闭界面。收到“停”先调它。', {
    reason: z.string().max(120).optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const viaMcp = await store.cancelActiveJobs('stop_all').catch(() => null);
    const stopped = await store.rpc('stop_all', a, a.timeoutSeconds || 5);
    return viaMcp && viaMcp.cancelled ? { ...stopped, cancelledViaMcp: viaMcp.cancelled } : stopped;
  });

  reg('cancel_active', 'P1-11：取消由 MCP 启动且仍在运行的所有 job（用户中断/关闭会话时服务端也会自动调用）。', {}, async () => store.cancelActiveJobs('manual cancel_active'));

  reg('break_entity', '破坏实体（船/矿车/展示框/画/盔甲架）直到移除并报告掉落；不要用 mine_block 处理这些。', {
    entityId: z.number().int().optional(),
    uuid: z.string().optional(),
    type: z.string().optional(),
    radius: z.number().min(1).max(32).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('break_entity', a, a.timeoutSeconds || 60));

  reg('collect_drops', '走向附近掉落物并捡起，直到半径内没有物品或超时；捡不到时用 path_to 走到掉落物所在高度再试。', {
    radius: z.number().min(1).max(16).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => {
    const radius = a.radius || 8;
    const seconds = a.timeoutSeconds || 60;
    const first = await store.rpc('collect_drops', a, seconds);
    // collect_drops is a job: wait for its terminal state before reading metrics, otherwise the
    // fallback below would always run (the start answer has no metrics yet).
    const done1 = first && first.jobId ? await waitJob(a.jobId || first.jobId, seconds, store, 'collect_drops') : first;
    const collected = (done1 && done1.metrics && Number(done1.metrics.collected)) || 0;
    // A done job with 0 collected means: it walked around and could not reach anything (2D walk_to),
    // so the vertical fallback below is exactly the case that must still run.
    if (collected > 0) return { ...done1, retriedWithPath: false };

    // The drop may sit one or two blocks above/below the player (walk_to is 2D). Find the nearest
    // item, walk to its exact block with Baritone, then collect once more.
    let items = null;
    try {
      items = await store.rpc('entity_query', { radius: radius + 4, type: 'item' }, 5);
    } catch { /* no items */ }
    const near = ((items && items.entities) || []).sort((x, y) => (x.distance || 0) - (y.distance || 0))[0];
    if (!near) return { ...done1, retriedWithPath: false, nearestItem: null };
    let pathJob = null;
    try {
      pathJob = await store.rpc('path_to', { x: near.blockX, y: near.blockY, z: near.blockZ, range: 1 }, 30);
    } catch { /* keep the first result */ }
    if (pathJob && pathJob.jobId) {
      for (let i = 0; i < 120; i++) {
        await new Promise((r) => setTimeout(r, 400));
        const st = await store.rpc('job_status', { jobId: pathJob.jobId }, 5).catch(() => null);
        if (!st || ['done', 'failed', 'cancelled'].includes(st.state)) break;
      }
    }
    const second = await store.rpc('collect_drops', a, seconds);
    const done2 = second && second.jobId ? await waitJob(second.jobId, seconds, store, 'collect_drops') : second;
    return {
      ...done2,
      retriedWithPath: !!(pathJob && pathJob.jobId),
      firstPass: { state: done1 && done1.state, collected },
      nearestItem: [near.blockX, near.blockY, near.blockZ],
    };
  });

  reg('dig_shaft', '安全向下挖：mode=stair（默认 45° 阶梯）或 shaft（1x2 竖井）；先探水/岩浆，拒绝 1x1 直下。', {
    level: z.number().int().min(-64).max(320),
    mode: z.enum(['stair', 'shaft', 'up', 'down']).optional(),
    yaw: z.number().optional(),
    maxBlocks: z.number().int().min(8).max(20000).optional(),
    timeoutSeconds: z.number().int().min(1).max(1200).optional(),
  }, async (a) => store.rpc('dig_shaft', a, a.timeoutSeconds || 900));

  reg('dig_sealed_shaft', 'Ph5 P5-1：密封竖井——每挖一格就用圆石/泥土把头顶上方封住，海水/含水层不会灌进来；拒绝岩浆、拒绝下方有空洞。用于海底/水下作业。', {
    level: z.number().int().min(-64).max(320),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('dig_sealed_shaft', a, a.timeoutSeconds || 300));

  reg('branch_mine', '鱼骨挖矿：主巷 1x2 + 每 spacing 格一条支巷（挖完退回主巷），自动插火把、探水/岩浆。', {    corridorLength: z.number().int().min(4).max(256).optional(),
    spacing: z.number().int().min(2).max(8).optional(),
    branchLength: z.number().int().min(2).max(128).optional(),
    branches: z.number().int().min(0).max(64).optional(),
    yaw: z.number().optional(),
    side: z.enum(['left', 'right']).optional(),
    maxBlocks: z.number().int().min(8).max(20000).optional(),
    timeoutSeconds: z.number().int().min(1).max(1200).optional(),
  }, async (a) => store.rpc('branch_mine', a, a.timeoutSeconds || 900));

  reg('dig_tunnel', '实心岩石隧道 A->B（P2-1）：沿直线挖 1 宽 2 高走廊，每 8 格插火把，逐块验证（无幽灵方块），可自动沿隧道走回起点。from/to 为 [x,y,z]，坡度超过 45° 会拒绝。', {
    from: z.array(z.number().int()).length(3),
    to: z.array(z.number().int()).length(3),
    maxBlocks: z.number().int().min(8).max(20000).optional(),
    torches: z.boolean().optional(),
    returnTrip: z.boolean().optional(),
    timeoutSeconds: z.number().int().min(1).max(3600).optional(),
  }, async (a) => store.rpc('dig_tunnel', a, a.timeoutSeconds || 1800));

  reg('throw_item', '右键投掷/使用手里的物品（如末影之眼），返回生成的投射物实体 id。', {    item: z.string().optional(),
    slot: z.number().int().min(0).max(8).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('throw_item', a, a.timeoutSeconds || 5));

  reg('eye_throw', '末影之眼协议（P1-7）：投一颗眼，前 4 tick 抓到投射物，追踪到落点并报方位角；掉落的眼会走回去捡起来。一次只投一颗，禁止原地连投。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('eye_throw', a, a.timeoutSeconds || 60));

  reg('track_entity', '异步采样实体轨迹 N tick，返回 dx/dy/dz、水平方位 yaw 和 vertical=up/down/flat。', {
    entityId: z.number().int(),
    ticks: z.number().int().min(2).max(200).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('track_entity', a, a.timeoutSeconds || 30));

  reg('place_boat', '在指定的水面方块上放下船，返回船实体 id（等实体真正生成后再返回）。', {
    x: coord, y: coord, z: coord,
    item: z.string().optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const placed = await store.rpc('place_boat', a, a.timeoutSeconds || 5);
    if (!placed || placed.entityId >= 0) return placed;
    // The boat entity is spawned one tick after the use packet; the bridge answers immediately, so
    // poll a few times here (each round trip is a tick or more) instead of returning entityId -1.
    for (let i = 0; i < 8; i++) {
      await new Promise((r) => setTimeout(r, 120));
      let q = null;
      try { q = await store.rpc('entity_query', { radius: 8, type: 'boat' }, 5); } catch { /* retry */ }
      const e = ((q && q.entities) || []).find((x) => String(x.type || '').includes('boat'));
      if (e) {
        const { note, ...rest } = placed;
        return { ...rest, entityId: e.id, entity: e, resolvedMs: (i + 1) * 120, note: 'entity resolved by polling entity_query' };
      }
    }
    return placed;
  });

  reg('boat_sail', '上船并驶向 (x,z)，到达 range 内自动下船。', {
    x: z.number(), z: z.number(),
    range: z.number().min(1).max(16).optional(),
    timeoutSeconds: z.number().int().min(1).max(600).optional(),
  }, async (a) => store.rpc('boat_sail', a, a.timeoutSeconds || 240));

  reg('light_map', '扫描脚下可行走区域的光照：返回 darkCells 数量、最暗方块光、黑暗格子列表。', {
    center: z.array(z.number().int()).length(3).optional(),
    y: z.number().int().optional(),
    radius: z.number().int().min(1).max(32).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('light_map', a, a.timeoutSeconds || 5));

  reg('torch_at', '在 (x,y,z) 放一支火把（自动找相邻实心方块吸附），需要快捷栏有火把。', {
    x: coord, y: coord, z: coord,
    timeoutSeconds: t,
  }, async (a) => store.rpc('torch_at', a, a.timeoutSeconds || 5));

  reg('triangulate', '纯计算：多条 (x,z,yaw) 射线求交点，用于末影之眼三角定位。射线夹角可以很小（远距离目标），质量按夹角和交点在两条射线前方判断。', {    rays: z.array(z.object({ x: z.number(), z: z.number(), yaw: z.number() })).min(2).max(6),
  }, async (a) => {
    const d = (yaw) => ({ x: -Math.sin(yaw * Math.PI / 180), z: Math.cos(yaw * Math.PI / 180) });
    const d1 = d(a.rays[0].yaw);
    const d2 = d(a.rays[1].yaw);
    const det = d1.x * d2.z - d1.z * d2.x;
    const angle = Math.acos(Math.max(-1, Math.min(1, d1.x * d2.x + d1.z * d2.z))) * 180 / Math.PI;
    if (Math.abs(det) < 1e-6) {
      return { ok: false, error: 'rays are parallel', angleDeg: angle };
    }
    const px = a.rays[1].x - a.rays[0].x;
    const pz = a.rays[1].z - a.rays[0].z;
    const t = (px * d2.z - pz * d2.x) / det;
    const t2 = (px * d1.z - pz * d1.x) / det;
    if (t < 0 || t2 < 0) {
      return { ok: false, error: 'intersection is behind one of the rays', angleDeg: angle, distanceFromFirstRay: Math.round(t) };
    }
    // Angular noise dominates the error: a 1-block position error at a 1 degree angle is ~57 blocks.
    // Quality therefore only says how sharp the intersection is, not how close it is to the target.
    const quality = angle >= 10 ? 'good' : angle >= 2 ? 'fair' : 'poor';
    return {
      ok: true,
      x: Math.round(a.rays[0].x + d1.x * t),
      z: Math.round(a.rays[0].z + d1.z * t),
      distanceFromFirstRay: Math.round(t),
      distanceFromSecondRay: Math.round(t2),
      angleDeg: Math.round(angle * 100) / 100,
      quality,
      note: 'near-parallel rays are normal when the target is far; the error is roughly (position noise) / sin(angle)',
    };
  });

  reg('inventory_swap', '通过玩家背包菜单把主背包槽位与快捷栏槽位交换（服务端同步），用于把镐子/方块移到快捷栏。', {
    fromSlot: z.number().int().min(0).max(45),
    hotbarSlot: z.number().int().min(0).max(8),
    timeoutSeconds: t,
  }, async (a) => store.rpc('inventory_swap', a, a.timeoutSeconds || 5));

  reg('hotbar', '读取快捷栏 9 个槽位的物品（快速检查手上的工具/方块）。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('hotbar', a, a.timeoutSeconds || 5));
  reg('inventory_slots', 'List all 36 inventory slots with invSlot/menuSlot indices, item id, count and tool durability.', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('inventory_slots', a, a.timeoutSeconds || 5));
  reg('save_world', '保存世界（发送 /save-all）。重启客户端前必须先调用，否则强制结束进程会丢失上次自动存档之后的进度（位置/方块）。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('save_world', a, a.timeoutSeconds || 15));
  reg('resync', '强制清理客户端状态：关界面、取消所有 job、停 Baritone，并返回最新背包快照（真正的服务端同步需要重登）。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('resync', a, a.timeoutSeconds || 10));
  reg('container_open', '右键打开方块容器，并回读菜单与槽位。同步，默认 10 秒。', {    x: coord, y: coord, z: coord,
    face: z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('container_open', a, a.timeoutSeconds || 10));

  reg('container_read', '读取当前打开的容器菜单；默认只返回非空槽（省 token），full:true 返回全部槽位。标记哪些是玩家背包槽。', {
    full: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('container_read', a, a.timeoutSeconds || 10));

  reg('container_move', '点击容器槽位并回读验证。mode=quick/take/put/split/move；fromSlot 是菜单槽索引。默认只回传变化槽（changed）与非空槽；光标持物时会先放回背包再 quick。', {
    fromSlot: z.number().int().min(0),
    toSlot: z.number().int().min(0).optional(),
    mode: z.enum(['quick', 'take', 'put', 'split', 'move']).optional(),
    count: z.number().int().min(1).max(64).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('container_move', a, a.timeoutSeconds || 10));

  reg('container_close', '关闭当前容器菜单。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('container_close', a, a.timeoutSeconds || 10));

  reg('villager_trades', '读取当前村民菜单的报价（付款 A/B、结果、uses、maxUses、是否售罄）。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('villager_trades', a, a.timeoutSeconds || 10));

  reg('trade_select', '选择并执行第 index 个村民交易，times 为次数；返回 uses 前后对比。', {
    index: z.number().int().min(0),
    times: z.number().int().min(1).max(16).optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('trade_select', a, a.timeoutSeconds || 10));

  reg('screenshot', '在渲染线程抓取真实画面，返回 PNG 路径；inline=true 时同时返回图片内容（供视觉模型）。', {
    inline: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => {
    const shot = await store.rpc('screenshot', {}, a.timeoutSeconds || 5);
    if (!a.inline) return shot;
    try {
      const data = await fsp.readFile(shot.path);
      return {
        __mcpContent: true,
        content: [
          { type: 'text', text: JSON.stringify({ ok: true, ...shot }) },
          { type: 'image', data: data.toString('base64'), mimeType: 'image/png' },
        ],
      };
    } catch (e) {
      return { ...shot, inlineError: e.message };
    }
  });

  reg('jobs_list', '列出本客户端当前的 job（id/tool/state/progress）；CR 用来看"现在手里还有什么在跑"。', {}, async () =>
    store.rpc('jobs_list', {}, 10));

  // 2026-11-04 Ph8: wait_events is allowed again, but a hard cap of 10 s prevents the agent
  // from standing still for minutes. The mod's own max is 120 s; the server clamps before RPC.
  reg('wait_events', '阻塞等待游戏事件（聊天/受伤/死亡/复活），返回事件；最多 10 秒，超出按 10 秒。', {
    timeoutSeconds: z.number().int().min(0).max(120).optional(),
    limit: z.number().int().min(1).max(100).optional(),
    since: z.number().int().min(0).optional(),
  }, async (a) => {
    const requested = a.timeoutSeconds == null ? 5 : Number(a.timeoutSeconds);
    const timeoutSeconds = Math.min(10, Math.max(0, Number.isFinite(requested) ? requested : 5));
    const result = await store.rpc('wait_events', { ...a, timeoutSeconds }, Math.max(2, timeoutSeconds + 5));
    return { ...result, timeoutSeconds, capped: requested > 10 };
  });

  // 2026-11-02: the only explicit pull tool. Every tool response already carries infoTxt;
  // this exists so the CR can pull without starting/stopping anything.
  reg('query_event', '主动拉取 InfoTxt 并清零；等同一次空操作。没有事件时返回 infoTxt: []。', {}, async () => ({ ok: true }));

  reg('tool_usage', '统计 MCP 每个工具的调用次数（持久化到 workspace/runtime/tool-usage.json）。参数 limit 控制返回条数，按次数降序。', {
    limit: z.number().int().min(1).max(1000).optional(),
    timeoutSeconds: t,
  }, async (a) => usage.snapshot({ limit: a.limit }));

  reg('job_status', '查询任意异步任务的完整状态：state/progress/metrics/reason/error/result；寻路失败时附 layerFoot。', {
    jobId: z.string().min(1),
  }, async (a) => enrichPathFailure(await store.job(a.jobId)));

  reg('job_cancel', '取消任意异步任务；若已终止则返回当前状态。', {
    jobId: z.string().min(1),
  }, async (a) => store.cancel(a.jobId));

  // ---------------------------------------------------------------------------------------------
  // Ph5 T2: memory and todo list live on the SERVER (SavedData in <world>/data/zff_agent.dat), so two
  // clients - or two AIs - on the same world share them. The calls travel over the mod channel, not
  // over a port (T4); the client bridge only forwards them.
  // ---------------------------------------------------------------------------------------------
  reg('zff_status', '服务端状态（来自 mod 的 SavedData）：dist、watchdog 配置、actorId、memory/todo 数量、rev、在线玩家。', {}, async () => store.rpc('zff_status', {}, 10));

  reg('zff_todo_add', '把任务加到服务端 TodoList（所有 AI 共享同一份清单）。spec 可是字符串或 {tool,args}/{steps}。', {
    title: z.string().min(1).max(300),
    priority: z.number().int().min(0).max(9).optional(),
    spec: z.union([z.string().max(8192), z.record(z.any())]).optional(),
    note: z.string().max(2000).optional(),
  }, async (a) => store.rpc('zff_todo_add', {
    title: a.title,
    priority: a.priority === undefined ? 5 : a.priority,
    spec: a.spec === undefined ? '' : (typeof a.spec === 'string' ? a.spec : JSON.stringify(a.spec)),
    note: a.note || '',
  }, 15));

  reg('zff_todo_list', '列出服务端 TodoList 的全部任务（id/status/priority/spec/jobId/actorId）。', {}, async () => store.rpc('zff_todo_list', {}, 15));

  reg('zff_todo_done', '把服务端 TodoList 的一个任务标记为 done。', {
    id: z.string().min(1).max(80),
  }, async (a) => store.rpc('zff_todo_done', { id: a.id }, 15));

  reg('zff_todo_update', '更新服务端 TodoList 任务的状态和备注（例如 running / cancelled）。', {
    id: z.string().min(1).max(80),
    status: z.enum(['pending', 'running', 'done', 'failed', 'cancelled']).optional(),
    note: z.string().max(2000).optional(),
  }, async (a) => store.rpc('zff_todo_update', { id: a.id, status: a.status || '', note: a.note || '' }, 15));

  reg('zff_todo_clear', '清空服务端 TodoList。', {}, async () => store.rpc('zff_todo_clear', {}, 15));

  reg('memory_read', '读取 <workspace>/.mcp-agent 下的记忆文件（最大 30 KB 文本）。', {
    name: z.string().min(1).max(200),
  }, async (a) => (await memory.read(a.name)));

  reg('memory_write', '原子写入/追加记忆文件；内容上限 256 KiB；用于记录目标、坐标和验证结果。', {
    name: z.string().min(1).max(200),
    content: z.string().max(256 * 1024),
    mode: z.enum(['replace', 'append']).optional(),
  }, async (a) => memory.write(a.name, a.content, a.mode || 'replace'));

  reg('memory_list', '列出 <workspace>/.mcp-agent 下的记忆文件和大小。', {}, async () => {
    const files = await memory.list();
    return { dir: memory.dir, files, count: files.length };
  });

  reg('mc_instances', '列出实例心跳文件；默认只返回 20 秒内仍存活的实例。不会返回 token。Ph7 起每个实例带 `username`（桥接上报的游戏内名字，未进世界为 null）与 `inWorld`。', {
    includeExpired: z.boolean().optional(),
    withUsername: z.boolean().optional(),
  }, async (a) => {
    const list = a.withUsername === false
      ? (await store.listInstances(!!a.includeExpired)).map((x) => sanitizeInstance(x, x.path))
      : await store.describeInstances(!!a.includeExpired);
    return { dir: store.dir, instances: list, count: list.length };
  });

  reg('mc_attach', '手动按实例文件或 port+tokenFile 附加客户端；不会接受内联 token。', {
    instanceFile: z.string().optional(),
    port: z.number().int().min(1).max(65535).optional(),
    tokenFile: z.string().optional(),
  }, async (a) => {
    const probed = await store.attach(a);
    return { ok: true, info: { ...probed.info, port: probed.port }, instance: sanitizeInstance(probed, probed._file) };
  });

  reg('bridge_info', '读取当前桥接客户端的信息（版本、加载器、MC 版本、玩家、维度）；不含 token。', {}, async () => {
    const info = await store.info();
    return info;
  });

  // ------------------------------------------------------------------ Ph6 M1 primitives (§4)
  //
  // chat_turn / env_read / store_* are the only three things CR needs to live in the game. They are
  // deliberately small: the queue, the cursor and the flag all live here, in Node, never in the mod.

  reg('chat_turn', '说完再听：发送（可选）+ 读取未读聊天。只有 CR 调用；message 可为字符串或数组（≤5 条，逐条回复）；drain=false 只发不听且不清除 Flag_User_Chat。', {
    message: z.union([z.string().max(256), z.array(z.string().max(256)).max(5)]).optional(),
    drain: z.boolean().optional(),
    ack: z.number().int().optional(),
    gapMs: z.number().int().min(0).max(1000).optional(),
  }, async (a) => {
    const gapMs = a.gapMs == null ? 250 : a.gapMs;
    const texts = a.message == null ? [] : Array.isArray(a.message) ? a.message : [a.message];
    const sent = [];
    const failed = [];
    const duplicate = [];
    for (let i = 0; i < texts.length; i++) {
      const text = sanitizeChatText(texts[i]);
      if (!text) continue;
      // P7-19: the same text within 2 s is almost always the agent calling chat + chat_turn, or
      // repeating the array; drop it instead of spamming the player.
      if (hub.recentlySent(text)) { duplicate.push(text); continue; }
      if (i > 0 && gapMs > 0) await sleep(gapMs);
      try {
        await store.rpc('chat', { message: text }, 5);
        const [rec] = hub.recordSent([{ text, delivered: true }]);
        sent.push(rec);
      } catch (e) {
        const [rec] = hub.recordSent([{ text, delivered: false }]);
        sent.push(rec);
        failed.push({ text, error: codeOf(e), message: e && e.message ? e.message : String(e) });
      }
    }
    if (sent.length) await cr.appendSent(sent);
    if (a.drain === false) {
      // §2.8.5: a pure send never clears the flag, and it does not consume the queue either.
      return {
        sent,
        failed,
        duplicate,
        received: [],
        self: hub.self.slice(-20),
        mustReply: [],
        cursor: hub.cursor,
        flagUserChat: hub.flagUserChat,
        drained: false,
      };
    }
    hub.clearFlag('chat_turn');
    const drained = hub.drain({ ack: a.ack, replying: sent.length > 0 });
    return { sent, failed, duplicate, ...drained, drained: true };
  });

  reg('env_read', '读取 mod 常驻采样的环境 inbox（位置/血量/氧气/时间/天气/当前 job/附近/警报/staleMs）。采样在客户端 tick 里，不在 agent 里。', {
    timeoutSeconds: t,
  }, async (a) => {
    const env = await store.rpc('env_read', {}, a.timeoutSeconds || 3);
    await mirrorEnv(ctx.workspace, env);
    return env;
  });

  reg('query_env_info', '立即读取环境：声音字幕、游戏/现实时间、天气、季节（Serene Seasons 可选）。不创建 job。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('env_info', {}, a.timeoutSeconds || 3));

  reg('gui_describe', '读取当前 GUI 的 Screen/widgets/menu；widget index 用于 gui_click/gui_set。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('gui_describe', {}, a.timeoutSeconds || 3));

  reg('gui_click', '点击当前 GUI 的 widget（按 gui_describe 的 index）。', {
    index: z.number().int().min(0),
    timeoutSeconds: t,
  }, async (a) => store.rpc('gui_click', { index: a.index }, a.timeoutSeconds || 3));

  reg('gui_set', '设置当前 GUI 的 slider/editbox（按 index；slider 值 0..1）。', {
    index: z.number().int().min(0),
    value: z.number(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('gui_set', { index: a.index, value: a.value }, a.timeoutSeconds || 3));

  reg('gui_click_at', '按 GUI 缩放坐标点击当前 Screen（自绘界面的兜底）。', {
    x: coord,
    y: coord,
    timeoutSeconds: t,
  }, async (a) => store.rpc('gui_click_at', { x: a.x, y: a.y }, a.timeoutSeconds || 3));

  reg('gui_scroll', '在当前 Screen 的 GUI 坐标处滚动（自绘界面的兜底）。', {
    x: coord,
    y: coord,
    delta: z.number(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('gui_scroll', { x: a.x, y: a.y, delta: a.delta }, a.timeoutSeconds || 3));

  reg('create_probe', '读取 Create 方块实体上的 ScrollValueBehaviour（如转速控制器当前值）。', {
    x: coord, y: coord, z: coord,
    timeoutSeconds: t,
  }, async (a) => store.rpc('create_probe', a, a.timeoutSeconds || 3));

  reg('create_scroll', '直接设置 Create ScrollValueBehaviour 的值（如转速控制器），不需要打开 GUI。', {
    x: coord, y: coord, z: coord,
    value: z.number().int(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('create_scroll', a, a.timeoutSeconds || 3));

  reg('gui_close', '关闭当前 GUI。', {
    timeoutSeconds: t,
  }, async (a) => store.rpc('gui_close', {}, a.timeoutSeconds || 3));

  reg('store_get', '读 store：store=cr|mod。mod 只读（客户端写的 mod-store.json），cr 是 agent 自己的 store。', {
    store: z.enum(['cr', 'mod']),
    key: z.string().max(200).optional(),
    section: z.enum(['chat', 'env', 'inventory', 'backend']).optional(),
  }, async (a) => {
    if (a.store === 'mod') {
      const gd = await store.gameDir();
      const read = await readModStore(gd);
      if (!read.ok) return read;
      if (a.key) {
        const value = Object.prototype.hasOwnProperty.call(read.store, a.key) ? read.store[a.key] : null;
        return { ok: true, file: read.file, ageMs: read.ageMs, key: a.key, value };
      }
      if (a.section) {
        return { ok: true, file: read.file, ageMs: read.ageMs, key: a.section, value: read.store[a.section] ?? null };
      }
      return { ok: true, file: read.file, ageMs: read.ageMs, keys: Object.keys(read.store), store: read.store };
    }
    if (!a.key) return { ok: false, error: 'key_required', message: 'store=cr needs a key (use store_list to see them)' };
    const entry = await cr.get(a.key);
    return { ok: true, store: 'cr', key: a.key, found: !!entry, entry };
  });

  reg('store_set', '写 CR store（只有 cr 可写；同名 key 与 mod store 互不相干）。', {
    store: z.literal('cr').optional(),
    key: z.string().min(1).max(200),
    value: z.any(),
    tags: z.array(z.string().max(40)).max(20).optional(),
  }, async (a) => {
    const entry = await cr.set(a.key, a.value === undefined ? null : a.value, a.tags);
    return { ok: true, store: 'cr', entry };
  });

  reg('store_list', '列出 CR store 的 key（可按前缀）；CR 自主整理用。', {
    store: z.literal('cr').optional(),
    prefix: z.string().max(200).optional(),
  }, async (a) => cr.list(a.prefix));

  reg('store_delete', '删除 CR store 的一个 key。', {
    store: z.literal('cr').optional(),
    key: z.string().min(1).max(200),
  }, async (a) => ({ ok: true, store: 'cr', ...(await cr.delete(a.key)) }));

  reg('zff_chat_stats', '聊天中断诊断：unread/inflight 数量、cursor、Flag_User_Chat、规则与豁免表。不改任何状态。', {}, async () => ({
    ok: true,
    ...hub.stats(),
  }));

  reg('zff_preempt', 'CR 抢占记录（§2.3）：把 preempted 事件写进 CR store，便于复盘"为什么这个任务没做完"。', {
    jobId: z.string().max(120).optional(),
    taskId: z.string().max(120).optional(),
    tool: z.string().max(80),
    reason: z.string().max(200).optional(),
  }, async (a) => {
    const evt = { by: 'cr', jobId: a.jobId || null, taskId: a.taskId || null, tool: a.tool, reason: a.reason || null, at: Date.now() };
    await cr.recordPreempted(evt);
    return { ok: true, preempted: evt };
  });

  reg('zff_kill_client', '管理员下线（§2.6）第 2 步：先 disconnect，10 s 内没退出就结束该客户端进程。只允许作用于当前已附加实例的 gameDir。', {
    reason: z.string().max(200).optional(),
    dryRun: z.boolean().optional(),
  }, async (a) => {
    const gd = await store.gameDir();
    if (!gd) return { ok: false, error: 'no_client', message: '没有已附加的客户端实例' };
    const before = await store.listInstances(false).catch(() => []);
    let disconnected = false;
    try {
      await store.rpc('disconnect', {}, 10);
      disconnected = true;
    } catch { /* the client may already be gone */ }
    if (a.dryRun) return { ok: true, dryRun: true, gameDir: gd, disconnected, before: before.length };
    const deadline = Date.now() + 10_000;
    let alive = true;
    while (Date.now() < deadline) {
      await sleep(500);
      const now = await store.listInstances(false).catch(() => []);
      alive = now.some((x) => x.gameDir === gd);
      if (!alive) break;
    }
    let killed = false;
    let killDetail = null;
    if (alive) {
      try {
        const { execFile } = await import('node:child_process');
        const script = `$p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like '*${gd.replace(/'/g, "''")}*' } | Select-Object -First 1 -ExpandProperty ProcessId; if ($p) { Stop-Process -Id $p -Force; $p }`;
        killDetail = await new Promise((resolve) => {
          execFile('powershell', ['-NoProfile', '-Command', script], { windowsHide: true, timeout: 15_000 }, (err, stdout) => {
            resolve({ error: err ? String(err.message) : null, pid: String(stdout || '').trim() });
          });
        });
        killed = !!(killDetail && killDetail.pid);
      } catch (e) {
        killDetail = { error: String(e && e.message ? e.message : e) };
      }
    }
    const stamp = { terminated: true, at: Date.now(), reason: a.reason || null, gameDir: gd, disconnected, killed, killDetail };
    try {
      const fsp2 = await import('node:fs/promises');
      await fsp2.mkdir(path.join(ctx.workspace, 'runtime'), { recursive: true });
      await fsp2.writeFile(path.join(ctx.workspace, 'runtime', 'zff-runtime.json'), JSON.stringify(stamp, null, 2), 'utf8');
    } catch { /* the marker is advisory */ }
    return { ok: true, ...stamp };
  });

  // ------------------------------------------------------------------ Ph6 ISSUE batch (§5)
  //
  // These tools fix problems found in real play (docs/ph6/ISSUE.md). They live in the mod's
  // `IssueTools` class; `Tools.call` delegates there from its default branch, so nothing above needs to
  // change. Descriptions say what is *not* guaranteed (client-side prediction vs server confirmation).

  reg('wake', '起床（ISSUE-2）：发 STOP_SLEEPING 并逐 tick 回读 isSleeping（最多 60 tick）。不在床上时返回 note，不报错。', {}, async () =>
    store.rpc('wake', {}, 12));

  const normalizeUse = (r) => {
    // The mod used to report `consumed_early` when the client had to restart its using state; the food
    // WAS consumed, so present it as a normal success (and keep the diagnostic flag).
    if (r && r.consumed === true && r.result === 'consumed_early') {
      return { ...r, consumedEarly: true, result: 'consumed' };
    }
    return r;
  };
  reg('eat', '吃东西（ISSUE-3）：选中→按住 useItem→保持到吃完→release→回读 food/数量。物品必须带 food 组件。', {
    slot: z.number().int().min(0).max(35).optional(),
    item: z.string().max(120).optional(),
    hand: z.enum(['main', 'off']).optional(),
    maxTicks: z.number().int().min(20).max(400).optional(),
  }, async (a) => normalizeUse(await store.rpc('eat', a, 20)));

  reg('use_item', '通用「按住右键」（ISSUE-3）：弓/药水/盾/食物均可，超时强制松开。不要求 food 组件。', {
    slot: z.number().int().min(0).max(35).optional(),
    item: z.string().max(120).optional(),
    hand: z.enum(['main', 'off']).optional(),
    maxTicks: z.number().int().min(20).max(400).optional(),
  }, async (a) => normalizeUse(await store.rpc('use_item', a, 20)));

  reg('read_book', '读手上的书写成书/书与笔（ISSUE-15）：返回 title/author/pages 纯文本。不是书 → unsupported。', {
    slot: z.number().int().min(0).max(35).optional(),
    hand: z.enum(['main', 'off']).optional(),
  }, async (a) => store.rpc('read_book', a, 8));

  reg('read_sign', '读告示牌正反两面 4 行文字（ISSUE-15）。', {
    x: optCoord, y: optCoord, z: optCoord,
    pos: z.array(z.number().int()).length(3).optional(),
  }, async (a) => store.rpc('read_sign', a, 8));

  reg('item_info', '读手持/指定槽的完整 components（ISSUE-15），书面会展开成纯文本 pages。', {
    slot: z.number().int().min(0).max(35).optional(),
    hand: z.enum(['main', 'off']).optional(),
  }, async (a) => store.rpc('item_info', a, 8));

  reg('screenshot_gui', '含 GUI 覆盖层的截图（ISSUE-15）：先 flush GUI 再抓主渲染目标；文件名带 -gui。', {}, async () =>
    store.rpc('screenshot_gui', {}, 10));

  reg('walk_dir', '语义化移动（ISSUE-7）：direction=north/south/east/west（yaw 0→+Z 南，90→-X 西，180→-Z 北，270→+X 东）。返回 jobId。', {
    direction: z.enum(['north', 'south', 'east', 'west', 'n', 's', 'e', 'w', '+x', '-x', '+z', '-z']).optional(),
    yaw: z.number().optional(),
    ticks: z.number().int().min(1).max(400).optional(),
    jump: z.boolean().optional(),
    sprint: z.boolean().optional(),
    up: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('walk_dir', a, 3));

  reg('path_open_ex', 'path_open 的显式版（ISSUE-5）：默认只开门/栅栏门/活板门；openContainers:true 恢复“顺手开箱”。返回 jobId。', {
    x: coord, y: coord, z: coord,
    range: z.number().int().min(0).max(64).optional(),
    openContainers: z.boolean().optional(),
    timeoutSeconds: t,
  }, async (a) => store.rpc('path_open_ex', a, 3));

  return server;
}

/**
 * Ph6 §2.8: mirror the last `env_read` into `<workspace>/runtime/env-inbox.json`.
 *
 * The environment itself is sampled in the client and read over the bridge; this file exists so the
 * DSH plugin (a separate Node process) can tell "the game is paused / disconnected" (staleMs) without
 * speaking MCP. It is a hint, never the source of truth, so failures are ignored.
 */
let lastEnvMirrorAt = 0;
async function mirrorEnv(workspace, env) {
  const now = Date.now();
  if (now - lastEnvMirrorAt < ENV_MIRROR_MS) return;
  lastEnvMirrorAt = now;
  try {
    const dir = path.join(workspace, 'runtime');
    await fsp.mkdir(dir, { recursive: true });
    const body = { ...env, mirroredAt: now };
    const tmp = path.join(dir, 'env-inbox.json.tmp');
    await fsp.writeFile(tmp, JSON.stringify(body, null, 2), 'utf8');
    await fsp.rename(tmp, path.join(dir, 'env-inbox.json'));
  } catch { /* mirroring is best effort */ }
}

/** §2.8.1: the internal ingest endpoint. Loopback only, and it requires the shared internal token. */
async function readInternalToken(workspace) {
  const dir = path.join(workspace, 'runtime');
  const file = path.join(dir, 'mcp-internal-token');
  try {
    const token = (await fsp.readFile(file, 'utf8')).trim();
    if (token) return token;
  } catch { /* create it below */ }
  const token = crypto.randomBytes(24).toString('base64url');
  await fsp.mkdir(dir, { recursive: true });
  await fsp.writeFile(file, token, 'utf8');
  return token;
}

function startInternalServer(hub, token, workspace, ctx) {
  const httpServer = http.createServer(async (req, res) => {
    const send = (code, obj) => {
      const body = JSON.stringify(obj);
      res.writeHead(code, { 'content-type': 'application/json; charset=utf-8', 'content-length': Buffer.byteLength(body) });
      res.end(body);
    };
    try {
      const url = new URL(req.url || '/', 'http://127.0.0.1');
      if (url.pathname === '/internal/health') return send(200, { ok: true, port: INTERNAL_PORT });
      const givenEarly = req.headers['x-zff-internal'] || '';
      if (req.method === 'POST' && url.pathname === '/internal/heartbeat') {
        if (givenEarly !== token) return send(401, { ok: false, error: 'bad_token' });
        lastHeartbeatMs = Date.now();
        return send(200, { ok: true, at: lastHeartbeatMs, pid: process.pid });
      }
      const given = req.headers['x-zff-internal'] || '';
      if (given !== token) return send(401, { ok: false, error: 'bad_token' });
      if (req.method === 'POST' && url.pathname === '/internal/chat_event') {
        const body = await readJsonBody(req, 4 * 1024 * 1024);
        const events = Array.isArray(body) ? body : body && Array.isArray(body.events) ? body.events : [];
        const result = hub.ingest(events);
        // 2026-11-02: chat is an EmergencyEventList latch (InfoTxt), not an old interrupt.
        if (result.flagged) ctx.emergency.setUserChat(1);
        return send(200, { ok: true, ...result, ...{ unread: hub.unread.size } });
      }
      if (req.method === 'POST' && url.pathname === '/internal/event') {
        const body = await readJsonBody(req, 1_048_576);
        const events = Array.isArray(body) ? body : body && Array.isArray(body.events) ? body.events : body ? [body] : [];
        const results = [];
        for (const evt of events) {
          // New architecture: every event feeds EmergencyState; the old interrupt
          // engine only keeps FALL (the one thing allowed to stop_all).
          ctx.emergency.ingest(evt);
          if (String(evt?.type || evt?.kind || '') === 'fall') results.push(ctx.interrupts.ingest(evt));
        }
        return send(200, { ok: true, accepted: results.filter((r) => r.accepted).length, results, pending: ctx.interrupts.stats().pending });
      }
      if (req.method === 'GET' && url.pathname === '/internal/interrupts') {
        return send(200, ctx.interrupts.stats());
      }
      if (req.method === 'GET' && url.pathname === '/internal/state') {
        return send(200, { ok: true, ...hub.stats() });
      }
      if (req.method === 'POST' && url.pathname === '/internal/rules') {
        hub.reloadRules(true);
        return send(200, { ok: true, rules: hub.rules });
      }
      return send(404, { ok: false, error: 'unknown endpoint' });
    } catch (e) {
      return send(500, { ok: false, error: String(e && e.message ? e.message : e) });
    }
  });
  return new Promise((resolve, reject) => {
    httpServer.once('error', reject);
    httpServer.listen(INTERNAL_PORT, '127.0.0.1', () => resolve(httpServer));
  });
}

async function readJsonBody(req, limit = 1_048_576) {
  return await new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (buf) => {
      size += buf.length;
      if (size > limit) {
        reject(new BridgeError('internal', 'request body too large'));
        req.destroy();
        return;
      }
      chunks.push(buf);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve(undefined);
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { reject(new BridgeError('internal', 'invalid JSON body')); }
    });
    req.on('error', reject);
  });
}

function startEmergencyPoller({ store, emergency, log = () => {} }) {
  let busy = false;
  const tick = async () => {
    if (busy) return;
    busy = true;
    try {
      const env = await store.rpc('env_read', {}, 3);
      emergency.updateEnv(env);
      const jl = await store.rpc('jobs_list', {}, 3);
      emergency.updateJobs(jl && Array.isArray(jl.jobs) ? jl.jobs : []);
    } catch { /* no client yet — keep the previous state */ } finally { busy = false; }
  };
  const timer = setInterval(() => { void tick(); }, 500);
  timer.unref?.();
  setTimeout(() => { void tick(); }, 300).unref?.();
  return timer;
}

async function main() {
  const config = parseArgs(process.argv);
  if (config.help) {
    console.error('usage: node src/server.js [--stdio | --http --port 57610] [--state-dir <dir>] [--workspace <dir>] [--instance <file>] [--token-file <file>]');
    process.exit(0);
  }
  const store = new BridgeStore(config);
  const memory = new MemoryStore(config.workspace);
  await memory.ensure();
  // Ph6: the chat hub owns the unread queue / cursor / ack / Flag_User_Chat; the CR store is the
  // agent's own persistent store (§2.4). Both live in the MCP server, not in the mod.
  const hub = new ChatHub({ workspace: path.resolve(config.workspace) });
  await hub.ensureRulesFile();
  const cr = new CrStore(path.resolve(config.workspace));
  const interrupts = new InterruptManager({
    workspace: path.resolve(config.workspace),
    log: (m) => console.error(m),
    // §6.5: fall is the one exception - stop everything at once, the player has to save itself.
    onFall: async (evt) => {
      try { await store.cancelActiveJobs('fall'); } catch { /* best effort */ }
      try { await store.rpc('stop_all', { reason: `fall: ${evt.detail}` }, 5); } catch { /* best effort */ }
    },
  });
  const emergency = new EmergencyState({ log: (m) => console.error(m) });
  const ctx = { hub, cr, workspace: path.resolve(config.workspace), interrupts, emergency };
  startEmergencyPoller({ store, emergency, log: (m) => console.error(m) });
  if (config.instanceFile) {
    try { await store.attach({ instanceFile: config.instanceFile, tokenFile: config.tokenFile }); }
    catch (e) { console.error('manual attach failed:', e.message); }
  }

  if (!config.http) {
    const server = createServer(store, memory, ctx);
    await server.connect(new StdioServerTransport());
    server.server.onclose = () => {
      store.cancelActiveJobs('stdio closed').catch(() => { /* best effort */ });
    };
    console.error(`mcp-server stdio ready (workspace=${path.resolve(config.workspace)}, instances=${store.dir})`);
    return;
  }

  const transports = new Map();
  // Some clients (the DSH MCP client included) never send DELETE: they just drop the SSE stream.
  // Treat "stream closed and no new request for 2 s" as the end of the session so a closed agent
  // does not leave jobs running; any new request on the same session cancels the timer.
  const closeTimers = new Map();
  const bumpActivity = (sid) => {
    if (!sid) return;
    const t = closeTimers.get(sid);
    if (t) {
      clearTimeout(t);
      closeTimers.delete(sid);
    }
  };
  const httpServer = http.createServer(async (req, res) => {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (url.pathname === '/health') {
      res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('ok\n');
      return;
    }
    // Ph7: a plain HTTP view of the instance list (username included) for non-MCP consumers such as
    // the ZFF MC DSH plugin, which has no MCP client of its own. Loopback-only by construction.
    if (url.pathname === '/instances') {
      try {
        const includeExpired = url.searchParams.get('expired') === '1';
        const instances = await store.describeInstances(includeExpired);
        const body = JSON.stringify({
          ok: true,
          workspace: path.resolve(config.workspace),
          dir: store.dir,
          count: instances.length,
          instances: instances.map((i) => ({
            port: i.port,
            gameDir: i.gameDir,
            username: i.username,
            account: i.account ?? null,
            inWorld: i.inWorld,
            loader: i.loader,
            mcVersion: i.mcVersion,
            bridgeVersion: i.bridgeVersion,
            protocol: i.protocol,
            live: i.live,
            ageMs: i.ageMs,
          })),
        });
        res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
        res.end(body);
      } catch (e) {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ ok: false, error: String(e && e.message ? e.message : e) }));
      }
      return;
    }
    if (url.pathname !== '/mcp') {
      res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('not found\n');
      return;
    }
    try {
      const sessionId = req.headers['mcp-session-id'];
      if (req.method !== 'POST' || !sessionId) {
        console.error(`[http] ${req.method} ${url.pathname} session=${sessionId || '-'} known=${sessionId ? transports.has(String(sessionId)) : false}`);
      }
      bumpActivity(sessionId ? String(sessionId) : null);
      if (req.method === 'GET' && sessionId) {
        const sid = String(sessionId);
        res.on('close', () => {
          bumpActivity(sid);
          const t = setTimeout(() => {
            closeTimers.delete(sid);
            store.cancelActiveJobs('MCP session stream closed')
              .catch((e) => console.error('[cancel-active] failed:', e && e.message));
          }, 2000);
          closeTimers.set(sid, t);
        });
      }
      let transport = sessionId ? transports.get(String(sessionId)) : undefined;
      if (!transport && req.method === 'POST' && !sessionId) {
        transport = new StreamableHTTPServerTransport({
          sessionIdGenerator: () => crypto.randomUUID(),
          onsessioninitialized: (id) => transports.set(id, transport),
        });
        transport.onclose = () => {
          if (transport.sessionId) transports.delete(transport.sessionId);
          // A closed session means the agent is gone: do not leave the game working unattended.
          store.cancelActiveJobs('MCP session closed')
            .catch((e) => console.error('[cancel-active] failed:', e && e.message));
        };
        const server = createServer(store, memory, ctx);
        await server.connect(transport);
      }
      if (!transport) {
        res.writeHead(sessionId ? 404 : 400, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32000, message: sessionId ? 'unknown MCP session' : 'missing MCP session; POST initialize first' }, id: null }));
        return;
      }
      const body = req.method === 'POST' ? await readJsonBody(req) : undefined;
      await transport.handleRequest(req, res, body);
    } catch (e) {
      if (!res.headersSent) {
        res.writeHead(500, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ jsonrpc: '2.0', error: { code: -32603, message: e.message }, id: null }));
      } else {
        try { res.end(); } catch { /* ignore */ }
      }
    }
  });
  await new Promise((resolve, reject) => {
    httpServer.once('error', reject);
    httpServer.listen(config.port, '127.0.0.1', resolve);
  });
  console.error(`mcp-server http listening on 127.0.0.1:${config.port}/mcp (workspace=${path.resolve(config.workspace)}, instances=${store.dir})`);
  // Ph6 §2.8: the ingest endpoint the daemon forwards mod chat pushes to. It has to be up before the
  // first chat line arrives, so it starts together with the public one and never blocks it.
  const internalToken = await readInternalToken(path.resolve(config.workspace));
  let internalServer = null;
  try {
    internalServer = await startInternalServer(hub, internalToken, path.resolve(config.workspace), ctx);
    console.error(`mcp-server internal ingest listening on 127.0.0.1:${INTERNAL_PORT} (token in runtime/mcp-internal-token)`);
  } catch (e) {
    console.error(`mcp-server internal ingest failed to start: ${e.message} (chat interrupt will not work)`);
  }
  // ── 2026-11-01 需求 2/3/6 ────────────────────────────────────────────────
  // ① 落一份"我是谁"的状态文件：插件/状态栏靠它显示 pid、工作目录、启动方式，
  //    并且能据此把 server + daemon 一起杀掉（需求 3），而不是只按端口猜。
  const stateFile = path.join(path.resolve(config.workspace), 'runtime', 'zff-mcp-server.json');
  const stateBody = {
    pid: process.pid,
    port: config.port,
    internalPort: INTERNAL_PORT,
    workspace: path.resolve(config.workspace),
    startedAt: Date.now(),
    startedBy: config.startedBy || 'manual',
    ownerHeartbeat: config.ownerHeartbeat === true,
    exitWithoutInstanceSeconds: Number(config.exitWithoutInstanceSeconds) || 0,
    argv: process.argv.slice(2),
  };
  try {
    fs.mkdirSync(path.dirname(stateFile), { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify(stateBody, null, 2) + '\n', 'utf8');
  } catch (e) {
    console.error(`mcp-server could not write ${stateFile}: ${e.message}`);
  }
  // ② 心跳看门狗（需求 6）：DSH 关闭 → 插件停止打点 → 30 s 后自己退出，不留孤儿进程。
  let heartbeatTimer = null;
  if (config.ownerHeartbeat) {
    const timeout = Math.max(5_000, Number(config.heartbeatTimeoutMs) || 30_000);
    const startedAt = Date.now();
    heartbeatTimer = setInterval(() => {
      const now = Date.now();
      if (lastHeartbeatMs > 0) {
        if (now - lastHeartbeatMs > timeout) {
          console.error(`mcp-server: no owner heartbeat for ${now - lastHeartbeatMs} ms (DSH closed?) - exiting`);
          cleanup();
          process.exit(0);
        }
      } else if (now - startedAt > Math.max(timeout * 3, 90_000)) {
        // 插件用 --owner-heartbeat 起的，却一次心跳都没打：说明 DSH 早就没了。
        console.error('mcp-server: never received an owner heartbeat - exiting');
        cleanup();
        process.exit(0);
      }
    }, 5_000);
    heartbeatTimer.unref?.();
  }
  const cleanup = () => {
    try {
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (noInstanceTimer) clearInterval(noInstanceTimer);
      // 只删自己写的（pid 一致），避免把后来者覆盖掉
      const cur = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
      if (Number(cur?.pid) === process.pid) fs.unlinkSync(stateFile);
    } catch { /* 尽力 */ }
  };
  // 2026-11-04: 客户端关闭后不要让 server/daemon 永久占端口。只有“曾经见过实例”才开始计时，
  // 避免启动时游戏还没连上就误退。插件启动时 --exit-without-instance 60。
  let noInstanceTimer = null;
  {
    const seconds = Number(config.exitWithoutInstanceSeconds);
    if (Number.isFinite(seconds) && seconds > 0) {
      const graceMs = Math.max(10_000, seconds * 1000);
      let seenInstance = false;
      let missingSince = 0;
      noInstanceTimer = setInterval(async () => {
        let live = [];
        try { live = await store.listInstances(false); } catch { return; }
        if (live.length) {
          seenInstance = true;
          missingSince = 0;
          return;
        }
        if (!seenInstance) return;
        const now = Date.now();
        if (!missingSince) missingSince = now;
        if (now - missingSince >= graceMs) {
          console.error(`mcp-server: no live Minecraft instance for ${now - missingSince} ms - exiting`);
          try { await store.cancelActiveJobs('no-instance'); } catch { /* 尽力 */ }
          cleanup();
          httpServer.close();
          if (internalServer) internalServer.close();
          process.exit(0);
        }
      }, 5_000);
      noInstanceTimer.unref?.();
    }
  }
  const shutdown = () => {
    cleanup();
    httpServer.close();
    if (internalServer) internalServer.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  process.on('exit', cleanup);
}

main().catch((e) => {
  console.error('mcp-server fatal:', e.stack || e.message || String(e));
  process.exit(1);
});



