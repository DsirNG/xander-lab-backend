import assert from "node:assert/strict";
import { createHmac, randomInt } from "node:crypto";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const coreDirectory = resolve(workersDirectory, "services/core");
const agentDirectory = resolve(workersDirectory, "services/agent");
const contentDirectory = resolve(workersDirectory, "services/content-automation");
const gatewayUrl = process.env.GATEWAY_URL || "http://127.0.0.1:8787";
const modelUrl = "http://127.0.0.1:49871/v1";
const modelApiKey = "agent-approval-smoke-only";
const modelName = "agent-approval-smoke";
let postId;
const userId = randomInt(5_000_000, 6_000_000);
const username = `agent-approval-smoke-${userId}`;
let seedMaterialId;

function readVars(text) {
  return Object.fromEntries(text.split(/\r?\n/)
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => {
      const separator = line.indexOf("=");
      return [line.slice(0, separator), JSON.parse(line.slice(separator + 1))];
    }));
}

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function runD1(directory, database, statement) {
  const wranglerEntry = resolve(workersDirectory, "node_modules/wrangler/bin/wrangler.js");
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [wranglerEntry,
      "d1", "execute", database, "--local", "--json", "--command", statement,
    ], { cwd: directory, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => { stdout += chunk; });
    child.stderr.setEncoding("utf8").on("data", (chunk) => { stderr += chunk; });
    child.once("error", rejectPromise);
    child.once("close", (code) => {
      if (code !== 0) {
        const diagnostic = (stderr || stdout).trim().slice(0, 500);
        rejectPromise(new Error(`Local D1 approval smoke query failed (${diagnostic || code}).`));
        return;
      }
      try {
        resolvePromise(JSON.parse(stdout));
      } catch {
        rejectPromise(new Error("Local D1 approval smoke query returned invalid JSON."));
      }
    });
  });
}

