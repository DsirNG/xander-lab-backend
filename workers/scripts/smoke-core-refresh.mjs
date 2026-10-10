import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { access, mkdtemp, rm, unlink, writeFile } from "node:fs/promises";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const workersDir = path.resolve(scriptDir, "..");
const coreDir = path.join(workersDir, "services", "core");
const wranglerPath = path.join(workersDir, "node_modules", "wrangler", "bin", "wrangler.js");
const jwtSecret = "local-refresh-smoke-jwt-secret-only-not-production";
const serviceToken = "local-refresh-smoke-service-token-only-not-production";
const encryptionKey = Buffer.alloc(32, 0x5a).toString("base64");
const refreshGraceSeconds = 1;
const timeoutMs = 30_000;

function runWrangler(args) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [wranglerPath, ...args], {
      cwd: workersDir,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { output = (output + chunk).slice(-6000); });
    child.stderr.on("data", (chunk) => { output = (output + chunk).slice(-6000); });
    child.once("error", reject);
    child.once("close", (code) => {
      if (code === 0) resolve(output);
      else reject(new Error(`Wrangler exited with ${code}:\n${output}`));
    });
  });
}

async function unusedPort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return address.port;
}

function seedToken(sid, tokenId, tokenSecret) {
  const tokenHash = createHash("sha256").update(`${tokenId}:${tokenSecret}`).digest("hex");
  return {
    raw: `rt_${sid}.${tokenId}.${tokenSecret}`,
    sql: `INSERT INTO auth_refresh_session (sid, account_id, family_id, current_token_id, current_token_hash, generation, status, issued_at, expires_at) VALUES ('${sid}', 990001, '${sid}', '${tokenId}', '${tokenHash}', 0, 'ACTIVE', '2026-10-10T00:00:00.000Z', '2099-01-01T00:00:00.000Z');`,
  };
}

