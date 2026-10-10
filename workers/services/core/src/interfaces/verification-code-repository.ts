export interface VerificationCodeRepository {
  save(email: string, code: string): Promise<void>;
  consume(email: string, code: string): Promise<boolean>;
  delete(email: string): Promise<void>;
}
