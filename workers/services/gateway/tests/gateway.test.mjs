import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import test from "node:test";
import gatewayWorker from "../.test-build/index.js";

const secret = "gateway-local-test-secret-with-at-least-32-bytes";

test("CSDN connection test rejects anonymous callers and forwards the resolved owner only", async () => {
  let forwarded;
  await withFetch(async (input, init) => {
    const request = asRequest(input, init);
    if (new URL(request.url).pathname === "/internal/agent/auth/resolve") return authResolverResponse(17);
    forwarded = request;
    return Response.json({ code: 200, data: { username: "alice" } });
  }, async () => {
    const url = "https://gateway.example/api/publishing/csdn/connection/test";
    const anonymous = await gatewayWorker.fetch(new Request(url, { method: "POST", headers: { "x-user-id": "999" } }), environment());
    assert.equal(anonymous.status, 401);
    assert.equal(forwarded, undefined);
    const authorized = await gatewayWorker.fetch(new Request(url, { method: "POST",
      headers: { authorization: `Bearer ${signToken()}`, "x-user-id": "999" } }), environment());
    assert.equal(authorized.status, 200);
    assert.equal(new URL(forwarded.url).origin, "https://integration.example");
    assert.equal(forwarded.headers.get("x-user-id"), "17");
  });
});

