import assert from "node:assert/strict";
import test from "node:test";
import { apiLanguage, englishNotice, localizeApiResponse } from "../.test-build/api-language.js";
import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import ts from "typescript";
import gateway from "../.test-build/index.js";

const request = (language, path = "/api/auth/login") => new Request(`https://gateway.example${path}`, {
  headers: language ? { "accept-language": language } : {},
});
const envelope = (message, status = 400, data = null) => Response.json({ code: status, message, data }, { status });

test("language negotiation supports regional tags, weights and Chinese fallback", () => {
  for (const [header, expected] of [
    [null, "zh-CN"], ["fr, en-GB;q=0.8,zh;q=0.5", "en"],
    ["en;q=0.5, zh-TW;q=0.9", "zh-CN"], ["EN-us", "en"],
    ["en;q=0, zh;q=0.5", "zh-CN"], ["en;q=invalid", "zh-CN"],
    ["en;q=2", "zh-CN"], ["french,not-en", "zh-CN"],
  ]) assert.equal(apiLanguage(header), expected);
});

test("public API notices translate actionable validations and retain code and business data", async () => {
  const data = { title: "中文文章", message: "用户内容", history: ["过去的回答"], value: 23 };
  const upstream = envelope("密码长度为 6-50 个字符", 400, data);
  upstream.headers.set("retry-after", "10");
  upstream.headers.set("vary", "Origin");
  upstream.headers.set("etag", '"old-body"');
  upstream.headers.set("content-length", "999");
  const response = await localizeApiResponse(upstream, request("en"));
  assert.equal(response.status, 400);
  assert.deepEqual(await response.json(), {
    code: 400, message: "The password must contain 6–50 characters", data,
  });
  assert.equal(response.headers.get("content-language"), "en");
  assert.equal(response.headers.get("vary"), "Origin, Accept-Language");
  assert.equal(response.headers.get("retry-after"), "10");
  assert.equal(response.headers.get("etag"), null);
  assert.equal(response.headers.get("content-length"), null);
});

test("Chinese and English success and errors stay separate from Agent reply preferences", async () => {
  for (const [source, status, language, expected] of [
    ["success", 200, "en", "Success"], ["success", 200, "zh-CN", "操作成功"],
    ["未登录或登录已过期", 401, "en", "You are signed out or your session has expired"],
    ["积分不足，请先获取积分", 402, "en", "Insufficient points. Please add points first"],
    ["会话 ID 无效", 400, "en", "Invalid conversation id"],
    ["AppID 不能为空", 400, "en", "AppID is required"],
    ["Invalid request body", 400, "zh-CN", "请求内容格式不正确"],
  ]) {
    const req = request(language);
    req.headers.set("x-reply-language", language === "en" ? "zh" : "en");
    const response = await localizeApiResponse(envelope(source, status), req);
    assert.equal((await response.json()).message, expected);
    assert.equal(response.status, status);
  }
});

test("unknown notices have localized fallbacks without changing business error codes", async () => {
  for (const language of ["en", "zh-CN"]) {
    const response = await localizeApiResponse(Response.json({
      code: 1001, message: "unrecognized upstream text", data: null,
    }, { status: 400 }), request(language));
    const body = await response.json();
    assert.equal(body.code, 1001);
    assert.equal(body.message, language === "en"
      ? "Invalid request. Please check your input" : "请求参数不正确，请检查后重试");
  }
  for (const message of ["toString", "__proto__", "constructor"]) {
    const response = await localizeApiResponse(envelope(message), request("en"));
    assert.equal((await response.json()).message, "Invalid request. Please check your input");
  }
});

test("existing literal API and business error notices have English translations", () => {
  const services = fileURLToPath(new URL("../../", import.meta.url));
  const messages = new Set();
  function scan(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) { scan(path); continue; }
      if (!path.endsWith(".ts")) continue;
      const source = ts.createSourceFile(path, readFileSync(path, "utf8"), ts.ScriptTarget.Latest, true);
      function collect(node) {
        const expression = (ts.isCallExpression(node) || ts.isNewExpression(node)) && node.expression.getText(source);
        if (expression && (/apiError|BusinessError|errorResponse/.test(expression)
          || ts.isNewExpression(node) && /Error/.test(expression))) {
          for (const argument of node.arguments || []) {
            if (ts.isStringLiteral(argument) && /[\u3400-\u9fff]/.test(argument.text)) messages.add(argument.text);
          }
        }
        if (ts.isPropertyAssignment(node) && node.name.getText(source) === "message"
          && ts.isStringLiteral(node.initializer) && /[\u3400-\u9fff]/.test(node.initializer.text)) {
          messages.add(node.initializer.text);
        }
        ts.forEachChild(node, collect);
      }
      collect(source);
    }
  }
  for (const entry of readdirSync(services, { withFileTypes: true })) {
    if (entry.isDirectory()) scan(join(services, entry.name, "src"));
  }
  assert.ok(messages.size > 600, "The coverage scan must include all services");
  assert.deepEqual([...messages].filter(message => !englishNotice(message)), [], "Missing English API notices");
  for (const message of messages) assert.doesNotMatch(englishNotice(message), /[\u3400-\u9fff]/);
});

test("streams are not consumed, and downloads, protocol responses and non-envelopes are untouched", async () => {
  let cancelled = false;
  const stream = new Response(new ReadableStream({ cancel() { cancelled = true; } }), {
    headers: { "content-type": "text/event-stream" },
  });
  assert.equal(await localizeApiResponse(stream, request("en")), stream);
  assert.equal(stream.bodyUsed, false);
  await stream.body.cancel();
  assert.equal(cancelled, true);
  const download = envelope("success", 200, { message: "中文" });
  download.headers.set("content-disposition", "attachment; filename=export.json");
  const cases = [
    [download, request("en")], [envelope("success", 200), request("en", "/mcp")],
    [Response.json({ message: "中文" }), request("en")],
    [new Response("invalid JSON", { headers: { "content-type": "application/json" } }), request("en")],
    [new Response(null, { status: 204 }), request("en")],
  ];
  for (const [response, req] of cases) {
    assert.equal(await localizeApiResponse(response, req), response);
    assert.equal(response.bodyUsed, false);
  }
});

test("Gateway localizes authentication and upstream responses, forwards locale and keeps CORS", async () => {
  const env = {
    CORS_ALLOWED_ORIGINS: "https://app.example", CORE_SERVICE_TOKEN: "secret",
    CORE_SERVICE: { async fetch(req) {
      assert.equal(req.headers.get("accept-language"), "en");
      return envelope("邮箱格式不正确");
    } },
  };
  const auth = await gateway.fetch(new Request("https://gateway.example/api/blog/posts", {
    method: "POST",
    headers: { "accept-language": "en", origin: "https://app.example" },
  }), env);
  assert.equal(auth.status, 401);
  assert.equal((await auth.json()).message, "You are signed out or your session has expired");
  assert.equal(auth.headers.get("access-control-allow-origin"), "https://app.example");
  assert.ok(auth.headers.get("x-trace-id"));
  const response = await gateway.fetch(new Request("https://gateway.example/api/auth/login", {
    method: "POST", headers: { "accept-language": "en" },
  }), env);
  assert.equal(response.status, 400);
  assert.equal((await response.json()).message, "Invalid email address");
});
