// Test: the daemon cancels the job when the caller disappears mid-poll (user abort of the agent).
// Simulates what happens when DSH kills the pwsh/node process that is polling job_status.
//
//   node test/daemon-abort.mjs
//
// Writes evidence/daemon-abort.json. Exit 0 = pass.
import fs from 'node:fs';
import path from 'node:path';

const DAEMON = process.env.MC_DAEMON || 'http://127.0.0.1:57611';
const OUT = path.resolve(process.env.MC_ROOT || 'D:/mc/MCP Development', 'evidence', 'daemon-abort.json');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(tool, args, timeoutMs = 20000, signal) {
  const res = await fetch(`${DAEMON}/call`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ tool, args, timeoutMs }),
    signal,
  });
  return res.json();
}

const report = { test: 'daemon cancels the job when the caller aborts', at: new Date().toISOString() };
let failed = false;
try {
  const started = await call('walk', { yaw: 0, ticks: 400 });
  const jobId = started.json && started.json.jobId;
  report.jobId = jobId;
  if (!jobId) throw new Error('walk did not return a jobId: ' + JSON.stringify(started).slice(0, 200));

  // arm the daemon watch exactly like ph4.mjs's job() does, then die mid-poll
  await fetch(`${DAEMON}/watch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jobId, ttlMs: 3500 }),
  });
  // poll once like ph4.mjs does, then drop the connection in the middle of a poll
  await call('job_status', { jobId });
  const ac = new AbortController();
  const pending = call('job_status', { jobId }, 20000, ac.signal).catch((e) => (e && e.name) || 'aborted');
  await sleep(250);
  ac.abort();
  report.abortedAt = Date.now();
  await pending;
  await sleep(5000);

  const after = await call('job_status', { jobId });
  const st = (after.json && after.json.state) || (after.json && after.json.job && after.json.job.state) || after.state;
  report.stateAfterAbort = st;
  report.raw = after.json;
  if (st !== 'cancelled') {
    failed = true;
    report.error = `job state is ${st}, expected cancelled`;
  }
} catch (e) {
  failed = true;
  report.error = e.message;
}
report.pass = !failed;
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ pass: report.pass, jobId: report.jobId, state: report.stateAfterAbort, error: report.error }));
process.exit(failed ? 1 : 0);
