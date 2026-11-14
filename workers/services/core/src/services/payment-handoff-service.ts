import type { PaymentHandoff, PaymentHandoffRepository } from "../interfaces/payment-handoff-repository.js";

export const PAYMENT_HANDOFF_TTL = 300;
/** Binds a QR navigation intent to the authenticated account, without sharing login credentials. */
export class PaymentHandoffService {
  constructor(private readonly repository: PaymentHandoffRepository) {}
  async create(userId: number, productId: string, quantity: number, outTradeNo: string | null, now = Date.now()) {
    const ticket = crypto.randomUUID().replaceAll("-", "");
    const state: PaymentHandoff = {
      userId, productId, quantity, outTradeNo,
      status: outTradeNo ? "ORDER_CREATED" : "WAITING", expiresAt: now + PAYMENT_HANDOFF_TTL * 1000,
    };
    await this.repository.save(ticket, state, PAYMENT_HANDOFF_TTL);
    return { ticket, ...state };
  }
  async read(ticket: string, userId: number, now = Date.now()): Promise<PaymentHandoff | null> {
    const state = await this.repository.find(ticket);
    return state && state.userId === userId && Number.isFinite(state.expiresAt) && state.expiresAt > now ? state : null;
  }
  async attach(ticket: string, userId: number, outTradeNo: string, now = Date.now()) {
    const state = await this.read(ticket, userId, now);
    if (!state) return null;
    if (!state.outTradeNo) await this.repository.attach(ticket, outTradeNo, Math.max(1, Math.ceil((state.expiresAt - now) / 1000)));
    return this.read(ticket, userId, now);
  }
  delete(ticket: string) { return this.repository.delete(ticket); }
}
