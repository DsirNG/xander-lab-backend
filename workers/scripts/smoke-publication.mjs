import { createHmac, randomInt, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const workersDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const coreDirectory = resolve(workersDirectory, "services/core");
const contentDirectory = resolve(workersDirectory, "services/content-automation");
const userId = randomInt(4_000_000, 5_000_000);
const username = `publication-smoke-${userId}`;
const gatewayUrl = process.env.GATEWAY_URL || "http://127.0.0.1:8787";
const coreUrl = process.env.CORE_URL || "http://127.0.0.1:8788";
const contentUrl = process.env.CONTENT_AUTOMATION_URL || "http://127.0.0.1:8791";

function readVars(text) {
  return Object.fromEntries(text.split(/\r?\n/)
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => {
      const separator = line.indexOf("=");
      return [line.slice(0, separator), JSON.parse(line.slice(separator + 1))];
    }));
}

function runD1(statement, { databaseName = "dinqor-content-automation-local", directory = contentDirectory } = {}) {
  const pnpmEntry = process.env.npm_execpath;
  if (!pnpmEntry) throw new Error("Run this smoke check through `pnpm smoke:publication`.");
  const result = spawnSync(process.execPath, [pnpmEntry, "exec", "wrangler", "d1", "execute",
    databaseName, "--local", "--json", "--command", statement,
  ], { cwd: directory, encoding: "utf8", windowsHide: true });
  if (result.status !== 0) {
    throw new Error(`Local Content D1 smoke query failed (${result.error?.message ?? result.status ?? "spawn"}).`);
  }
  try {
    return JSON.parse(result.stdout);
  } catch {
    throw new Error("Local Content D1 smoke query returned invalid JSON.");
  }
}

function accessToken(secret, subject = userId, role = "USER") {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const signingInput = `${encode({ alg: "HS256", typ: "JWT" })}.${encode({
    sub: String(subject), exp: Math.floor(Date.now() / 1000) + 300, type: "access", ver: 0, role,
  })}`;
  return `${signingInput}.${createHmac("sha256", secret).update(signingInput).digest("base64url")}`;
}

