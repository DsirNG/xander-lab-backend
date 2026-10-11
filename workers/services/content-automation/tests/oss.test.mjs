import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";
import test from "node:test";
import { OssArticleContentMapper } from "../.test-build/mappers/oss-article-content-mapper.js";
import { OssBlogMediaMapper } from "../.test-build/mappers/oss-blog-media-mapper.js";
import {
  buildOssCanonicalRequest,
  createOssObjectUrl,
  createOssV4Authorization,
  inferOssRegion,
  signedOssGetHeaders,
  signedOssHeadHeaders,
  signedOssPostHeaders,
  signedOssPutHeaders,
} from "../src/oss.ts";

test("OSS V4 signing uses the canonical request in Aliyun's published example", async () => {
  const headers = new Headers({
    "content-disposition": "attachment",
    "content-length": "3",
    "content-md5": "ICy5YqxZB1uWSwcVLSNLcA==",
    "content-type": "text/plain",
    "x-oss-content-sha256": "UNSIGNED-PAYLOAD",
    "x-oss-date": "20250411T064124Z",
  });
  const url = new URL("https://oss-cn-hangzhou.aliyuncs.com/examplebucket/exampleobject");
  const canonicalRequest = buildOssCanonicalRequest(
    "PUT",
    url,
    headers,
    ["content-disposition", "content-length"],
  );
  assert.equal(
    createHash("sha256").update(canonicalRequest).digest("hex"),
    "c46d96390bdbc2d739ac9363293ae9d710b14e48081fcb22cd8ad54b63136eca",
  );
  const hmac = (key, value) => createHmac("sha256", key).update(value).digest();
  const dateKey = hmac("aliyun_v4yourAccessKeySecret", "20250411");
  const regionKey = hmac(dateKey, "cn-hangzhou");
  const serviceKey = hmac(regionKey, "oss");
  const signingKey = hmac(serviceKey, "aliyun_v4_request");
  const stringToSign = [
    "OSS4-HMAC-SHA256",
    "20250411T064124Z",
    "20250411/cn-hangzhou/oss/aliyun_v4_request",
    "c46d96390bdbc2d739ac9363293ae9d710b14e48081fcb22cd8ad54b63136eca",
  ].join("\n");
  const expectedSignature = hmac(signingKey, stringToSign).toString("hex");
  const authorization = await createOssV4Authorization(
    "PUT",
    url,
    headers,
    {
      accessKeyId: "LTAI****************",
      accessKeySecret: "yourAccessKeySecret",
      region: "cn-hangzhou",
    },
    "20250411T064124Z",
    ["content-disposition", "content-length"],
  );

  assert.equal(
    authorization,
    "OSS4-HMAC-SHA256 Credential=LTAI****************/20250411/cn-hangzhou/oss/aliyun_v4_request, AdditionalHeaders=content-disposition;content-length, Signature=" + expectedSignature,
  );
});

test("OSS object URLs encode path segments and reject traversal", () => {
  assert.equal(
    createOssObjectUrl(
      "https://examplebucket.oss-cn-hangzhou.aliyuncs.com",
      "articles/first draft?.md",
    ).href,
    "https://examplebucket.oss-cn-hangzhou.aliyuncs.com/articles/first%20draft%3F.md",
  );
  assert.throws(
    () => createOssObjectUrl(
      "https://examplebucket.oss-cn-hangzhou.aliyuncs.com",
      "articles/../private.md",
    ),
    /Invalid OSS object key/,
  );
});

test("regional OSS endpoints combine with the existing separate bucket setting", () => {
  const url = createOssObjectUrl(
    "oss-cn-beijing.aliyuncs.com",
    "article-content/a.md",
    "food-cinder",
  );
  assert.equal(
    url.href,
    "https://food-cinder.oss-cn-beijing.aliyuncs.com/article-content/a.md",
  );
  assert.equal(inferOssRegion("oss-cn-beijing.aliyuncs.com"), "cn-beijing");
  assert.equal(
    inferOssRegion("https://food-cinder.oss-cn-beijing.aliyuncs.com"),
    "cn-beijing",
  );
});

