import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {dirname, join, resolve} from 'node:path';
import {randomBytes, timingSafeEqual} from 'node:crypto';
import os from 'node:os';

const here = dirname(fileURLToPath(import.meta.url));
const finite = (v, lo, hi) => typeof v === 'number' && Number.isFinite(v) && v >= lo && v <= hi;
const id = v => typeof v === 'string' && /^[a-zA-Z0-9:_-]{1,100}$/.test(v);
const text = (v, max=100) => typeof v === 'string' ? v.slice(0,max) : '';

export function validatePacket(p) {
  if (!p || p.schema !== 1 || !id(p.nodeId) || !id(p.sessionId) || !Number.isSafeInteger(p.seq) || p.seq < 0) throw Error('Invalid identity / schema / sequence');
  if (!finite(p.sentAt, 1, 1e15) || !['LIVE','DEMO','REPLAY'].includes(p.source)) throw Error('Invalid time / source');
  let pose=null;
  if(p.pose != null) {
    const q=p.pose;
    if(!finite(q.lat,-90,90)||!finite(q.lon,-180,180)||!finite(q.ageMs,0,86400000)) throw Error('Invalid position');
    for(const [k,lo,hi] of [['headingDeg',0,360],['speedMps',0,100],['accuracyM',0,10000],['decelerationMps2',-100,100]]) if(q[k]!=null&&!finite(q[k],lo,hi)) throw Error('Invalid '+k);
    pose={lat:q.lat,lon:q.lon,ageMs:q.ageMs,headingDeg:q.headingDeg??null,speedMps:q.speedMps??null,accuracyM:q.accuracyM??null,headingSource:text(q.headingSource,30),decelerationMps2:q.decelerationMps2??null,slowing:q.slowing===true};
  }
  if(!Array.isArray(p.events)||p.events.length>32||!Array.isArray(p.chain)||p.chain.length>100) throw Error('Invalid event / chain bounds');
  const events=p.events.map(e=>{
    if(!id(e.id)||!id(e.origin)||!finite(e.ageMs,0,86400000)) throw Error('Invalid event');
    return {id:e.id,kind:text(e.kind,40),origin:e.origin,via:text(e.via,100),ageMs:e.ageMs,provenance:text(e.provenance,20)||'UNKNOWN'};
  });
  return {schema:1,nodeId:p.nodeId,sessionId:p.sessionId,seq:p.seq,sentAt:p.sentAt,source:p.source,
    role:text(p.role,30),rank:Number.isInteger(p.rank)?p.rank:-1,meshRunning:p.meshRunning===true,
    pose,events,chain:p.chain.filter(c=>id(c.id)).map(c=>({id:c.id,rank:Number.isInteger(c.rank)?c.rank:-1,role:text(c.role,30)}))};
}

export function createDesk({token=randomBytes(18).toString('hex'),clock=Date.now}={}) {
  const nodes=new Map(), events=new Map(), history=[], retired=new Map();
  const auth=req=>{
    const got=Buffer.from((req.headers.authorization||'').replace(/^Bearer /,'')), want=Buffer.from(token);
    return got.length===want.length && timingSafeEqual(got,want);
  };
  const send=(res,status,value)=>{res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));};
  const ingest=p=>{
    const now=clock(); const old=nodes.get(p.nodeId);
    if(old && old.sessionId===p.sessionId && p.seq<=old.seq) return false;
    const retiredKey=p.nodeId+':'+p.sessionId;
    if(retired.has(retiredKey)) return false;
    if(old && old.sessionId!==p.sessionId) retired.set(p.nodeId+':'+old.sessionId,now);
    for(const [k,t] of retired) if(now-t>300000) retired.delete(k);
    while(retired.size>2000) retired.delete(retired.keys().next().value);
    const node={...p,receivedAt:now,clockSkewMs:now-p.sentAt}; nodes.set(p.nodeId,node);
    for(const [k,n] of nodes) if(now-n.receivedAt>60000) nodes.delete(k);
    while(nodes.size>200) nodes.delete(nodes.keys().next().value);
    for(const e of p.events) {
      // One event across relays, but retain the reporting node observation.
      if(!events.has(e.id)) events.set(e.id,{...e,receivedAt:now,reportedBy:p.nodeId,eventAt:now-e.ageMs,source:p.source});
    }
    while(events.size>200) events.delete(events.keys().next().value);
    history.push({receivedAt:now,packet:p});if(history.length>3000)history.shift();
    return true;
  };
  const server=http.createServer(async(req,res)=>{
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'");
    const url=new URL(req.url,'http://localhost');
    try {
      if(url.pathname.startsWith('/api/')){
        if(!auth(req))return send(res,401,{error:'Pairing token required'});
        // Browser writes are same-origin. Native Android POST has no Origin header.
        if(req.headers.origin && req.headers.origin!==`http://${req.headers.host}`)return send(res,403,{error:'Cross-origin request rejected'});
        if(req.method==='GET'&&url.pathname==='/api/state')return send(res,200,{schema:1,now:clock(),nodes:[...nodes.values()],events:[...events.values()].slice(-60)});
        if(req.method==='GET'&&url.pathname==='/api/recording')return send(res,200,{schema:1,type:'resilane-telemetry-recording',frames:history});
        if(req.method==='POST'&&url.pathname==='/api/telemetry'){
          if(req.headers['content-type']?.split(';')[0]!=='application/json')return send(res,415,{error:'JSON required'});
          let body='',bytes=0;
          for await(const chunk of req){bytes+=chunk.length;if(bytes>65536){send(res,413,{error:'Packet too large'});req.destroy();return;}body+=chunk;}
          const p=validatePacket(JSON.parse(body));return send(res,200,{accepted:ingest(p)});
        }
        return send(res,404,{error:'Unknown endpoint'});
      }
      if(req.method!=='GET')return send(res,405,{error:'GET only'});
      if(url.pathname==='/favicon.ico'){res.writeHead(204);res.end();return;}
      const files={'/':['index.html','text/html'],'/app.js':['app.js','text/javascript'],'/style.css':['style.css','text/css']};
      if(url.pathname==='/saira.ttf'){
        const f=join(here,'../../app/src/main/res/font/saira.ttf');res.writeHead(200,{'Content-Type':'font/ttf'});res.end(await readFile(f));return;
      }
      const file=files[url.pathname];if(!file)return send(res,404,{error:'Not found'});
      const content=await readFile(join(here,file[0]));res.writeHead(200,{'Content-Type':file[1]});res.end(content);
    } catch(e){if(!res.headersSent)send(res,400,{error:text(e.message)});else res.end();}
  });
  server.requestTimeout=5000;server.headersTimeout=10000;
  return {server,token,ingest,nodes};
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  const port=Number(process.env.MESH_DESK_PORT||8787),host=process.env.MESH_DESK_HOST||'0.0.0.0';
  const {server,token}=createDesk({token:process.env.MESH_DESK_TOKEN||undefined});
  server.listen(port,host,()=>{
    console.log(`RESILANE Mesh Desk: http://localhost:${port}`);
    for(const values of Object.values(os.networkInterfaces()))for(const n of values||[])if(n.family==='IPv4'&&!n.internal)console.log(`Phone laptop address: ${n.address}:${port}`);
    console.log(`Pairing token: ${token}\nTrusted local Wi-Fi only; telemetry uses HTTP. Close this process to stop sharing.`);
  });
}
