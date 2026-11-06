import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const url = process.env.MCP_URL || 'http://127.0.0.1:57610/mcp';
const client = new Client({ name: 'mcp-smoke', version: '0.0.1' }, { capabilities: {} });
const transport = new StreamableHTTPClientTransport(new URL(url));
await client.connect(transport);
const tools = await client.listTools();
console.log('tools', tools.tools.length, tools.tools.map((t) => t.name).join(','));
for (const [name, args] of [
  ['bridge_info', {}],
  ['mc_instances', {}],
  ['get_state', {}],
  ['scan_area', { radius: 8, includeEntities: true }],
  ['memory_write', { name: 'smoke.md', content: '# smoke\nbridge works\n' }],
  ['memory_read', { name: 'smoke.md' }],
  ['screenshot', {}],
]) {
  const res = await client.callTool({ name, arguments: args });
  const text = res.content && res.content[0] ? res.content[0].text : JSON.stringify(res);
  console.log('---', name, res.isError ? 'ERROR' : 'OK', text.slice(0, 700));
}
await client.close();