function encode(value) {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function signToken({ subject = 17, role = "USER" } = {}) {
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({
    sub: String(subject),
    exp: Math.floor(Date.now() / 1000) + 600,
    type: "access",
    role,
    jti: "jwt-id-17",
    sid: "session-17",
  });
  const message = `${header}.${payload}`;
  const signature = createHmac("sha256", secret).update(message).digest("base64url");
  return `${message}.${signature}`;
}

function environment() {
  return {
    JWT_SECRET: secret,
    CORS_ALLOWED_ORIGINS: "https://app.example",
    CORE_SERVICE_URL: "https://core.example",
    CORE_SERVICE_TOKEN: "core-internal-secret",
    AGENT_SERVICE_URL: "https://agent.example",
    AGENT_SERVICE_TOKEN: "agent-internal-secret",
    PLATFORM_INTEGRATION_SERVICE_URL: "https://integration.example",
    PLATFORM_INTEGRATION_SERVICE_TOKEN: "integration-internal-secret",
    CONTENT_AUTOMATION_SERVICE_URL: "https://content.example",
    CONTENT_AUTOMATION_SERVICE_TOKEN: "content-internal-secret",
    BLOGHTML_PREVIEW_SERVICE_URL: "https://preview.example",
    BLOGHTML_PREVIEW_SERVICE_TOKEN: "preview-internal-secret",
    KNOWLEDGE_SERVICE_URL: "https://knowledge.example",
    KNOWLEDGE_SERVICE_TOKEN: "knowledge-internal-secret",
    PAYMENT_SERVICE_URL: "https://payment.example",
    PAYMENT_SERVICE_TOKEN: "payment-internal-secret",
  };
}

async function withFetch(mockFetch, action) {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = mockFetch;
  try {
    return await action();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

function asRequest(input, init) {
  return input instanceof Request ? input : new Request(input, init);
}

function authResolverResponse(userId = 17) {
  return new Response(JSON.stringify({ userId }), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
}

test("public routes strip forged identity and propagate safe correlation and CORS headers", async () => {
  let forwarded;
  await withFetch(async (input) => {
    forwarded = input;
    return new Response("ok", { status: 200 });
  }, async () => {
    const request = new Request("https://gateway.example/api/blog/posts", {
      headers: {
        origin: "https://app.example",
        "x-user-id": "999",
        "x-user-role": "ADMIN",
        "x-dinqor-authenticated-user-id": "999",
        "x-auth-jti": "forged",
        "x-dinqor-service-token": "forged",
        "x-forwarded-for": "203.0.113.8",
        "x-trace-id": "trace-safe.1",
      },
    });
    const response = await gatewayWorker.fetch(request, environment());

    assert.equal(response.status, 200);
    assert.equal(forwarded.url, "https://content.example/api/blog/posts");
    assert.equal(forwarded.headers.get("x-user-id"), null);
    assert.equal(forwarded.headers.get("x-user-role"), null);
    assert.equal(forwarded.headers.get("x-dinqor-authenticated-user-id"), null);
    assert.equal(forwarded.headers.get("x-auth-jti"), null);
    assert.equal(forwarded.headers.get("x-forwarded-for"), null);
    assert.equal(forwarded.headers.get("x-dinqor-service-token"), "content-internal-secret");
    assert.equal(forwarded.headers.get("x-trace-id"), "trace-safe.1");
    assert.match(forwarded.headers.get("x-request-id"), /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
    assert.equal(response.headers.get("x-trace-id"), "trace-safe.1");
    assert.equal(response.headers.get("access-control-allow-origin"), "https://app.example");
    assert.equal(response.headers.get("access-control-allow-credentials"), "true");
  });
});

test("same-account service bindings forward Gateway requests with the internal service token", async () => {
  let forwarded;
  const env = {
    ...environment(),
    CORE_SERVICE: {
      async fetch(request) {
        forwarded = request;
        return new Response(JSON.stringify({ code: 200, message: "success", data: false }), {
          headers: { "content-type": "application/json" },
        });
      },
    },
  };

  const response = await gatewayWorker.fetch(
    new Request("https://gateway.example/api/auth/validate"),
    env,
  );

  assert.equal(response.status, 200);
  assert.equal(forwarded.url, "https://core.example/api/auth/validate");
  assert.equal(forwarded.headers.get("x-dinqor-service-token"), "core-internal-secret");
  assert.deepEqual(await response.json(), { code: 200, message: "success", data: false });
});

test("Core routes delegate active-session validation to Core instead of resolving twice", async () => {
  const token = signToken();
  let forwarded;
  await withFetch(async (input, init) => {
    forwarded = asRequest(input, init);
    return new Response("ok");
  }, async () => {
    const response = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/recitations/materials?page=1", {
        headers: { authorization: `Bearer ${token}`, "x-user-id": "999" },
      }),
      environment(),
    );

    assert.equal(response.status, 200);
    assert.equal(forwarded.url, "https://core.example/api/recitations/materials?page=1");
    assert.equal(forwarded.headers.get("x-dinqor-service-token"), "core-internal-secret");
    assert.equal(forwarded.headers.get("x-user-id"), null);
    assert.equal(forwarded.headers.get("x-dinqor-authenticated-user-id"), null);
  });
});

test("private writes reject anonymous requests before reaching a service", async () => {
  let upstreamCalled = false;
  await withFetch(async () => {
    upstreamCalled = true;
    return new Response("unexpected");
  }, async () => {
    const response = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/blog/posts", { method: "POST" }),
      environment(),
    );
    assert.equal(response.status, 401);
    assert.deepEqual(await response.json(), {
      code: 401,
      message: "未登录或登录已过期",
      data: null,
    });
    assert.equal(upstreamCalled, false);
  });
});

test("MCP OAuth client management resolves the DinQorAI user session", async () => {
  const token = signToken();
  let forwarded;
  let upstreamCalled = false;
  await withFetch(async (input, init) => {
    const request = asRequest(input, init);
    if (request.url === "https://core.example/internal/agent/auth/resolve") {
      return authResolverResponse();
    }
    upstreamCalled = true;
    forwarded = request;
    return new Response("[]");
  }, async () => {
    const anonymous = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/mcp/oauth/clients"),
      environment(),
    );
    assert.equal(anonymous.status, 401);
    assert.equal(upstreamCalled, false);

    const authenticated = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/mcp/oauth/clients", {
        headers: { authorization: `Bearer ${token}` },
      }),
      environment(),
    );
    assert.equal(authenticated.status, 200);
    assert.equal(forwarded.url, "https://integration.example/api/mcp/oauth/clients");
    assert.equal(forwarded.headers.get("x-user-id"), "17");
    assert.equal(forwarded.headers.get("x-dinqor-authenticated-user-id"), "17");
    assert.equal(forwarded.headers.get("x-dinqor-service-token"), "integration-internal-secret");
  });
});

test("MCP transport and OAuth protocol endpoints keep their independent authentication", async () => {
  const forwarded = [];
  await withFetch(async (input, init) => {
    forwarded.push(asRequest(input, init));
    return new Response("accepted");
  }, async () => {
    const transport = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/mcp/csdn", {
        method: "POST",
        headers: { authorization: "Bearer mcp-oauth-access-token" },
        body: "{}",
      }),
      environment(),
    );
    assert.equal(transport.status, 200);
    assert.equal(forwarded.at(-1).headers.get("x-user-id"), null);
    assert.equal(forwarded.at(-1).headers.get("authorization"), "Bearer mcp-oauth-access-token");

    for (const path of ["/api/mcp/oauth/register", "/api/mcp/oauth/token", "/api/mcp/oauth/revoke"]) {
      const response = await gatewayWorker.fetch(
        new Request(`https://gateway.example${path}`, { method: "POST", body: "{}" }),
        environment(),
      );
      assert.equal(response.status, 200);
      assert.equal(forwarded.at(-1).url, `https://integration.example${path}`);
      assert.equal(forwarded.at(-1).headers.get("x-user-id"), null);
    }
  });
});

