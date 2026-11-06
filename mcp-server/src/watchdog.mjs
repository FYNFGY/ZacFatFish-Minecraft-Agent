// Client watchdog: detects a dead Minecraft client (no fresh bridge heartbeat and no java process),
// archives the log tail, relaunches DeepSeekLaunch.bat and reports. Max 2 restarts per 30 minutes.
// Run: node watchdog.mjs [--launch <bat>] [--once]
import fs from 'node:fs';
import path from 'node:path';
import { exec, spawn } from 'node:child_process';

const args = process.argv.slice(2);
const LAUNCH = process.env.LAUNCH_BAT || 'D:\\SoftWare\\PCL 正式版 2.12.2\\PCL\\DeepSeekLaunch.bat';
const ROOT = process.env.ROOT || 'D:\\mc\\MCP Development';
const LOG_DIR = path.join(ROOT, 'runtime');
const CRASH_DIR = path.join(ROOT, 'runtime', 'crashes');
const LOG = path.join(LOG_DIR, 'watchdog.log');
const STATE = path.join(process.env.LOCALAPPDATA || '', 'state', 'headlessmc-mcp', 'instances');
const GAME_LOG = path.join(ROOT, 'logs', 'latest.log');
const HEARTBEAT_FRESH_MS = 45_000;
// Presence of this file means "do not relaunch the game" (the user closed it on purpose).
const STOP_FILE = process.env.WATCHDOG_STOP_FILE || 'D:/mc/MCP Development/runtime/watchdog.stop';
const CHECK_MS = 5_000;
const MAX_RESTARTS = 2;
const WINDOW_MS = 30 * 60_000;
const restarts = [];
let lastRestartAt = 0;
let lossArchived = false;

fs.mkdirSync(LOG_DIR, { recursive: true });
fs.mkdirSync(CRASH_DIR, { recursive: true });

function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}`;
  console.log(line);
  fs.appendFileSync(LOG, line + '\n');
}

function javaRunning() {
  return new Promise((resolve) => {
    exec('tasklist /FI "IMAGENAME eq java.exe" /NH', (err, stdout) => {
      if (err) return resolve(false);
      resolve(/java\.exe/i.test(stdout));
    });
  });
}

function stopped() {
  try {
    return fs.existsSync(STOP_FILE);
  } catch {
    return false;
  }
}

function newestHeartbeat() {
  try {
    const files = fs.readdirSync(STATE).filter((f) => f.endsWith('.json'));
    let newest = 0;
    for (const f of files) {
      try {
        const inst = JSON.parse(fs.readFileSync(path.join(STATE, f), 'utf8'));
        if (typeof inst.heartbeatAt === 'number' && inst.heartbeatAt > newest) newest = inst.heartbeatAt;
      } catch { /* partial file */ }
    }
    return newest;
  } catch {
    return 0;
  }
}

function archiveCrash(reason) {
  try {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    const out = path.join(CRASH_DIR, `client-${stamp}.log`);
    const tail = fs.existsSync(GAME_LOG) ? fs.readFileSync(GAME_LOG, 'utf8').split('\n').slice(-160).join('\n') : '(no game log)';
    fs.writeFileSync(out, `reason: ${reason}\n\n${tail}`, 'utf8');
    log(`crash log archived: ${out}`);
  } catch (e) {
    log(`archive failed: ${e}`);
  }
}

function restart() {
  const now = Date.now();
  while (restarts.length && now - restarts[0] > WINDOW_MS) restarts.shift();
  if (restarts.length >= MAX_RESTARTS) {
    log('restart budget exhausted (2 in 30 min); giving up, human intervention needed');
    return false;
  }
  restarts.push(now);
  try {
    const dir = path.dirname(LAUNCH);
    spawn('cmd.exe', ['/c', 'start', '""', '/D', dir, LAUNCH], { detached: true, stdio: 'ignore' }).unref();
    log(`restart #${restarts.length}: launched ${LAUNCH}`);
    return true;
  } catch (e) {
    log(`restart failed: ${e}`);
    return false;
  }
}

async function once() {
  const hb = newestHeartbeat();
  const fresh = hb > 0 && Date.now() - hb < HEARTBEAT_FRESH_MS;
  const alive = await javaRunning();
  if (fresh) { lossArchived = false; return { fresh, alive }; }
  if (alive) {
    log(`bridge heartbeat stale (${hb ? Math.round((Date.now() - hb) / 1000) + 's' : 'none'}) but java is running; waiting`);
    return { fresh, alive };
  }
  if (Date.now() - lastRestartAt < 120_000) {
    log('client is gone but a restart is still settling; waiting (grace period)');
    return { fresh, alive };
  }
  if (!lossArchived) {
    log('client is gone (no heartbeat, no java); archiving and restarting');
    archiveCrash('no heartbeat and no java process');
    lossArchived = true;
  }
  if (restart()) lastRestartAt = Date.now();
  return { fresh, alive };
}

if (args.includes('--once')) {
  const r = await once();
  log(`once: ${JSON.stringify(r)}`);
  process.exit(0);
}

log(`watchdog started (launch=${LAUNCH})`);
let lastState = 'unknown';
while (true) {
  if (stopped()) {
    log("stop file present - exiting without relaunching");
    process.exit(0);
  }
  try {
    const r = await once();
    const s = r.fresh ? 'connected' : 'lost';
    if (s !== lastState) {
      log(`state: ${s}`);
      lastState = s;
    }
  } catch (e) {
    log(`error: ${e}`);
  }
  await new Promise((r) => setTimeout(r, CHECK_MS));
}




