// MCP-level tool caller (authentic MCP streamable-http session, not raw RPC).
// Lives in mcp-server/src so Node resolves the SDK from mcp-server/node_modules.
// Usage:
//   node src/mcp-call.mjs --list
//   node src/mcp-call.mjs <tool> '<json args>' [--out <file>]
// Env: MCP_URL (default http://127.0.0.1:57610/mcp), MC_PRINT (chars printed, default 4000)
import fs from 'node:fs';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const url = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp';
const argv = process.argv.slice(2);
const client = new Client({ name: 'dsa-probe', version: '0.0.1' }, { capabilities: {} });
await client.connect(new StreamableHTTPClientTransport(new URL(url)));

if (argv[0] === '--list') {
  const tools = await client.listTools();
  console.log('tools=' + tools.tools.length);
  console.log(tools.tools.map((t) => t.name).join(','));
  await client.close();
  process.exit(0);
}

const tool = argv[0];
let args = {};
// Prefer MC_ARGS (avoids PowerShell 5.1 mangling quotes in native-process args).
const rawArgs = process.env.MC_ARGS || (argv[1] && !argv[1].startsWith('--') ? argv[1] : '{}');
args = JSON.parse(rawArgs);
const outIdx = argv.indexOf('--out');
const outFile = outIdx >= 0 ? argv[outIdx + 1] : null;

const t0 = Date.now();
let res;
try {
  res = await client.callTool({ name: tool, arguments: args }, undefined, { timeout: Number(process.env.MC_TIMEOUT || 120000) });
} catch (e) {
  console.log(`tool=${tool} ms=${Date.now() - t0} TRANSPORT_ERROR`);
  console.log(String(e && e.message ? e.message : e));
  await client.close();
  process.exit(3);
}
const ms = Date.now() - t0;
const texts = (res.content || []).filter((c) => c.type === 'text').map((c) => c.text);
const images = (res.content || []).filter((c) => c.type === 'image').length;
const payload = texts.join('\n');
if (outFile) {
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.writeFileSync(outFile, payload, 'utf8');
  console.log(`[saved ${Buffer.byteLength(payload)} bytes -> ${outFile}]`);
}
console.log(`tool=${tool} ms=${ms} isError=${!!res.isError} textBytes=${Buffer.byteLength(payload)} images=${images}`);
const limit = Number(process.env.MC_PRINT || 4000);
console.log(payload.length > limit ? payload.slice(0, limit) + `\n...[truncated ${payload.length - limit} chars]` : payload);
await client.close();