test("authenticated private requests resolve active identity and replace forged headers", async () => {
  const token = signToken();
  const calls = [];
  await withFetch(async (input, init) => {
    const request = asRequest(input, init);
    calls.push(request);
    if (request.url === "https://core.example/internal/agent/auth/resolve") {
      assert.equal(request.headers.get("x-dinqor-service-token"), "core-internal-secret");
      assert.deepEqual(await request.json(), { accessToken: token });
      return authResolverResponse();
    }
    return new Response("created", { status: 201 });
  }, async () => {
    const request = new Request("https://gateway.example/api/blog/posts", {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        "x-user-id": "999",
        "x-user-role": "ADMIN",
        "x-dinqor-authenticated-user-id": "999",
        "x-auth-jti": "forged",
      },
      body: "{}",
    });
    const response = await gatewayWorker.fetch(request, environment());

    assert.equal(response.status, 201);
    assert.equal(calls.length, 2);
    const forwarded = calls[1];
    assert.equal(forwarded.headers.get("x-user-id"), "17");
    assert.equal(forwarded.headers.get("x-dinqor-authenticated-user-id"), "17");
    assert.equal(forwarded.headers.get("x-user-role"), "USER");
    assert.equal(forwarded.headers.get("x-auth-jti"), "jwt-id-17");
    assert.equal(forwarded.headers.get("x-auth-sid"), "session-17");
    assert.equal(forwarded.headers.get("x-dinqor-service-token"), "content-internal-secret");
  });
});

test("admin routes reject authenticated non-admin users", async () => {
  const token = signToken({ role: "USER" });
  let upstreamCalled = false;
  await withFetch(async (input, init) => {
    const request = asRequest(input, init);
    if (request.url === "https://core.example/internal/agent/auth/resolve") {
      return authResolverResponse();
    }
    upstreamCalled = true;
    return new Response("unexpected");
  }, async () => {
    const response = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/admin/users", {
        headers: { authorization: `Bearer ${token}` },
      }),
      environment(),
    );
    assert.equal(response.status, 403);
    assert.equal(upstreamCalled, false);
  });
});

test("websocket policy accepts a query token and retains it for the upstream handshake", async () => {
  const token = signToken();
  let forwarded;
  await withFetch(async (input, init) => {
    const request = asRequest(input, init);
    if (request.url === "https://core.example/internal/agent/auth/resolve") {
      return authResolverResponse();
    }
    forwarded = request;
    return new Response("handshake accepted");
  }, async () => {
    const response = await gatewayWorker.fetch(
      new Request(`https://gateway.example/ws/agent?token=${encodeURIComponent(token)}`),
      environment(),
    );
    assert.equal(response.status, 200);
    assert.equal(forwarded.url, `https://agent.example/ws/agent?token=${encodeURIComponent(token)}`);
    assert.equal(forwarded.headers.get("x-user-id"), "17");
    assert.equal(forwarded.headers.get("x-dinqor-service-token"), "agent-internal-secret");
  });
});

test("CORS preflight is answered at the Gateway and disallowed origins are rejected", async () => {
  let upstreamCalled = false;
  await withFetch(async () => {
    upstreamCalled = true;
    return new Response("unexpected");
  }, async () => {
    const preflight = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/blog/posts", {
        method: "OPTIONS",
        headers: {
          origin: "https://app.example",
          "access-control-request-method": "POST",
          "access-control-request-headers": "authorization,content-type",
        },
      }),
      environment(),
    );
    assert.equal(preflight.status, 204);
    assert.equal(preflight.headers.get("access-control-allow-methods"), "GET, POST, PUT, PATCH, DELETE, OPTIONS");
    assert.equal(preflight.headers.get("access-control-allow-headers"), "authorization,content-type");

    const rejected = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/blog/posts", {
        headers: { origin: "https://attacker.example" },
      }),
      environment(),
    );
    assert.equal(rejected.status, 403);
    assert.equal(upstreamCalled, false);
  });
});