const contentVars = readVars(await readFile(resolve(contentDirectory, ".dev.vars"), "utf8"));
const coreVars = readVars(await readFile(resolve(coreDirectory, ".dev.vars"), "utf8"));
const gatewayVars = readVars(await readFile(resolve(workersDirectory, "services/gateway/.dev.vars"), "utf8"));
let userInserted = false;
let adminInserted = false;
let postId;
let mediaId;
let planId;
let materialId;
let blogAgentTaskId;
try {
  runD1(`INSERT INTO sys_user(id, username, nickname, email, role, status) VALUES (${userId}, '${username}', 'Publication Smoke', '${username}@example.test', 'USER', 1)`, {
    databaseName: "dinqor-core-local",
    directory: coreDirectory,
  });
  userInserted = true;
  const adminId = userId + 1;
  runD1(`INSERT INTO sys_user(id, username, nickname, email, role, status) VALUES (${adminId}, '${username}-admin', 'Smoke Admin', '${username}-admin@example.test', 'ADMIN', 1)`, {
    databaseName: "dinqor-core-local",
    directory: coreDirectory,
  });
  adminInserted = true;
  const createBody = {
    title: `Publication smoke ${userId}`,
    summary: "Local article creation and idempotent retry",
    content: "A small article body created through the Gateway and Content Worker.",
    categoryId: "backend",
    tags: [`publication-smoke-${userId}`],
    publish: false,
  };
  const idempotencyKey = randomUUID();
  const create = () => fetch(`${gatewayUrl}/api/blog/posts`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`,
      "content-type": "application/json",
      "idempotency-key": idempotencyKey,
    },
    body: JSON.stringify(createBody),
  });
  const createdResponse = await create();
  const created = await createdResponse.json();
  if (!createdResponse.ok || created.code !== 200 || created.data?.userId !== userId || created.data?.status !== 0) {
    throw new Error("Gateway-to-Content article creation failed.");
  }
  postId = created.data.id;
  const retryResponse = await create();
  const retry = await retryResponse.json();
  if (!retryResponse.ok || retry.data?.id !== postId) {
    throw new Error("Article creation did not replay the idempotency key.");
  }

  const notificationResponse = await fetch(`${contentUrl}/internal/content/notifications`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN,
    },
    body: JSON.stringify({
      userId,
      type: "RUN_FAILED",
      title: "Publication smoke",
      message: "Owner-scoped notification persistence",
    }),
  });
  if (!notificationResponse.ok) throw new Error("Content did not persist an internal notification.");
  const notification = await notificationResponse.json();
  const notifications = await fetch(`${gatewayUrl}/api/notifications`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const notificationList = await notifications.json();
  if (!notifications.ok || notificationList.data?.total !== 1
    || notificationList.data?.records?.[0]?.id !== notification.id
    || notificationList.data?.unreadCount !== 1) {
    throw new Error("Gateway-to-Content owner-scoped notification read failed.");
  }
  const markRead = await fetch(`${gatewayUrl}/api/notifications/read-all`, {
    method: "PATCH",
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const unreadCount = await fetch(`${gatewayUrl}/api/notifications/unread-count`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const unread = await unreadCount.json();
  if (!markRead.ok || !unreadCount.ok || unread.data !== 0) {
    throw new Error("Content notification read acknowledgement did not update its unread count.");
  }

  const eventStream = await fetch(`${contentUrl}/api/notifications/events?afterId=${notification.id}`, {
    headers: {
      "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN,
      "x-user-id": String(userId),
    },
  });
  if (!eventStream.ok || !eventStream.body) throw new Error("Content did not open the owner-scoped notification SSE stream.");
  const streamReader = eventStream.body.getReader();
  try {
    const connected = new TextDecoder().decode((await streamReader.read()).value);
    if (!connected.includes(": connected")) throw new Error("Content SSE stream did not register the active connection.");
    const broadcastResponse = await fetch(`${gatewayUrl}/api/admin/notifications/test-broadcast`, {
      method: "PATCH",
      headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET, adminId, "ADMIN")}` },
    });
    const broadcast = await broadcastResponse.json();
    if (!broadcastResponse.ok || broadcast.data !== 1) {
      throw new Error("Core admin diagnostic did not reach Content or count the active SSE connection.");
    }
    const deadline = Date.now() + 12_000;
    let received = "";
    while (!received.includes("SSE_TEST_BROADCAST") && Date.now() < deadline) {
      let timeoutId;
      const timeout = new Promise((_, reject) => {
        timeoutId = setTimeout(() => reject(new Error("Timed out waiting for the Content SSE broadcast.")), deadline - Date.now());
      });
      let chunk;
      try { chunk = await Promise.race([streamReader.read(), timeout]); }
      finally { clearTimeout(timeoutId); }
      if (chunk.done) break;
      received += new TextDecoder().decode(chunk.value);
    }
    if (!received.includes("SSE_TEST_BROADCAST")) {
      throw new Error("Content did not deliver the diagnostic event to the active SSE connection.");
    }
  } finally {
    await streamReader.cancel();
  }

  const mediaRegistration = await fetch(`${contentUrl}/internal/content/media/record-stored`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN },
    body: JSON.stringify({ userId, stored: {
      url: `https://cdn.example.test/smoke/${userId}.png`, originalName: `media-smoke-${userId}.png`,
      storedName: `${userId}.png`, size: 128, contentType: "image/png",
    } }),
  });
  const mediaAsset = await mediaRegistration.json();
  if (!mediaRegistration.ok || mediaAsset.userId !== userId) throw new Error("Content did not register the owner-scoped media metadata.");
  mediaId = mediaAsset.id;
  const mediaGalleryResponse = await fetch(`${gatewayUrl}/api/blog/media/images?scope=recent&keyword=${encodeURIComponent(`media-smoke-${userId}`)}`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const mediaGallery = await mediaGalleryResponse.json();
  const otherOwnerMedia = await fetch(`${contentUrl}/api/blog/media/images?scope=recent`, {
    headers: { "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN, "x-user-id": String(userId + 1) },
  });
  if (!mediaGalleryResponse.ok || mediaGallery.data?.length !== 1 || mediaGallery.data[0].id !== mediaId
    || !otherOwnerMedia.ok || (await otherOwnerMedia.json()).data.length !== 0) {
    throw new Error("Content media gallery did not preserve the owner boundary through Gateway.");
  }

  const materialResponse = await fetch(`${coreUrl}/internal/agent/recitation/material/create`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-dinqor-service-token": coreVars.INTERNAL_SERVICE_TOKEN,
    },
    body: JSON.stringify({
      userId,
      draft: { title: `Plan material smoke ${userId}`, content: "Only the owner may reference this material.", knowledgeType: "CONCEPT" },
    }),
  });
  const material = await materialResponse.json();
  if (!materialResponse.ok || !Number.isSafeInteger(material.id)) {
    throw new Error("Core could not create the recitation material used by the plan boundary smoke.");
  }
  materialId = material.id;

  const planBody = {
    clientRequestId: randomUUID(),
    topic: `Scheduled plan smoke ${userId}`,
    scheduleType: "DAILY",
    timezone: "Asia/Singapore",
    triggerTimes: ["23:59"],
    topics: ["D1 plan persistence"],
    knowledgeMaterialId: materialId,
  };
  const createPlan = () => fetch(`${gatewayUrl}/api/blog-plans`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(planBody),
  });
  const planResponse = await createPlan();
  const plan = await planResponse.json();
  if (!planResponse.ok || plan.data?.status !== "ACTIVE" || plan.data?.topic !== planBody.topic
    || plan.data?.userId !== undefined) {
    throw new Error("Gateway-to-Content plan creation failed or leaked its owner field.");
  }
  planId = plan.data.id;
  const planRetry = await createPlan();
  const retriedPlan = await planRetry.json();
  if (!planRetry.ok || retriedPlan.data?.id !== planId) {
    throw new Error("Blog plan did not replay its client request id.");
  }
  const crossOwnerPlan = await fetch(`${contentUrl}/api/blog-plans`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN,
      "x-user-id": String(userId + 1),
    },
    body: JSON.stringify({ ...planBody, clientRequestId: randomUUID() }),
  });
  if (crossOwnerPlan.status !== 404) {
    throw new Error(`Content accepted a plan reference to another user's Core material (HTTP ${crossOwnerPlan.status}).`);
  }
  const pausedPlanResponse = await fetch(`${gatewayUrl}/api/blog-plans/${planId}/status`, {
    method: "PATCH",
    headers: {
      authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ action: "PAUSED" }),
  });
  const pausedPlan = await pausedPlanResponse.json();
  const nextRun = await fetch(`${gatewayUrl}/api/blog-plans/${planId}/next`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const nextRunValue = await nextRun.json();
  if (!pausedPlanResponse.ok || pausedPlan.data?.status !== "PAUSED"
    || !nextRun.ok || typeof nextRunValue.data !== "string") {
    throw new Error("Blog plan status or next-run preview failed through Gateway.");
  }

  const blogAgentCreate = await fetch(`${gatewayUrl}/api/blog-agent/tasks`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({ input: `Durable BlogAgent D1 smoke ${userId}`, clientRequestId: `smoke-${userId}` }),
  });
  const blogAgentTask = await blogAgentCreate.json();
  if (!blogAgentCreate.ok || blogAgentTask.data?.status !== "created"
    || blogAgentTask.data?.userId !== userId || blogAgentTask.data?.stage !== "analyze") {
    throw new Error("Gateway-to-Content BlogAgent task creation failed.");
  }
  blogAgentTaskId = blogAgentTask.data.id;
  const blogAgentDetailResponse = await fetch(`${gatewayUrl}/api/blog-agent/tasks/${blogAgentTaskId}`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const blogAgentDetail = await blogAgentDetailResponse.json();
  if (!blogAgentDetailResponse.ok || blogAgentDetail.data?.task?.id !== blogAgentTaskId
    || blogAgentDetail.data?.messages?.length !== 1) {
    throw new Error("Content D1 BlogAgent task detail did not hydrate its initial message.");
  }
  const internalBlogAgentCreate = await fetch(`${contentUrl}/internal/agent/blogagent/create`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN },
    body: JSON.stringify({ userId, input: "Agent boundary idempotency", clientRequestId: `internal-${userId}` }),
  });
  const internalTask = await internalBlogAgentCreate.json();
  const internalRetry = await fetch(`${contentUrl}/internal/agent/blogagent/create`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN },
    body: JSON.stringify({ userId, input: "Agent boundary idempotency", clientRequestId: `internal-${userId}` }),
  });
  const internalRetryTask = await internalRetry.json();
  if (!internalBlogAgentCreate.ok || !internalRetry.ok || internalTask.id !== internalRetryTask.id) {
    throw new Error("Content Agent boundary did not preserve the idempotent task identity.");
  }

  const response = await fetch(`${contentUrl}/internal/agent/publications/${postId}/sync/juejin`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-dinqor-service-token": contentVars.INTERNAL_SERVICE_TOKEN,
    },
    body: JSON.stringify({ userId, postId }),
  });
  if (response.status !== 401) {
    throw new Error(`Expected Platform to reject publication without a Juejin credential (received HTTP ${response.status}).`);
  }
  const error = await response.json();
  if (error.code !== 401) throw new Error("Content did not preserve the Platform authorization error.");

  const queryResults = runD1(`SELECT status, attempts, error_code, user_id, task_id FROM blog_publish_record WHERE task_id = -${postId} AND user_id = ${userId} AND platform = 'juejin'`);
  const record = queryResults[0]?.results?.[0];
  if (record?.status !== "FAILED" || record.attempts !== 1 || record.user_id !== userId || record.task_id !== -postId) {
    throw new Error("Content did not persist the owner-scoped failed publication attempt in D1.");
  }

  const managedPageResponse = await fetch(`${gatewayUrl}/api/blog/posts/mine?page=1&size=10&search=${encodeURIComponent(`Publication smoke ${userId}`)}`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const managedPage = await managedPageResponse.json();
  if (!managedPageResponse.ok || managedPage.data?.total !== 1 || managedPage.data?.records?.[0]?.id !== postId) {
    throw new Error("Gateway-to-Content private article listing failed.");
  }
  const managedDetailResponse = await fetch(`${gatewayUrl}/api/blog/posts/mine/${postId}`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const managedDetail = await managedDetailResponse.json();
  if (!managedDetailResponse.ok || managedDetail.data?.id !== postId || managedDetail.data?.content !== createBody.content) {
    throw new Error("Gateway-to-Content private article detail failed.");
  }
  const editResponse = await fetch(`${gatewayUrl}/api/blog/posts/${postId}`, {
    method: "PUT",
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`, "content-type": "application/json" },
    body: JSON.stringify({ title: `Publication smoke edited ${userId}`, summary: "Updated through private management", tags: ["private-management"] }),
  });
  const edited = await editResponse.json();
  if (!editResponse.ok || edited.data?.title !== `Publication smoke edited ${userId}`
    || edited.data?.tags?.[0] !== "private-management") {
    throw new Error("Gateway-to-Content private article update failed.");
  }
  const publishStatusResponse = await fetch(`${gatewayUrl}/api/blog/posts/${postId}/status`, {
    method: "PATCH",
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`, "content-type": "application/json" },
    body: JSON.stringify({ status: 1 }),
  });
  const publishedStatus = await publishStatusResponse.json();
  const publishReceiptResponse = await fetch(`${gatewayUrl}/api/blog/posts/publish-status?requestId=${encodeURIComponent(idempotencyKey)}`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const publishReceipt = await publishReceiptResponse.json();
  const rhythmResponse = await fetch(`${gatewayUrl}/api/blog/posts/publish-rhythm`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const rhythm = await rhythmResponse.json();
  const statsResponse = await fetch(`${gatewayUrl}/api/blog/posts/manage-stats`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const stats = await statsResponse.json();
  if (!publishStatusResponse.ok || publishedStatus.data?.status !== 1
    || !publishReceiptResponse.ok || publishReceipt.data?.status !== "published" || publishReceipt.data?.postId !== postId
    || !rhythmResponse.ok || !Array.isArray(rhythm.data?.days) || rhythm.data.days.length !== 7
    || !statsResponse.ok || stats.data?.published !== 1) {
    throw new Error("Gateway-to-Content publish status, rhythm, or dashboard stats failed.");
  }
  const trashResponse = await fetch(`${gatewayUrl}/api/blog/posts/${postId}`, {
    method: "DELETE", headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const trashedPageResponse = await fetch(`${gatewayUrl}/api/blog/posts/mine?status=-1`, {
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const trashedPage = await trashedPageResponse.json();
  if (!trashResponse.ok || !trashedPageResponse.ok || trashedPage.data?.records?.[0]?.status !== -1) {
    throw new Error("Gateway-to-Content trash and trash listing failed.");
  }
  const restoreResponse = await fetch(`${gatewayUrl}/api/blog/posts/${postId}/status`, {
    method: "PATCH",
    headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}`, "content-type": "application/json" },
    body: JSON.stringify({ status: 0 }),
  });
  const trashAgainResponse = await fetch(`${gatewayUrl}/api/blog/posts/${postId}`, {
    method: "DELETE", headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  const permanentDeleteResponse = await fetch(`${gatewayUrl}/api/blog/posts/${postId}/permanent`, {
    method: "DELETE", headers: { authorization: `Bearer ${accessToken(gatewayVars.JWT_SECRET)}` },
  });
  if (!restoreResponse.ok || !trashAgainResponse.ok || !permanentDeleteResponse.ok) {
    throw new Error("Gateway-to-Content trash restore or permanent deletion failed.");
  }
  const reminderBody = {
    clientRequestId: "reminder-" + userId,
    recipientEmail: username + "@example.test",
    subject: "Reminder smoke " + userId,
    message: "A future local D1 reminder.",
    frequency: "ONCE",
    scheduledAt: "2030-01-01T10:00:00.000Z",
  };
  const createReminder = () => fetch(gatewayUrl + "/api/email-reminders", {
    method: "POST",
    headers: { authorization: "Bearer " + accessToken(gatewayVars.JWT_SECRET), "content-type": "application/json" },
    body: JSON.stringify(reminderBody),
  });
  const reminderResponse = await createReminder();
  const reminder = await reminderResponse.json();
  const reminderRetryResponse = await createReminder();
  const reminderRetry = await reminderRetryResponse.json();
  if (!reminderResponse.ok || !reminderRetryResponse.ok || !reminder.data?.id
    || reminderRetry.data?.id !== reminder.data.id) {
    throw new Error("Gateway-to-Content email reminder creation or idempotent retry failed.");
  }
  const reminderListResponse = await fetch(gatewayUrl + "/api/email-reminders?status=SCHEDULED&page=1&size=5", {
    headers: { authorization: "Bearer " + accessToken(gatewayVars.JWT_SECRET) },
  });
  const reminderList = await reminderListResponse.json();
  if (!reminderListResponse.ok || reminderList.data?.total !== 1
    || reminderList.data?.records?.[0]?.id !== reminder.data.id
    || reminderList.data?.stats?.pending !== 1) {
    throw new Error("Gateway-to-Content email reminder list or stats failed.");
  }
  const reminderStatus = (status) => fetch(gatewayUrl + "/api/email-reminders/" + reminder.data.id + "/status", {
    method: "PATCH",
    headers: { authorization: "Bearer " + accessToken(gatewayVars.JWT_SECRET), "content-type": "application/json" },
    body: JSON.stringify({ status }),
  });
  const pausedReminder = await reminderStatus("PAUSED");
  const resumedReminder = await reminderStatus("PENDING");
  const deletedReminder = await fetch(gatewayUrl + "/api/email-reminders/" + reminder.data.id, {
    method: "DELETE", headers: { authorization: "Bearer " + accessToken(gatewayVars.JWT_SECRET) },
  });
  if (!pausedReminder.ok || !resumedReminder.ok || !deletedReminder.ok) {
    throw new Error("Gateway-to-Content email reminder pause, resume or soft-delete failed.");
  }
  const unauthorizedReminder = await fetch(contentUrl + "/internal/platform-integration/mail/reminder", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ email: username + "@example.test", subject: "test", text: "test" }),
  });
  if (unauthorizedReminder.status !== 401) {
    throw new Error("Platform accepted the transactional-mail contract without service authentication.");
  }
  console.log("Content smoke passed (Gateway article CRUD and media gallery, email reminder lifecycle, notification reads and live diagnostic SSE broadcast, plan storage, Core material ownership, BlogAgent task persistence and Agent-boundary idempotency, schedule preview, Content → Platform boundary, and owner-scoped publication record).");
} finally {
  runD1("DELETE FROM email_reminder_task WHERE user_id = " + userId);
  runD1(`DELETE FROM blog_media_asset WHERE user_id = ${userId}`);
  runD1(`DELETE FROM blog_agent_task WHERE user_id = ${userId}`);
  runD1(`DELETE FROM blog_notification WHERE user_id = ${userId}`);
  runD1(`DELETE FROM blog_plan_run WHERE plan_id IN (SELECT id FROM blog_content_plan WHERE user_id = ${userId}); DELETE FROM blog_content_plan WHERE user_id = ${userId}`);
  if (postId) {
    runD1(`DELETE FROM blog_publish_record WHERE task_id = -${postId} AND user_id = ${userId}; DELETE FROM blog_post WHERE id = ${postId} AND user_id = ${userId}`);
    runD1(`DELETE FROM blog_tag WHERE name = 'publication-smoke-${userId}' AND NOT EXISTS (SELECT 1 FROM blog_post_tag WHERE tag_id = blog_tag.id)`);
  }
  if (materialId) runD1(`DELETE FROM recitation_material WHERE id = ${materialId} AND user_id = ${userId}`, {
    databaseName: "dinqor-core-local",
    directory: coreDirectory,
  });
  if (userInserted) runD1(`DELETE FROM sys_user WHERE id = ${userId}`, {
    databaseName: "dinqor-core-local",
    directory: coreDirectory,
  });
  if (adminInserted) runD1(`DELETE FROM sys_user WHERE id = ${userId + 1}`, {
    databaseName: "dinqor-core-local",
    directory: coreDirectory,
  });
}
