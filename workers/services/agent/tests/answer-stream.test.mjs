import assert from "node:assert/strict";
import test from "node:test";
import { AgentAnswerStream } from "../.test-build/services/agent-answer-stream.js";

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };

test("slow persistence does not block previews; checkpoints coalesce and drain in order", async () => {
  const gate = deferred();
  const previews = [], writes = [];
  const stream = new AgentAnswerStream((offset, text) => previews.push({ offset, text }), async text => {
    writes.push(text);
    if (writes.length === 1) await gate.promise;
  }, () => assert.fail("unexpected failure"));
  stream.push("A"); stream.push("B"); stream.push("C");
  assert.deepEqual(previews, [{ offset: 0, text: "A" }, { offset: 1, text: "B" }, { offset: 2, text: "C" }]);
  assert.deepEqual(writes, ["A"]);
  let drained = false;
  const draining = stream.flush().then(() => { drained = true; });
  await Promise.resolve(); assert.equal(drained, false);
  gate.resolve(); await draining;
  assert.deepEqual(writes, ["A", "BC"]);
  // Tools may request a second model completion in the same turn.
  stream.push("D"); await stream.flush();
  assert.deepEqual(writes, ["A", "BC", "D"]);
});

test("checkpoint failure is observed, stops generation and prevents a false successful drain", async () => {
  let failures = 0;
  const stream = new AgentAnswerStream(() => {}, async () => { throw new Error("storage unavailable"); }, () => { failures++; });
  stream.push("draft");
  await assert.rejects(stream.flush(), /storage unavailable/);
  assert.equal(failures, 1);
  assert.throws(() => stream.push("later"), /storage unavailable/);
});

test("short pending text is checkpointed on the flush timer", async t => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const writes = [];
  const stream = new AgentAnswerStream(() => {}, async text => { writes.push(text); }, () => {});
  stream.push("A"); await stream.flush();
  stream.push("B");
  assert.deepEqual(writes, ["A"]);
  t.mock.timers.tick(120); await stream.flush();
  assert.deepEqual(writes, ["A", "B"]);
});
