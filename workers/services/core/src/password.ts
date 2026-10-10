const HASH_PREFIX = "pbkdf2-sha256-hmacpepper-v1";
const HASH_ITERATIONS = 100_000;
const MAX_HASH_ITERATIONS = 100_000;
const MIN_PEPPER_BYTES = 32;
const SALT_BYTES = 16;
const HASH_BYTES = 32;
const encoder = new TextEncoder();

function encodeBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function decodeBase64Url(value: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) return null;
  try {
    const base64 = value.replace(/-/g, "+").replace(/_/g, "/")
      .padEnd(Math.ceil(value.length / 4) * 4, "=");
    return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
  } catch {
    return null;
  }
}

async function derive(
  password: string,
  pepper: string,
  salt: Uint8Array,
  iterations: number,
): Promise<Uint8Array> {
  if (encoder.encode(pepper).byteLength < MIN_PEPPER_BYTES) {
    throw new Error("Password pepper must contain at least 32 bytes.");
  }
  const pepperKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(pepper),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const pepperedPassword = await crypto.subtle.sign("HMAC", pepperKey, encoder.encode(password));
  const key = await crypto.subtle.importKey("raw", pepperedPassword, "PBKDF2", false, ["deriveBits"]);
  const saltCopy = new Uint8Array(salt.byteLength);
  saltCopy.set(salt);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: saltCopy, iterations },
    key,
    HASH_BYTES * 8,
  );
  return new Uint8Array(bits);
}

/** Uses Worker Web Crypto and a per-environment pepper; no password KDF runs in a JS loop. */
export async function hashPassword(password: string, pepper: string): Promise<string> {
  const salt = crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const hash = await derive(password, pepper, salt, HASH_ITERATIONS);
  return `$${HASH_PREFIX}$${HASH_ITERATIONS}$${encodeBase64Url(salt)}$${encodeBase64Url(hash)}`;
}

export async function verifyPassword(
  password: string,
  encoded: string | null,
  pepper: string,
): Promise<boolean> {
  if (!encoded) return false;
  const match = /^\$(pbkdf2-sha256-hmacpepper-v1)\$(\d{6})\$([A-Za-z0-9_-]+)\$([A-Za-z0-9_-]+)$/.exec(encoded);
  if (!match) return false;
  const iterations = Number(match[2]);
  const salt = decodeBase64Url(match[3]);
  const expected = decodeBase64Url(match[4]);
  if (match[1] !== HASH_PREFIX || iterations < 100_000 || iterations > MAX_HASH_ITERATIONS
    || salt?.byteLength !== SALT_BYTES || expected?.byteLength !== HASH_BYTES) {
    return false;
  }
  const actual = await derive(password, pepper, salt, iterations);
  let mismatch = 0;
  for (let index = 0; index < HASH_BYTES; index += 1) mismatch |= actual[index] ^ expected[index];
  return mismatch === 0;
}
