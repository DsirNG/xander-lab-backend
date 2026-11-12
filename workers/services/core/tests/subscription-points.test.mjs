import assert from 'node:assert/strict';
import test from 'node:test';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { D1BillingMapper } from '../.test-build/mappers/d1-billing-mapper.js';
import { D1PaymentEntitlementMapper } from '../.test-build/mappers/d1-payment-entitlement-mapper.js';

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
  return {sql,billing:new D1BillingMapper(db),entitlements:new D1PaymentEntitlementMapper(db)};
}
const debit=(billing,key,amount)=>billing.appendLedger({userId:1,bizType:'chat',bizKey:key,bizRef:null,amount,balanceDelta:-amount,unitPrice:'test',model:null,inputTokens:null,outputTokens:null,cachedTokens:null,remark:null});

test('upgrade pays a fresh allowance, resets 30 days and retries never grant twice',async()=>{
  const {sql,billing,entitlements}=fixture();
  assert.equal(await entitlements.grantOnce(1,'PLUS',30,'virtualpay:first'),true);
  assert.equal(await entitlements.grantOnce(1,'PLUS',30,'virtualpay:first'),false);
  sql.exec("UPDATE sys_user SET tier_expires_at=datetime('now','+2 days') WHERE id=1");
  await entitlements.grantOnce(1,'PRO',30,'virtualpay:second');
  assert.equal((await billing.wallet(1)).balance,80000);
  assert.ok(sql.prepare("SELECT julianday(tier_expires_at)-julianday('now') AS days FROM sys_user WHERE id=1").get().days>29.99);
  await assert.rejects(entitlements.grantOnce(1,'PLUS',30,'virtualpay:lower'),/subscription_upgrade_conflict/);
  await assert.rejects(entitlements.grantOnce(1,'PRO',30,'virtualpay:same'),/subscription_upgrade_conflict/);
});

test('spend earliest expiring lots first and permanent points last, idempotently',async()=>{
  const {sql,billing,entitlements}=fixture();
  await entitlements.grantOnce(1,'PLUS',30,'virtualpay:first');
  sql.exec("UPDATE subscription_points_lot SET expires_at=strftime('%Y-%m-%dT%H:%M:%fZ','now','+1 day')");
  await entitlements.grantOnce(1,'PRO',30,'virtualpay:second');
  await debit(billing,'debit',25000); await debit(billing,'debit',25000);
  assert.deepEqual(sql.prepare('SELECT remaining FROM subscription_points_lot ORDER BY event_key').all().map(r=>r.remaining),[0,45000]);
  assert.equal((await billing.wallet(1)).permanentBalance,10000);
  await debit(billing,'debit2',47000);
  assert.equal((await billing.wallet(1)).subscriptionBalance,0);
  assert.equal((await billing.wallet(1)).permanentBalance,8000);
});

test('expired points cannot be spent or displayed, purchases are permanent and idempotent',async()=>{
  const {sql,billing,entitlements}=fixture();
  await entitlements.grantOnce(1,'PLUS',30,'virtualpay:first');
  sql.exec("UPDATE subscription_points_lot SET expires_at='2000-01-01T00:00:00Z'; UPDATE sys_user SET tier_expires_at='2000-01-01T00:00:00Z' WHERE id=1");
  assert.equal(await billing.findBalance(1),10000);
  assert.equal(await entitlements.grantPoints(1,20000,'virtualpay:points'),true);
  assert.equal(await entitlements.grantPoints(1,20000,'virtualpay:points'),false);
  assert.equal((await billing.wallet(1)).permanentBalance,30000);
  assert.equal((await billing.wallet(1)).balance,30000);
  assert.equal(await billing.findConsumedToday(1),0);
  assert.equal(sql.prepare("SELECT count(*) AS n FROM points_ledger WHERE biz_type='subscription-expire'").get().n,1);
});

test('account merge preserves subscription lot expiry instead of making it permanent',async()=>{
  const {sql,billing,entitlements}=fixture();
  await entitlements.grantOnce(1,'PLUS',30,'virtualpay:first');
  await billing.transferBalance(1,2,'merge','test','merge');
  assert.equal((await billing.wallet(2)).permanentBalance,15000);
  assert.equal((await billing.wallet(2)).subscriptionBalance,20000);
  sql.exec("UPDATE subscription_points_lot SET expires_at='2000-01-01T00:00:00Z'");
  assert.equal(await billing.findBalance(2),15000);
});

test('multiple expired lots are settled once before a new debit',async()=>{
  const {sql,billing,entitlements}=fixture();
  await entitlements.grantOnce(1,'PLUS',30,'virtualpay:first');
  await entitlements.grantOnce(1,'PRO',30,'virtualpay:second');
  sql.exec("UPDATE subscription_points_lot SET expires_at='2000-01-01T00:00:00Z'");
  await debit(billing,'after-expiry',1000);
  assert.equal((await billing.wallet(1)).balance,9000);
  assert.equal((await billing.wallet(1)).subscriptionBalance,0);
  assert.deepEqual(sql.prepare("SELECT balance_after FROM points_ledger WHERE biz_type='subscription-expire' ORDER BY id").all().map(r=>r.balance_after),[60000,10000]);
});
