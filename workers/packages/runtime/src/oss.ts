const encoder = new TextEncoder();
const OSS_SIGNATURE_VERSION = "OSS4-HMAC-SHA256";
const OSS_UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

export interface OssV4Credentials {
  accessKeyId: string;
  accessKeySecret: string;
  region: string;
}

function encodeOssUriComponent(value: string): string {
  return encodeURIComponent(value).replace(/[!'()*]/g, (character) =>
    "%" + character.charCodeAt(0).toString(16).toUpperCase().padStart(2, "0"));
}

function toHex(bytes: ArrayBuffer | Uint8Array): string {
  const values = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  return Array.from(values, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(value: string): Promise<string> {
  return toHex(await crypto.subtle.digest("SHA-256", encoder.encode(value)));
}

async function hmac(key: Uint8Array, value: string): Promise<Uint8Array> {
  const keyBuffer = new Uint8Array(key.byteLength);
  keyBuffer.set(key);
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    keyBuffer.buffer as ArrayBuffer,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(value)));
}

async function deriveOssV4SigningKey(
  accessKeySecret: string,
  date: string,
  region: string,
): Promise<Uint8Array> {
  const dateKey = await hmac(encoder.encode("aliyun_v4" + accessKeySecret), date);
  const regionKey = await hmac(dateKey, region);
  const serviceKey = await hmac(regionKey, "oss");
  return hmac(serviceKey, "aliyun_v4_request");
}

function canonicalQuery(url: URL): string {
  const pairs: [string, string, boolean][] = [];
  const rawQuery = url.search.startsWith("?") ? url.search.slice(1) : url.search;
  for (const parameter of rawQuery.split("&")) {
    if (!parameter) continue;
    const separator = parameter.indexOf("=");
    const hasValue = separator >= 0;
    let key = "";
    let value = "";
    let found = false;
    new URLSearchParams(parameter).forEach((parsedValue, parsedKey) => {
      if (found) return;
      key = parsedKey;
      value = parsedValue;
      found = true;
    });
    pairs.push([encodeOssUriComponent(key), encodeOssUriComponent(value), hasValue]);
  }
  return pairs
    .sort((left, right) =>
      (left[0] < right[0] ? -1 : left[0] > right[0] ? 1 : 0)
        || (left[1] < right[1] ? -1 : left[1] > right[1] ? 1 : 0)
        || Number(left[2]) - Number(right[2]))
    .map(([key, value, hasValue]) => hasValue ? key + "=" + value : key)
    .join("&");
}

export function buildOssCanonicalRequest(
  method: string,
  url: URL,
  headers: Headers,
  additionalHeaderNames: string[] = [],
): string {
  const additionalHeaders = [...new Set(additionalHeaderNames.map((name) => name.toLowerCase()))].sort();
  if (additionalHeaders.some((name) => !headers.has(name)
    || name === "x-oss-content-sha256" || name === "x-oss-date")) {
    throw new Error("Invalid OSS additional header list");
  }

  const signedHeaderNames = new Set([
    "x-oss-content-sha256",
    "x-oss-date",
    ...additionalHeaders,
  ]);
  for (const name of ["content-md5", "content-type"]) {
    if (headers.has(name)) signedHeaderNames.add(name);
  }
  headers.forEach((_value, name) => {
    if (name.startsWith("x-oss-")) signedHeaderNames.add(name);
  });

  const canonicalHeaders = [...signedHeaderNames]
    .sort()
    .map((name) => {
      const value = headers.get(name);
      if (value === null) throw new Error("Missing signed OSS header: " + name);
      return name + ":" + value.trim().replace(/\s+/g, " ") + "\n";
    })
    .join("");

  return [
    method.toUpperCase(),
    url.pathname || "/",
    canonicalQuery(url),
    canonicalHeaders,
    additionalHeaders.join(";"),
    OSS_UNSIGNED_PAYLOAD,
  ].join("\n");
}

/**
 * Creates the OSS V4 Authorization header using the Workers Web Crypto API.
 * Required OSS headers, standard content headers, and explicitly listed
 * optional headers are included in the canonical request.
 */
export async function createOssV4Authorization(
  method: string,
  url: URL,
  headers: Headers,
  credentials: OssV4Credentials,
  timestamp: string,
  additionalHeaderNames: string[] = [],
): Promise<string> {
  if (!/^\d{8}T\d{6}Z$/.test(timestamp)) {
    throw new Error("OSS V4 timestamp must use UTC ISO8601 format");
  }
  if (!/^[a-z0-9-]+$/i.test(credentials.region)) {
    throw new Error("Invalid OSS region");
  }

  const date = timestamp.slice(0, 8);
  const additionalHeaders = [...new Set(additionalHeaderNames.map((name) => name.toLowerCase()))].sort();
  const canonicalRequest = buildOssCanonicalRequest(
    method,
    url,
    headers,
    additionalHeaders,
  );
  const scope = date + "/" + credentials.region + "/oss/aliyun_v4_request";
  const stringToSign = [
    OSS_SIGNATURE_VERSION,
    timestamp,
    scope,
    await sha256Hex(canonicalRequest),
  ].join("\n");

  const signingKey = await deriveOssV4SigningKey(
    credentials.accessKeySecret,
    date,
    credentials.region,
  );
  const signature = toHex(await hmac(signingKey, stringToSign));

  const additionalHeaderParameter = additionalHeaders.length > 0
    ? ", AdditionalHeaders=" + additionalHeaders.join(";")
    : "";
  return OSS_SIGNATURE_VERSION
    + " Credential=" + credentials.accessKeyId + "/" + scope
    + additionalHeaderParameter
    + ", Signature=" + signature;
}

export function inferOssRegion(endpoint: string): string | undefined {
  try {
    const normalized = endpoint.includes("://") ? endpoint : "https://" + endpoint;
    const hostname = new URL(normalized).hostname;
    return hostname.match(/(?:^|\.)oss-([a-z0-9-]+)\.aliyuncs\.com$/i)?.[1];
  } catch {
    return undefined;
  }
}

export function createOssObjectUrl(
  endpoint: string,
  objectKey: string,
  bucket?: string,
): URL {
  const normalizedEndpoint = endpoint.includes("://") ? endpoint : "https://" + endpoint;
  const base = new URL(normalizedEndpoint);
  if (base.protocol !== "https:" || base.username || base.password
    || (base.pathname !== "/" && base.pathname !== "") || base.search || base.hash) {
    throw new Error("OSS endpoint must be a clean HTTPS endpoint");
  }
  if (bucket && !/^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$/i.test(bucket)) {
    throw new Error("Invalid OSS bucket name");
  }
  if (/^oss-[a-z0-9-]+\.aliyuncs\.com$/i.test(base.hostname)) {
    if (!bucket) throw new Error("OSS bucket is required with a regional endpoint");
    base.hostname = bucket + "." + base.hostname;
  } else if (bucket && /\.oss-[a-z0-9-]+\.aliyuncs\.com$/i.test(base.hostname)
    && !base.hostname.toLowerCase().startsWith(bucket.toLowerCase() + ".")) {
    throw new Error("OSS bucket does not match the configured bucket endpoint");
  }
  if (objectKey.startsWith("/") || objectKey.includes("\\")) {
    throw new Error("Invalid OSS object key");
  }
  const segments = objectKey.split("/");
  if (segments.some((segment) => !segment || segment === "." || segment === "..")) {
    throw new Error("Invalid OSS object key");
  }
  base.pathname = "/" + segments.map(encodeOssUriComponent).join("/");
  return base;
}

export async function signedOssGetHeaders(
  url: URL,
  credentials: OssV4Credentials,
  now = new Date(),
): Promise<Headers> {
  const timestamp = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  const headers = new Headers({
    "x-oss-content-sha256": OSS_UNSIGNED_PAYLOAD,
    "x-oss-date": timestamp,
  });
  headers.set(
    "authorization",
    await createOssV4Authorization("GET", url, headers, credentials, timestamp),
  );
  return headers;
}

/** Creates a short-lived OSS V4 URL that grants GET access to one object. */
export async function signedOssGetUrl(
  url: URL,
  credentials: OssV4Credentials,
  expiresSeconds = 600,
  now = new Date(),
): Promise<URL> {
  if (!Number.isSafeInteger(expiresSeconds) || expiresSeconds < 1 || expiresSeconds > 604_800) {
    throw new Error("OSS presigned URL expiry must be between 1 and 604800 seconds");
  }
  const timestamp = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  const date = timestamp.slice(0, 8);
  const scope = date + "/" + credentials.region + "/oss/aliyun_v4_request";
  const signedUrl = new URL(url.toString());
  signedUrl.searchParams.set("x-oss-additional-headers", "host");
  signedUrl.searchParams.set("x-oss-credential", credentials.accessKeyId + "/" + scope);
  signedUrl.searchParams.set("x-oss-date", timestamp);
  signedUrl.searchParams.set("x-oss-expires", String(expiresSeconds));
  signedUrl.searchParams.set("x-oss-signature-version", OSS_SIGNATURE_VERSION);

  const canonicalRequest = [
    "GET",
    signedUrl.pathname || "/",
    canonicalQuery(signedUrl),
    "host:" + signedUrl.host + "\n",
    "host",
    OSS_UNSIGNED_PAYLOAD,
  ].join("\n");
  const stringToSign = [
    OSS_SIGNATURE_VERSION,
    timestamp,
    scope,
    await sha256Hex(canonicalRequest),
  ].join("\n");
  const signingKey = await deriveOssV4SigningKey(credentials.accessKeySecret, date, credentials.region);
  signedUrl.searchParams.set("x-oss-signature", toHex(await hmac(signingKey, stringToSign)));
  return signedUrl;
}

async function signedOssMethodHeaders(
  method: "PUT" | "POST" | "DELETE" | "HEAD",
  url: URL,
  credentials: OssV4Credentials,
  now: Date,
  contentType?: string,
): Promise<Headers> {
  const timestamp = now.toISOString().replace(/[:-]|\.\d{3}/g, "");
  const headers = new Headers({
    "x-oss-content-sha256": OSS_UNSIGNED_PAYLOAD,
    "x-oss-date": timestamp,
  });
  if (contentType) headers.set("content-type", contentType);
  headers.set(
    "authorization",
    await createOssV4Authorization(method, url, headers, credentials, timestamp),
  );
  return headers;
}

/** Signs an OSS PutObject request using the required unsigned-payload marker. */
export function signedOssPutHeaders(
  url: URL,
  credentials: OssV4Credentials,
  contentType: string,
  now = new Date(),
): Promise<Headers> {
  return signedOssMethodHeaders("PUT", url, credentials, now, contentType);
}

/** Signs an OSS multipart initiation or completion request. */
export function signedOssPostHeaders(
  url: URL,
  credentials: OssV4Credentials,
  contentType = "application/xml",
  now = new Date(),
): Promise<Headers> {
  return signedOssMethodHeaders("POST", url, credentials, now, contentType);
}

/** Signs an OSS object metadata request without downloading its content. */
export function signedOssHeadHeaders(
  url: URL,
  credentials: OssV4Credentials,
  now = new Date(),
): Promise<Headers> {
  return signedOssMethodHeaders("HEAD", url, credentials, now);
}

/** Signs an OSS DeleteObject request. */
export function signedOssDeleteHeaders(
  url: URL,
  credentials: OssV4Credentials,
  now = new Date(),
): Promise<Headers> {
  return signedOssMethodHeaders("DELETE", url, credentials, now);
}
