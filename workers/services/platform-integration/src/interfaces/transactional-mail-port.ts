export interface TransactionalMailPort {
  isConfigured(): boolean;
  send(input: { email: string; subject: string; text: string; html: string | null }): Promise<boolean>;
}
