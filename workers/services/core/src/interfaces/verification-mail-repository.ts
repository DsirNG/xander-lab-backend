export type VerificationMailResult = "sent" | "unconfigured" | "failed";

export interface VerificationMailRepository {
  deliver(email: string, code: string): Promise<VerificationMailResult>;
}
