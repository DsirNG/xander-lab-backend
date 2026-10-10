import assert from "node:assert/strict";
import test from "node:test";
import { hashPassword, verifyPassword } from "../.test-build/password.js";

const pepper = "local-test-password-pepper-at-least-32-bytes";

test("passwords use salted PBKDF2 and verify without accepting legacy BCrypt hashes", async () => {
  const first = await hashPassword("correct horse battery staple", pepper);
  const second = await hashPassword("correct horse battery staple", pepper);
  assert.match(first, /^\$pbkdf2-sha256-hmacpepper-v1\$100000\$/);
  assert.notEqual(first, second);
  assert.equal(await verifyPassword("correct horse battery staple", first, pepper), true);
  assert.equal(await verifyPassword("wrong password", first, pepper), false);
  assert.equal(await verifyPassword(
    "correct horse battery staple",
    first,
    "another-pepper-value-that-is-at-least-32-bytes",
  ), false);
  assert.equal(await verifyPassword("password", "$2a$10$legacy-bcrypt-hash", pepper), false);
  assert.equal(await verifyPassword("password", "$pbkdf2-sha256$1$bad$hash", pepper), false);
});