async function main() {
  if (!await access(wranglerPath).then(() => true, () => false)) {
    throw new Error("Install the workers workspace dependencies before running this smoke test.");
  }

  const stateDir = await mkdtemp(path.join(os.tmpdir(), "dinqor-core-refresh-smoke-"));
  const runId = randomUUID().replaceAll("-", "");
  const configPath = path.join(coreDir, `.refresh-smoke-${runId}.json`);
  const databaseName = `core-refresh-${runId.slice(0, 12)}`;
  const databaseId = randomUUID();
  const port = await unusedPort();
  const config = {
    "$schema": "../../node_modules/wrangler/config-schema.json",
    name: `dinqor-core-refresh-smoke-${runId.slice(0, 8)}`,
    main: "src/index.ts",
    compatibility_date: "2026-10-10",
    d1_databases: [{
      binding: "DB",
      database_name: databaseName,
      database_id: databaseId,
      migrations_dir: "migrations",
    }],
  };
  await writeFile(configPath, `${JSON.stringify(config, null, 2)}\n`, { flag: "wx" });
  const envFile = path.join(stateDir, "refresh-smoke.env");
  await writeFile(envFile, [
    `JWT_SECRET=${jwtSecret}`,
    `INTERNAL_SERVICE_TOKEN=${serviceToken}`,
    "AUTH_REFRESH_PROTOCOL_ENABLED=true",
    `AUTH_REFRESH_ENCRYPTION_KEY=${encryptionKey}`,
    `AUTH_REFRESH_GRACE_SECONDS=${refreshGraceSeconds}`,
    "JWT_ACCESS_TTL_SECONDS=60",
    "",
  ].join("\n"), { flag: "wx" });

  let server;
  let serverOutput = "";
  try {
    await runWrangler([
      "d1", "migrations", "apply", databaseName, "--local",
      "--config", configPath, "--persist-to", stateDir,
    ]);
    await runWrangler([
      "d1", "execute", databaseName, "--local", "--config", configPath,
      "--persist-to", stateDir, "--command",
      "INSERT INTO sys_user (id, username, nickname, email, role, status) VALUES (990001, 'refresh-smoke@example.invalid', 'Refresh Smoke', 'refresh-smoke@example.invalid', 'USER', 1);",
    ]);

    server = spawn(process.execPath, [wranglerPath, "dev", "--local", "--config", configPath,
      "--port", String(port), "--persist-to", stateDir, "--env-file", envFile,
      "--show-interactive-dev-session=false"], {
      cwd: workersDir,
      stdio: ["ignore", "pipe", "pipe"],
    });
    server.stdout.setEncoding("utf8");
    server.stderr.setEncoding("utf8");
    server.stdout.on("data", (chunk) => { serverOutput = (serverOutput + chunk).slice(-6000); });
    server.stderr.on("data", (chunk) => { serverOutput = (serverOutput + chunk).slice(-6000); });

    const baseUrl = `http://127.0.0.1:${port}`;
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (server.exitCode !== null) throw new Error(`Core smoke Worker exited early:\n${serverOutput}`);
      try {
        const ready = await fetch(`${baseUrl}/readyz`);
        if (ready.ok) break;
      } catch {
        // Wrangler is still starting its local runtime.
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    const ready = await fetch(`${baseUrl}/readyz`);
    assert.equal(ready.status, 200, `Core smoke Worker did not become ready:\n${serverOutput}`);

    async function refresh(token) {
      const response = await fetch(`${baseUrl}/api/auth/refresh`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-dinqor-service-token": serviceToken,
        },
        body: JSON.stringify({ refreshToken: token }),
      });
      return { response, body: await response.json() };
    }

    const firstSession = seedToken("refresh-smoke-reuse", "refresh-smoke-token-a", "A".repeat(43));
    await runWrangler([
      "d1", "execute", databaseName, "--local", "--config", configPath,
      "--persist-to", stateDir, "--command", firstSession.sql,
    ]);
    const concurrent = await Promise.all([refresh(firstSession.raw), refresh(firstSession.raw)]);
    assert.deepEqual(concurrent.map(({ response, body }) => ({
      status: response.status, code: body?.code, message: body?.message,
    })), [{ status: 200, code: 200, message: "success" }, { status: 200, code: 200, message: "success" }]);
    const successor = concurrent[0].body.data.refreshToken;
    assert.ok(successor?.startsWith("rt_"));
    assert.equal(concurrent[1].body.data.refreshToken, successor,
      "simultaneous refreshes must replay one D1-committed successor");
    const replay = await refresh(firstSession.raw);
    assert.equal(replay.response.status, 200);
    assert.equal(replay.body.data.refreshToken, successor);
    await new Promise((resolve) => setTimeout(resolve, refreshGraceSeconds * 1000 + 150));
    assert.equal((await refresh(firstSession.raw)).response.status, 401);
    assert.equal((await refresh(successor)).response.status, 401,
      "reuse after grace must revoke the active session family");

    const logoutSession = seedToken("refresh-smoke-logout", "refresh-smoke-token-b", "B".repeat(43));
    await runWrangler([
      "d1", "execute", databaseName, "--local", "--config", configPath,
      "--persist-to", stateDir, "--command", logoutSession.sql,
    ]);
    const issued = await refresh(logoutSession.raw);
    assert.equal(issued.response.status, 200);
    const loggedOut = await fetch(`${baseUrl}/api/auth/logout`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${issued.body.data.accessToken}`,
        "x-dinqor-service-token": serviceToken,
      },
    });
    assert.equal(loggedOut.status, 200);
    assert.equal((await refresh(issued.body.data.refreshToken)).response.status, 401,
      "logout must revoke its D1 refresh session");

    console.log("Core stateful refresh smoke passed: D1 rotation, concurrent replay, reuse revocation, and logout.");
  } finally {
    if (server && server.exitCode === null) {
      server.kill();
      await Promise.race([
        new Promise((resolve) => server.once("close", resolve)),
        new Promise((resolve) => setTimeout(resolve, 3000)),
      ]);
    }
    await unlink(configPath).catch(() => {});
    await rm(stateDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