async function api(path, { method = "GET", token, body } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  return fetch(new URL(path, gatewayUrl), {
    method,
    headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

function startMockModel() {
  let requests = 0;
  let received = 0;
  const server = createServer(async (request, response) => {
    received += 1;
    if (request.method !== "POST" || request.url !== "/v1/chat/completions"
      || request.headers.authorization !== `Bearer ${modelApiKey}`) {
      response.writeHead(404).end();
      return;
    }
    let bodyText = "";
    for await (const chunk of request) bodyText += chunk;
    const body = JSON.parse(bodyText);
    if (!Array.isArray(body.tools)) {
      const lastUserMessage = [...body.messages].reverse().find((message) => message.role === "user")?.content ?? "";
      const quizSessionId = lastUserMessage.match(/测验会话 ID：([1-9]\d*)/)?.[1];
      if (!quizSessionId) {
        response.writeHead(400).end();
        return;
      }
      requests += 1;
      response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
      response.write(`data: ${JSON.stringify({ choices: [{ delta: { content: `1. JWT 的三个组成部分是什么？\\n2. 签名用于验证什么？\\n3. 载荷通常包含什么信息？\\n\n测验会话 ID：${quizSessionId}` } }] })}\n\n`);
      response.end(`data: ${JSON.stringify({ choices: [], usage: { prompt_tokens: 22, completion_tokens: 16 } })}\n\ndata: [DONE]\n\n`);
      return;
    }
    if (!body.tools.some((tool) => tool.function?.name === "remember")
      || !body.tools?.some((tool) => tool.function?.name === "recall_memory")) {
      response.writeHead(400).end();
      return;
    }
    requests += 1;
    const lastUserMessage = [...body.messages].reverse().find((message) => message.role === "user")?.content ?? "";
    let toolName = "publish_post";
    let toolArguments = { postId, platform: "csdn" };
    if (/请记住我的偏好/.test(lastUserMessage)) {
      toolName = "remember";
      toolArguments = { memoryKey: `approval-smoke-${userId}`, kind: "PREFERENCE", content: "偏好简洁中文" };
    } else if (/回忆记忆偏好/.test(lastUserMessage)) {
      toolName = "recall_memory";
      toolArguments = { query: "偏好回复", limit: 4 };
    } else if (/查询我的个人知识/.test(lastUserMessage)) {
      if (!body.tools.some((tool) => tool.function?.name === "query_knowledge")) {
        response.writeHead(400).end();
        return;
      }
      toolName = "query_knowledge";
      toolArguments = { search: `agent-knowledge-smoke-${userId}`, limit: 5 };
    } else if (/请把这条内容保存到我的知识库/.test(lastUserMessage)) {
      if (!body.tools.some((tool) => tool.function?.name === "create_knowledge")) {
        response.writeHead(400).end();
        return;
      }
      toolName = "create_knowledge";
      toolArguments = {
        title: `agent-created-knowledge-${userId}`,
        content: "JWT 由头部、载荷和签名组成。",
        knowledgeType: "CONCEPT",
      };
    } else if (/开始测验/.test(lastUserMessage)) {
      if (!body.tools.some((tool) => tool.function?.name === "start_knowledge_quiz")) {
        response.writeHead(400).end();
        return;
      }
      toolName = "start_knowledge_quiz";
      toolArguments = { materialId: seedMaterialId, questionCount: 3 };
    } else if (/提交测验答案/.test(lastUserMessage)) {
      if (!body.tools.some((tool) => tool.function?.name === "record_knowledge_quiz")) {
        response.writeHead(400).end();
        return;
      }
      const priorQuiz = body.messages.find((message) => message.role === "assistant"
        && message.content?.includes("测验会话 ID："))?.content ?? "";
      const quizSessionId = Number(priorQuiz.match(/测验会话 ID：([1-9]\d*)/)?.[1]);
      if (!Number.isSafeInteger(quizSessionId) || quizSessionId < 1) {
        response.writeHead(400).end();
        return;
      }
      toolName = "record_knowledge_quiz";
      toolArguments = {
        quizSessionId,
        verdict: "已完成复习。",
        items: [{ question: "JWT 的三个组成部分是什么？", userAnswer: "头部、载荷和签名", comment: "完整正确。", credit: 1 }],
      };
    } else if (/创建答题卡/.test(lastUserMessage)) {
      if (!body.tools.some((tool) => tool.function?.name === "emit_quiz")) {
        response.writeHead(400).end();
        return;
      }
      toolName = "emit_quiz";
      toolArguments = {
        title: "HTTP smoke quiz",
        questions: [
          { prompt: "Which method retrieves a resource?", type: "single", options: ["GET", "POST"] },
          { prompt: "What does a response header carry?", type: "text", options: [] },
        ],
      };
    } else if (/创建交付卡/.test(lastUserMessage)) {
      if (!body.tools.some((tool) => tool.function?.name === "emit_artifact")) {
        response.writeHead(400).end();
        return;
      }
      toolName = "emit_artifact";
      toolArguments = {
        name: "Local artifact smoke",
        files: [
          { path: "src/main.ts", content: "export const ready = true;" },
          { path: "public/index.html", content: "<!doctype html><title>Smoke</title>" },
        ],
      };
    } else {
      if (!body.tools.some((tool) => tool.function?.name === "publish_post")) {
        response.writeHead(400).end();
        return;
      }
      if (!body.messages[0]?.content?.includes(`approval-smoke-${userId}`)
        || !body.messages[0]?.content?.includes("偏好简洁中文")) {
        response.writeHead(400).end();
        return;
      }
    }
    response.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    const events = [
      { choices: [{ delta: { tool_calls: [{
        index: 0,
        id: `agent-memory-smoke-${requests}`,
        type: "function",
        function: { name: toolName, arguments: JSON.stringify(toolArguments) },
      }] } }] },
      { choices: [], usage: { prompt_tokens: 20, completion_tokens: 8 } },
    ];
    for (const event of events) response.write(`data: ${JSON.stringify(event)}\n\n`);
    response.end("data: [DONE]\n\n");
  });
  return new Promise((resolvePromise, rejectPromise) => {
    server.once("error", rejectPromise);
    server.listen(49871, "127.0.0.1", () => resolvePromise({
      server,
      get requests() { return requests; },
      get received() { return received; },
    }));
  });
}

const coreVars = readVars(await readFile(resolve(coreDirectory, ".dev.vars"), "utf8"));
const agentVars = readVars(await readFile(resolve(agentDirectory, ".dev.vars"), "utf8"));
if (agentVars.AGENT_MODEL_BASE_URL !== modelUrl
  || agentVars.AGENT_MODEL_API_KEY !== modelApiKey
  || agentVars.AGENT_MODEL_NAME !== modelName
  || agentVars.AGENT_MODEL_API_STYLE !== "CHAT_COMPLETIONS") {
  throw new Error("Start `pnpm dev` with the documented local mock-model settings before this smoke check.");
}

const header = encode({ alg: "HS256", typ: "JWT" });
const payload = encode({ sub: String(userId), exp: Math.floor(Date.now() / 1000) + 300, type: "access", ver: 0 });
const signingInput = `${header}.${payload}`;
const token = `${signingInput}.${createHmac("sha256", coreVars.JWT_SECRET).update(signingInput).digest("base64url")}`;
let conversationId;
let skillId;
let userInserted = false;
let mock;
try {
  await runD1(coreDirectory, "dinqor-core-local", `
    INSERT INTO sys_user(id, username, nickname, email, role, status)
    VALUES (${userId}, '${username}', 'Agent Approval Smoke', '${username}@example.test', 'USER', 1);
    INSERT INTO sys_user_points(user_id, balance) VALUES (${userId}, 1000000)
  `);
  userInserted = true;
  const createdPostResponse = await api("/api/blog/posts", {
    method: "POST",
    token,
    body: {
      title: `Agent approval smoke ${userId}`,
      summary: "Local Agent publication approval flow",
      content: "A small post used to verify Agent approval through Content Automation.",
      categoryId: "backend",
      tags: [`agent-approval-smoke-${userId}`],
      publish: false,
    },
  });
  const createdPost = await createdPostResponse.json();
  assert.equal(createdPostResponse.status, 200);
  assert.equal(createdPost.data?.userId, userId);
  postId = createdPost.data.id;
  mock = await startMockModel();

  const createdResponse = await api("/api/agent/conversations", {
    method: "POST", token, body: { content: "长期记忆和发布验证" },
  });
  const created = await createdResponse.json();
  assert.equal(createdResponse.status, 200);
  conversationId = created.data.conversation.id;

  const seedKnowledgeResponse = await api("/api/recitations/materials", {
    method: "POST",
    token,
    body: {
      title: `agent-knowledge-smoke-${userId}`,
      content: "JWT 包含头部、载荷和签名。",
      knowledgeType: "CONCEPT",
    },
  });
  const seedKnowledge = await seedKnowledgeResponse.json();
  assert.equal(seedKnowledgeResponse.status, 200);
  assert.ok(seedKnowledge.data.id);
  seedMaterialId = seedKnowledge.data.id;

  const queriedKnowledgeResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "查询我的个人知识" },
  });
  const queriedKnowledgeStream = await queriedKnowledgeResponse.text();
  assert.equal(queriedKnowledgeResponse.status, 200);
  assert.match(queriedKnowledgeStream, /JWT 包含头部、载荷和签名/);

  const createKnowledgeResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "请把这条内容保存到我的知识库" },
  });
  const createKnowledgeStream = await createKnowledgeResponse.text();
  assert.equal(createKnowledgeResponse.status, 200);
  assert.match(createKnowledgeStream, /已将“agent-created-knowledge-/);
  const createdKnowledgeRows = (await runD1(coreDirectory, "dinqor-core-local", `
    SELECT id, user_id FROM recitation_material
    WHERE user_id = ${userId} AND title = 'agent-created-knowledge-${userId}'
  `))[0]?.results;
  assert.equal(createdKnowledgeRows?.length, 1);
  assert.equal(createdKnowledgeRows?.[0]?.user_id, userId);

  const quizResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "开始测验，复习我的 JWT 知识" },
  });
  const quizStream = await quizResponse.text();
  assert.equal(quizResponse.status, 200);
  assert.match(quizStream, /JWT 的三个组成部分是什么/);
  const quizSessionId = Number(quizStream.match(/测验会话 ID：([1-9]\d*)/)?.[1]);
  assert.ok(Number.isSafeInteger(quizSessionId) && quizSessionId > 0);

  const gradeResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "提交测验答案：JWT 由头部、载荷和签名组成" },
  });
  const gradeStream = await gradeResponse.text();
  assert.equal(gradeResponse.status, 200);
  assert.match(gradeStream, /测验完成：1\/1 题完全正确/);
  const gradedRows = (await runD1(coreDirectory, "dinqor-core-local", `
    SELECT session.status, session.conversation_id, attempt.user_id, attempt.question_count,
           attempt.correct_count, attempt.score
    FROM knowledge_quiz_session session
    JOIN knowledge_quiz_attempt attempt ON attempt.quiz_session_id = session.id
    WHERE session.id = ${quizSessionId} AND session.user_id = ${userId}
  `))[0]?.results;
  assert.equal(gradedRows?.length, 1);
  assert.equal(gradedRows?.[0]?.status, "GRADED");
  assert.equal(gradedRows?.[0]?.conversation_id, conversationId);
  assert.equal(gradedRows?.[0]?.user_id, userId);
  assert.equal(gradedRows?.[0]?.question_count, 1);
  assert.equal(gradedRows?.[0]?.correct_count, 1);
  assert.equal(gradedRows?.[0]?.score, 100);

  const cardResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "创建答题卡" },
  });
  const cardStream = await cardResponse.text();
  assert.equal(cardResponse.status, 200);
  assert.match(cardStream, /event: quiz/);
  const cardRows = (await runD1(agentDirectory, "dinqor-agent-local", `
    SELECT content FROM agent_message WHERE conversation_id = ${conversationId} AND kind = 'quiz'
  `))[0]?.results;
  assert.equal(cardRows?.length, 1);
  assert.deepEqual(JSON.parse(cardRows[0].content), {
    type: "quiz", id: `adhoc-${conversationId}-6`, title: "HTTP smoke quiz",
    questions: [
      { id: "q1", prompt: "Which method retrieves a resource?", type: "single", options: ["GET", "POST"] },
      { id: "q2", prompt: "What does a response header carry?", type: "text", options: [] },
    ],
  });
  assert.equal(mock.requests, 6);

  const artifactResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "创建交付卡" },
  });
  const artifactStream = await artifactResponse.text();
  assert.equal(artifactResponse.status, 200);
  assert.match(artifactStream, /event: artifact/);
  const artifactRows = (await runD1(agentDirectory, "dinqor-agent-local", `
    SELECT content FROM agent_message WHERE conversation_id = ${conversationId} AND kind = 'artifact'
  `))[0]?.results;
  assert.equal(artifactRows?.length, 1);
  assert.deepEqual(JSON.parse(artifactRows[0].content), {
    type: "artifact", id: `artifact-${conversationId}-7`, name: "Local artifact smoke",
    framework: "", runHint: "", entry: "public/index.html",
    files: [
      { path: "src/main.ts", language: "typescript", content: "export const ready = true;", truncated: false },
      { path: "public/index.html", language: "html", content: "<!doctype html><title>Smoke</title>", truncated: false },
    ],
  });
  assert.equal(mock.requests, 7);

  const rememberedResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "请记住我的偏好：偏好简洁中文" },
  });
  const rememberedStream = await rememberedResponse.text();
  assert.equal(rememberedResponse.status, 200);
  assert.match(rememberedStream, /event: tool_result/);
  assert.match(rememberedStream, /已记住/);
  const memoryRows = (await runD1(agentDirectory, "dinqor-agent-local", `
    SELECT memory_key, kind, content FROM agent_memory WHERE user_id = ${userId}
  `))[0]?.results;
  assert.equal(memoryRows?.[0]?.memory_key, `approval-smoke-${userId}`);
  assert.equal(memoryRows?.[0]?.kind, "PREFERENCE");
  assert.equal(memoryRows?.[0]?.content, "偏好简洁中文");

  const recalledResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: "回忆记忆偏好" },
  });
  const recalledStream = await recalledResponse.text();
  assert.equal(recalledResponse.status, 200);
  assert.match(recalledStream, /偏好简洁中文/);

  const skillResponse = await api("/api/agent/skills", {
    method: "POST",
    token,
    body: {
      skillKey: `approval-smoke-${userId}`,
      name: "Approval smoke publisher",
      description: "Temporary Skill used to validate an owner approval.",
      instructions: "Only synchronize an existing post after the user explicitly names the destination.",
      toolNames: ["publish_post"],
    },
  });
  const skill = await skillResponse.json();
  assert.equal(skillResponse.status, 200);
  skillId = skill.data.id;

  const runResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: `Sync post ${postId} to CSDN` },
  });
  const stream = await runResponse.text();
  assert.equal(runResponse.status, 200);
  assert.match(stream, /event: approval_required/,
    `Model mock accepted requests: ${mock.requests}; received requests: ${mock.received}`);
  assert.equal(mock.requests, 10);

  const approvalsResponse = await api(`/api/agent/conversations/${conversationId}/approvals`, { token });
  const approvals = await approvalsResponse.json();
  assert.equal(approvalsResponse.status, 200);
  assert.equal(approvals.data.length, 1);
  const approval = approvals.data[0];
  assert.equal(approval.status, "PENDING");
  assert.equal(approval.toolName, "publish_post");
  assert.equal(approval.summary.args.postId, postId);
  assert.equal(approval.summary.args.platform, "csdn");
  assert.match(approval.requestFingerprint, /^[a-f0-9]{64}$/);

  const decisionResponse = await api(
    `/api/agent/conversations/${conversationId}/approvals/${approval.id}/decision`,
    { method: "POST", token, body: { approved: false, reason: "Smoke test rejection" } },
  );
  const decision = await decisionResponse.json();
  assert.equal(decisionResponse.status, 200);
  assert.equal(decision.data.status, "REJECTED");

  const approvedRunResponse = await api(`/api/agent/conversations/${conversationId}/messages/stream`, {
    method: "POST", token, body: { content: `Sync post ${postId} to CSDN` },
  });
  const approvedRunStream = await approvedRunResponse.text();
  assert.equal(approvedRunResponse.status, 200);
  assert.match(approvedRunStream, /event: approval_required/);
  assert.equal(mock.requests, 11);

  const updatedApprovalsResponse = await api(`/api/agent/conversations/${conversationId}/approvals`, { token });
  const updatedApprovals = await updatedApprovalsResponse.json();
  assert.equal(updatedApprovalsResponse.status, 200);
  const pendingApproval = updatedApprovals.data.find((item) => item.status === "PENDING");
  assert.ok(pendingApproval);
  const approvedDecisionResponse = await api(
    `/api/agent/conversations/${conversationId}/approvals/${pendingApproval.id}/decision`,
    { method: "POST", token, body: { approved: true, reason: "Verify Content boundary" } },
  );
  const approvedDecision = await approvedDecisionResponse.json();
  assert.equal(approvedDecisionResponse.status, 200);
  assert.equal(approvedDecision.data.status, "APPROVED");
  assert.equal(approvedDecision.data.invocationStatus, "FAILED");

  const stateRows = (await runD1(agentDirectory, "dinqor-agent-local", `
    SELECT conversation.status AS conversation_status, approval.status AS approval_status,
           invocation.status AS invocation_status, invocation.error_message AS invocation_error
    FROM agent_approval approval
    JOIN agent_tool_invocation invocation ON invocation.invocation_id = approval.invocation_id
    JOIN agent_conversation conversation ON conversation.id = approval.conversation_id
    WHERE approval.id = ${approval.id} AND approval.user_id = ${userId}
  `))[0]?.results;
  assert.equal(stateRows?.[0]?.conversation_status, "ready");
  assert.equal(stateRows?.[0]?.approval_status, "REJECTED");
  assert.equal(stateRows?.[0]?.invocation_status, "FAILED");
  assert.equal(stateRows?.[0]?.invocation_error, "用户拒绝审批");
  const contentRows = (await runD1(contentDirectory, "dinqor-content-automation-local", `
    SELECT status, attempts, user_id, task_id FROM blog_publish_record
    WHERE task_id = -${postId} AND user_id = ${userId} AND platform = 'csdn'
  `))[0]?.results;
  const approvedInvocation = (await runD1(agentDirectory, "dinqor-agent-local", `
    SELECT status, error_message FROM agent_tool_invocation
    WHERE invocation_id = (
      SELECT invocation_id FROM agent_approval
      WHERE conversation_id = ${conversationId} AND status = 'APPROVED'
    )
  `))[0]?.results;
  assert.equal(contentRows?.[0]?.status, "FAILED", `Agent invocation: ${JSON.stringify(approvedInvocation)}`);
  assert.equal(contentRows?.[0]?.attempts, 1);
  assert.equal(contentRows?.[0]?.user_id, userId);
  assert.equal(contentRows?.[0]?.task_id, -postId);
  console.log("Agent smoke passed (local knowledge query/write/quiz, quiz and artifact card persistence, memory, owner-scoped approval/rejection, and Content hand-off with a mock model).");
} finally {
  if (mock) await new Promise((resolvePromise) => mock.server.close(resolvePromise));
  if (conversationId) {
    await runD1(agentDirectory, "dinqor-agent-local", `DELETE FROM agent_conversation WHERE id = ${conversationId} AND user_id = ${userId}`);
  }
  await runD1(agentDirectory, "dinqor-agent-local", `DELETE FROM agent_memory WHERE user_id = ${userId}`);
  await runD1(coreDirectory, "dinqor-core-local", `
    DELETE FROM knowledge_quiz_attempt WHERE user_id = ${userId};
    DELETE FROM knowledge_quiz_session WHERE user_id = ${userId};
    DELETE FROM recitation_material WHERE user_id = ${userId};
  `);
  if (skillId) await runD1(agentDirectory, "dinqor-agent-local", `DELETE FROM agent_skill WHERE id = ${skillId} AND user_id = ${userId}`);
  if (postId) {
    await runD1(contentDirectory, "dinqor-content-automation-local", `
      DELETE FROM blog_publish_record WHERE task_id = -${postId} AND user_id = ${userId};
      DELETE FROM blog_post WHERE id = ${postId} AND user_id = ${userId};
      DELETE FROM blog_tag WHERE name = 'agent-approval-smoke-${userId}'
        AND NOT EXISTS (SELECT 1 FROM blog_post_tag WHERE tag_id = blog_tag.id)
    `);
  }
  if (userInserted) {
    await runD1(coreDirectory, "dinqor-core-local", `DELETE FROM points_ledger WHERE user_id = ${userId}; DELETE FROM sys_user_points WHERE user_id = ${userId}; DELETE FROM sys_user WHERE id = ${userId}`);
  }
}
