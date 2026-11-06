/**
 * Ph6 §2.4 — the two stores, from the Node side.
 *
 * | store | who writes            | who reads | where                                  |
 * |-------|-----------------------|-----------|----------------------------------------|
 * | cr    | the agent (store_set) | CR, SR    | `<workspace>/.mcp-agent/cr-store.json` |
 * | mod   | the mod (client tick) | agent     | `<gameDir>/zff/mod-store.json`         |
 *
 * The two are deliberately never synchronised: the same key name means two different values. The
 * agent reads the mod store explicitly with `store_get {store:"mod"}`.
 *
 * Writes are serialised through one promise chain: `chat_turn` writes `chat.sent` from inside a tool
 * call while the agent may call `store_set` at the same time, and a half-written JSON file would lose
 * the whole store.
 */
import fsp from 'node:fs/promises';
import path from 'node:path';

const EMPTY = { version: 1, updatedAt: 0, entries: {} };

export class CrStore {
  constructor(workspace) {
    this.workspace = workspace;
    this.dir = path.join(workspace, '.mcp-agent');
    this.file = path.join(this.dir, 'cr-store.json');
    this.queue = Promise.resolve();
    this.cache = null;
  }

  async load(force = false) {
    if (this.cache && !force) return this.cache;
    try {
      const raw = await fsp.readFile(this.file, 'utf8');
      const parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object' || typeof parsed.entries !== 'object') throw new Error('bad shape');
      this.cache = parsed;
    } catch {
      this.cache = { ...EMPTY, entries: {} };
    }
    return this.cache;
  }

  /** Serialised read-modify-write; returns the value that was stored. */
  async mutate(fn) {
    const run = async () => {
      const data = await this.load(true);
      const result = await fn(data);
      data.updatedAt = Date.now();
      await fsp.mkdir(this.dir, { recursive: true });
      const tmp = `${this.file}.tmp`;
      await fsp.writeFile(tmp, JSON.stringify(data, null, 2), 'utf8');
      await fsp.rename(tmp, this.file);
      this.cache = data;
      return result;
    };
    const next = this.queue.then(run, run);
    // Keep the chain alive even when one write fails.
    this.queue = next.then(() => undefined, () => undefined);
    return next;
  }

  async get(key) {
    const data = await this.load();
    const entry = data.entries[key];
    return entry ? { key, ...entry } : null;
  }

  async set(key, value, tags) {
    return this.mutate((data) => {
      const prev = data.entries[key];
      data.entries[key] = {
        value,
        tags: Array.isArray(tags) ? tags : prev && Array.isArray(prev.tags) ? prev.tags : [],
        updatedAt: Date.now(),
        version: (prev && prev.version ? prev.version : 0) + 1,
      };
      return { key, ...data.entries[key] };
    });
  }

  async delete(key) {
    return this.mutate((data) => {
      const existed = !!data.entries[key];
      delete data.entries[key];
      return { key, deleted: existed };
    });
  }

  async list(prefix) {
    const data = await this.load();
    const keys = Object.keys(data.entries)
      .filter((k) => !prefix || k.startsWith(prefix))
      .sort();
    return {
      file: this.file,
      count: keys.length,
      keys,
      entries: keys.map((k) => ({
        key: k,
        tags: data.entries[k].tags || [],
        updatedAt: data.entries[k].updatedAt || 0,
        size: JSON.stringify(data.entries[k].value || null).length,
      })),
    };
  }

  /** `chat_turn` owns `chat.sent`; the agent never writes it by hand (§4.1). */
  async appendSent(items) {
    if (!items || !items.length) return;
    await this.mutate((data) => {
      const prev = data.entries['chat.sent'];
      const list = prev && Array.isArray(prev.value) ? prev.value : [];
      list.push(...items.map((x) => ({ ts: x.ts || Date.now(), text: x.text, seq: x.seq })));
      while (list.length > 200) list.shift();
      data.entries['chat.sent'] = { value: list, tags: ['chat'], updatedAt: Date.now(), version: (prev && prev.version ? prev.version : 0) + 1 };
      return undefined;
    });
  }

  /** Preemption log (§2.3): why a task did not finish. */
  async recordPreempted(evt) {
    await this.mutate((data) => {
      const prev = data.entries['preempted'];
      const list = prev && Array.isArray(prev.value) ? prev.value : [];
      list.push(evt);
      while (list.length > 100) list.shift();
      data.entries['preempted'] = { value: list, tags: ['preempt'], updatedAt: Date.now(), version: (prev && prev.version ? prev.version : 0) + 1 };
      return undefined;
    });
  }
}

/** Reads the mod store written by the client. Read-only by contract. */
export async function readModStore(gameDir) {
  if (!gameDir) {
    return { ok: false, error: 'no_game_dir', message: 'no bridge client is attached, so the mod store path is unknown' };
  }
  const file = path.join(gameDir, 'zff', 'mod-store.json');
  try {
    const raw = await fsp.readFile(file, 'utf8');
    const parsed = JSON.parse(raw);
    return { ok: true, file, store: parsed, ageMs: Date.now() - (parsed.updatedAt || 0) };
  } catch (e) {
    return { ok: false, error: e.code === 'ENOENT' ? 'not_written_yet' : 'read_failed', file, message: String(e.message || e) };
  }
}
