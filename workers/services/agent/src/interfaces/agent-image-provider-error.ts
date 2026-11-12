/** Carries only allowlisted diagnostics; never includes provider bodies or credentials. */
export class AgentImageProviderError extends Error {
  constructor(
    readonly stage: "request" | "http" | "response" | "image" | "usage",
    readonly providerStatus?: number,
    readonly networkReason?: "timeout" | "connection",
  ) {
    super(`Image provider failed at ${stage}`);
    this.name = "AgentImageProviderError";
  }
}
