// Dependency-free perspective renderer. World positions are meters: east X, up Y, north -Z.
const $=id=>document.getElementById(id), canvas=$('scene'),ctx=canvas.getContext('2d');
let mode='demo',paused=false,selected=null,nodes=[],events=[],origin=null,elapsed=0,last=performance.now(),lastUI=0,followFleet=true;
let replay=null,replayIndex=0,localFrames=[],demoCycle=-1,demoSeen=new Set(),serverNow=Date.now(),lastPoll=0,polling=false;
const visual=new Map(),colors={OBSERVER:'#f0b81c',RELAY:'#4f7fe8',RECEIVER:'#2bd67b'};
const cam={yaw:.57,pitch:.8,radius:85,x:0,z:0};let width=800,height=500,projections=[];
const reduced=matchMedia('(prefers-reduced-motion: reduce)');let noMotion=reduced.matches;
reduced.addEventListener('change',e=>{noMotion=e.matches});
const clamp=(n,a,b)=>Math.min(b,Math.max(a,n)), nfmt=n=>n==null?'—':n.toFixed(1);
const color=n=>colors[n.role]||'#a06be0';
function node(id,tag,text){const el=document.createElement(tag);if(id)el.className=id;if(text!=null)el.textContent=text;return el}
function enu(p){if(!origin)origin={lat:p.lat,lon:p.lon};return {x:(p.lon-origin.lon)*111320*Math.cos(origin.lat*Math.PI/180),z:-(p.lat-origin.lat)*111320}}
function age(n){return Math.max(0,serverNow-n.receivedAt)}
function setMode(next){mode=next;elapsed=0;selected=null;followFleet=true;paused=false;$('pause').textContent='Pause display';origin=null;visual.clear();nodes=[];events=[];demoSeen.clear();demoCycle=-1;localFrames=[];
  $('demo').classList.toggle('active',next==='demo');$('live').classList.toggle('active',next==='live');
  $('seek').disabled=next!=='replay';$('pairing').open=next==='live';
  $('scene-source').textContent=next==='demo'?'SYNTHETIC REHEARSAL':next==='replay'?'RECORDED SESSION':'LIVE PHONE TELEMETRY';
  $('status').textContent=next==='demo'?'DEMO · synthetic positions':next==='replay'?'REPLAY · recorded data':'LIVE · pairing required';
  $('hint').textContent=next==='demo'?'Demo: A brakes, B passes, C follows. No real phone is connected.':next==='replay'?'Recorded telemetry; drag the timeline to seek.':'Phone GPS positions are approximate. Stale nodes freeze and fade.';
}
$('demo').onclick=()=>setMode('demo');$('live').onclick=()=>{setMode('live');poll()};
$('connect').onclick=()=>{sessionStorage.setItem('mesh-token',$('token').value.trim());setMode('live');poll()};
$('token').value=sessionStorage.getItem('mesh-token')||'';
$('pause').onclick=()=>{paused=!paused;$('pause').textContent=paused?'Resume display':'Pause display'};
$('top').onclick=()=>{cam.pitch=1.52;cam.yaw=0};
$('reset').onclick=fit;
function fit(){const ps=nodes.filter(n=>n.pose).map(n=>enu(n.pose));if(!ps.length)return;cam.x=ps.reduce((s,p)=>s+p.x,0)/ps.length;cam.z=ps.reduce((s,p)=>s+p.z,0)/ps.length;cam.radius=clamp(Math.max(55,...ps.map(p=>Math.hypot(p.x-cam.x,p.z-cam.z)*3.2)),25,3000);selected=null;followFleet=true;cam.pitch=.85;cam.yaw=.57}
async function poll(){if(mode!=='live'||polling||paused)return;const token=sessionStorage.getItem('mesh-token');if(!token)return;
  polling=true;
  try{const r=await fetch('/api/state',{headers:{Authorization:'Bearer '+token},signal:AbortSignal.timeout(2500)});if(!r.ok){if(r.status===401)sessionStorage.removeItem('mesh-token');throw Error(r.status===401?'Pairing token rejected':'Bridge error '+r.status)}const data=await r.json();serverNow=data.now;nodes=data.nodes;events=data.events;
    $('status').textContent=`LIVE · ${nodes.filter(n=>age(n)<3000).length} phones reporting`;
  }catch(e){$('status').textContent='LIVE · '+e.message;serverNow=Date.now();lastPoll=performance.now()+1250}finally{polling=false}
}
function demo(dt){const t=elapsed%32,cycle=Math.floor(elapsed/32);serverNow=Date.now();if(cycle!==demoCycle){demoSeen.clear();events=[];demoCycle=cycle;visual.clear()}
  const pos=[{id:'A',role:'OBSERVER',east:0,north:t<12?t*2.2:26.4+(t-12)*.45,speed:t<12?2.2:.45},
    {id:'B',role:'RELAY',east:t>9&&t<24?3.5*Math.min(1,(t-9)/3,(24-t)/3):0,north:t*2-15+Math.max(0,t-12)*.6,speed:t>12?2.6:2},
    {id:'C',role:'RECEIVER',east:0,north:t*1.7-28,speed:1.7}];
  const add=(key,kind,who)=>{if(demoSeen.has(key))return;demoSeen.add(key);events.push({id:`demo:${cycle}:${key}`,kind,origin:who,via:who,reportedBy:who,eventAt:serverNow,receivedAt:serverNow,source:'DEMO',provenance:'DEMO'})};
  if(t>=12)add('brake','BRAKING','A');if(t>=20.2)add('pass','OVERTAKE_DEMO','B');if(t>=13)add('relay','RELAY_OBSERVED','B');
  nodes=pos.map((p,i)=>({schema:1,nodeId:p.id,sessionId:'demo',seq:Math.floor(elapsed*4),sentAt:serverNow,receivedAt:serverNow,source:'DEMO',role:p.role,rank:i,meshRunning:true,
    pose:{lat:12.9716+p.north/111320,lon:77.5946+p.east/(111320*Math.cos(12.9716*Math.PI/180)),headingDeg:0,speedMps:p.speed,accuracyM:0,ageMs:0,headingSource:'synthetic',slowing:p.id==='A'&&t>=12&&t<16},chain:pos.map((p,i)=>({id:p.id,rank:i})),events:events.map(e=>({...e,ageMs:serverNow-e.eventAt}))}));
  if(dt && Math.floor(elapsed*4)!==Math.floor((elapsed-dt)*4))for(const n of nodes)localFrames.push({receivedAt:serverNow,packet:n});
  if(localFrames.length>3000)localFrames.splice(0,localFrames.length-3000);
  if(elapsed<.1)fit();
}
function playReplay(){if(!replay?.length)return;serverNow=Date.now();const t=replay[0].receivedAt+elapsed*1000,map=new Map(),es=new Map();
  // Bounded recordings (3k frames), rebuild on seek to preserve disconnects and event age.
  for(const f of replay){if(f.receivedAt>t)break;const p=f.packet;map.set(p.nodeId,{...p,receivedAt:serverNow-(t-f.receivedAt)});
    for(const e of p.events||[])if(!es.has(e.id))es.set(e.id,{...e,reportedBy:p.nodeId,source:p.source,eventAt:serverNow-(t-f.receivedAt)-e.ageMs,receivedAt:serverNow-(t-f.receivedAt)});
  }nodes=[...map.values()];events=[...es.values()].slice(-60);const duration=(replay.at(-1).receivedAt-replay[0].receivedAt)/1000;
  $('seek').value=duration?elapsed/duration*100:0;if(elapsed>=duration){elapsed=duration;paused=true;$('pause').textContent='Resume display'}
}
$('seek').oninput=()=>{if(replay){elapsed=Number($('seek').value)/100*(replay.at(-1).receivedAt-replay[0].receivedAt)/1000;visual.clear();playReplay();renderUI()}};
$('replay').onchange=async e=>{try{const file=e.target.files[0];if(!file)return;if(file.size>25e6)throw Error('Replay limit is 25 MB');const data=JSON.parse(await file.text());
  if(data.type!=='resilane-telemetry-recording'||!Array.isArray(data.frames)||!data.frames.length||data.frames.length>3000)throw Error('Use a Mesh Desk session recording. Phone hazard-only exports do not contain a continuous pose trace.');
  for(const f of data.frames){const p=f.packet;if(!Number.isFinite(f.receivedAt)||!p||typeof p.nodeId!=='string'||typeof p.role!=='string'||!['LIVE','DEMO','REPLAY'].includes(p.source)||!Array.isArray(p.events)||p.events.length>32||!Array.isArray(p.chain)||p.chain.length>100)throw Error('Invalid replay frame');
    if(p.events.some(e=>!e||typeof e.id!=='string'||typeof e.kind!=='string'||typeof e.origin!=='string'||!Number.isFinite(e.ageMs)||e.ageMs<0||e.ageMs>86400000)||p.chain.some(c=>!c||typeof c.id!=='string'))throw Error('Invalid replay events or chain');
    if(p.pose){const q=p.pose;if(!Number.isFinite(q.lat)||!Number.isFinite(q.lon)||Math.abs(q.lat)>90||Math.abs(q.lon)>180||!Number.isFinite(q.ageMs)||q.ageMs<0)throw Error('Invalid replay position');for(const [key,min,max] of [['headingDeg',0,360],['speedMps',0,100],['accuracyM',0,10000],['decelerationMps2',-100,100]])if(q[key]!=null&&(!Number.isFinite(q[key])||q[key]<min||q[key]>max))throw Error('Invalid replay '+key)}
  }
  replay=data.frames.sort((a,b)=>a.receivedAt-b.receivedAt);setMode('replay');paused=false;$('pause').textContent='Pause display';playReplay();fit();
}catch(err){$('status').textContent=err.message}finally{e.target.value=''}};
$('export').onclick=async()=>{try{let data;if(mode==='live'){const r=await fetch('/api/recording',{headers:{Authorization:'Bearer '+(sessionStorage.getItem('mesh-token')||'')}});if(!r.ok)throw Error('Connect viewer before export');data=await r.json()}else data={schema:1,type:'resilane-telemetry-recording',frames:mode==='replay'?replay:localFrames};
  const url=URL.createObjectURL(new Blob([JSON.stringify(data)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=`resilane-${mode}-${Date.now()}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000);
}catch(e){$('status').textContent=e.message}};

function renderUI(){
  $('count').textContent=nodes.filter(n=>n.pose&&age(n)<10000).length;$('meshcount').textContent=nodes.filter(n=>n.meshRunning&&age(n)<10000).length;
  $('elapsed').textContent=`${String(Math.floor(elapsed/60)).padStart(2,'0')}:${String(Math.floor(elapsed%60)).padStart(2,'0')}`;
  $('empty').hidden=nodes.some(n=>n.pose&&age(n)<60000);
  $('empty').replaceChildren(node('','span',nodes.length?'Waiting for a fresh phone position.':'Waiting for paired phones.'),node('','small',nodes.length?'Check GPS reception, precise location permission and mesh state.':'Start the mesh, then enable Mesh Desk on each phone.'));
  $('nodes').replaceChildren();
  for(const n of nodes){const stale=age(n)>3000,b=node('node'+(selected===n.nodeId?' selected':'')+(stale?' stale':''),'button');b.style.borderLeftColor=color(n);b.onclick=()=>{selected=selected===n.nodeId?null:n.nodeId;renderUI()};
    const title=node('node-title','div');title.append(node('', 'span',`Node ${n.nodeId.slice(-8).toUpperCase()}`),node('', 'span',stale?'STALE':mode==='demo'?'DEMO':'REPORTING'));b.append(title);
    const info=node('node-info','div');info.append(node('','span',n.role),node('','span',n.pose?.speedMps==null?'— km/h':(n.pose.speedMps*3.6).toFixed(1)+' km/h'));b.append(info);
    const heading=n.pose?.headingDeg==null?'unknown heading':Math.round(n.pose.headingDeg)+'° '+(n.pose.headingSource||'heading');
    b.append(node('node-meta','div',`${n.source} · ${heading}`),node('node-meta','div',`${n.pose?.accuracyM==null?'accuracy unknown':'±'+nfmt(n.pose.accuracyM)+' m'} · seen ${(age(n)/1000).toFixed(1)}s ago`),node('node-meta','div',`Bluetooth mesh ${n.meshRunning?'running':'stopped'} · desk ${stale?'stale':'connected'}`));$('nodes').append(b);
  }
  if(!nodes.length)$('nodes').append(node('aside-note','p','No phone telemetry in this session.'));
  const recent=events.slice(-12).reverse();$('events').replaceChildren();$('eventcount').textContent=events.length;
  for(const e of recent){const item=node('event'+(e.kind==='BRAKING'?' brake':''),'div');item.append(node('','strong',e.kind.replaceAll('_',' ')),node('','small',`Origin ${e.origin} · via ${e.via||e.reportedBy} · ${e.provenance||'UNKNOWN'}`),node('','small',`${Math.max(0,(serverNow-e.eventAt)/1000).toFixed(0)}s ago · ${e.source}`));$('events').append(item)}
  if(!recent.length)$('events').append(node('aside-note','p','Waiting for road events.'));
}

// Perspective projection and painter-sorted geometry: works offline without a GPU library.
function project(x,y,z){const dx=x-cam.x,dz=z-cam.z,sy=Math.sin(cam.yaw),cy=Math.cos(cam.yaw),sp=Math.sin(cam.pitch),cp=Math.cos(cam.pitch);
  const vx=dx*cy-dz*sy,vy=-dx*sy*sp+y*cp-dz*cy*sp,depth=cam.radius-(dx*sy*cp+y*sp+dz*cy*cp);if(depth<1)return null;const s=Math.min(width,height)*1.18/depth;return {x:width/2+vx*s,y:height*.46-vy*s,d:depth,s}}
function stroke3(points,color,lineWidth=1,dash=[]){const ps=points.map(p=>project(...p));if(ps.some(p=>!p))return;ctx.beginPath();ps.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y));ctx.strokeStyle=color;ctx.lineWidth=lineWidth;ctx.setLineDash(dash);ctx.stroke();ctx.setLineDash([])}
function circle3(x,z,r,c){stroke3(Array.from({length:41},(_,i)=>[x+r*Math.cos(i/40*2*Math.PI),.03,z+r*Math.sin(i/40*2*Math.PI)]),c,1,[3,4])}
function draw(){ctx.clearRect(0,0,width,height);ctx.fillStyle='#101212';ctx.fillRect(0,0,width,height);projections=[];
  if(followFleet&&!selected&&!paused&&!noMotion){const ps=nodes.filter(n=>n.pose&&age(n)<10000).map(n=>enu(n.pose));if(ps.length){cam.x+=(ps.reduce((s,p)=>s+p.x,0)/ps.length-cam.x)*.05;cam.z+=(ps.reduce((s,p)=>s+p.z,0)/ps.length-cam.z)*.05}}
  const extent=Math.min(500,cam.radius*1.5),step=cam.radius>200?20:5,cx=Math.floor(cam.x/step)*step,cz=Math.floor(cam.z/step)*step;
  for(let i=-extent;i<=extent;i+=step){stroke3([[cx+i,0,cz-extent],[cx+i,0,cz+extent]],'#222727');stroke3([[cx-extent,0,cz+i],[cx+extent,0,cz+i]],'#222727')}
  const active=nodes.filter(n=>n.pose&&age(n)<60000);
  for(const n of active){const p=enu(n.pose);if(Math.hypot(p.x,p.z)>100000)continue;let v=visual.get(n.nodeId);if(!v){v={...p,heading:n.pose.headingDeg??0,trail:[]};visual.set(n.nodeId,v)}
    if(!paused){const alpha=noMotion||age(n)>3000?1:.2;v.x+=(p.x-v.x)*alpha;v.z+=(p.z-v.z)*alpha;const target=n.pose.headingDeg??v.heading;v.heading+=(((target-v.heading+540)%360)-180)*alpha;
      if(!v.trail.length||Math.hypot(v.x-v.trail.at(-1)[0],v.z-v.trail.at(-1)[2])>.5){v.trail.push([v.x,.04,v.z]);if(v.trail.length>90)v.trail.shift()}}
    if(selected===n.nodeId){cam.x=v.x;cam.z=v.z}
    stroke3(v.trail,color(n)+'77',2);if(n.pose.accuracyM!=null&&n.pose.accuracyM>0)circle3(v.x,v.z,Math.min(50,n.pose.accuracyM),'#747b7955');
  }
  // Draw the reported ordering, never claim these are measured BLE edges.
  const drawn=new Set();for(const n of active)for(let i=1;i<n.chain.length;i++){const a=n.chain[i-1].id,b=n.chain[i].id,key=[a,b].sort().join(':');if(drawn.has(key))continue;drawn.add(key);const av=visual.get(a),bv=visual.get(b);if(av&&bv)stroke3([[av.x,.2,av.z],[bv.x,.2,bv.z]],'#6a725c',1,[4,7])}
  const faces=[],labels=[];
  const box=(v,cx,cy,cz,w,h,l,fill)=>{const angle=v.heading*Math.PI/180,s=Math.sin(angle),c=Math.cos(angle);const verts=[];
    for(const y of [cy,cy+h])for(const z of [cz-l/2,cz+l/2])for(const x of [cx-w/2,cx+w/2])verts.push([v.x+x*c-z*s,y,v.z+x*s+z*c]);
    for(const ix of [[0,1,3,2],[4,6,7,5],[0,4,5,1],[2,3,7,6],[0,2,6,4],[1,5,7,3]]){const ps=ix.map(i=>project(...verts[i]));if(ps.some(p=>!p))continue;faces.push({ps,fill,d:ps.reduce((s,p)=>s+p.d,0)/4})}
  };
  for(const n of active){const v=visual.get(n.nodeId);if(!v)continue;const p=project(v.x,1.5,v.z);if(!p)continue;const stale=age(n)>3000||n.pose.ageMs+age(n)>5000;const brake=!stale&&n.pose.slowing===true;
    box(v,0,.25,0,1.8,.65,4,stale?'#424646':color(n));box(v,0,.9,.1,1.45,.55,2,'#526367');box(v,0,1.45,.1,1.45,.08,1.5,stale?'#363a39':'#899494');
    for(const x of [-.88,.88])for(const z of [-1.25,1.25])box(v,x,.08,z,.28,.5,.7,'#121718');
    box(v,-.55,.5,2.02,.4,.15,.08,brake?'#ff2828':'#652b2b');box(v,.55,.5,2.02,.4,.15,.08,brake?'#ff2828':'#652b2b');
    const a=v.heading*Math.PI/180;stroke3([[v.x,.3,v.z],[v.x+Math.sin(a)*4,.3,v.z-Math.cos(a)*4]],stale?'#777':color(n),2);
    labels.push({p,n,brake,stale});projections.push({x:p.x,y:p.y,id:n.nodeId});
  }
  faces.sort((a,b)=>b.d-a.d);for(const f of faces){ctx.beginPath();f.ps.forEach((p,i)=>i?ctx.lineTo(p.x,p.y):ctx.moveTo(p.x,p.y));ctx.closePath();ctx.fillStyle=f.fill;ctx.fill();ctx.strokeStyle='#080b0b66';ctx.lineWidth=.8;ctx.stroke()}
  ctx.font='12px Saira, sans-serif';ctx.textAlign='center';
  for(const {p,n,brake,stale} of labels){ctx.fillStyle=brake?'#ff827c':stale?'#999':color(n);ctx.fillText(n.nodeId.slice(-6).toUpperCase()+(brake?' · SLOWING':stale?' · STALE':''),p.x,p.y-13)}
  ctx.textAlign='left';ctx.fillStyle='#a0aaa6';ctx.font='10px Saira, sans-serif';ctx.fillText(`LOCAL ENU · ${step} m GRID · ORBIT TO EXPLORE`,20,54);
}
const resize=new ResizeObserver(()=>{const r=canvas.getBoundingClientRect();width=r.width;height=r.height;const dpr=Math.min(2,devicePixelRatio);canvas.width=width*dpr;canvas.height=height*dpr;ctx.setTransform(dpr,0,0,dpr,0,0)});resize.observe(canvas);
let drag=null;canvas.onpointerdown=e=>{canvas.setPointerCapture(e.pointerId);drag={x:e.clientX,y:e.clientY,travel:0,pan:e.shiftKey};canvas.focus()};
canvas.onpointermove=e=>{if(!drag)return;const dx=e.clientX-drag.x,dy=e.clientY-drag.y;drag.travel+=Math.abs(dx)+Math.abs(dy);if(drag.pan){selected=null;followFleet=false;cam.x-=dx*cam.radius/500;cam.z-=dy*cam.radius/500}else{cam.yaw-=dx*.008;cam.pitch=clamp(cam.pitch+dy*.006,.2,1.52)}drag.x=e.clientX;drag.y=e.clientY};
canvas.onpointerup=e=>{if(drag?.travel<5){const r=canvas.getBoundingClientRect(),p=projections.find(p=>Math.hypot(p.x-e.clientX+r.left,p.y-e.clientY+r.top)<28);if(p)selected=p.id}drag=null};canvas.onpointercancel=()=>drag=null;
canvas.addEventListener('wheel',e=>{e.preventDefault();cam.radius=clamp(cam.radius*Math.exp(e.deltaY*.001),12,3000)},{passive:false});
canvas.onkeydown=e=>{if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','+','-','='].includes(e.key)){e.preventDefault();if(e.key==='ArrowLeft')cam.yaw-=.1;if(e.key==='ArrowRight')cam.yaw+=.1;if(e.key==='ArrowUp')cam.pitch=clamp(cam.pitch+.1,.2,1.52);if(e.key==='ArrowDown')cam.pitch=clamp(cam.pitch-.1,.2,1.52);if(['+','='].includes(e.key))cam.radius=clamp(cam.radius*.9,12,3000);if(e.key==='-')cam.radius=clamp(cam.radius*1.1,12,3000)}};
function frame(now){const dt=Math.min(.1,(now-last)/1000);last=now;if(!paused){elapsed+=dt;serverNow+=dt*1000;if(mode==='demo')demo(dt);if(mode==='replay')playReplay();if(mode==='live'&&now-lastPoll>250){lastPoll=now;poll()}}if(now-lastUI>350){renderUI();lastUI=now}draw();requestAnimationFrame(frame)}
setMode('demo');demo(0);fit();requestAnimationFrame(frame);
