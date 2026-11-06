// Ph6 M2: the daemon client, extracted from the deleted Ph5 agent runtime (`agent/core.mjs`).
//
// The Ph5 SR/CR/AR/ESRS/ESRA runtime is gone: scheduling belongs to DSH's sub-agents, chat belongs to
// the CR main agent, perception belongs to the client mod. What survives is this thin client, which the
// maintenance scripts in `scripts/` still use to call one tool through the daemon:
//
//   POST http://127.0.0.1:57611/call {tool, args, timeoutMs} -> {ok, json, text, ms}
//
// Nothing here knows anything about agents, so it is safe for the scripts and for tests.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';

export const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
export const DAEMON = process.env.MC_DAEMON || 'http://127.0.0.1:57611';
/** Where scenes/evidence/scrapbooks live; kept for the scripts that still write there. */
export const RUNTIME_DIR = path.join(ROOT, 'runtime');
export const AGENT_DIR = path.join(RUNTIME_DIR, 'agent');

export function ensureDir(dir = RUNTIME_DIR) {
  fs.mkdirSync(dir, { recursive: true });
}

/** POST /call to the daemon; returns {ok, json, text, ms, error?}. */
export function call(toolName, args = {}, timeoutMs = 60_000) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ tool: toolName, args, timeoutMs });
    const req = http.request(
      `${DAEMON}/call`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
        timeout: timeoutMs + 5_000,
      },
      (res) => {
        let buf = '';
        res.setEncoding('utf8');
        res.on('data', (c) => { buf += c; });
        res.on('end', () => {
          try { resolve(JSON.parse(buf)); } catch (e) { reject(new Error('bad daemon json: ' + buf.slice(0, 300))); }
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('daemon timeout')));
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

/** Same call, but throws on failure and unwraps the JSON payload. */
export async function tool(toolName, args = {}, timeoutMs = 60_000) {
  const r = await call(toolName, args, timeoutMs);
  if (!r || r.ok === false) throw new Error((r && r.error) || 'daemon call failed');
  return r.json !== null && r.json !== undefined ? r.json : r.text;
}

/** Reads the shared internal token the MCP server writes (chat ingest / state). */
export function internalToken() {
  try {
    return fs.readFileSync(path.join(RUNTIME_DIR, 'mcp-internal-token'), 'utf8').trim();
  } catch {
    return '';
  }
}
