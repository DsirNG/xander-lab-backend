import type { WorkerServiceBinding } from "@dinqor/worker-runtime";

export interface CoreServiceEnvironment {
  CORE_SERVICE_URL?: string;
  CORE_SERVICE_TOKEN?: string;
  CORE_SERVICE?: WorkerServiceBinding;
}

export function coreServiceRequest(
  env: CoreServiceEnvironment,
  path: string,
  notConfiguredMessage = "Core service is not configured",
): { url: URL; token: string } {
  const baseUrl = env.CORE_SERVICE_URL?.trim();
  const token = env.CORE_SERVICE_TOKEN;
  if (!token || (!baseUrl && !env.CORE_SERVICE)) throw new Error(notConfiguredMessage);

  // A binding routes by service name, so this origin is only needed to form a Request URL.
  return { url: new URL(path, baseUrl || "https://dinqor-core.invalid"), token };
}
