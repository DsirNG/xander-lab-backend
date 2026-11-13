import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { D1AgentConversationMapper } from '../.test-build/mappers/d1-agent-conversation-mapper.js';
import { AgentConversationService } from '../.test-build/services/agent-conversation-service.js';
import { handleAgentConversationRequest } from '../.test-build/controllers/agent-conversation-controller.js';
function database() {
  const sql = new DatabaseSync(':memory:');
  const directory = new URL('../migrations/', import.meta.url);
  for (const name of readdirSync(directory).filter(n => n.endsWith('.sql')).sort()) {
    sql.exec(readFileSync(new URL(name, directory), 'utf8'));
  }
  const db = {
    prepare(query) {
      let args = [];
      const bound = {
        bind(...values) { args = values; return bound; },
        async first() { return sql.prepare(query).get(...args) ?? null; },
        async all() { return { results: sql.prepare(query).all(...args) }; },
        async run() { return bound.execute(); },
        execute() {
          const statement = sql.prepare(query);
          if (statement.columns().length) {
            const results = statement.all(...args);
            return { results, meta: { changes: results.length } };
          }
          const result = statement.run(...args);
          return { results: [], meta: { changes: Number(result.changes) } };
        },
      };
      return bound;
    },
    async batch(statements) {
      sql.exec('BEGIN');
      try { const results = statements.map(s => s.execute()); sql.exec('COMMIT'); return results; }
      catch (error) { sql.exec('ROLLBACK'); throw error; }
    },
  };
  sql.exec("INSERT INTO agent_conversation (id,user_id,title,status,run_version) VALUES (7,42,'original','ready',3)");
  return { sql, repository: new D1AgentConversationMapper(db) };
}


test('fork copies only earlier history with new IDs, preserves tools/attachments and original, and is idempotent', async () => {
 const {sql,repository:r}=database();
 try {
  const add=sql.prepare('INSERT INTO agent_message (id,conversation_id,run_version,role,kind,content,invocation_id,attachments_json) VALUES (?,7,2,?,?,?,?,?)');
  add.run(1,'user','message','first',null,'[{"url":"owned-asset"}]');
  add.run(2,'assistant','tool_call','call','invoke-1',null);
  add.run(3,'tool','tool_result','result','invoke-1',null);
  add.run(4,'assistant','answer','answer',null,null);
  add.run(5,'user','message','original question',null,null);
  add.run(6,'assistant','answer','later answer',null,null);
  const original=await r.listMessages(7);
  const before=await r.findOwnedConversation(42,7);
  const fork=await r.fork(42,7,5,'request-key-00001','edited title');
  assert.notEqual(fork.id,7);
  assert.equal(fork.status,'ready');
  assert.equal(fork.shareToken,null);
  const messages=await r.listMessages(fork.id);
  assert.deepEqual(messages.map(m=>m.content),['first','call','result','answer']);
  assert.ok(messages.every(m=>m.id>6));
  assert.equal(messages[0].attachmentsJson,original[0].attachmentsJson);
  assert.equal(messages[1].invocationId,messages[2].invocationId);
  const retry=await r.fork(42,7,5,'request-key-00001','edited title');
  assert.equal(retry.id,fork.id);
  assert.equal((await r.listMessages(fork.id)).length,4);
  assert.deepEqual(await r.listMessages(7),original);
  assert.deepEqual(await r.findOwnedConversation(42,7),before);
  assert.equal(await r.fork(99,7,5,'request-key-00002','bad'),null);
  assert.equal(await r.fork(42,7,4,'request-key-00003','assistant anchor'),null);
  assert.equal(await r.fork(42,7,999,'request-key-00004','missing'),null);
  assert.equal(await r.fork(42,7,1,'request-key-00001','reused key'),null);
  const first=await r.fork(42,7,1,'request-key-00005','first edit');
  assert.deepEqual(await r.listMessages(first.id),[]);
 } finally {sql.close()}
});
test('rename validates titles and scopes writes to the authenticated owner', async () => {
 const {sql,repository:r}=database();
 try {
  const service=new AgentConversationService(r);
  const request=title=>new Request('https://test/api/agent/conversations/7/title',{method:'POST',body:JSON.stringify({title})});
  assert.equal((await handleAgentConversationRequest(request('new title'),service,async()=>99)).status,404);
  assert.equal((await handleAgentConversationRequest(request('  '),service,async()=>42)).status,400);
  assert.equal((await handleAgentConversationRequest(request('a'.repeat(101)),service,async()=>42)).status,400);
  const response=await handleAgentConversationRequest(request('  new title  '),service,async()=>42);
  assert.equal(response.status,200);
  assert.equal((await response.json()).data.title,'new title');
  assert.equal((await r.findOwnedConversation(42,7)).title,'new title');
  const bad=new Request('https://test/api/agent/conversations/7/fork',{method:'POST',body:JSON.stringify({messageId:-1,content:'x',requestId:'short'})});
  assert.equal((await handleAgentConversationRequest(bad,service,async()=>42)).status,400);
 } finally {sql.close()}
});
