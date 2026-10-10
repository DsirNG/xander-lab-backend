export interface VerificationMailPort {
  isConfigured(): boolean;
  send(email: string, code: string): Promise<boolean>;
}
