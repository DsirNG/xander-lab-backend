import type { RedisEnvironment } from "../redis.js";
import { consumeVerificationCode, deleteVerificationCode, saveVerificationCode } from "../redis.js";
import type { VerificationCodeRepository } from "../interfaces/verification-code-repository.js";

export class RedisVerificationCodeMapper implements VerificationCodeRepository {
  constructor(private readonly env: RedisEnvironment) {}

  save(email: string, code: string): Promise<void> {
    return saveVerificationCode(this.env, email, code);
  }

  consume(email: string, code: string): Promise<boolean> {
    return consumeVerificationCode(this.env, email, code);
  }

  delete(email: string): Promise<void> {
    return deleteVerificationCode(this.env, email);
  }
}
