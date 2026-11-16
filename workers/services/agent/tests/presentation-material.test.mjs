import test from 'node:test';
import assert from 'node:assert/strict';
import { AgentWebResearchSession } from '../.test-build/services/agent-web-research-service.js';
import { HttpPresentationMaterialMapper } from '../.test-build/mappers/http-presentation-material-mapper.js';
import { PresentationMaterialService } from '../.test-build/services/presentation-material-service.js';
import { TavilyWebResearchMapper } from '../.test-build/mappers/tavily-web-research-mapper.js';
const signal=()=>new AbortController().signal;
test('searched candidates retain provenance and cannot be invented or reused across turns',async()=>{
 const session=new AgentWebResearchSession({images:async()=>[{url:'https://brand.com/product.png',sourceUrl:'https://brand.com/product',description:'Exact package'},{url:'http://127.0.0.1/a'}]});
 const result=await session.searchImages({query:'product',domains:['brand.com']},signal());
 assert.equal(result.results.length,1);const candidate=result.results[0];
 assert.equal(session.imageCandidate(candidate.candidateId).sourceUrl,'https://brand.com/product');
 assert.throws(()=>session.imageCandidate('invented'));
 assert.throws(()=>new AgentWebResearchSession({}).imageCandidate(candidate.candidateId));
 let owner;const service=new PresentationMaterialService({read:async()=>({bytes:new Uint8Array([137,80,78,71]),width:2,height:1,contentType:'image/png'})},{uploadImage:async userId=>{owner=userId;return {id:42,url:'https://owned.com/image.png'};}});
 const imported=await service.import(7,session.imageCandidate(candidate.candidateId),signal());
 assert.equal(owner,7);assert.equal(imported.assetId,42);assert.equal(imported.provenance,'web-source');assert.equal(imported.sourceUrl,'https://brand.com/product');
});
test('source import rejects private redirects and never exposes raw transport errors',async()=>{
 let calls=0;
 const mapper=new HttpPresentationMaterialMapper('https://renderer.com','secret',async()=>{calls++;return new Response(null,{status:302,headers:{location:'http://127.0.0.1/admin'}});});
 await assert.rejects(()=>mapper.read({url:'https://brand.com/image',sourceUrl:'https://brand.com'},signal()),/素材转换失败/);assert.equal(calls,1);
 const broken=new HttpPresentationMaterialMapper('https://renderer.com','secret',async()=>{throw new Error('secret credential details');});
 await assert.rejects(()=>broken.read({url:'https://brand.com/image',sourceUrl:'https://brand.com'},signal()),error=>!error.message.includes('secret')&&error.status===502);
});
test('image search asks for actual source images and keeps available page attribution',async()=>{
 let body;
 const mapper=new TavilyWebResearchMapper('key',async(_url,init)=>{body=JSON.parse(init.body);return Response.json({images:[{url:'https://brand.com/logo.png',description:'Logo',source_url:'https://brand.com/about'}]});});
 const found=await mapper.images({query:'brand logo',limit:8,domains:['brand.com']},signal());
 assert.equal(body.include_images,true);assert.deepEqual(body.include_domains,['brand.com']);assert.equal(found[0].sourceUrl,'https://brand.com/about');
});