test("virtual-host object requests include the bucket in the V4 canonical URI", () => {
  const url = createOssObjectUrl(
    "oss-cn-beijing.aliyuncs.com",
    "diagnostics/core-oss-smoke-test.txt",
    "dinqor",
  );
  const canonicalRequest = buildOssCanonicalRequest("PUT", url, new Headers({
    "content-type": "text/plain; charset=utf-8",
    "x-oss-content-sha256": "UNSIGNED-PAYLOAD",
    "x-oss-date": "20261011T075126Z",
  }));

  assert.equal(url.pathname, "/diagnostics/core-oss-smoke-test.txt");
  assert.equal(canonicalRequest.split("\n")[1], "/dinqor/diagnostics/core-oss-smoke-test.txt");
});

test("OSS canonical URI adds a virtual-host bucket for direct URLs and custom endpoints", () => {
  const headers = new Headers({
    "x-oss-content-sha256": "UNSIGNED-PAYLOAD",
    "x-oss-date": "20261011T075126Z",
  });
  const virtualHostUrl = new URL("https://examplebucket.oss-cn-hangzhou.aliyuncs.com/exampleobject");
  const customEndpointUrl = createOssObjectUrl("https://oss.example.com", "exampleobject", "examplebucket");

  assert.equal(buildOssCanonicalRequest("GET", virtualHostUrl, headers).split("\n")[1], "/examplebucket/exampleobject");
  assert.equal(buildOssCanonicalRequest("GET", customEndpointUrl, headers).split("\n")[1], "/examplebucket/exampleobject");
});

test("private object GET uses server-side OSS headers without an optional-header clause", async () => {
  const headers = await signedOssGetHeaders(
    new URL("https://examplebucket.oss-cn-hangzhou.aliyuncs.com/article-content/a.md"),
    {
      accessKeyId: "test-access-key",
      accessKeySecret: "test-access-secret",
      region: "cn-hangzhou",
    },
    new Date("2025-04-11T06:41:24.000Z"),
  );

  assert.equal(headers.get("x-oss-date"), "20250411T064124Z");
  assert.equal(headers.get("x-oss-content-sha256"), "UNSIGNED-PAYLOAD");
  assert.match(headers.get("authorization") ?? "", /^OSS4-HMAC-SHA256 Credential=test-access-key\/20250411\/cn-hangzhou\/oss\/aliyun_v4_request, Signature=[0-9a-f]{64}$/);
});

test("OSS multipart query canonicalization preserves valueless operation flags", () => {
  const canonical = buildOssCanonicalRequest(
    "POST",
    new URL("https://bucket.oss-cn-hangzhou.aliyuncs.com/key?uploadId=abc&uploads"),
    new Headers({
      "content-type": "application/xml",
      "x-oss-content-sha256": "UNSIGNED-PAYLOAD",
      "x-oss-date": "20250411T064124Z",
    }),
  );
  assert.match(canonical, /\nuploadId=abc&uploads\n/);
});

test("OSS signs multipart POST and object HEAD methods with V4 headers", async () => {
  const url = new URL("https://bucket.oss-cn-hangzhou.aliyuncs.com/key?uploads");
  const credentials = {
    accessKeyId: "test-access-key",
    accessKeySecret: "test-access-secret",
    region: "cn-hangzhou",
  };
  const post = await signedOssPostHeaders(url, credentials, "application/xml",
    new Date("2025-04-11T06:41:24.000Z"));
  const head = await signedOssHeadHeaders(url, credentials,
    new Date("2025-04-11T06:41:24.000Z"));

  assert.equal(post.get("content-type"), "application/xml");
  assert.match(post.get("authorization") ?? "", /^OSS4-HMAC-SHA256 Credential=test-access-key\/20250411\/cn-hangzhou\/oss\/aliyun_v4_request, Signature=/);
  assert.match(head.get("authorization") ?? "", /^OSS4-HMAC-SHA256 Credential=test-access-key\/20250411\/cn-hangzhou\/oss\/aliyun_v4_request, Signature=/);
});

