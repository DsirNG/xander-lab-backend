import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readdirSync, readFileSync } from "node:fs";
import { D1AgentToolApprovalMapper } from "../.test-build/mappers/d1-agent-tool-approval-mapper.js";
import { AgentToolApprovalService } from "../.test-build/services/agent-tool-approval-service.js";
import { AgentContentToolsService } from "../.test-build/services/agent-content-tools-service.js";
import { ContentAgentToolsMapper } from "../.test-build/mappers/content-agent-tools-mapper.js";

function setup(t, delivery) {
  const sql = new DatabaseSync(":memory:");
  t.after(() => sql.close());
  const directory = new URL("../migrations/", import.meta.url);
  for (const name of readdirSync(directory).filter(name => name.endsWith(".sql")).sort())
    sql.exec(readFileSync(new URL(name, directory), "utf8"));
  sql.exec("INSERT INTO agent_conversation (id, user_id, title, status, run_version) VALUES (7, 42, 'Mail', 'running', 1)");
  const db = {
    prepare(query) {
      let args = [];
      const bound = {
        bind(...values) { args = values; return bound; },
        async first() { return sql.prepare(query).get(...args) ?? null; },
        async all() { return { results: sql.prepare(query).all(...args) }; },
        execute() {
          const statement = sql.prepare(query);
          if (statement.columns().length) {
            const results = statement.all(...args);
            return { results, meta: { changes: results.length } };
          }
          return { results: [], meta: { changes: Number(statement.run(...args).changes) } };
        },
        async run() { return bound.execute(); },
      };
      return bound;
    },
    async batch(statements) {
      sql.exec("BEGIN");
      try {
        const results = statements.map(statement => statement.execute());
        sql.exec("COMMIT");
        return results;
      } catch (error) { sql.exec("ROLLBACK"); throw error; }
    },
  };
  const calls = [], answers = [];
  const tools = new AgentContentToolsService(new ContentAgentToolsMapper({
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.test",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "test-service-token",
    CONTENT_AUTOMATION_SERVICE: { async fetch(request) {
      assert.equal(new URL(request.url).pathname, "/internal/content-automation/mail/send");
      assert.equal(request.headers.get("x-user-id"), "42");
      assert.equal(request.headers.get("x-dinqor-service-token"), "test-service-token");
      const body = await request.json();
      calls.push(body);
      return Response.json({ code: 200, data: { ...body, id: 19, deliveryMode: "IMMEDIATE", status: delivery } });
    } },
  }));
  const repository = new D1AgentToolApprovalMapper(db);
  const service = new AgentToolApprovalService(repository, { async finishTurn(userId, conversationId, runVersion, answer) {
    assert.deepEqual([userId, conversationId, runVersion], [42, 7, 1]);
    answers.push(answer);
    sql.exec("UPDATE agent_conversation SET status = 'ready' WHERE id = 7");
  } }, {}, undefined, tools);
  return { sql, calls, answers, repository, service };
}

for (const [delivery, expected] of [["SENT", "SUCCEEDED"], ["FAILED", "UNKNOWN"]]) {
  test("persisted email approval preserves exact result and journals " + expected, async t => {
    const { sql, calls, answers, repository, service } = setup(t, delivery);
    const input = { recipientEmail: "owner@example.test", subject: "问候", message: "你在干嘛" };
    const pending = await service.requestContentMutation(42, 7, 1, "send_email", input);
    assert.equal(calls.length, 0);
    assert.equal(sql.prepare("SELECT status FROM agent_conversation WHERE id = 7").get().status, "awaiting_approval");
    const decided = await service.decide(42, 7, pending.approval.id, true, null);
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0], { ...input, clientRequestId: pending.approval.invocationId });
    assert.equal(decided.invocationStatus, expected);
    const journal = sql.prepare("SELECT status, result_json FROM agent_tool_invocation").get();
    assert.equal(journal.status, expected);
    assert.equal(JSON.parse(journal.result_json).email.message, input.message);
    const message = sql.prepare("SELECT content FROM agent_message WHERE kind = 'tool_result'").get();
    assert.equal(JSON.parse(message.content).email.id, 19);
    const event = sql.prepare("SELECT event_type, data_content FROM agent_event WHERE event_type IN ('tool_end', 'tool_unknown')").get();
    assert.equal(event.event_type, expected === "UNKNOWN" ? "tool_unknown" : "tool_end");
    assert.equal(JSON.parse(event.data_content).result.email.clientRequestId, pending.approval.invocationId);
    assert.equal((await repository.listApprovals(42, 7))[0].invocationStatus, expected);
    assert.equal(await repository.listApprovals(99, 7), null);
    await assert.rejects(service.decide(42, 7, pending.approval.id, true, null), error => error.status === 409);
    assert.equal(calls.length, 1);
    assert.equal(answers.length, 1);
    if (expected === "UNKNOWN") assert.match(answers[0], /未能确认/);
    else assert.match(answers[0], /尚未确认送达/);
  });
}
