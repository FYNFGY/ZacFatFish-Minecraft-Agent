import fs from 'node:fs';
import path from 'node:path';

/**
 * Per-tool call counters for the MCP server.
 *
 * Persisted so a server restart does not erase the history. Counts are incremented at dispatch
 * time, before the handler runs, so failed calls are included too.
 */
export class ToolUsage {
  constructor ({ file, log = () => {} } = {}) {
    this.file = file;
    this.log = log;
    this.startedAt = Date.now();
    this.total = 0;
    this.counts = Object.create(null);
    this.lastUsedAt = Object.create(null);
    this.load();
  }

  load () {
    if (!this.file) return;
    try {
      const raw = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      if (!raw || typeof raw !== 'object') return;
      if (raw.startedAt) this.startedAt = Number(raw.startedAt) || this.startedAt;
      if (raw.total) this.total = Number(raw.total) || 0;
      if (raw.counts && typeof raw.counts === 'object') {
        for (const [name, n] of Object.entries(raw.counts)) {
          if (typeof name === 'string' && Number.isFinite(Number(n))) {
            this.counts[name] = Math.max(0, Math.floor(Number(n)));
          }
        }
      }
      if (raw.lastUsedAt && typeof raw.lastUsedAt === 'object') {
        for (const [name, at] of Object.entries(raw.lastUsedAt)) {
          if (typeof name === 'string' && Number.isFinite(Number(at))) this.lastUsedAt[name] = Number(at);
        }
      }
    } catch {
      // Missing/corrupt file: start a fresh in-memory history.
    }
  }

  record (name) {
    const key = String(name || '').trim();
    if (!key) return;
    this.counts[key] = (this.counts[key] || 0) + 1;
    this.lastUsedAt[key] = Date.now();
    this.total += 1;
    this.save();
  }

  save () {
    if (!this.file) return;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = this.file + '.tmp';
      const body = JSON.stringify({
        version: 1,
        startedAt: this.startedAt,
        total: this.total,
        counts: this.counts,
        lastUsedAt: this.lastUsedAt,
      }, null, 2);
      fs.writeFileSync(tmp, body, 'utf8');
      fs.renameSync(tmp, this.file);
    } catch (e) {
      this.log('tool-usage save failed: ' + (e?.message || e));
    }
  }

  snapshot ({ limit = 200 } = {}) {
    const n = Math.max(1, Math.min(1000, Number(limit) || 200));
    const rows = Object.entries(this.counts)
      .map(([name, count]) => ({ name, count, lastUsedAt: this.lastUsedAt[name] || null }))
      .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
    return {
      ok: true,
      startedAt: this.startedAt,
      totalCalls: this.total,
      uniqueTools: rows.length,
      counts: rows.slice(0, n),
      truncated: rows.length > n,
      source: this.file,
    };
  }
}
