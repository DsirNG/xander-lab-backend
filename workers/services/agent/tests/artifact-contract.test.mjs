import test from "node:test";
import assert from "node:assert/strict";
import { buildArtifactCard, ResponseError } from "../.test-build/services/agent-run-service.js";
import { createArtifactTool } from "../.test-build/services/agent-artifact-tool-adapters.js";
import { AgentToolExecutionError } from "../.test-build/services/agent-tool-runtime.js";
import { ARTIFACT_TOOLS } from "../.test-build/services/agent-tool-definitions.js";

const card = (input) => buildArtifactCard(input, 7, 1).artifact;
for (const format of ["docx", "pdf"]) {
  test(`explicit ${format} normalizes Markdown paths and entry`, () => {
    const artifact = card({ kind: "code", format, entry: "nested/report.md", files: [
      { path: "nested/report.md", content: "# Report" }, { path: "other", content: "正文" },
    ] });
    assert.equal(artifact.kind, "document");
    assert.equal(artifact.format, format);
    assert.equal(artifact.entry, `nested/report.${format}`);
    assert.deepEqual(artifact.files.map(f => f.path), [`nested/report.${format}`, `other.${format}`]);
    assert.ok(artifact.files.every(f => f.format === format && f.language === ""));
  });
}

test("mixed formats are inferred per file and documents retain 200k characters", () => {
  const artifact = card({ entry: "b.PDF", files: [
    { path: "a.DOCX", content: "字".repeat(200_000) },
    { path: "b.PDF", content: "# PDF" }, { path: "code.ts", content: "x".repeat(20_001) },
  ] });
  assert.equal(artifact.kind, "document");
  assert.equal(artifact.entry, "b.pdf");
  assert.deepEqual(artifact.files.map(f => f.format), ["docx", "pdf", undefined]);
  assert.equal(artifact.files[0].content.length, 200_000);
  assert.equal(artifact.files[0].truncated, false);
  assert.equal(artifact.files[2].content.length, 20_000);
  assert.equal(artifact.files[2].truncated, true);
});

test("oversized documents and normalized path collisions are rejected", () => {
  assert.throws(() => card({ files: [{ path: "a.pdf", content: "x".repeat(200_001) }] }), ResponseError);
  assert.throws(() => card({ format: "docx", files: [
    { path: "a.md", content: "one" }, { path: "a.pdf", content: "two" },
  ] }), ResponseError);
});

test("documents are still converted after ordinary code reaches its total limit", () => {
  const artifact = card({ files: [...[1, 2, 3].map(n => ({ path: `${n}.ts`, content: "x".repeat(20_000) })),
    { path: "report.pdf", content: "report" }] });
  assert.equal(artifact.files.length, 4);
  assert.equal(artifact.files[3].format, "pdf");
});

test("adapter awaits every upload and returns binary metadata without source language/content", async () => {
  const calls = [];
  let finish;
  const gate = new Promise(resolve => { finish = resolve; });
  const service = { async create(...args) {
    calls.push(args);
    if (calls.length === 2) await gate;
    return { id: calls.length, url: `https://files.test/${args[1]}`, size: 123,
      contentType: args[2] === "pdf" ? "application/pdf" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document" };
  } };
  let settled = false;
  const pending = createArtifactTool(service, 42, 7, 1).execute({}, { entry: "b.PDF", files: [
    { path: "a.docx", content: "# Word", contentType: "text/markdown" }, { path: "b.PDF", content: "# PDF" },
  ] }).then(result => { settled = true; return result; });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(calls.length, 2);
  assert.equal(settled, false);
  finish();
  const result = await pending;
  assert.equal(result.ok, true);
  assert.equal(result.data.fileCount, 2);
  assert.equal(result.data.artifact.entry, "b.pdf");
  assert.deepEqual(calls, [[42, "a.docx", "docx", "# Word"], [42, "b.pdf", "pdf", "# PDF"]]);
  for (const [index, file] of result.data.artifact.files.entries()) {
    assert.equal(file.content, "");
    assert.equal(file.language, "");
    assert.equal(file.attachmentId, index + 1);
    assert.equal(file.size, 123);
    assert.equal(file.url, `https://files.test/${file.path}`);
    assert.match(file.contentType, /^application\//);
  }
});

test("missing service and upload failures never return success", async () => {
  const input = { format: "pdf", files: [{ path: "report.md", content: "# Report" }] };
  await assert.rejects(createArtifactTool(undefined, 1, 1, 1).execute({}, input),
    error => error instanceof AgentToolExecutionError && error.code === "DOCUMENT_SERVICE_UNAVAILABLE");
  const failure = new Error("upload failed");
  await assert.rejects(createArtifactTool({ create: async () => { throw failure; } }, 1, 1, 1).execute({}, input), error => error === failure);
});

test("a later upload failure rejects the entire artifact delivery", async () => {
  let uploads = 0;
  const failure = new Error("second upload failed");
  const service = { async create() {
    if (++uploads === 2) throw failure;
    return { id: 1, url: "https://files.test/a.pdf", size: 123, contentType: "application/pdf" };
  } };
  await assert.rejects(createArtifactTool(service, 1, 1, 1).execute({}, {
    format: "pdf", files: [{ path: "a.md", content: "one" }, { path: "b.md", content: "two" }],
  }), error => error === failure);
  assert.equal(uploads, 2);
});

test("tool contract describes server rendering and accepts full document source", () => {
  const tool = ARTIFACT_TOOLS.find(tool => tool.name === "emit_artifact");
  assert.match(tool.description, /服务端.*Markdown.*Word\/PDF.*上传/);
  assert.doesNotMatch(tool.description, /客户端生成/);
  assert.equal(tool.parameters.properties.files.items.properties.content.maxLength, 200_000);
});
