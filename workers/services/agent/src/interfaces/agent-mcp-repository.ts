export interface AgentMcpRemoteTool {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export interface AgentMcpServerRecord {
  id: number;
  ownerUserId: number;
  serverKey: string;
  displayName: string;
  endpointUrl: string;
  headersEncrypted: string | null;
  enabled: boolean;
  tools: AgentMcpRemoteTool[];
  lastStatus: "OK" | "FAILED" | null;
  lastError: string | null;
  lastConnectedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AgentMcpServerInput {
  ownerUserId: number;
  serverKey: string;
  displayName: string;
  endpointUrl: string;
  headersEncrypted: string | null;
  enabled: boolean;
}

export interface AgentMcpServerRepository {
  list(ownerUserId: number): Promise<AgentMcpServerRecord[]>;
  listEnabledVisibleTo(userId: number): Promise<AgentMcpServerRecord[]>;
  find(id: number, ownerUserId: number): Promise<AgentMcpServerRecord | null>;
  findByKey(ownerUserId: number, serverKey: string): Promise<AgentMcpServerRecord | null>;
  insert(input: AgentMcpServerInput): Promise<AgentMcpServerRecord>;
  update(id: number, ownerUserId: number, input: Omit<AgentMcpServerInput, "ownerUserId" | "serverKey">): Promise<boolean>;
  updateProbe(id: number, ownerUserId: number, values: {
    tools?: AgentMcpRemoteTool[];
    lastStatus: "OK" | "FAILED";
    lastError: string | null;
    lastConnectedAt: string | null;
  }): Promise<boolean>;
  delete(id: number, ownerUserId: number): Promise<boolean>;
}
