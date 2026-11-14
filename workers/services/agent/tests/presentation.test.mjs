import test from 'node:test';
import assert from 'node:assert/strict';
import { AgentPresentationTaskService } from '../.test-build/services/agent-presentation-task-service.js';
import { parsePresentationDeck } from '../.test-build/services/agent-presentation-validation.js';
import { HttpPresentationRendererMapper } from '../.test-build/mappers/http-presentation-renderer-mapper.js';
import { handleAgentPresentationRequest } from '../.test-build/controllers/agent-presentation-controller.js';
import { createPresentationTools } from '../.test-build/services/agent-presentation-tool-adapters.js';
import { DurableAgentPresentationMapper } from '../.test-build/mappers/durable-agent-presentation-mapper.js';
import { preparePresentationDeck } from '../.test-build/services/agent-presentation-preparation.js';

const id = 'a'.repeat(32);
const deck = { title: 'Deck', slides: [{ title: 'One', bullets: ['First'] }, { title: 'Two', bullets: ['Second'] }] };
const input = { id, userId: 7, conversationId: 9, deck };

test('structured story and factual chart survive preparation without flattening into bullet lists', () => {
  const rich = {title:'A useful story',slides:[
    {title:'Turn research into a deliverable',layout:'process',bullets:[],subtitle:'A reviewable workflow',takeaway:'Review evidence before sharing',
      blocks:[{heading:'Collect',body:'Import the source documents'},{heading:'Verify',body:'Check citations and unresolved questions'}]},
    {title:'Provided dataset',layout:'chart',bullets:[],chart:{unit:'',caption:'User-provided sample, not product performance',points:[{label:'A',value:0},{label:'B',value:12.5}]}}
  ]};
  const parsed=parsePresentationDeck(rich);
  assert.deepEqual(preparePresentationDeck(rich),{deck:parsed,adjustedSlides:0});
  assert.equal(parsed.slides[0].blocks[1].body,'Check citations and unresolved questions');
  assert.equal(parsed.slides[1].chart.points[1].value,12.5);
  for (const invalid of [
    {...rich.slides[1],chart:{...rich.slides[1].chart,points:[{label:'A',value:NaN},{label:'B',value:2}]}},
  ]) assert.throws(()=>parsePresentationDeck({title:'Bad',slides:[invalid]}));
  assert.doesNotThrow(() => parsePresentationDeck({title:'Agent decides',slides:[
    {...rich.slides[0],bullets:['Must not disappear']},
    {...rich.slides[0],layout:'comparison',blocks:[...rich.slides[0].blocks,{heading:'Third',body:'Agent may choose this structure'}]},
    {...rich.slides[1],chart:undefined},
  ]}));
});

test('legacy layouts normalize accidental rich blocks without rejecting the whole deck', () => {
  const result = preparePresentationDeck({title:'Compatible',slides:[{
    title:'Capabilities', layout:'bullets', bullets:[], blocks:[
      {heading:'Answer', body:'Return evidence and citations'},
      {heading:'Draft', body:'Create an editable deliverable'}
    ]
  }]});
  assert.deepEqual(result.deck.slides[0].bullets,[
    'Answer：Return evidence and citations', 'Draft：Create an editable deliverable'
  ]);
  assert.equal(result.deck.slides[0].blocks, undefined);
  assert.equal(result.adjustedSlides, 1);
});

