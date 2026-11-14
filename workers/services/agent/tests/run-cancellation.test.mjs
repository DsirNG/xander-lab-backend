import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync } from 'node:fs';
import { D1AgentRunMapper } from '../.test-build/mappers/d1-agent-run-mapper.js';
import { AgentImageService } from '../.test-build/services/agent-image-service.js';
import { AgentRunService } from '../.test-build/services/agent-run-service.js';

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
  sql.exec("INSERT INTO agent_conversation (id,user_id,title,status,run_version) VALUES (7,42,'test','running',3)");
  return { sql, repository: new D1AgentRunMapper(db) };
}

test('a pending approval cannot be replaced by a newly claimed chat turn', async () => {
  const { sql, repository } = database();
  try {
    sql.exec("UPDATE agent_conversation SET status='awaiting_approval' WHERE id=7");
    assert.equal(await repository.claimTurn(42, 7, 'replace approval', []), null);
    assert.equal(sql.prepare('SELECT run_version FROM agent_conversation WHERE id=7').get().run_version, 3);
    assert.equal(sql.prepare('SELECT COUNT(*) AS count FROM agent_message WHERE conversation_id=7').get().count, 0);
  } finally { sql.close(); }
});

test('durable stop is terminal, idempotent, owner scoped and fences late output across a new turn', async () => {
  const { sql, repository: r } = database();
  try {
    assert.equal(await r.cancelTurn(99, 7), null);
    assert.equal(await r.isCancellationRequested(42, 7, 3), false);
    const stopped = await r.cancelTurn(42, 7);
    assert.equal(stopped.status, 'ready');
    assert.equal(await r.isCancellationRequested(42, 7, 3), true);
    await r.cancelTurn(42, 7);
    assert.equal(sql.prepare('SELECT count(*) n FROM agent_event').get().n, 1);
    assert.equal(JSON.parse(sql.prepare('SELECT data_content FROM agent_event').get().data_content).cancelled, true);
    await assert.rejects(r.appendEvent(7, 3, 'tool_result', { late: true }), { name: 'AbortError' });
    await assert.rejects(r.finishTurn(42, 7, 3, 'late answer'));
    assert.equal(await r.failTurn(42, 7, 3, 'late error'), 0);
    const version = await r.claimTurn(42, 7, 'new request', []);
    assert.equal(version, 4);
    assert.equal(await r.isCancellationRequested(42, 7, 3), true);
    assert.equal(await r.isCancellationRequested(42, 7, 4), false);
    await r.cancelTurn(42, 7, 3);
    assert.equal(await r.isCancellationRequested(42, 7, 4), false);
    await assert.rejects(r.saveSpecialMessage(42, 7, 3, 'artifact', {}));
    assert.equal(sql.prepare('SELECT count(*) n FROM agent_message WHERE role=\'assistant\'').get().n, 0);
  } finally { sql.close(); }
});

test('cancel during image provider request prevents fallbacks, storage and billing', async () => {
  let cancelled = false;
  let calls = 0;
  const service = new AgentImageService({ async candidates() { return { endpoints: [{}, {}] }; } },
    { async generate() { calls++; cancelled = true; throw new Error('late provider failure'); } },
    { async recordGenerated() { assert.fail('must not save'); } },
    { async chargeTokens() { assert.fail('must not charge'); } });
  await assert.rejects(service.generate(42, 7, 3, { prompt: 'dog' }, undefined, async () => {
    if (cancelled) throw new DOMException('stopped', 'AbortError');
  }), { name: 'AbortError' });
  assert.equal(calls, 1);
});

test('stop acknowledges only after the child accepts cancellation; failed child cancellation is retryable', async () => {
  const { sql, repository } = database();
  let attempts = 0;
  const child = { async cancel() { if (++attempts === 1) throw new Error('child unavailable'); } };
  const service = new AgentRunService(repository, {}, {}, undefined, undefined, undefined,
    undefined, undefined, child);
  try {
    await assert.rejects(service.cancelTurn(42, 7), /child unavailable/);
    const pending = await repository.findOwnedConversation(42, 7);
    assert.equal(pending.status, 'running');
    assert.equal(pending.cancelRequested, 1);
    await assert.rejects(repository.finishTurn(42, 7, 3, 'must not complete'));
    assert.equal(await repository.failTurn(42, 7, 3, 'must not override stop'), 0);
    assert.equal(sql.prepare('SELECT count(*) n FROM agent_event').get().n, 0);
    const stopped = await service.cancelTurn(42, 7);
    assert.equal(stopped.status, 'ready');
    assert.equal(attempts, 2);
    assert.equal(sql.prepare('SELECT count(*) n FROM agent_event').get().n, 1);
  } finally { sql.close(); }
});

test('a successful image response arriving after stop never starts storage or billing', async () => {
  let cancelled = false;
  const service = new AgentImageService({ async candidates() { return { endpoints: [{}] }; } },
    { async generate() { cancelled = true; return { bytes: new Uint8Array([1]), usage: {} }; } },
    { async recordGenerated() { assert.fail('must not save'); } },
    { async chargeTokens() { assert.fail('must not charge'); } });
  await assert.rejects(service.generate(42, 7, 3, { prompt: 'dog' }, undefined, async () => {
    if (cancelled) throw new DOMException('stopped', 'AbortError');
  }), { name: 'AbortError' });
});

test('recent tool context is conversation scoped, ordered and bounded', async () => {
  const { sql, repository } = database();
  try {
    sql.exec("INSERT INTO agent_conversation (id,user_id,title,status,run_version) VALUES (8,99,'foreign','running',1)");
    const insert = sql.prepare("INSERT INTO agent_event (conversation_id,run_version,event_type,data_content) VALUES (?,3,?,?)");
    for (let id = 1; id <= 45; id++) insert.run(7, 'tool_result', JSON.stringify({ id }));
    insert.run(8, 'tool_result', JSON.stringify({ id: 'foreign' }));
    insert.run(7, 'answer_delta', 'not a tool');
    const results = await repository.recentToolResults(7);
    assert.equal(results.length, 40);
    assert.deepEqual(results.map(result => result.id), Array.from({ length: 40 }, (_, index) => index + 6));
  } finally { sql.close(); }
});
