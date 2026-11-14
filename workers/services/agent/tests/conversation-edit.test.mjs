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

test('private result projection restores every tool family, errors and timestamps with bounded owner-conversation data', async () => {
  const { sql, repository } = database();
  try {
    sql.exec("INSERT INTO agent_conversation (id,user_id,title,status,run_version) VALUES (8,99,'other','ready',1)");
    const insert = sql.prepare('INSERT INTO agent_event (conversation_id,run_version,event_type,data_content) VALUES (?,?,?,?)');
    insert.run(8, 1, 'tool_result', JSON.stringify({ tool: 'query_posts', result: { title: 'private-other-user' } }));
    insert.run(7, 1, 'tool_result', '{invalid');
    insert.run(7, 1, 'answer_delta', JSON.stringify('not-a-result'));
    for (let index = 0; index < 503; index++) {
      insert.run(7, 2, index === 502 ? 'tool_error' : 'tool_result', JSON.stringify({ tool: ['query_posts', 'query_plans', 'query_plan_series', 'query_email_reminders', 'query_knowledge', 'mcp_custom'][index % 6], result: { index } }));
    }
    const results = await repository.listWorkspaceResults(7);
    assert.equal(results.length, 500);
    assert.equal(JSON.parse(results[0].dataContent).result.index, 3);
    assert.equal(JSON.parse(results.at(-1).dataContent).result.index, 502);
    assert.equal(results.at(-1).eventType, 'tool_error');
    assert.ok(results.every(row => row.runVersion === 2 && row.createdAt));
    assert.equal(results.some(row => row.dataContent.includes('private-other-user')), false);
  } finally { sql.close(); }
});

test('all document files in history receive fresh owned attachment URLs without the five-upload truncation', async () => {
  const { sql, repository } = database();
  try {
    const artifact = { type: 'artifact', files: Array.from({ length: 12 }, (_, index) => ({ path: `file-${index}.pdf`, content: '', attachmentId: index + 1, url: 'https://files.test/expired' })) };
    sql.prepare("INSERT INTO agent_message (conversation_id,run_version,role,kind,content) VALUES (7,3,'assistant','artifact',?)").run(JSON.stringify(artifact));
    const lookups = [];
    const service = new AgentConversationService(repository, {
      async refreshStoredMany(userId, values) {
        lookups.push({ userId, values });
        return values.map(references => references.flatMap(reference => reference.id === 12 ? [] : [{ id: reference.id, url: `https://files.test/fresh-${reference.id}`, contentType: 'application/pdf', size: 100 }]));
      },
    });
    const snapshot = await service.get(42, 7);
    const files = JSON.parse(snapshot.messages[0].content).files;
    assert.equal(files.length, 12);
    assert.equal(files[10].url, 'https://files.test/fresh-11');
    assert.equal(files[11].url, undefined);
    assert.equal(lookups.at(-1).values.length, 12);
    assert.ok(lookups.every(lookup => lookup.userId === 42));
    lookups.length = 0;
    assert.equal(await service.get(99, 7), null);
    assert.equal(lookups.length, 0);
  } finally { sql.close(); }
});


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
