import { Redis } from "@upstash/redis/cloudflare";

export interface RedisEnvironment {
  UPSTASH_REDIS_REST_URL?: string;
  UPSTASH_REDIS_REST_TOKEN?: string;
}

export function hasRedisCredentials(env: RedisEnvironment): boolean {
  return Boolean(env.UPSTASH_REDIS_REST_URL && env.UPSTASH_REDIS_REST_TOKEN);
}

const INCREMENT_WITH_EXPIRY = `
local count = redis.call('INCR', KEYS[1])
if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return count
`;
const CONSUME_CODE = `
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
`;
const UPDATE_EPHEMERAL_JSON = `
local raw = redis.call('GET', KEYS[1])
if not raw then return nil end
if string.sub(raw, 1, 10) ~= 'ephemeral:' then return nil end
local state = cjson.decode(string.sub(raw, 11))
local expected = cjson.decode(ARGV[1])
local allowed = false
for _, value in ipairs(expected) do
  if state.status == value then allowed = true end
end
if not allowed then return nil end
local updates = cjson.decode(ARGV[2])
for key, value in pairs(updates) do state[key] = value end
local encoded = 'ephemeral:' .. cjson.encode(state)
redis.call('SET', KEYS[1], encoded, 'EX', ARGV[3])
return encoded
`;
const CONSUME_EPHEMERAL_JSON = `
local raw = redis.call('GET', KEYS[1])
if not raw then return nil end
if string.sub(raw, 1, 10) ~= 'ephemeral:' then return nil end
local state = cjson.decode(string.sub(raw, 11))
if state.status ~= ARGV[1] or state.exchangeCode ~= ARGV[2] then return nil end
redis.call('DEL', KEYS[1])
return raw
`;
const encoder = new TextEncoder();

function client(env: RedisEnvironment): Redis {
  const url = env.UPSTASH_REDIS_REST_URL;
  const token = env.UPSTASH_REDIS_REST_TOKEN;
  if (!url || !token) throw new Error("Upstash Redis is not configured");
  return new Redis({ url, token, retry: false, enableTelemetry: false });
}

async function digest(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

export async function consumeFixedWindow(
  env: RedisEnvironment,
  namespace: string,
  identifier: string,
  limit: number,
  windowSeconds: number,
  nowMilliseconds = Date.now(),
): Promise<{ allowed: boolean; resetAt: number }> {
  if (!/^[a-z][a-z0-9:-]{0,63}$/.test(namespace)
    || !Number.isSafeInteger(limit) || limit < 1
    || !Number.isSafeInteger(windowSeconds) || windowSeconds < 1
    || !Number.isSafeInteger(nowMilliseconds) || nowMilliseconds < 0) {
    throw new Error("Invalid rate limit configuration");
  }
  const bucket = Math.floor(nowMilliseconds / (windowSeconds * 1000));
  const resetAt = (bucket + 1) * windowSeconds * 1000;
  const key = `dinqor:core:ratelimit:${namespace}:${bucket}:${await digest(identifier)}`;
  const count = await client(env).eval<[string], number>(
    INCREMENT_WITH_EXPIRY,
    [key],
    [String(windowSeconds + 1)],
  );
  if (!Number.isSafeInteger(count) || count < 1) {
    throw new Error("Upstash returned an invalid rate limit counter");
  }
  return { allowed: count <= limit, resetAt };
}

export async function saveVerificationCode(
  env: RedisEnvironment,
  emailKey: string,
  code: string,
): Promise<void> {
  await client(env).set(`dinqor:core:verification:${await digest(emailKey)}`, code, { ex: 300 });
}

export async function consumeVerificationCode(
  env: RedisEnvironment,
  emailKey: string,
  code: string,
): Promise<boolean> {
  const consumed = await client(env).eval<[string], number>(
    CONSUME_CODE,
    [`dinqor:core:verification:${await digest(emailKey)}`],
    [code],
  );
  return consumed === 1;
}

export async function deleteVerificationCode(env: RedisEnvironment, emailKey: string): Promise<void> {
  await client(env).del(`dinqor:core:verification:${await digest(emailKey)}`);
}

function ephemeralKey(namespace: string, identifier: string): Promise<string> {
  return digest(identifier).then((value) => `dinqor:core:${namespace}:${value}`);
}

export async function saveEphemeralJson(
  env: RedisEnvironment,
  namespace: string,
  identifier: string,
  value: Record<string, unknown>,
  ttlSeconds: number,
): Promise<void> {
  if (!/^[a-z][a-z0-9-]{0,31}$/.test(namespace)
    || !Number.isSafeInteger(ttlSeconds) || ttlSeconds < 1) {
    throw new Error("Invalid ephemeral state configuration");
  }
  await client(env).set(await ephemeralKey(namespace, identifier), `ephemeral:${JSON.stringify(value)}`, { ex: ttlSeconds });
}

export async function readEphemeralJson<T extends Record<string, unknown>>(
  env: RedisEnvironment,
  namespace: string,
  identifier: string,
): Promise<T | null> {
  const value = await client(env).get<string>(await ephemeralKey(namespace, identifier));
  if (!value || typeof value !== "string" || !value.startsWith("ephemeral:")) return null;
  try {
    const parsed: unknown = JSON.parse(value.slice("ephemeral:".length));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as T
      : null;
  } catch {
    return null;
  }
}

export async function updateEphemeralJson(
  env: RedisEnvironment,
  namespace: string,
  identifier: string,
  expectedStatuses: string[],
  updates: Record<string, unknown>,
  ttlSeconds: number,
): Promise<Record<string, unknown> | null> {
  if (!Number.isSafeInteger(ttlSeconds) || ttlSeconds < 1) return null;
  const result = await client(env).eval<[string, string, string], string | null>(
    UPDATE_EPHEMERAL_JSON,
    [await ephemeralKey(namespace, identifier)],
    [JSON.stringify(expectedStatuses), JSON.stringify(updates), String(ttlSeconds)],
  );
  if (typeof result !== "string" || !result.startsWith("ephemeral:")) return null;
  try {
    const parsed: unknown = JSON.parse(result.slice("ephemeral:".length));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

export async function consumeEphemeralJson(
  env: RedisEnvironment,
  namespace: string,
  identifier: string,
  status: string,
  exchangeCode: string,
): Promise<Record<string, unknown> | null> {
  const result = await client(env).eval<[string, string], string | null>(
    CONSUME_EPHEMERAL_JSON,
    [await ephemeralKey(namespace, identifier)],
    [status, exchangeCode],
  );
  if (typeof result !== "string" || !result.startsWith("ephemeral:")) return null;
  try {
    const parsed: unknown = JSON.parse(result.slice("ephemeral:".length));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed)
      ? parsed as Record<string, unknown>
      : null;
  } catch {
    return null;
  }
}

export async function deleteEphemeralJson(
  env: RedisEnvironment,
  namespace: string,
  identifier: string,
): Promise<void> {
  await client(env).del(await ephemeralKey(namespace, identifier));
}
