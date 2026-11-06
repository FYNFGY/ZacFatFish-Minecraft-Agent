import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const transport = new StdioClientTransport({
  command: 'D:\\Node\\node.exe',
  args: [
    'D:\\mc\\MCP Development\\mcp-server\\src\\server.js',
    '--stdio',
    '--state-dir',
    'D:\\mc\\MCP Development\\runtime\\state',
    '--workspace',
    'D:\\mc',
  ],
  cwd: 'D:\\mc\\MCP Development\\mcp-server',
  stderr: 'inherit',
});
const client = new Client({ name: 'stdio-smoke', version: '0.0.1' }, { capabilities: {} });
await client.connect(transport);
const tools = await client.listTools();
console.log('tools', tools.tools.length);
const res = await client.callTool({ name: 'bridge_info', arguments: {} });
console.log(res.content[0].text);
await client.close();
