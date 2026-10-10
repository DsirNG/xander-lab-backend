import assert from "node:assert/strict";
import test from "node:test";
import contentWorker from "../.test-build/index.js";

const serviceToken = "content-internal-contract-test-token";

function environment() {
  const queries = [];
  return {
    queries,
    INTERNAL_SERVICE_TOKEN: serviceToken,
    DB: {
      prepare(query) {
        let values = [];
        const sql = query.replace(/\s+/g, " ").trim();
        const statement = {
          bind(...bound) { values = bound; return statement; },
          async first() {
            queries.push({ sql, values });
            if (sql.includes("SELECT COUNT(*) AS count FROM blog_post")) {
              return { count: values[0] === 42 ? 3 : 0 };
            }
            throw new Error(`Unexpected query: ${sql}`);
          },
        };
        return statement;
      },
    },
  };
}

function request(body, token = serviceToken) {
  return new Request("https://content.test/internal/content/profile/managed-count", {
    method: "POST",
    headers: { "content-type": "application/json", "x-dinqor-service-token": token },
    body: JSON.stringify(body),
  });
}

test("managed post count is an internal, authenticated, owner-scoped read", async () => {
  const env = environment();
  const unauthorized = await contentWorker.fetch(request({ userId: 42 }, "wrong-token"), env);
  assert.equal(unauthorized.status, 401);
  assert.equal(env.queries.length, 0);

  const counted = await contentWorker.fetch(request({ userId: 42 }), env);
  assert.equal(counted.status, 200);
  assert.deepEqual(await counted.json(), { count: 3 });
  assert.deepEqual(env.queries[0], {
    sql: "SELECT COUNT(*) AS count FROM blog_post WHERE user_id = ? AND status IN (0, 1)",
    values: [42],
  });

  const malformed = await contentWorker.fetch(request({ userId: "42" }), env);
  assert.equal(malformed.status, 400);
});
