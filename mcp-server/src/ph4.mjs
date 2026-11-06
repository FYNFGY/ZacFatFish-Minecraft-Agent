// Ph4 helper: talk to the mc-daemon (MCP level) from the shell.
//   node src/ph4.mjs state
//   node src/ph4.mjs pstate
//   node src/ph4.mjs call <tool> '<json>' [--out file]        (or set PH4_ARGS to avoid quoting)
//   node src/ph4.mjs job <tool> '<json>' [--timeout 600] [--out file]
//   node src/ph4.mjs wait <seconds>
//   node src/ph4.mjs cmd <minecraft command without slash>
// Importable: call(tool,args), job(tool,args,timeoutSec)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const DAEMON = process.env.DAEMON || 'http://127.0.0.1:57611';
const ROOT = process.env.MC_ROOT || 'D:/mc/MCP Development';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function post(pathname, body, timeoutMs = 900000) {
  return new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request(
      DAEMON + pathname,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) },
        timeout: timeoutMs,
      },
      (res) => {
        let buf = '';
        res.setEncoding('utf8');
        res.on('data', (c) => { buf += c; });
        res.on('end', () => {
          try { resolve(JSON.parse(buf)); } catch (e) { reject(new Error('bad json: ' + buf.slice(0, 800))); }
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('daemon timeout')));
    req.on('error', reject);
    req.write(data);
    req.end();
  });
}

export async function call(tool, args = {}, timeoutMs = 900000) {
  const r = await post('/call', { tool, args, timeoutMs });
  if (!r.ok) throw new Error(`transport: ${r.error || r.text}`);
  if (r.isError) {
    const err = new Error(`tool error: ${r.text}`);
    err.payload = r.json;
    throw err;
  }
  return r.json;
}

export async function job(tool, args, timeoutSec = 600) {
  const t0 = Date.now();
  const created = await call(tool, args);
  const jobId = created.jobId;
  if (!jobId) return { created, ms: Date.now() - t0 };
  // Ore watch: while a mining job runs, poll the ore-probe stream and announce every newly found
  // ore in the game chat (the user wants a live reaction to new ores while mining).
  const watchOres = process.env.PH4_WATCH_ORES === '1';
  const seen = new Set();
  const announce = async (seedOnly = false) => {
    try {
      const log = await call('ore_log', { limit: 100 });
      for (const o of log.ores || []) {
        if (!o || !o.pos) continue;
        const key = `${o.pos.join(',')}|${o.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        if (!watchOres || seedOnly) continue;
        const name = ORE_NAMES[o.id] || o.id;
        const msg = `发现新矿石：${name} (${o.id}) @ ${o.pos.join(',')}`;
        console.log('[ORE] ' + msg);
        try { await call('chat', { message: msg }); } catch { /* chat is best effort */ }
      }
    } catch { /* ore stream is best effort */ }
  };
  await announce(true);
  let lastOrePoll = Date.now();
  let last = null;
  while (Date.now() - t0 < timeoutSec * 1000) {
    // Keep the daemon's watch armed: if this process dies between polls (user abort), the daemon
    // cancels the job after the TTL instead of leaving the game working.
    post('/watch', { jobId, ttlMs: 3500 }).catch(() => { /* non fatal */ });
    const st = await call('job_status', { jobId });
    last = st;
    if (watchOres && Date.now() - lastOrePoll > 1200) {
      lastOrePoll = Date.now();
      await announce();
    }
    if (st.state === 'done' || st.state === 'failed' || st.state === 'cancelled') {
      if (watchOres) await announce();
      return { jobId, ms: Date.now() - t0, job: st, oresSeen: [...seen] };
    }
    await sleep(400);
  }
  return { jobId, ms: Date.now() - t0, job: last, timeout: true };
}

const ORE_NAMES = {
  'minecraft:coal_ore': '煤矿',
  'minecraft:deepslate_coal_ore': '深层煤矿',
  'minecraft:iron_ore': '铁矿',
  'minecraft:deepslate_iron_ore': '深层铁矿',
  'minecraft:copper_ore': '铜矿',
  'minecraft:deepslate_copper_ore': '深层铜矿',
  'minecraft:gold_ore': '金矿',
  'minecraft:deepslate_gold_ore': '深层金矿',
  'minecraft:redstone_ore': '红石矿',
  'minecraft:deepslate_redstone_ore': '深层红石矿',
  'minecraft:lapis_ore': '青金石矿',
  'minecraft:deepslate_lapis_ore': '深层青金石矿',
  'minecraft:diamond_ore': '钻石矿',
  'minecraft:deepslate_diamond_ore': '深层钻石矿',
  'minecraft:emerald_ore': '绿宝石矿',
  'minecraft:deepslate_emerald_ore': '深层绿宝石矿',
  'minecraft:ancient_debris': '远古残骸',
  'create:zinc_ore': '锌矿',
  'create:deepslate_zinc_ore': '深层锌矿',
  'minecraft:nether_gold_ore': '下界金矿',
  'minecraft:nether_quartz_ore': '下界石英矿',
};

function save(outFile, value) {
  if (!outFile) return;
  const full = path.isAbsolute(outFile) ? outFile : path.join(ROOT, outFile);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, JSON.stringify(value, null, 2) + '\n', 'utf8');
  console.log(`[saved ${full}]`);
}

async function main() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  const outIdx = argv.indexOf('--out');
  const outFile = outIdx >= 0 ? argv[outIdx + 1] : null;
  const timeoutIdx = argv.indexOf('--timeout');
  const timeoutSec = timeoutIdx >= 0 ? Number(argv[timeoutIdx + 1]) : 600;

  if (cmd === 'state') {
    const st = await call('get_state', { compact: false });
    console.log(JSON.stringify(st));
    save(outFile, st);
  } else if (cmd === 'pstate') {
    const st = await call('get_state', { compact: false });
    const p = st.player || {};
    console.log(JSON.stringify({
      pos: [p.x, p.y, p.z], hp: p.health, food: p.food, slot: p.selectedSlot,
      main: p.mainHand && p.mainHand.id, used: p.inventory && p.inventory.usedSlots,
      free: p.inventory && p.inventory.freeSlots, gameMode: p.gameMode,
      ghosts: st.ghostCount, screen: st.screen, tools: p.toolStatus,
    }, null, 2));
  } else if (cmd === 'call') {
    const args = JSON.parse(process.env.PH4_ARGS || (argv[2] ? argv[2] : '{}'));
    const res = await call(argv[1], args);
    console.log(JSON.stringify(res));
    save(outFile, res);
  } else if (cmd === 'job') {
    const args = JSON.parse(process.env.PH4_ARGS || (argv[2] ? argv[2] : '{}'));
    const res = await job(argv[1], args, timeoutSec);
    console.log(JSON.stringify(res));
    save(outFile, res);
  } else if (cmd === 'wait') {
    await sleep(Number(argv[1] || 1) * 1000);
    console.log('waited ' + argv[1] + 's');
  } else if (cmd === 'cmd') {
    const res = await call('command', { command: argv.slice(1).join(' ') });
    console.log(JSON.stringify(res));
  } else {
    console.log('usage: state|pstate|call|job|wait|cmd');
    process.exit(2);
  }
}

const entry = process.argv[1] ? path.resolve(process.argv[1]) : '';
if (entry === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    await main();
  } catch (e) {
    console.error('ERROR: ' + (e && e.message ? e.message : e));
    if (e && e.payload) console.error(JSON.stringify(e.payload));
    process.exit(1);
  }
}
