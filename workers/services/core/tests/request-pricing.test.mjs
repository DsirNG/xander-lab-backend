import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { D1BillingMapper } from '../.test-build/mappers/d1-billing-mapper.js';
import { BillingService } from '../.test-build/services/billing-service.js';
import { handleBillingRequest } from '../.test-build/billing.js';
function fixture() {
  const sql = new DatabaseSync(':memory:');
  const dir = new URL('../migrations/', import.meta.url);
  for (const file of readdirSync(dir).filter(f=>f.endsWith('.sql')).sort()) sql.exec(readFileSync(new URL(file,dir),'utf8'));
  sql.exec("INSERT INTO sys_user(id,username) VALUES(1,'one'),(2,'two'); INSERT INTO sys_user_points(user_id,balance) VALUES(1,10000),(2,5000)");
  const db = {
    prepare(query) {
      let args=[];
      const stmt={bind(...values){args=values;return stmt;},
        async first(){return sql.prepare(query).get(...args)??null;},
        async all(){return {results:sql.prepare(query).all(...args)};},
        async run(){const row=sql.prepare(query).run(...args);return {success:true,meta:{changes:Number(row.changes)}};}};
      return stmt;
    },
    async batch(stmts){sql.exec('BEGIN');try{const rows=[];for(const s of stmts) rows.push(await s.run());sql.exec('COMMIT');return rows;}catch(e){sql.exec('ROLLBACK');throw e;}}
  };
  return {sql,billing:new D1BillingMapper(db),service:new BillingService(new D1BillingMapper(db))};
}

const admin = async () => ({id:1,role:'ADMIN'});
const call = (service, path, body, method='POST') => handleBillingRequest(new Request('https://core.test'+path, {
  method, ...(body === undefined ? {} : {body:JSON.stringify(body),headers:{'content-type':'application/json'}}),
}), {}, service, admin);
const save = (service, body) => call(service,'/api/admin/billing/model-prices/image',body,'PUT');
const charge = (service,key,usage={}) => call(service,'/internal/agent/billing/charge-tokens',{
  userId:1,bizType:'blog-agent-illustration',bizKey:key,model:'image',inputTokens:0,outputTokens:0,cachedTokens:0,...usage,
});

test('request pricing persists, appears in both APIs, and charges once regardless of usage',async(t)=>{
  const {sql,service}=fixture(); t.after(()=>sql.close());
  const saved=await save(service,{billingMode:'REQUEST',milliPerRequest:1250});
  assert.equal(saved.status,200);
  assert.equal((await saved.json()).data.milliPerRequest,1250);
  const list=await call(service,'/api/admin/billing/model-prices',undefined,'GET');
  assert.equal((await list.json()).data[0].billingMode,'REQUEST');
  const prices=await call(service,'/api/points/prices',undefined,'GET');
  assert.equal((await prices.json()).data.models.image.milliPerRequest,1250);
  assert.equal((await charge(service,'first')).status,200);
  assert.equal((await charge(service,'first')).status,200);
  assert.equal((await charge(service,'second',{inputTokens:99999,outputTokens:88888})).status,200);
  const rows=sql.prepare('SELECT amount,unit_price FROM points_ledger ORDER BY id').all();
  assert.deepEqual(rows.map(r=>r.amount),[1250,1250]);
  assert.equal(rows[0].unit_price,'REQUEST:1250 image');
  assert.equal(await service.balance(1),7500);
});

test('legacy token saves, mode switches, disabled fallback and free requests remain explicit',async(t)=>{
  const {sql,service}=fixture(); t.after(()=>sql.close());
  const saved=await save(service,{inputMilliPer1kToken:50,outputMilliPer1kToken:200});
  assert.equal((await saved.json()).data.billingMode,'TOKEN');
  assert.equal((await charge(service,'no-usage')).status,400);
  await charge(service,'token',{inputTokens:1000,outputTokens:1000});
  assert.equal(await service.balance(1),9750);
  await save(service,{billingMode:'REQUEST',milliPerRequest:0});
  assert.equal((await charge(service,'free')).status,200);
  assert.equal(await service.balance(1),9750);
  await save(service,{billingMode:'REQUEST',milliPerRequest:1000,enabled:false});
  assert.equal((await charge(service,'disabled')).status,400);
  await call(service,'/api/admin/billing/model-prices/image',undefined,'DELETE');
  assert.equal((await charge(service,'deleted')).status,400);
});

test('invalid request rates and modes are rejected without persisting changes',async(t)=>{
  const {sql,service}=fixture(); t.after(()=>sql.close());
  for(const body of [
    {billingMode:'OTHER',milliPerRequest:1}, {billingMode:'REQUEST'},
    ...[-1,0.5,'12',null,Number.MAX_SAFE_INTEGER+1].map(milliPerRequest=>({billingMode:'REQUEST',milliPerRequest})),
  ]) assert.equal((await save(service,body)).status,400,JSON.stringify(body));
  assert.equal(sql.prepare('SELECT count(*) AS n FROM billing_model_price').get().n,0);
});