test('model layout mismatches adapt before submission without losing page content or changing page count', () => {
  const slides = [
    {title:'Cover',layout:'cover',bullets:['A','B','C']},
    {title:'Capabilities',layout:'columns',bullets:['One','Two','Three','Four','Five']},
    {title:'Details',layout:'bullets',bullets:['1','2','3','4','5','Sixth detail'],notes:'Original notes'},
  ];
  const original=structuredClone(slides);
  const result=preparePresentationDeck({title:'Deck',slides});
  assert.equal(result.deck.slides.length,3);
  assert.deepEqual(result.deck.slides[0].bullets,['A','B','C']);
  assert.equal(result.deck.slides[0].layout,'bullets');
  assert.equal(result.deck.slides[1].layout,'bullets');
  assert.deepEqual(result.deck.slides[1].bullets,slides[1].bullets);
  assert.equal(result.deck.slides[2].bullets.length,5);
  assert.match(result.deck.slides[2].notes,/Original notes/);
  assert.match(result.deck.slides[2].notes,/Sixth detail/);
  assert.deepEqual(slides,original);
  assert.equal(result.adjustedSlides,3);
});

test('long Chinese text and explicit line breaks fit the renderer and retain originals in notes', () => {
  const title='人工智能'.repeat(20);
  const bullet='场景应用'.repeat(30);
  const multiline='First\nSecond\nThird\nFourth';
  const {deck:prepared}=preparePresentationDeck({title:'Deck',slides:[
    {title,layout:'bullets',bullets:[bullet,multiline],notes:'Existing notes',sources:[{title:'Evidence',url:'https://example.org/report'}]},
  ]});
  const slide=prepared.slides[0];
  assert.ok(slide.title.length<=62);
  assert.ok(slide.bullets[0].length<=84);
  assert.ok(slide.bullets[1].split('\n').length<=2);
  assert.ok(slide.notes.includes(title));
  assert.ok(slide.notes.includes(bullet));
  assert.ok(slide.notes.includes(multiline));
  assert.match(slide.notes,/Existing notes/);
  assert.equal(slide.sources[0].url,'https://example.org/report');
});

test('already valid decks remain unchanged and automatic fitting is idempotent', () => {
  const source=parsePresentationDeck(deck);
  assert.deepEqual(preparePresentationDeck(source),{deck:source,adjustedSlides:0});
  const prepared=preparePresentationDeck({title:'Dense',slides:[{title:'Page',bullets:['长内容'.repeat(40)]}]}).deck;
  assert.deepEqual(preparePresentationDeck(prepared),{deck:prepared,adjustedSlides:0});
});

test('automatic fitting never drops excess notes or relaxes source and control-character validation', () => {
  assert.throws(()=>preparePresentationDeck({title:'Deck',slides:[{title:'Page',bullets:Array(20).fill('内容'.repeat(100)),notes:'Existing notes'}]}),error=>error.status===400&&error.message.includes('完整保留'));
  assert.throws(()=>preparePresentationDeck({title:'Deck',slides:[{title:'Page',bullets:['abc\u0000def']}]}));
  assert.throws(()=>preparePresentationDeck({title:'Deck',slides:[{title:'Page',bullets:['A'],sources:[{title:'X',url:'javascript:alert(1)'}]}]}));
});

test('PPT tool submits fitted slides once under the original idempotency key and reports full notes', async () => {
  let calls=0;
  const tools=createPresentationTools({async create(user,conversation,key,value){
    calls++;
    assert.equal(user,7);assert.equal(conversation,9);assert.equal(key,'original-invocation');
    assert.equal(value.slides[0].layout,'bullets');
    assert.equal(value.slides.length,1);
    assert.ok(value.slides[0].notes.includes('Very detailed text '.repeat(20).trim()));
    return {...value,id,deck:value,status:'queued',version:1};
  }},async()=>{});
  const result=await tools.get('create_presentation').execute({userId:7,conversationId:9,idempotencyKey:'original-invocation',invocationId:'original-invocation',signal:new AbortController().signal},
    {title:'Deck',slides:[{title:'Page',layout:'cover',bullets:['One','Two','Very detailed text '.repeat(20)]}]});
  assert.equal(calls,1);
  assert.equal(result.ok,true);
  assert.match(result.answer,/自动适配/);
  assert.match(result.answer,/备注/);
});
function fixture(render = async () => ({ pptx: new Uint8Array([80,75]), source: new Uint8Array([1]), previews: [new Uint8Array([2]),new Uint8Array([3])] })) {
  const values = new Map(), files = new Map();
  let serial = 0, renders = 0;
  const storage = {
    async get(key) { return structuredClone(values.get(key)); },
    async put(key, value) { values.set(key, structuredClone(value)); },
    async setAlarm(time) { values.set('alarm',time); }, async deleteAlarm() { values.delete('alarm'); },
  };
  const attachments = {
    async upload(userId,file) { const ref = { id: ++serial, url: `https://files.test/${serial}`, name: file.name }; files.set(serial,{userId,ref}); return ref; },
    async resolveOwned(userId,ids) { return ids.map(id => { assert.equal(files.get(id).userId,userId); return files.get(id).ref; }); },
  };
  const renderer = { async render(value) { renders++; return render(value); } };
  const make = () => new AgentPresentationTaskService(storage,renderer,attachments);
  return { service: make(), make, storage, values, files, renders: () => renders };
}

