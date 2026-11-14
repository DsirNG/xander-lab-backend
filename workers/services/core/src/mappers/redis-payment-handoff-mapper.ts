import type { PaymentHandoff, PaymentHandoffRepository } from "../interfaces/payment-handoff-repository.js";
import { deleteEphemeralJson, readEphemeralJson, saveEphemeralJson, updateEphemeralJson, type RedisEnvironment } from "../redis.js";

const NS = "payment-handoff";
export class RedisPaymentHandoffMapper implements PaymentHandoffRepository {
  constructor(private readonly env: RedisEnvironment) {}
  save(ticket: string, state: PaymentHandoff, ttl: number): Promise<void> {
    return saveEphemeralJson(this.env, NS, ticket, state, ttl);
  }
  find(ticket: string): Promise<PaymentHandoff | null> {
    return readEphemeralJson<PaymentHandoff>(this.env, NS, ticket);
  }
  async attach(ticket: string, outTradeNo: string, ttl: number): Promise<void> {
    // First order wins across mobile retries. The original expiry is not extended.
    await updateEphemeralJson(this.env, NS, ticket, ["WAITING"], { outTradeNo, status: "ORDER_CREATED" }, ttl);
  }
  async delete(ticket: string): Promise<void> { await deleteEphemeralJson(this.env, NS, ticket); }
}
