import type { RedisEnvironment } from "../redis.js";
import { deleteEphemeralJson, readEphemeralJson, saveEphemeralJson } from "../redis.js";
import type { WechatPendingLoginRepository } from "../interfaces/wechat-pending-login-repository.js";

const NAMESPACE = "wechat-pending";

export class RedisWechatPendingLoginMapper implements WechatPendingLoginRepository {
  constructor(private readonly env: RedisEnvironment) {}

  save(token: string, openid: string, ttlSeconds: number): Promise<void> {
    return saveEphemeralJson(this.env, NAMESPACE, token, { openid }, ttlSeconds);
  }

  find(token: string): Promise<{ openid?: unknown } | null> {
    return readEphemeralJson(this.env, NAMESPACE, token);
  }

  delete(token: string): Promise<void> {
    return deleteEphemeralJson(this.env, NAMESPACE, token);
  }
}
