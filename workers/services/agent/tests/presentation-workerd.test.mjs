import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';

// Exercise actual SQLite storage transactions and alarms, beyond in-memory unit doubles.
test('workerd persists PPT revisions atomically and enforces owner isolation', async () => {
  const require = createRequire(import.meta.url);
  const wranglerRequire = createRequire(require.resolve('wrangler'));
  const { build } = wranglerRequire('esbuild');
  const { Miniflare } = wranglerRequire('miniflare');
  const modulePath = fileURLToPath(new URL('../.test-build/services/agent-presentation-task-service.js', import.meta.url)).replaceAll('\\', '/');
  const source = `import {AgentPresentationTaskService} from './agent-presentation-task-service.js';
    export class PresentationObject {
      constructor(state) {
        this.tasks = new AgentPresentationTaskService(state.storage,
          { async render() { throw new Error('simulated renderer failure'); } },
          { async upload() { throw new Error('unexpected upload'); }, async resolveOwned() { return []; } },
          callback => state.blockConcurrencyWhile(callback));
      }
      async fetch(request) {
        const input = await request.json();
        try {
          const action = new URL(request.url).pathname;
          if (action === '/start') await this.tasks.start(input);
          else if (action === '/revise') await this.tasks.mutate(input.userId, 'revise', input);
          return Response.json(await this.tasks.view(input.userId));
        } catch (error) { return Response.json({ error: error.message }, { status: error.status || 500 }); }
      }
      alarm() { return this.tasks.alarm(); }
    }
    export default { fetch(request, env) { return env.TASKS.get(env.TASKS.idFromName('smoke')).fetch(request); } };`;
  const bundle = await build({ stdin: { contents: source, resolveDir: dirname(modulePath), loader: 'js' }, bundle: true, format: 'esm', platform: 'browser', write: false });
  const runtime = new Miniflare({ workers: [{ config: {
    name: 'presentation-test', compatibilityDate: '2026-10-10',
    manifest: { mainModule: 'worker.js', modules: { 'worker.js': { type: 'esm', contents: bundle.outputFiles[0].text } } },
    env: { TASKS: { type: 'durable-object', worker: 'presentation-test', exportName: 'PresentationObject' } },
    exports: { PresentationObject: { type: 'durable-object', storage: 'sqlite' } },
  } }], logRequests: false, telemetry: { enabled: false } });
  try {
    const call = (path, input) => runtime.dispatchFetch(`http://test${path}`, { method: 'POST', body: JSON.stringify(input) });
    const input = { id: 'a'.repeat(32), userId: 7, conversationId: 9, deck: { title: 'Transactions', slides: [{ title: 'One', bullets: ['Keep'] }, { title: 'Two', bullets: ['Revise'] }] } };
    assert.equal((await call('/start', input)).status, 200);
    let view;
    for (let count = 0; count < 60; count++) {
      view = await (await call('/get', { userId: 7 })).json();
      if (view.status === 'failed') break;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    assert.equal(view.status, 'failed');
    const revision = { userId: 7, expectedVersion: 1, requestId: 'workerd-revision-1234', slideIndex: 1, slide: { title: 'Changed', bullets: ['Updated'] } };
    const revised = await (await call('/revise', revision)).json();
    assert.equal(revised.version, 2);
    assert.equal(revised.deck.slides[0].title, 'One');
    assert.equal((await (await call('/revise', revision)).json()).version, 2);
    assert.equal((await call('/get', { userId: 8 })).status, 404);
  } finally { await runtime.dispose(); }
});
