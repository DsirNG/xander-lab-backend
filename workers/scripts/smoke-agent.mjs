import { createHmac, randomInt } from "node:crypto";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const coreDirectory = resolve(workersDirectory, "services/core");
const agentDirectory = resolve(workersDirectory, "services/agent");
const gatewayUrl = process.env.GATEWAY_URL || "http://127.0.0.1:8787";
const userId = randomInt(700_000, 900_000);
const username = `agent-smoke-${userId}`;

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
  const pnpmEntry = process.env.npm_execpath;
  if (!pnpmEntry) throw new Error("Run this smoke check through `pnpm smoke:agent`.");
  const result = spawnSync(process.execPath, [pnpmEntry,
    "exec", "wrangler", "d1", "execute", database, "--local", "--command", statement,
  ], {
    cwd: directory,
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    throw new Error(`Local D1 smoke setup failed (${result.error?.message ?? result.status ?? "spawn"}).`);
  }
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

const coreVars = readVars(await readFile(resolve(coreDirectory, ".dev.vars"), "utf8"));
const header = encode({ alg: "HS256", typ: "JWT" });
const payload = encode({ sub: String(userId), exp: Math.floor(Date.now() / 1000) + 300, type: "access", ver: 0 });
const signingInput = `${header}.${payload}`;
const token = `${signingInput}.${createHmac("sha256", coreVars.JWT_SECRET).update(signingInput).digest("base64url")}`;

runD1(coreDirectory, "dinqor-core-local", `INSERT INTO sys_user(id, username, nickname, email, role, status) VALUES (${userId}, '${username}', 'Agent Smoke', '${username}@example.test', 'USER', 1)`);
let conversationId;
try {
  const createdResponse = await api("/api/agent/conversations", {
    method: "POST", token, body: { content: "Agent layered local smoke" },
  });
  const created = await createdResponse.json();
  if (!createdResponse.ok || created.code !== 200) throw new Error("Agent conversation create failed");
  conversationId = created.data.conversation.id;

  const listedResponse = await api("/api/agent/conversations", { token });
  const listed = await listedResponse.json();
  if (!listedResponse.ok || !listed.data.items.some((row) => row.id === conversationId)) {
    throw new Error("Agent conversation list failed");
  }

  for (const [method, suffix] of [["POST", "pin"], ["POST", "read"]]) {
    const response = await api(`/api/agent/conversations/${conversationId}/${suffix}`, { method, token });
    if (!response.ok) throw new Error(`Agent ${suffix} update failed`);
  }
  const shareResponse = await api(`/api/agent/conversations/${conversationId}/share`, { method: "POST", token });
  const share = await shareResponse.json();
  if (!shareResponse.ok || typeof share.data !== "string") throw new Error("Agent share enable failed");
  const sharedResponse = await api(`/api/agent/conversations/shared/${share.data}`);
  if (!sharedResponse.ok) throw new Error("Agent public share read failed");
  const detailResponse = await api(`/api/agent/conversations/${conversationId}`, { token });
  const detail = await detailResponse.json();
  if (!detailResponse.ok || detail.data.conversation.id !== conversationId) throw new Error("Agent detail read failed");

  const skillKey = `agent-smoke-${userId}`;
  const skillResponse = await api("/api/agent/skills", {
    method: "POST",
    token,
    body: {
      skillKey,
      name: "Agent smoke skill",
      description: "Temporary local skill used by the Worker smoke check.",
      instructions: "Summarize the requested changes clearly.",
    },
  });
  const skill = await skillResponse.json();
  if (!skillResponse.ok || skill.data.skillKey !== skillKey || skill.data.version !== 1) {
    throw new Error("Agent skill create failed");
  }
  const skillsResponse = await api("/api/agent/skills", { token });
  const skills = await skillsResponse.json();
  if (!skillsResponse.ok || !skills.data.some((item) => item.id === skill.data.id)) {
    throw new Error("Agent skill list failed");
  }
  const archiveResponse = await api(`/api/agent/skills/${skill.data.id}`, { method: "DELETE", token });
  if (!archiveResponse.ok) throw new Error("Agent skill archive failed");
  console.log(`Agent Gateway smoke passed (conversation CRUD/share and skill version lifecycle; conversation=${conversationId}).`);
} finally {
  if (conversationId) runD1(agentDirectory, "dinqor-agent-local", `DELETE FROM agent_conversation WHERE id = ${conversationId} AND user_id = ${userId}`);
  runD1(agentDirectory, "dinqor-agent-local", `DELETE FROM agent_skill WHERE user_id = ${userId}`);
  runD1(coreDirectory, "dinqor-core-local", `DELETE FROM sys_user WHERE id = ${userId}`);
}
