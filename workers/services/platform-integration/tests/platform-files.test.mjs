import assert from "node:assert/strict";
import test from "node:test";
import { handlePlatformFileRequest } from "../.test-build/controllers/platform-file-controller.js";
import { PlatformFileService } from "../.test-build/services/platform-file-service.js";

function fileFixture() {
  const rows = new Map();
  const objects = new Map();
  const repository = {
    rows,
    async create(asset) {
      rows.set(asset.storedName, { ...asset, createdAt: "2026-01-01T00:00:00Z" });
    },
    async find(storedName) { return rows.get(storedName) ?? null; },
    async delete(storedName) { rows.delete(storedName); },
  };
  const storage = {
    objects,
    async put(objectKey, body, contentType) {
      objects.set(objectKey, { body: new Uint8Array(body), contentType });
    },
    async read(objectKey, maxBytes) {
      const object = objects.get(objectKey);
      if (!object) return null;
      assert.ok(object.body.byteLength <= maxBytes);
      return { body: new Response(object.body).body, contentType: object.contentType };
    },
    async delete(objectKey) { objects.delete(objectKey); },
  };
  return { repository, storage, service: new PlatformFileService(repository, storage) };
}

function createFile(name = "image.png", type = "image/png", bytes = "png-data") {
  return new File([bytes], name, { type });
}

function request(path, { method = "GET", formData, token = "platform-token", userId } = {}) {
  const headers = {
    ...(token ? { "x-dinqor-service-token": token } : {}),
    ...(userId ? { "x-user-id": String(userId) } : {}),
  };
  if (formData) headers["content-type"] = `multipart/form-data; boundary=${formData.boundary ?? ""}`;
  return new Request(`https://platform.test${path}`, {
    method,
    headers: formData ? undefined : headers,
    ...(formData ? { body: formData } : {}),
  });
}

test("Platform file service stores bytes in OSS and only metadata in its repository", async () => {
  const fixture = fileFixture();
  const result = await fixture.service.upload(42, createFile("portrait.PNG"), "avatar");
  assert.equal(result.originalName, "portrait.PNG");
  assert.equal(result.extension, "png");
  assert.match(result.url, /^\/api\/download\/[0-9a-f-]{36}\.png$/);

  const asset = fixture.repository.rows.get(result.storedName);
  assert.equal(asset.userId, 42);
  assert.equal(asset.objectKey, `avatars/${result.storedName}`);
  assert.equal(asset.byteSize, 8);
  assert.match(asset.contentSha256, /^[a-f0-9]{64}$/);
  assert.equal(Object.hasOwn(asset, "content"), false);
  assert.equal(new TextDecoder().decode(fixture.storage.objects.get(asset.objectKey).body), "png-data");

  const downloaded = await fixture.service.download(result.storedName);
  assert.equal(downloaded.asset.originalName, "portrait.PNG");
  assert.equal(await new Response(downloaded.object.body).text(), "png-data");
});

test("Platform file service validates input and removes earlier objects when a batch fails", async () => {
  const fixture = fileFixture();
  await assert.rejects(
    fixture.service.upload(42, createFile("bad.html", "text/html"), "upload"),
    (error) => error.status === 400,
  );
  await assert.rejects(fixture.service.upload(0, createFile(), "upload"), (error) => error.status === 401);
  await assert.rejects(fixture.service.upload(42, new File([], "empty.png", { type: "image/png" }), "upload"),
    (error) => error.status === 400);

  const files = [createFile("ok.png"), createFile("bad.html", "text/html")];
  await assert.rejects(fixture.service.uploadBatch(42, files), (error) => error.status === 400);
  assert.equal(fixture.repository.rows.size, 0);
  assert.equal(fixture.storage.objects.size, 0);
  await assert.rejects(fixture.service.download("../../secret"), (error) => error.status === 400);
});

test("Platform upload controller requires projected identity and supports multipart uploads", async () => {
  const fixture = fileFixture();
  const deniedForm = new FormData();
  deniedForm.append("file", createFile());
  const denied = await handlePlatformFileRequest(
    new Request("https://platform.test/api/upload/oss", { method: "POST", body: deniedForm }),
    { INTERNAL_SERVICE_TOKEN: "platform-token" },
    fixture.service,
  );
  assert.equal(denied.status, 401);

  const form = new FormData();
  form.append("file", createFile("camera.webp", "image/webp"));
  form.append("type", "video");
  const uploaded = await handlePlatformFileRequest(
    new Request("https://platform.test/api/upload/oss", {
      method: "POST",
      headers: { "x-user-id": "42" },
      body: form,
    }),
    { INTERNAL_SERVICE_TOKEN: "platform-token" },
    fixture.service,
  );
  assert.equal(uploaded.status, 200);
  const payload = await uploaded.json();
  assert.equal(payload.code, 200);
  assert.equal(fixture.repository.rows.get(payload.data.storedName).objectKey,
    `videos/${payload.data.storedName}`);
});

test("Platform downloads stay public while internal file contracts require the service token", async () => {
  const fixture = fileFixture();
  const file = await fixture.service.upload(42, createFile("avatar.png"), "avatar");
  const env = { INTERNAL_SERVICE_TOKEN: "platform-token" };
  const denied = await handlePlatformFileRequest(
    new Request(`https://platform.test/internal/integration/files/download/${file.storedName}`),
    { ...env, INTERNAL_SERVICE_TOKEN: "wrong-token" }, fixture.service,
  );
  assert.equal(denied.status, 401);

  const internal = await handlePlatformFileRequest(
    new Request(`https://platform.test/internal/integration/files/download/${file.storedName}`, {
      headers: { "x-dinqor-service-token": "platform-token" },
    }), env, fixture.service,
  );
  assert.equal(internal.status, 200);
  assert.equal(internal.headers.get("content-type"), "image/png");
  assert.match(internal.headers.get("content-disposition"), /filename\*=UTF-8''avatar\.png/);
  assert.equal(await internal.text(), "png-data");

  const exported = await handlePlatformFileRequest(
    new Request("https://platform.test/api/export/csv?type=blog"), env, fixture.service,
  );
  assert.equal(exported.status, 200);
  assert.equal(exported.headers.get("content-type"), "text/csv;charset=UTF-8");
  assert.match(await exported.text(), /Spring Boot/);
});