test('durable PPT survives reconstruction, refreshes owned file references and duplicate start does not rerender', async () => {
  const f = fixture();
  await f.service.start(input);
  assert.equal((await f.service.view(7)).status,'queued');
  await f.make().alarm();
  await f.service.start(input);
  await f.service.alarm();
  const view = await f.service.view(7);
  assert.equal(view.status,'completed');
  assert.equal(view.files.previews.length,2);
  assert.equal(f.renders(),1);
  assert.equal(f.values.has('alarm'),false);
  await assert.rejects(f.service.view(8),error => error.status === 404);
});

test('single-slide revision preserves all other content and historical download; request replay is idempotent', async () => {
  const f = fixture(); await f.service.start(input); await f.service.alarm();
  const old = await f.service.view(7);
  const change = { expectedVersion: 1, requestId: 'request-1234', slideIndex: 1, slide: { title:'Revised',bullets:['Changed'] } };
  await f.service.mutate(7,'revise',change);
  await f.service.mutate(7,'revise',change);
  let latest = await f.service.view(7);
  assert.equal(latest.version,2);
  assert.deepEqual(latest.deck.slides[0],old.deck.slides[0]);
  assert.equal(latest.deck.slides[1].title,'Revised');
  assert.equal((await f.service.view(7,1)).files.pptx.id,old.files.pptx.id);
  await assert.rejects(f.service.mutate(7,'revise',{...change,requestId:'new-request',expectedVersion:1}),error => error.status === 409);
  await f.service.alarm();
  latest = await f.service.view(7);
  assert.equal(latest.status,'completed');
  assert.equal(latest.history.length,2);
});

test('cancel during render fences late uploads and retry uses a new attempt', async () => {
  let release;
  const f = fixture(() => new Promise(resolve => {release = resolve;}));
  await f.service.start(input);
  const alarm = f.service.alarm();
  await new Promise(resolve => setTimeout(resolve,0));
  await f.service.mutate(7,'cancel',{expectedVersion:1,requestId:'cancel-1234'});
  release({pptx:new Uint8Array([1]),source:new Uint8Array([1]),previews:[]});
  await alarm;
  assert.equal((await f.service.view(7)).status,'cancelled');
  assert.equal(f.files.size,0);
  await f.service.mutate(7,'retry',{expectedVersion:1,requestId:'retry-1234'});
  assert.equal((await f.service.view(7)).status,'queued');
  assert.equal(f.values.get('version:1').attempt,2);
});

test('interrupted render fails under watchdog and never repeats a provider call automatically', async () => {
  const f = fixture(); await f.service.start(input);
  f.values.set('version:1',{...f.values.get('version:1'),status:'rendering'});
  await f.make().alarm(); await f.make().alarm();
  assert.equal((await f.service.view(7)).status,'failed');
  assert.equal(f.renders(),0);
});

