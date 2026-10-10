import { jsonResponse } from "@dinqor/worker-runtime";
import type { IdentityAuthEnvironment } from "./identity-auth-controller.js";
import { resolveCurrentUser } from "./identity-auth-controller.js";
import type { IdentityAuthService } from "../services/identity-auth-service.js";
import {
  resolveAgentModelGateway,
  resolveFeatureModelGateway,
  type ModelGatewayEnvironment,
} from "../services/model-gateway-service.js";

const MAX_BODY_BYTES = 16 * 1024;
const MAX_ACCESS_TOKEN_BYTES = 8 * 1024;

export async function handleInternalAgentModelConfig(
  request: Request,
  env: ModelGatewayEnvironment,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/internal/agent/model-config"
    && url.pathname !== "/internal/agent/image-model-config") return null;
  if (request.method !== "POST") return null;
  return jsonResponse(url.pathname === "/internal/agent/image-model-config"
    ? await resolveFeatureModelGateway(env, "blog_agent_image")
    : await resolveAgentModelGateway(env));
}

export async function handleInternalAgentAuthResolve(
  request: Request,
  env: IdentityAuthEnvironment,
  identity: IdentityAuthService,
): Promise<Response | null> {
  const url = new URL(request.url);
  if (url.pathname !== "/internal/agent/auth/resolve" || request.method !== "POST") return null;
  const contentLength = request.headers.get("content-length");
  if (contentLength && Number(contentLength) > MAX_BODY_BYTES) return jsonResponse({ userId: null });
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return jsonResponse({ userId: null });
  }
  if (!body || typeof body !== "object" || Array.isArray(body)) return jsonResponse({ userId: null });
  const accessToken = (body as Record<string, unknown>).accessToken;
  if (typeof accessToken !== "string"
    || new TextEncoder().encode(accessToken).byteLength > MAX_ACCESS_TOKEN_BYTES) {
    return jsonResponse({ userId: null });
  }
  const authRequest = new Request(new URL("/api/auth/me", request.url), {
    headers: { authorization: "Bearer " + accessToken },
  });
  const user = await resolveCurrentUser(authRequest, env, identity);
  if (user instanceof Response) return user.status === 401 ? jsonResponse({ userId: null }) : user;
  return jsonResponse({ userId: user.id });
}
