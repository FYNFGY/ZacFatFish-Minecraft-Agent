const D='http://127.0.0.1:57611';
async function call(t,a={},to=60000){const r=await fetch(D+'/call',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tool:t,args:a,timeoutMs:to})});return r.json();}
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
async function waitJob(id,ms=120000){const t0=Date.now();let last=null;while(Date.now()-t0<ms){await sleep(100);const r=await call('job_status',{jobId:id},10000);last=r.json;if(last&&['done','failed','cancelled'].includes(last.state))return{job:last,ms:Date.now()-t0};}return{job:last,ms:Date.now()-t0,timeout:true};}
const st=await call('get_state'); const p=st.json.player;
console.log('start', p.x, p.y, p.z);
// 1. tower 5 up with dirt
const tw=await call('tower',{blocks:5,item:'minecraft:dirt',timeoutSeconds:120},20000);
const twj=await waitJob(tw.json.jobId,150000);
console.log('tower:', twj.job.state, twj.job.reason||'', 'y=', (await call('get_state')).json.player.y);
// 2. dig a 3-step staircase down
const st2=(await call('get_state')).json.player;
const dig=await call('dig_shaft',{level:Math.floor(st2.y)-3,mode:'stair',yaw:st2.yaw,timeoutSeconds:180},20000);
const dj=await waitJob(dig.json.jobId,200000);
const after=(await call('get_state')).json.player;
console.log('dig_shaft:', dj.job.state, dj.job.reason||'', 'msg=', dj.job.message||'', 'endY=', after.y, 'blocksMined=', dj.job.metrics?.blocksMined, 'steps=', dj.job.metrics?.steps);
const fs=await import('node:fs');
fs.writeFileSync('D:/mc/MCP Development/evidence/round3-dig.json', JSON.stringify({start:[p.x,p.y,p.z],tower:twj.job,dig:dj.job,end:[after.x,after.y,after.z]},null,2));