test('PPT validates bounds, link protocols, slide indexes and expected owner before any mutation', async () => {
  assert.throws(() => parsePresentationDeck({...deck,slides:[]}));
  assert.doesNotThrow(() => parsePresentationDeck({...deck,slides:[{title:'x',layout:'cover',bullets:['1','2','3']}]}));
  assert.throws(() => parsePresentationDeck({...deck,slides:[{title:'x',sources:[{title:'x',url:'javascript:alert(1)'}]}]}));
  const f = fixture(); await f.service.start(input);
  await assert.rejects(f.service.mutate(8,'cancel',{expectedVersion:1,requestId:'cancel-1234'}),error => error.status===404);
  assert.equal((await f.service.view(7)).status,'queued');
});

test('public PPT controller resolves identity before reading body; private resources stay owner-scoped', async () => {
  const denied = await handleAgentPresentationRequest(new Request(`https://app.test/api/agent/presentations/${id}`),{action(){throw new Error('must not run');}},async()=>new Response(null,{status:401}));
  assert.equal(denied.status,401);
  let called;
  const response = await handleAgentPresentationRequest(new Request(`https://app.test/api/agent/presentations/${id}/cancel`,{method:'POST',body:JSON.stringify({expectedVersion:1,requestId:'cancel-1234'})}),{async action(...args){called=args;return {id};}},async()=>7);
  assert.equal(response.status,200);
  assert.equal(called[0],7);
  assert.equal(called[2],'cancel');
  const large = await handleAgentPresentationRequest(new Request(`https://app.test/api/agent/presentations/${id}/revise`,{method:'POST',body:'x'.repeat(20_000)}),{action(){assert.fail('Oversized body must not execute');}},async()=>7);
  assert.equal(large.status,413);
});

test('renderer rejects redirects and malformed assets without exposing credentials', async () => {
  let request;
  const mapper = new HttpPresentationRendererMapper('https://renderer.test','secret-value',async value => {request=value;return new Response('secret-value',{status:302,headers:{location:'https://evil.test'}});});
  await assert.rejects(mapper.render(parsePresentationDeck(deck)),error => !error.message.includes('secret-value') && error.status===502);
  assert.equal(request.redirect,'manual');
  assert.equal(request.headers.get('authorization'),'Bearer secret-value');
});

test('renderer validates pinned revision, quality receipt, PNG and PPTX signatures', async () => {
  const output = {revision:'b243a950e9f85998705551fb1b77c6459573ff39',quality:{passed:true},pptx:btoa('PK00'),previews:[btoa(String.fromCharCode(137,80,78,71)),btoa(String.fromCharCode(137,80,78,71))],source:{deck}};
  const mapper = new HttpPresentationRendererMapper('https://renderer.test','secret',async()=>Response.json(output));
  assert.equal((await mapper.render(parsePresentationDeck(deck))).previews.length,2);
  output.quality.passed=false;
  await assert.rejects(mapper.render(parsePresentationDeck(deck)));
});

test('PPT tools preserve invocation identity, read-only query policy and deterministic task ids', async () => {
  let request, calls=0;
  const mapper = new DurableAgentPresentationMapper({idFromName:name=>name,get:name=>({async fetch(value){calls++;request=await value.json();return Response.json({data:{id:name}});}})});
  const first = await mapper.create(7,9,'invocation-1234',deck);
  const second = await mapper.create(7,9,'invocation-1234',deck);
  assert.equal(first.id,second.id); assert.equal(request.userId,7); assert.equal(calls,2);
  const events=[];
  const tools=createPresentationTools({async create(){return {id,status:'queued'};}},async(type,data)=>events.push({type,data}));
  assert.equal(tools.get('get_presentation').definition().readOnly,true);
  assert.equal(tools.get('create_presentation').definition().readOnly,false);
  await tools.get('create_presentation').execute({userId:7,conversationId:9,idempotencyKey:'call-1234',invocationId:'call-1234',signal:new AbortController().signal},deck);
  assert.equal(events[0].data.invocationId,'call-1234');
});
