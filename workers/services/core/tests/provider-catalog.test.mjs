import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { D1ModelProviderMapper } from '../.test-build/mappers/d1-model-provider-mapper.js';
import { ModelProviderService } from '../.test-build/services/model-provider-service.js';
import { handleAdminSettingsRequest } from '../.test-build/admin.js';
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
  return {sql,service:new ModelProviderService(new D1ModelProviderMapper(db))};
}


const env={ADMIN_MODEL_GATEWAY_ENABLED:'true',MODEL_PROVIDER_ENCRYPTION_KEY:btoa('a'.repeat(32)),resolveUser:async()=>({id:1,role:'ADMIN'})};
const call=(service,method,path,body)=>handleAdminSettingsRequest(new Request('https://core.test/api/admin/model-providers'+path,{method,...(body?{body:JSON.stringify(body)}:{})}),env,service,null,null,null);
const base={name:'Provider',vendor:'OPENAI_COMPATIBLE',baseUrl:'https://example.com/v1',apiKey:'test-secret'};
test('catalog persists, deduplicates, preserves old-client updates and never exposes credentials',async(t)=>{
 const {sql,service}=fixture();t.after(()=>sql.close());
 const created=await call(service,'POST','',{...base,models:['alpha',' beta ','alpha']});
 assert.equal(created.status,200); const row=(await created.json()).data;
 assert.deepEqual(row.models,['alpha','beta']); assert.equal(row.apiKeyConfigured,true);
 assert.ok(!JSON.stringify(row).includes('test-secret')); assert.ok(!('encryptedApiKey' in row));
 const updated=await call(service,'PUT','/'+row.id,{...base,apiKey:null});
 assert.deepEqual((await updated.json()).data.models,['alpha','beta']);
 const listed=await call(service,'GET','');assert.deepEqual((await listed.json()).data[0].models,['alpha','beta']);
 const cleared=await call(service,'PUT','/'+row.id,{...base,apiKey:null,models:[]});
 assert.deepEqual((await cleared.json()).data.models,[]);
});
test('malformed catalogs cannot be persisted',async(t)=>{
 const {sql,service}=fixture();t.after(()=>sql.close());
 for(const models of [null,'alpha',[1],[''],['x'.repeat(101)],Array(201).fill('alpha')]) {
  assert.equal((await call(service,'POST','',{...base,models})).status,400);
 }
 assert.equal(sql.prepare('SELECT count(*) AS n FROM model_provider').get().n,0);
});
