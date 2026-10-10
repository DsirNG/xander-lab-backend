const encoder = new TextEncoder();

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function fromBase64(value: string): Uint8Array | null {
  try {
    const binary = atob(value);
    return Uint8Array.from(binary, (character) => character.charCodeAt(0));
  } catch {
    return null;
  }
}

function arrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

async function encryptionKey(encoded: string | undefined): Promise<CryptoKey | null> {
  if (!encoded) return null;
  const raw = fromBase64(encoded.trim());
  if (!raw || raw.byteLength !== 32) return null;
  return crypto.subtle.importKey("raw", arrayBuffer(raw), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

export async function encryptSecret(secret: string, encodedKey: string | undefined): Promise<string | null> {
  const key = await encryptionKey(encodedKey);
  if (!key) return null;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: arrayBuffer(iv) }, key, arrayBuffer(encoder.encode(secret)),
  );
  return `${base64(iv)}.${base64(new Uint8Array(ciphertext))}`;
}

export async function decryptSecret(ciphertext: string, encodedKey: string | undefined): Promise<string | null> {
  const key = await encryptionKey(encodedKey);
  const [encodedIv, encodedData, extra] = ciphertext.split(".");
  const iv = encodedIv ? fromBase64(encodedIv) : null;
  const data = encodedData ? fromBase64(encodedData) : null;
  if (!key || !iv || iv.byteLength !== 12 || !data || extra !== undefined) return null;
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: arrayBuffer(iv) }, key, arrayBuffer(data),
    );
    return new TextDecoder("utf-8", { fatal: true }).decode(plain);
  } catch {
    return null;
  }
}
