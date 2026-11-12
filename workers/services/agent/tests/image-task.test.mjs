import test from 'node:test';
import assert from 'node:assert/strict';
import { AgentImageTaskService } from '../.test-build/services/agent-image-task-service.js';

function fixture(generate) {
  const values = new Map();
  const events = [];
  let status = 'running';
  const storage = {
    async get(key) { return structuredClone(values.get(key)); },
    async put(key, value) { values.set(key, structuredClone(value)); },
    async setAlarm(time) { values.set('alarm', time); },
    async deleteAlarm() { values.delete('alarm'); },
  };
  const repository = {
    async isCancellationRequested() { return status !== 'running'; },
    async findOwnedConversation() { return { status, runVersion: 3 }; },
    async appendEvent(_id, _version, type, data) { events.push({ type, data }); return events.length; },
    async finishTurn() { status = 'ready'; events.push({ type: 'complete' }); },
    async failTurn() { status = 'failed'; events.push({ type: 'error' }); },
  };
  return { storage, repository, values, events, status: () => status,
    service: new AgentImageTaskService(storage, repository, { generate }) };
}
const task = { userId: 42, conversationId: 234, runVersion: 3, args: { prompt: 'dog' } };

test('persisted image task finishes without the originating HTTP request and duplicate start is harmless', async () => {
  let calls = 0;
  const f = fixture(async () => { calls++; return { title: 'dog', url: 'https://example.test/dog.png' }; });
  await f.service.start(task);
  const recreated = new AgentImageTaskService(f.storage, f.repository, { generate: async () => {
    calls++; return { title: 'dog', url: 'https://example.test/dog.png' };
  } });
  await recreated.alarm();
  await recreated.start(task);
  await recreated.alarm();
  assert.equal(calls, 1);
  assert.equal(f.status(), 'ready');
  assert.equal(f.values.has('alarm'), false);
  assert.ok(f.events.some(e => e.type === 'tool_result'));
});

test('stop during generation discards late results without resuming the task', async () => {
  let resolve;
  const pending = new Promise(r => { resolve = r; });
  const f = fixture(async () => pending);
  await f.service.start(task);
  const work = f.service.alarm();
  while (!f.events.some(e => e.type === 'tool_start')) await new Promise(r => setImmediate(r));
  f.repository.isCancellationRequested = async () => true;
  resolve({ title: 'late dog', url: 'https://example.test/late.png' });
  await work;
  assert.equal(f.events.some(e => ['tool_result', 'complete', 'error'].includes(e.type)), false);
  assert.equal(f.values.get('task').done, true);
  assert.equal(f.values.has('alarm'), false);
});

test('alarm retry after an interrupted provider call fails durably without charging for another generation', async () => {
  const f = fixture(async () => { throw new Error('must not generate twice'); });
  await f.service.start(task);
  await f.storage.put('task', { ...task, started: true, done: false });
  await f.service.alarm();
  assert.equal(f.status(), 'failed');
  assert.deepEqual(f.events.map(e => e.type), ['error']);
});

test('provider failure clears the running state and publishes terminal failure', async () => {
  const f = fixture(async () => { throw new Error('provider unavailable'); });
  await f.service.start(task);
  await f.service.alarm();
  assert.equal(f.status(), 'failed');
  assert.deepEqual(f.events.slice(-2).map(e => e.type), ['tool_error', 'error']);
  assert.equal(f.values.has('alarm'), false);
});
