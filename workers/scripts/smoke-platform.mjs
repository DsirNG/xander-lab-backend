import { createHmac, randomInt } from "node:crypto";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const coreDirectory = resolve(workersDirectory, "services/core");
const platformDirectory = resolve(workersDirectory, "services/platform-integration");
const gatewayUrl = process.env.GATEWAY_URL || "http://127.0.0.1:8787";
const platformUrl = process.env.PLATFORM_INTEGRATION_URL || "http://127.0.0.1:8790";
const userId = randomInt(1_100_000, 1_300_000);
const username = `platform-smoke-${userId}`;

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
  if (!pnpmEntry) throw new Error("Run this smoke check through `pnpm smoke:platform`.");
  const result = spawnSync(process.execPath, [pnpmEntry,
    "exec", "wrangler", "d1", "execute", database, "--local", "--command", statement,
  ], { cwd: directory, encoding: "utf8", windowsHide: true });
  if (result.status !== 0) {
    throw new Error(`Local Platform D1 smoke setup failed (${result.error?.message ?? result.status ?? "spawn"}).`);
  }
}

async function api(path, token, method = "GET") {
  return fetch(new URL(path, gatewayUrl), { method, headers: { authorization: `Bearer ${token}` } });
}

const coreVars = readVars(await readFile(resolve(coreDirectory, ".dev.vars"), "utf8"));
const platformVars = readVars(await readFile(resolve(platformDirectory, ".dev.vars"), "utf8"));
const header = encode({ alg: "HS256", typ: "JWT" });
const payload = encode({
  sub: String(userId), exp: Math.floor(Date.now() / 1000) + 300,
  type: "access", role: "ADMIN", ver: 0,
});
const signingInput = `${header}.${payload}`;
const token = `${signingInput}.${createHmac("sha256", coreVars.JWT_SECRET).update(signingInput).digest("base64url")}`;

try {
  runD1(coreDirectory, "dinqor-core-local", `INSERT INTO sys_user(id, username, nickname, email, role, status) VALUES (${userId}, '${username}', 'Platform Smoke', '${username}@example.test', 'ADMIN', 1)`);
  const importResponse = await fetch(`${platformUrl}/internal/v1/platform-credentials/${userId}/csdn`, {
    method: "PUT",
    headers: { "content-type": "application/json", "x-dinqor-service-token": platformVars.INTERNAL_SERVICE_TOKEN },
    body: JSON.stringify({
      credential: { cookie: `CSDN=smoke-${userId}`, username: "smoke-user" },
      accountName: "smoke-user",
    }),
  });
  const imported = await importResponse.json();
  if (!importResponse.ok || imported.data?.saved !== true || JSON.stringify(imported).includes(`CSDN=smoke-${userId}`)) {
    throw new Error("Platform did not store the service-imported credential without returning it");
  }

  const ownStatusResponse = await api("/api/publishing/csdn/authorization/status", token);
  const ownStatus = await ownStatusResponse.json();
  if (!ownStatusResponse.ok || ownStatus.data?.status !== "AUTHORIZED"
    || JSON.stringify(ownStatus).includes(`CSDN=smoke-${userId}`)) {
    throw new Error("Gateway did not return the owner-scoped CSDN authorization status");
  }
  const missingStatusResponse = await api("/api/publishing/juejin/authorization/status", token);
  const missingStatus = await missingStatusResponse.json();
  if (!missingStatusResponse.ok || missingStatus.data?.status !== "NOT_AUTHORIZED") {
    throw new Error("Platform leaked another provider's authorization state");
  }
  const catalogWithoutCredential = await api("/api/publishing/juejin/catalog/sync", token, "POST");
  if (catalogWithoutCredential.status !== 401) {
    throw new Error("Juejin catalog sync did not require the caller's provider credential");
  }
  const publishWithoutCredential = await fetch(
    `${platformUrl}/internal/platform-integration/blog-posts/301/sync/juejin`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-dinqor-service-token": platformVars.INTERNAL_SERVICE_TOKEN,
      },
      body: JSON.stringify({
        userId, postId: 301,
        post: { id: 301, title: "Smoke article", summary: "", content: "A small article body." },
      }),
    },
  );
  if (publishWithoutCredential.status !== 401) {
    throw new Error("Internal Juejin publication did not require the user's provider credential");
  }

  const disconnectResponse = await api("/api/publishing/csdn/authorization", token, "DELETE");
  if (!disconnectResponse.ok || (await disconnectResponse.json()).code !== 200) {
    throw new Error("Gateway did not disconnect the user's Platform credential");
  }
  const afterDisconnect = await (await api("/api/publishing/csdn/authorization/status", token)).json();
  if (afterDisconnect.data?.status !== "NOT_AUTHORIZED") {
    throw new Error("Platform disconnect did not remove the caller's credential");
  }
  console.log("Platform smoke passed (credential import, owner-scoped status, protected catalog/publication contracts, and disconnect).");
} finally {
  runD1(platformDirectory, "dinqor-platform-integration-local", `DELETE FROM platform_credential WHERE user_id = ${userId}`);
  runD1(coreDirectory, "dinqor-core-local", `DELETE FROM sys_user WHERE id = ${userId}`);
}
