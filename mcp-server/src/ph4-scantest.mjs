// Phase-1 P1-1 acceptance helper: run a connected-surface scan and check which marker blocks are
// visible. Usage: node src/ph4-scantest.mjs [out.json]
import fs from 'node:fs';
import path from 'node:path';
import { call } from './ph4.mjs';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const outFile = process.argv[2] || 'evidence/ph4-p1-scan-connected.json';

const scan = async (label, args) => {
  const t0 = Date.now();
  const r = await call('scan_connected', args);
  const height = new Map();
  for (const h of r.heightMap || []) height.set(`${h[0]},${h[1]}`, { y: h[2], id: h[3] });
  const surface = new Map();
  for (const s of r.surface || []) surface.set(`${s[0]},${s[1]},${s[2]}`, { id: s[3], faces: s[4] });
  return {
    label, ms: Date.now() - t0, openCells: r.openCells, surfaceCount: r.surfaceCount,
    truncated: r.truncated, bytes: r.bytes,
    heightMap: height, surface, raw: r,
  };
};

const hasMarker = (res, x, y, z) => {
  const h = res.heightMap.get(`${x},${z}`);
  const s = res.surface.get(`${x},${y},${z}`);
  return {
    inHeightMap: !!(h && h.y === y), heightMapId: h ? h.id : null,
    inSurface: !!s, surfaceId: s ? s.id : null,
  };
};

const doorScan = await scan('door-open', { center: [1461, 62, -540], radius: 24, heightRadius: 24, includeEntities: false });
const redA = hasMarker(doorScan, 1458, 59, -543);
const blueA = hasMarker(doorScan, 1466, 59, -543);

const out = {
  startedAt: new Date().toISOString(),
  doorScan: { ms: doorScan.ms, openCells: doorScan.openCells, surfaceCount: doorScan.surfaceCount, truncated: doorScan.truncated },
  roomB_red_wool: redA,
  roomC_blue_wool: blueA,
};
console.log(JSON.stringify(out, null, 2));
const full = path.join(ROOT, outFile);
fs.mkdirSync(path.dirname(full), { recursive: true });
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log('[saved ' + full + ']');
console.log('door test: B visible=' + redA.inHeightMap + ' C visible=' + blueA.inHeightMap);
