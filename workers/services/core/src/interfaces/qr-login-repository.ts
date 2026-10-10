export interface QrLoginState extends Record<string, unknown> {
  status?: unknown;
  expiresAt?: unknown;
  exchangeCode?: unknown;
  userId?: unknown;
}

export interface QrLoginRepository {
  save(ticket: string, state: QrLoginState, ttlSeconds: number): Promise<void>;
  find(ticket: string): Promise<QrLoginState | null>;
  update(
    ticket: string,
    expectedStatuses: string[],
    updates: Record<string, unknown>,
    ttlSeconds: number,
  ): Promise<QrLoginState | null>;
  consume(ticket: string, status: string, exchangeCode: string): Promise<QrLoginState | null>;
  delete(ticket: string): Promise<void>;
}
