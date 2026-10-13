import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";

test("stale extension contexts fail closed without uncaught sendMessage errors", async () => {
  const source = await readFile(new URL("../content-bridge.js", import.meta.url), "utf8");
  const windowListeners = new Map();
  const postedMessages = [];
  const origin = "http://localhost:3000";
  let extensionListener;
  const window = {
    location: { origin },
    addEventListener(type, listener) { windowListeners.set(type, listener); },
    postMessage(message) { postedMessages.push(message); },
  };
  const chrome = {
    runtime: {
      id: "connector-extension",
      onMessage: { addListener(listener) { extensionListener = listener; } },
      sendMessage() { throw new Error("Extension context invalidated"); },
    },
  };

  runInNewContext(source, { chrome, window });
  const receiveMessage = windowListeners.get("message");
  const sendPageMessage = (data) => receiveMessage({ source: window, origin, data });

  assert.doesNotThrow(() => sendPageMessage({ channel: "dinqorai-platform-connector", type: "CANCEL", taskId: "123e4567-e89b-42d3-a456-426614174000" }));
  assert.doesNotThrow(() => sendPageMessage({ channel: "dinqorai-platform-connector", type: "PING", requestId: "ping-1" }));
  assert.equal(postedMessages.at(-1).type, "PONG");
  assert.equal(postedMessages.at(-1).available, false);

  assert.doesNotThrow(() => sendPageMessage({
    channel: "dinqorai-platform-connector",
    type: "START",
    requestId: "start-1",
    platform: "csdn",
    taskId: "123e4567-e89b-42d3-a456-426614174001",
    transferToken: "A".repeat(43),
  }));
  assert.equal(postedMessages.at(-1).type, "START_RESULT");
  assert.equal(postedMessages.at(-1).ok, false);

  extensionListener({
    type: "AUTHORIZATION_PROGRESS",
    taskId: "123e4567-e89b-42d3-a456-426614174002",
    state: "WAITING_FOR_SESSION",
    errorCode: "SESSION_COOKIE_MISSING",
    diagnostic: {
      sitePermissionGranted: true,
      sessionCookieFound: false,
      cookieCount: 12,
      cookie: "secret",
      cookieScopes: {
        domain: 2,
        userToken: 1,
        username: 3,
        partitioned: 4,
        partitionedToken: 5,
        identityHints: 2,
        cookieStoreMatched: true,
        partitionKeyAvailable: false,
        UserToken: "secret",
      },
    },
  });
  assert.equal(postedMessages.at(-1).errorCode, "SESSION_COOKIE_MISSING");
  assert.equal(JSON.stringify(postedMessages.at(-1).diagnostic), JSON.stringify({
    sitePermissionGranted: true,
    sessionCookieFound: false,
    cookieCount: 12,
    cookieScopes: {
      domain: 2,
      userToken: 1,
      username: 3,
      partitioned: 4,
      partitionedToken: 5,
      identityHints: 2,
      cookieStoreMatched: true,
      partitionKeyAvailable: false,
    },
  }));
});
