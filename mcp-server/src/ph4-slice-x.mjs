// ASCII slice along X: node src/ph4-slice-x.mjs <z> <x0> <x1> <y0> <y1>
import { call } from './ph4.mjs';

const [z, x0, x1, y0, y1] = process.argv.slice(2).map(Number);
const positions = [];
for (let x = x0; x <= x1; x++) for (let y = y0; y <= y1; y++) positions.push([x, y, z]);
const grid = {};
for (let i = 0; i < positions.length; i += 64) {
  const r = await call('block_query', { positions: positions.slice(i, i + 64) });
  for (const b of r.blocks || []) {
    const [bx, by] = b.pos;
    grid[bx] = grid[bx] || {};
    grid[bx][by] = b.air ? '.' : '#';
  }
}
for (let x = x0; x <= x1; x++) {
  let row = 'x=' + String(x).padStart(5) + ' ';
  for (let y = y0; y <= y1; y++) row += grid[x] && grid[x][y] !== undefined ? grid[x][y] : '?';
  console.log(row + '  y' + y0 + '..' + y1);
}
