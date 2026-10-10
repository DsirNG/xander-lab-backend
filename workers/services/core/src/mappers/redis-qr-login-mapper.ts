import type { RedisEnvironment } from "../redis.js";
import {
  consumeEphemeralJson,
  deleteEphemeralJson,
  readEphemeralJson,
  saveEphemeralJson,
  updateEphemeralJson,
} from "../redis.js";
import type { QrLoginRepository, QrLoginState } from "../interfaces/qr-login-repository.js";

const NAMESPACE = "qr-login";

export class RedisQrLoginMapper implements QrLoginRepository {
  constructor(private readonly env: RedisEnvironment) {}

  save(ticket: string, state: QrLoginState, ttlSeconds: number): Promise<void> {
    return saveEphemeralJson(this.env, NAMESPACE, ticket, state, ttlSeconds);
  }

  find(ticket: string): Promise<QrLoginState | null> {
    return readEphemeralJson<QrLoginState>(this.env, NAMESPACE, ticket);
  }

  async update(
    ticket: string,
    expectedStatuses: string[],
    updates: Record<string, unknown>,
    ttlSeconds: number,
  ): Promise<QrLoginState | null> {
    return await updateEphemeralJson(
      this.env, NAMESPACE, ticket, expectedStatuses, updates, ttlSeconds,
    ) as QrLoginState | null;
  }

  async consume(ticket: string, status: string, exchangeCode: string): Promise<QrLoginState | null> {
    return await consumeEphemeralJson(this.env, NAMESPACE, ticket, status, exchangeCode) as QrLoginState | null;
  }

  async delete(ticket: string): Promise<void> {
    await deleteEphemeralJson(this.env, NAMESPACE, ticket);
  }
}
