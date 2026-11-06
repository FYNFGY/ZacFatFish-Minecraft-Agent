// Dump an ASCII slice of blocks: node src/ph4-slice.mjs <x> <z0> <z1> <y0> <y1>
import { call } from './ph4.mjs';

const [x, z0, z1, y0, y1] = process.argv.slice(2).map(Number);
const positions = [];
for (let z = z0; z <= z1; z++) for (let y = y0; y <= y1; y++) positions.push([x, y, z]);
const grid = {};
for (let i = 0; i < positions.length; i += 64) {
  const r = await call('block_query', { positions: positions.slice(i, i + 64) });
  for (const b of r.blocks || []) {
    const [bx, by, bz] = b.pos;
    grid[bz] = grid[bz] || {};
    grid[bz][by] = b.air ? '.' : '#';
  }
}
const header = '      ';
let h = header;
for (let y = y0; y <= y1; y++) h += (y % 10 === 0 ? String(Math.floor(y / 10) % 10) : ' ');
console.log(h);
for (let z = z0; z <= z1; z++) {
  let row = 'z=' + String(z).padStart(4) + ' ';
  for (let y = y0; y <= y1; y++) row += grid[z] && grid[z][y] !== undefined ? grid[z][y] : '?';
  console.log(row);
}
