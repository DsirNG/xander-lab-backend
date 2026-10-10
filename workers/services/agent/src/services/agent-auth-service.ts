import { apiError, verifyAccessToken } from "@dinqor/worker-runtime";
import type { AgentAuthRepository } from "../interfaces/agent-auth.js";

const LOGIN_EXPIRED_MESSAGE = "未登录或登录已过期";

export class AgentAuthService {
  constructor(
    private readonly jwtSecret: string | undefined,
    private readonly identity: AgentAuthRepository,
  ) {}

  async currentUserId(request: Request): Promise<number | Response> {
    const authorization = request.headers.get("authorization");
    if (!authorization?.startsWith("Bearer ")) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    const token = authorization.slice(7).trim();
    if (!token) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    if (!this.jwtSecret || new TextEncoder().encode(this.jwtSecret).byteLength < 32) {
      return apiError(503, "身份服务尚未配置");
    }
    const claims = await verifyAccessToken(token, this.jwtSecret);
    if (!claims) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    const userId = await this.identity.resolveActiveUser(token);
    if (userId instanceof Response) return userId;
    if (userId === null || userId !== Number(claims.sub)) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    return userId;
  }

  async currentAdminUserId(request: Request): Promise<number | Response> {
    const authorization = request.headers.get("authorization");
    const token = authorization?.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
    if (!token) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    if (!this.jwtSecret || new TextEncoder().encode(this.jwtSecret).byteLength < 32) {
      return apiError(503, "身份服务尚未配置");
    }
    const claims = await verifyAccessToken(token, this.jwtSecret);
    if (!claims) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    if (claims.role !== "ADMIN") return apiError(403, "无权访问");
    const userId = await this.identity.resolveActiveUser(token);
    if (userId instanceof Response) return userId;
    if (userId === null || userId !== Number(claims.sub)) return apiError(401, LOGIN_EXPIRED_MESSAGE);
    return userId;
  }
}
