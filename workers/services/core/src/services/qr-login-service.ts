import type { QrLoginDevice, QrLoginRepository, QrLoginState } from "../interfaces/qr-login-repository.js";

export const QR_LOGIN_TTL_SECONDS = 120;

export class QrLoginService {
  constructor(private readonly repository: QrLoginRepository) {}

  async create(now = Date.now(), userAgent = ""): Promise<{ ticket: string; expiresAt: number }> {
    const ticket = randomHex(16);
    const expiresAt = now + QR_LOGIN_TTL_SECONDS * 1000;
    await this.repository.save(ticket, { status: "WAITING", device: parseDevice(userAgent), createdAt: now, expiresAt }, QR_LOGIN_TTL_SECONDS);
    return { ticket, expiresAt };
  }

  async status(ticket: string, now = Date.now()): Promise<{
    status: string;
    exchangeCode: string | null;
    device?: QrLoginDevice;
    createdAt?: number;
    expiresAt?: number;
  }> {
    const state = await this.repository.find(ticket);
    if (!state) return { status: "EXPIRED", exchangeCode: null };
    const active = isActive(state, now) && typeof state.status === "string";
    return {
      status: active ? state.status as string : "EXPIRED",
      exchangeCode: active && state.status === "CONFIRMED" && typeof state.exchangeCode === "string"
        ? state.exchangeCode : null,
      ...(state.device ? { device: state.device } : {}),
      ...(typeof state.createdAt === "number" && Number.isFinite(state.createdAt)
        ? { createdAt: state.createdAt } : {}),
      ...(typeof state.expiresAt === "number" && Number.isFinite(state.expiresAt)
        ? { expiresAt: state.expiresAt } : {}),
    };
  }

  async confirm(ticket: string, userId: number, now = Date.now()): Promise<boolean> {
    const state = await this.repository.find(ticket);
    if (!isActive(state, now)) return false;
    const remainingTtl = Math.ceil((state.expiresAt - now) / 1000);
    if (remainingTtl < 1) return false;
    const updated = await this.repository.update(ticket, ["WAITING", "SCANNED"], {
      status: "CONFIRMED",
      exchangeCode: randomHex(24),
      userId: String(userId),
    }, remainingTtl);
    return Boolean(updated);
  }

  async exchange(ticket: string, exchangeCode: string, now = Date.now()): Promise<number | null> {
    const state: QrLoginState | null = await this.repository.consume(ticket, "CONFIRMED", exchangeCode);
    // Consumption remains atomic; stale Redis records must never grant a session.
    if (!isActive(state, now)) return null;
    const userId = Number(state.userId);
    return Number.isSafeInteger(userId) && userId > 0 ? userId : null;
  }

  delete(ticket: string): Promise<void> {
    return this.repository.delete(ticket);
  }
}

function randomHex(byteLength: number): string {
  const bytes = crypto.getRandomValues(new Uint8Array(byteLength));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function isActive(state: QrLoginState | null, now: number): state is QrLoginState & { expiresAt: number } {
  return state !== null && typeof state.expiresAt === "number"
    && Number.isFinite(state.expiresAt) && state.expiresAt > now;
}

function parseDevice(userAgent: string): QrLoginDevice {
  const ua = userAgent.slice(0, 4096);
  const os: QrLoginDevice["os"] = /Android/i.test(ua) ? "android"
    : /iPhone|iPad|iPod/i.test(ua) ? "ios"
    : /Windows/i.test(ua) ? "windows"
    : /CrOS/i.test(ua) ? "chromeos"
    : /Macintosh|Mac OS X/i.test(ua) ? "macos"
    : /Linux/i.test(ua) ? "linux" : "unknown";
  const browser: QrLoginDevice["browser"] = /Edg(?:e|A|iOS)?\//i.test(ua) ? "edge"
    : /OPR\/|Opera/i.test(ua) ? "opera"
    : /Firefox\/|FxiOS\//i.test(ua) ? "firefox"
    : /Chrome\/|CriOS\//i.test(ua) ? "chrome"
    : /Safari\//i.test(ua) ? "safari" : "unknown";
  return { os, browser };
}
