import test from 'node:test';
import assert from 'node:assert/strict';
import { PresentationAssetMapper } from '../.test-build/mappers/presentation-asset-mapper.js';
import { AgentPresentationTaskService } from '../.test-build/services/agent-presentation-task-service.js';
const element=(extra)=>({type:'image',x:0,y:0,width:400,height:400,...extra});
const deck={title:'Images',slides:[{title:'A',layout:'canvas',elements:[element({assetId:7}),element({attachmentId:7}),element({assetId:7})],bullets:[],notes:'',sources:[]}]};

test('PPT resolves both image namespaces by ownership and deduplicates downloads',async()=>{
  const seen=[];
  const mapper=new PresentationAssetMapper({async resolveOwned(user,ids){assert.equal(user,42);assert.deepEqual(ids,[7]);return [{id:7,url:'https://storage.test/uploaded.png',contentType:'image/png'}];}},
    {async findOwned(user,id){assert.equal(user,42);assert.equal(id,7);return {id:7,url:'https://storage.test/generated.jpg',contentType:'image/jpeg'};}},async request=>{
      seen.push(request.url);assert.equal(request.redirect,'error');return new Response(new Uint8Array([1,2,3]));
    });
  const assets=await mapper.load(42,deck);
  assert.deepEqual(assets.map(v=>v.key),['media:7','attachment:7']);
  assert.deepEqual(seen,['https://storage.test/generated.jpg','https://storage.test/uploaded.png']);
  assert.equal(assets[0].data,'AQID');
});

test('foreign, unavailable and oversized PPT assets cannot reach rendering',async()=>{
  let downloads=0;
  const missing=new PresentationAssetMapper({async resolveOwned(){assert.fail();}},{async findOwned(){return null;}},async()=>{downloads++;});
  await assert.rejects(missing.load(42,deck),/PPT_IMAGE_NOT_FOUND/);assert.equal(downloads,0);
  for (const response of [new Response('x',{status:403}),new Response(new Uint8Array(8*1024*1024+1))]) {
    const mapper=new PresentationAssetMapper({}, {async findOwned(){return {url:'https://storage.test/file.png',contentType:'image/png'};}},async()=>response);
    await assert.rejects(mapper.load(42,{...deck,slides:[{...deck.slides[0],elements:[element({assetId:7})]}]}));
  }
});

test('task waits for assets before renderer and never publishes files on asset failure',async()=>{
  const data=new Map();let renders=0;
  const storage={async get(k){return data.get(k);},async put(k,v){data.set(k,v);},async setAlarm(){},async deleteAlarm(){}};
  const assets={async load(user,value){assert.equal(user,42);assert.equal(value.slides[0].elements[0].assetId,7);throw new Error('PPT_IMAGE_UNAVAILABLE: media:7');}};
  const service=new AgentPresentationTaskService(storage,{async render(){renders++;}}, {async resolveOwned(){assert.fail();}},undefined,assets);
  await service.start({id:'a'.repeat(32),userId:42,conversationId:1,deck});await service.alarm();
  const view=await service.view(42);
  assert.equal(view.status,'failed');assert.equal(view.files,undefined);assert.equal(renders,0);assert.match(view.error,/PPT_IMAGE_UNAVAILABLE/);
});
