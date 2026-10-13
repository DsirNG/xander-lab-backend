import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

test("migration 0009 preserves extension tasks and accepts WeChat MP", () => {
  const db = new DatabaseSync(":memory:");
  db.exec(`
    CREATE TABLE platform_extension_authorization (
      id TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL CHECK (user_id > 0),
      platform TEXT NOT NULL CHECK (platform IN ('csdn', 'juejin')),
      token_hash TEXT NOT NULL CHECK (length(token_hash) = 43),
      status TEXT NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
      expires_at TEXT NOT NULL,
      completed_at TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
    );
    CREATE INDEX idx_platform_extension_authorization_expiry
      ON platform_extension_authorization(status, expires_at);
    INSERT INTO platform_extension_authorization
      (id, user_id, platform, token_hash, status, expires_at)
    VALUES ('existing-task', 71, 'csdn', '${"a".repeat(43)}', 'PENDING', '2026-10-13T12:00:00.000Z');
  `);

  const migration = readFileSync(new URL("../migrations/0009_expand_platform_extension_authorization_platforms.sql", import.meta.url), "utf8");
  db.exec(migration);

  assert.deepEqual({ ...db.prepare("SELECT id, platform, status FROM platform_extension_authorization").get() }, {
    id: "existing-task", platform: "csdn", status: "PENDING",
  });
  assert.doesNotThrow(() => db.prepare(`
    INSERT INTO platform_extension_authorization
      (id, user_id, platform, token_hash, status, expires_at)
    VALUES ('wechat-task', 72, 'wechat_mp', '${"b".repeat(43)}', 'PENDING', '2026-10-13T12:00:00.000Z')
  `).run());
  assert.throws(() => db.prepare(`
    INSERT INTO platform_extension_authorization
      (id, user_id, platform, token_hash, status, expires_at)
    VALUES ('invalid-task', 73, 'unknown', '${"c".repeat(43)}', 'PENDING', '2026-10-13T12:00:00.000Z')
  `).run(), /CHECK constraint failed/);
  db.close();
});
