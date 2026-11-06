// Combine the two eye throws + triangulation + /locate reference into one evidence file.
import fs from 'node:fs';
import path from 'node:path';

const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';
const read = (p) => JSON.parse(fs.readFileSync(path.join(ROOT, p), 'utf8'));
const t1 = read('evidence/ph4-p1-eye-throw1.json').job.result;
const t2 = read('evidence/ph4-p1-eye-throw2.json').job.result;
const tri = read('evidence/ph4-p1-eye-triangulate.json');
const located = { x: 0, z: 0, distanceBlocks: 1609, note: '/locate structure minecraft:stronghold reported [0, ~, 0] (1609 blocks) - used only to measure the error' };
const error = Math.round(Math.hypot(tri.x - located.x, tri.z - located.z) * 100) / 100;
const baseline = Math.round(Math.hypot(t2.throwFromX - t1.throwFromX, t2.throwFromZ - t1.throwFromZ) * 10) / 10;
const out = {
  startedAt: new Date().toISOString(),
  throw1: { from: [t1.throwFromX, t1.throwFromZ], bearing: t1.bearing, trackedTicks: t1.trackedTicks, eyeConsumed: t1.eyeConsumed, pickedUp: t1.pickedUp, note: 'the eye broke (20% vanilla chance), no item to pick up' },
  throw2: { from: [t2.throwFromX, t2.throwFromZ], bearing: t2.bearing, trackedTicks: t2.trackedTicks, eyeConsumed: t2.eyeConsumed, pickedUp: t2.pickedUp },
  baselineBlocks: baseline,
  triangulation: tri,
  reference: located,
  errorBlocks: error,
  pass: tri.ok === true && error < 30,
  note: 'one throw per spot, 46.5 blocks apart; both bearings differ by only 1.64 deg because the target is ~1.6 km away - that is expected, not a tracking failure',
};
const full = path.join(ROOT, 'evidence/ph4-p1-eye-protocol.json');
fs.writeFileSync(full, JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ baseline, estimate: [tri.x, tri.z], located: [located.x, located.z], errorBlocks: error, pass: out.pass }, null, 2));