test("OSS multipart part PUT uses a signed content type without requiring a manual content length", async () => {
  const headers = await signedOssPutHeaders(
    new URL("https://bucket.oss-cn-hangzhou.aliyuncs.com/key?partNumber=1&uploadId=abc"),
    {
      accessKeyId: "test-access-key",
      accessKeySecret: "test-access-secret",
      region: "cn-hangzhou",
    },
    "application/octet-stream",
    new Date("2025-04-11T06:41:24.000Z"),
  );

  assert.equal(headers.get("content-type"), "application/octet-stream");
  assert.equal(headers.has("content-length"), false);
  assert.match(headers.get("authorization") ?? "", /^OSS4-HMAC-SHA256 Credential=test-access-key\/20250411\/cn-hangzhou\/oss\/aliyun_v4_request, Signature=/);
});

test("article content mapper signs OSS PUT and DELETE while keeping body out of D1", async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: new URL(url), init });
    return init.method === "GET" ? new Response("large markdown body") : new Response(null, { status: 200 });
  };
  try {
    const mapper = new OssArticleContentMapper({
      ALIYUN_OSS_ENDPOINT: "oss-cn-beijing.aliyuncs.com",
      ALIYUN_OSS_BUCKET: "article-bucket",
      ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
      ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
    });
    await mapper.write("posts/42/article.md", "large markdown body");
    assert.equal(await mapper.read("posts/42/article.md"), "large markdown body");
    await mapper.delete("posts/42/article.md");
    assert.deepEqual(calls.map(({ init }) => init.method), ["PUT", "GET", "DELETE"]);
    assert.equal(calls[0].url.href, "https://article-bucket.oss-cn-beijing.aliyuncs.com/article-content/posts/42/article.md");
    assert.equal(new Headers(calls[0].init.headers).get("content-type"), "text/markdown; charset=utf-8");
    assert.match(new Headers(calls[0].init.headers).get("authorization") ?? "", /^OSS4-HMAC-SHA256/);
    assert.match(new Headers(calls[2].init.headers).get("authorization") ?? "", /^OSS4-HMAC-SHA256/);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("OSS migration writes to dinqor, reads old objects from food-cinder, and returns proxied media URLs", async () => {
  const previousFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(input);
    calls.push({ url, method: init.method });
    if (init.method === "GET" && url.hostname.startsWith("dinqor.")) return new Response(null, { status: 404 });
    if (init.method === "GET") return new Response("old markdown");
    if (init.method === "HEAD" && url.hostname.startsWith("dinqor.")) return new Response(null, { status: 404 });
    if (init.method === "HEAD") return new Response(null, { status: 200 });
    return new Response(null, { status: 204 });
  };
  try {
    const env = {
      ALIYUN_OSS_ENDPOINT: "oss-cn-beijing.aliyuncs.com",
      ALIYUN_OSS_BUCKET: "dinqor",
      ALIYUN_OSS_LEGACY_BUCKET: "food-cinder",
      ALIYUN_OSS_ACCESS_KEY_ID: "test-access-key",
      ALIYUN_OSS_ACCESS_KEY_SECRET: "test-access-secret",
    };
    const articles = new OssArticleContentMapper(env);
    await articles.write("posts/42/article.md", "new markdown");
    assert.equal(calls.at(-1).url.hostname, "dinqor.oss-cn-beijing.aliyuncs.com");
    assert.equal(await articles.read("posts/42/article.md"), "old markdown");
    assert.deepEqual(calls.filter((call) => call.method === "GET").map((call) => call.url.hostname), [
      "dinqor.oss-cn-beijing.aliyuncs.com", "food-cinder.oss-cn-beijing.aliyuncs.com",
    ]);

    const media = new OssBlogMediaMapper({
      ...env,
      CONTENT_MEDIA_PUBLIC_BASE_URL: "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media",
    });
    const stored = await media.upload("42/2026/10/00000000-0000-0000-0000-000000000001.png", new Uint8Array([1]), "image/png");
    assert.equal(stored.url,
      "https://dinqor-content-automation.dinqor.workers.dev/api/public/blog/media/42/2026/10/00000000-0000-0000-0000-000000000001.png");
    assert.equal(calls.at(-1).url.hostname, "dinqor.oss-cn-beijing.aliyuncs.com");
  } finally {
    globalThis.fetch = previousFetch;
  }
});
