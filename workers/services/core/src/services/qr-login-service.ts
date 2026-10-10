import type { QrLoginRepository, QrLoginState } from "../interfaces/qr-login-repository.js";

export const QR_LOGIN_TTL_SECONDS = 120;

export class QrLoginService {
  constructor(private readonly repository: QrLoginRepository) {}

  async create(now = Date.now()): Promise<{ ticket: string; expiresAt: number }> {
    const ticket = randomHex(16);
    const expiresAt = now + QR_LOGIN_TTL_SECONDS * 1000;
    await this.repository.save(ticket, { status: "WAITING", expiresAt }, QR_LOGIN_TTL_SECONDS);
    return { ticket, expiresAt };
  }

  async status(ticket: string): Promise<{ status: string; exchangeCode: string | null }> {
    const state = await this.repository.find(ticket);
    if (!state || typeof state.status !== "string") return { status: "EXPIRED", exchangeCode: null };
    return {
      status: state.status,
      exchangeCode: state.status === "CONFIRMED" && typeof state.exchangeCode === "string"
        ? state.exchangeCode
        : null,
    };
  }

  async confirm(ticket: string, userId: number, now = Date.now()): Promise<boolean> {
    const state = await this.repository.find(ticket);
    if (!state || typeof state.expiresAt !== "number") return false;
    const remainingTtl = Math.ceil((state.expiresAt - now) / 1000);
    if (remainingTtl < 1) return false;
    const updated = await this.repository.update(ticket, ["WAITING", "SCANNED"], {
      status: "CONFIRMED",
      exchangeCode: randomHex(24),
      userId: String(userId),
    }, remainingTtl);
    return Boolean(updated);
  }

  async exchange(ticket: string, exchangeCode: string): Promise<number | null> {
    const state: QrLoginState | null = await this.repository.consume(ticket, "CONFIRMED", exchangeCode);
    const userId = Number(state?.userId);
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
