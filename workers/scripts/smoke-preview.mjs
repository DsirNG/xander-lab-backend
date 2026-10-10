import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const previewDirectory = resolve(workersDirectory, "services/bloghtml-preview");
const gatewayUrl = process.env.GATEWAY_URL || "http://127.0.0.1:8787";
const code = `<main data-smoke="${crypto.randomUUID()}">Preview smoke</main>`;
let sessionId;

function runD1(statement) {
  const wranglerEntry = resolve(workersDirectory, "node_modules/wrangler/bin/wrangler.js");
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [wranglerEntry,
      "d1", "execute", "dinqor-bloghtml-preview-local", "--local", "--json", "--command", statement,
    ], { cwd: previewDirectory, windowsHide: true, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => { stdout += chunk; });
    child.stderr.setEncoding("utf8").on("data", (chunk) => { stderr += chunk; });
    child.once("error", rejectPromise);
    child.once("close", (exitCode) => {
      if (exitCode !== 0) {
        rejectPromise(new Error(`Local Preview D1 query failed (${(stderr || stdout).trim().slice(0, 400) || exitCode}).`));
        return;
      }
      try {
        resolvePromise(JSON.parse(stdout));
      } catch {
        rejectPromise(new Error("Local Preview D1 query returned invalid JSON."));
      }
    });
  });
}

try {
  const createResponse = await fetch(new URL("/api/blog-html/previews", gatewayUrl), {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code }),
  });
  const created = await createResponse.json();
  assert.equal(createResponse.status, 200);
  assert.equal(created.code, 200);
  sessionId = created.data.id;
  assert.match(sessionId, /^[0-9a-f]{16}$/);

  const row = (await runD1(`
    SELECT session_id AS sessionId, inline_code AS inlineCode, object_key AS objectKey,
           code_size AS codeSize, content_sha256 AS contentSha256
    FROM preview_session WHERE session_id = '${sessionId}'
  `))[0]?.results?.[0];
  assert.equal(row?.sessionId, sessionId);
  assert.equal(row?.inlineCode, code);
  assert.equal(row?.objectKey, null);
  assert.equal(row?.codeSize, new TextEncoder().encode(code).byteLength);
  assert.match(row?.contentSha256 ?? "", /^[a-f0-9]{64}$/);

  const rawResponse = await fetch(new URL(`/blog-html/${sessionId}/raw`, gatewayUrl));
  assert.equal(rawResponse.status, 200);
  assert.equal(rawResponse.headers.get("content-type"), "text/html; charset=UTF-8");
  assert.equal(rawResponse.headers.get("cache-control"), "no-store");
  assert.equal(rawResponse.headers.get("x-content-type-options"), "nosniff");
  assert.match(rawResponse.headers.get("content-security-policy") ?? "", /frame-ancestors/);
  assert.match(await rawResponse.text(), /Preview smoke/);
  console.log("Preview smoke passed (Gateway create, D1 metadata, public raw page, and CSP headers).");
} finally {
  if (sessionId) await runD1(`DELETE FROM preview_session WHERE session_id = '${sessionId}'`);
}
