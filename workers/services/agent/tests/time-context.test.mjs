import assert from "node:assert/strict";
import test from "node:test";
import { agentTimeZone, currentTimeInstruction } from "../.test-build/services/agent-time-context.js";

test("missing or invalid client zones default to China time", () => {
  assert.equal(agentTimeZone(), "Asia/Shanghai");
  assert.equal(agentTimeZone("invalid"), "Asia/Shanghai");
  assert.equal(agentTimeZone("America/New_York"), "America/New_York");
});

test("server time resolves China midnight on the next calendar date", () => {
  const instruction = currentTimeInstruction("Asia/Shanghai", new Date("2026-11-13T16:05:00Z"));
  assert.match(instruction, /2026-11-13T16:05:00.000Z/);
  assert.match(instruction, /2026-11-14/);
  assert.match(instruction, /00:05:00/);
  assert.match(instruction, /Asia\/Shanghai/);
});

test("explicit user zones determine the local day independently of server zone", () => {
  const instruction = currentTimeInstruction("America/New_York", new Date("2026-11-13T02:05:00Z"));
  assert.match(instruction, /2026-11-12/);
  assert.match(instruction, /21:05:00/);
});