test("browser-extension CORS is limited to the one-use authorization completion route", async () => {
  const extensionOrigin = `chrome-extension://${"a".repeat(32)}`;
  const taskId = "123e4567-e89b-42d3-a456-426614174000";
  const completionPath = `/api/publishing/csdn/authorization/extension/${taskId}/complete`;
  let forwarded;

  await withFetch(async (input, init) => {
    forwarded = asRequest(input, init);
    return new Response("accepted");
  }, async () => {
    const preflight = await gatewayWorker.fetch(
      new Request(`https://gateway.example${completionPath}`, {
        method: "OPTIONS",
        headers: {
          origin: extensionOrigin,
          "access-control-request-method": "POST",
          "access-control-request-headers": "authorization,content-type",
        },
      }),
      environment(),
    );
    assert.equal(preflight.status, 204);
    assert.equal(preflight.headers.get("access-control-allow-origin"), extensionOrigin);

    const additionalPlatformPreflight = await gatewayWorker.fetch(
      new Request(`https://gateway.example/api/publishing/zhihu/authorization/extension/${taskId}/complete`, {
        method: "OPTIONS",
        headers: {
          origin: extensionOrigin,
          "access-control-request-method": "POST",
          "access-control-request-headers": "authorization,content-type",
        },
      }),
      environment(),
    );
    assert.equal(additionalPlatformPreflight.status, 204);
    assert.equal(additionalPlatformPreflight.headers.get("access-control-allow-origin"), extensionOrigin);

    const completion = await gatewayWorker.fetch(
      new Request(`https://gateway.example${completionPath}`, {
        method: "POST",
        headers: {
          origin: extensionOrigin,
          authorization: `Bearer ${"a".repeat(43)}`,
          "content-type": "application/json",
        },
        body: JSON.stringify({ credential: { cookie: "sessionid=secret" } }),
      }),
      environment(),
    );
    assert.equal(completion.status, 200);
    assert.equal(completion.headers.get("access-control-allow-origin"), extensionOrigin);
    assert.equal(forwarded.url, `https://integration.example${completionPath}`);

    const cancel = await gatewayWorker.fetch(
      new Request(`https://gateway.example/api/publishing/csdn/authorization/extension/${taskId}/cancel`, {
        method: "POST",
        headers: { origin: extensionOrigin, "content-type": "application/json" },
        body: "{}",
      }),
      environment(),
    );
    assert.equal(cancel.status, 403);
  });
});

test("optional public writes continue anonymously when the bearer token is invalid", async () => {
  let forwarded;
  await withFetch(async (input) => {
    forwarded = input;
    return new Response("counted", { status: 200 });
  }, async () => {
    const response = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/blog/posts/81/view", {
        method: "POST",
        headers: {
          authorization: "Bearer invalid-token",
          "x-user-id": "999",
        },
      }),
      environment(),
    );
    assert.equal(response.status, 200);
    assert.equal(forwarded.headers.get("x-user-id"), null);
    assert.equal(forwarded.headers.get("authorization"), "Bearer invalid-token");
  });
});

test("coarse rate limiting keys requests by route and authenticated user or edge IP", async () => {
  let rateKey;
  let upstreamCalled = false;
  const env = {
    ...environment(),
    GATEWAY_RATE_LIMIT_ENABLED: "true",
    GATEWAY_RATE_LIMITER: {
      async limit({ key }) {
        rateKey = key;
        return { success: false };
      },
    },
  };
  await withFetch(async () => {
    upstreamCalled = true;
    return new Response("unexpected");
  }, async () => {
    const response = await gatewayWorker.fetch(
      new Request("https://gateway.example/api/auth/login", {
        method: "POST",
        headers: { "cf-connecting-ip": "198.51.100.25" },
        body: "{}",
      }),
      env,
    );
    assert.equal(response.status, 429);
    assert.equal(response.headers.get("retry-after"), "60");
    assert.equal(rateKey, "core:POST:/api/auth/login:198.51.100.25");
    assert.equal(upstreamCalled, false);
  });
});

test("Swagger UI and OpenAPI docs are public and generated from the Gateway route manifest", async () => {
  const ui = await gatewayWorker.fetch(
    new Request("https://gateway.example/swagger-ui/index.html"),
    environment(),
  );
  assert.equal(ui.status, 200);
  assert.match(ui.headers.get("content-type"), /text\/html/);
  assert.match(await ui.text(), /\/v3\/api-docs/);

  const response = await gatewayWorker.fetch(
    new Request("https://gateway.example/v3/api-docs"),
    environment(),
  );
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /application\/json/);
  const spec = await response.json();
  assert.equal(spec.openapi, "3.0.3");
  assert.ok(spec.paths["/api/notifications/events"].get);
  assert.ok(spec.paths["/api/notifications"].get);
  assert.ok(Object.keys(spec.paths).length > 100);
  assert.equal(JSON.stringify(spec).includes("/internal/"), false);
});
