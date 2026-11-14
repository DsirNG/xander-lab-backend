export interface AgentWebSettingsRecord {
  enabled: boolean;
  encryptedApiKey: string | null;
  updatedAt: string;
}

/** Agent owns the singleton platform search configuration and its encrypted key. */
export interface AgentWebSettingsRepository {
  find(): Promise<AgentWebSettingsRecord | null>;
  save(input: { enabled: boolean; encryptedApiKey: string | null; updatedBy: number }): Promise<void>;
}
