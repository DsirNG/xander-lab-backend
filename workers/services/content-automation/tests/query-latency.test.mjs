import assert from "node:assert/strict";
import test from "node:test";
import { D1BlogPublicMapper } from "../.test-build/mappers/d1-blog-public-mapper.js";
import { D1BlogManagementMapper } from "../.test-build/mappers/d1-blog-management-mapper.js";

for (const kind of ["management"]) {
  test(`${kind} pagination starts rows before its count finishes`, async () => {
    let finishCount;
    let rowsStarted = false;
    const db = { prepare() {
      const statement = {
        bind() { return statement; },
        first() { return new Promise(resolve => { finishCount = resolve; }); },
        async all() { rowsStarted = true; return { results: [] }; },
      };
      return statement;
    } };
    const pending = kind === "public"
      ? new D1BlogPublicMapper(db).listPublished({ page: 1, size: 20, category: "", tag: "", search: "" })
      : new D1BlogManagementMapper(db).listMine(42, null, "", 1, 20);
    assert.equal(rowsStarted, true, "list query must not wait for the count network round trip");
    finishCount({ total: 0, count: 0 });
    assert.equal((await pending).total, 0);
  });
}

test("reminder count, rows and stats start without serial D1 waits", async () => {
  const { D1EmailReminderMapper } = await import("../.test-build/mappers/d1-email-reminder-mapper.js");
  const pendingQueries = [];
  const db = { prepare(sql) {
    const statement = {
      bind() { return statement; },
      first() { return new Promise(resolve => pendingQueries.push(() => resolve(sql.includes("AS active") ? { total: 0, active: 0, sent: 0, pending: 0 } : { count: 0 }))); },
      all() { return new Promise(resolve => pendingQueries.push(() => resolve({ results: [] }))); },
    };
    return statement;
  } };
  const pending = new D1EmailReminderMapper(db).listMine({ userId: 42, status: null, search: "", page: 1, size: 20 });
  assert.equal(pendingQueries.length, 3);
  pendingQueries.forEach(resolve => resolve());
  const page = await pending;
  assert.equal(page.total, 0);
  assert.deepEqual(page.records, []);
});

test("management statistics merge counts and start views without waiting", async () => {
  const reads = [];
  const db = { prepare(sql) {
    let values;
    const statement = {
      bind(...args) { values = args; return statement; },
      first() { return new Promise(resolve => reads.push({ sql, values, finish: () => resolve({ totalArticles: 5, drafts: 2, published: 3, trashed: 1, recentPublished: 2 }) })); },
      all() { return new Promise(resolve => reads.push({ sql, values, finish: () => resolve({ results: [{ date: "2026-10-30", views: 10 }] }) })); },
    };
    return statement;
  } };
  const pending = new D1BlogManagementMapper(db).manageStats(42, "2026-10-30");
  assert.equal(reads.length, 2, "both independent reads must start immediately");
  assert.deepEqual(reads[0].values, ["2026-10-24", "2026-10-30", 42]);
  assert.deepEqual(reads[1].values, [42, "2026-10-24T00:00:00.000Z", "2026-10-31T00:00:00.000Z"]);
  reads.forEach(read => read.finish());
  const stats = await pending;
  assert.equal(reads.length, 2, "recent publication count must not require a third query");
  assert.equal(stats.recentPublished, 2);
  assert.equal(stats.recentViews, 10);
  assert.equal(stats.averageViews, 5);
  assert.equal(stats.dailyViews.length, 7);
  assert.equal(stats.dailyViews[0].views, 0);
});

test("public page and total use one atomic D1 batch", async () => {
  let batches = 0;
  const db = {
    prepare(sql) { return { sql, bind(...values) { this.values = values; return this; } }; },
    async batch(statements) {
      batches++;
      assert.equal(statements.length, 2);
      assert.match(statements[0].sql, /COUNT/);
      assert.match(statements[1].sql, /ORDER BY/);
      return [
        { success: true, results: [{ total: 0 }] },
        { success: true, results: [] },
      ];
    },
  };
  const result = await new D1BlogPublicMapper(db).listPublished({ page: 1, size: 10, category: "", tag: "", search: "" });
  assert.equal(batches, 1);
  assert.equal(result.total, 0);
  assert.deepEqual(result.records, []);
});
