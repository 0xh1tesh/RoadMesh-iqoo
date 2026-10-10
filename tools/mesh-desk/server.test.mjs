import test from 'node:test';
import assert from 'node:assert/strict';
import {createDesk,validatePacket} from './server.mjs';
const packet=(seq=0)=>({schema:1,nodeId:'abc',sessionId:'one',seq,sentAt:1000,source:'LIVE',role:'OBSERVER',meshRunning:true,chain:[],events:[],pose:{lat:12,lon:77,ageMs:10,headingDeg:359,speedMps:1,accuracyM:4}});
test('reject nonfinite and invalid telemetry; null GNSS remains unknown',()=>{
  for(const value of [NaN,Infinity,91])assert.throws(()=>validatePacket({...packet(),pose:{...packet().pose,lat:value}}));
  assert.throws(()=>validatePacket({...packet(),events:[{id:'x',origin:'abc',ageMs:-1}]}));
  assert.equal(validatePacket({...packet(),pose:null}).pose,null);
});
test('deduplicate sequences, retired sessions and relay event copies',()=>{
  const d=createDesk({clock:()=>2000});assert.equal(d.ingest(validatePacket(packet(2))),true);
  assert.equal(d.ingest(validatePacket(packet(1))),false);
  const p={...packet(0),sessionId:'two',events:[{id:'abc:x:1',origin:'abc',kind:'BRAKING',ageMs:1}]};
  assert.equal(d.ingest(validatePacket(p)),true);assert.equal(d.ingest(validatePacket(packet(3))),false);
  assert.equal(d.nodes.get('abc').sessionId,'two');
});
test('HTTP auth, cross-origin rejection and recording round trip',async()=>{
  const {server}=createDesk({token:'example-test-token-12345'});await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}`,headers={Authorization:'Bearer example-test-token-12345','Content-Type':'application/json'};
  try{
    assert.equal((await fetch(url+'/api/state')).status,401);
    assert.equal((await fetch(url+'/api/telemetry',{method:'POST',headers:{...headers,Origin:'http://evil.example'},body:JSON.stringify(packet())})).status,403);
    const r=await fetch(url+'/api/telemetry',{method:'POST',headers,body:JSON.stringify(packet())});assert.equal(r.status,200);assert.deepEqual(await r.json(),{accepted:true});
    const s=await(await fetch(url+'/api/state',{headers})).json();assert.equal(s.nodes.length,1);assert.equal(s.nodes[0].pose.headingDeg,359);
    const rec=await(await fetch(url+'/api/recording',{headers})).json();assert.equal(rec.frames.length,1);assert.equal(rec.type,'resilane-telemetry-recording');
  }finally{await new Promise(r=>server.close(r))}
});
