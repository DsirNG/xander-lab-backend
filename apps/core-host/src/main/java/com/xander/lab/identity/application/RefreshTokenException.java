package com.xander.lab.identity.application;

/**
 * Refresh 协议对外错误分类。
 *
 * <p>401/403 表示凭据不可恢复，客户端可以清理本地会话；503 表示后端暂时不可用，
 * 客户端必须保留凭据并按协议重试一次。</p>
 */
public class RefreshTokenException extends RuntimeException {

    private final String code;
    private final int status;

    public RefreshTokenException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static RefreshTokenException from(RefreshTokenService.Rotation rotation) {
        return switch (rotation.outcome()) {
            case REUSE_DETECTED -> new RefreshTokenException(
                    "REFRESH_TOKEN_REUSED", 401, "刷新令牌已被重用");
            case ACCOUNT_DISABLED -> accountDisabled();
            case SESSION_REVOKED -> new RefreshTokenException(
                    "INVALID_REFRESH_TOKEN", 401, "刷新会话已失效");
            case INVALID -> new RefreshTokenException(
                    "INVALID_REFRESH_TOKEN", 401, "刷新令牌无效");
            case ROTATED, REPLAYED -> throw new IllegalArgumentException(
                    "成功的 rotation 不应转换为异常: " + rotation.outcome());
        };
    }

    public static RefreshTokenException accountDisabled() {
        return new RefreshTokenException("ACCOUNT_DISABLED", 403, "账号已被禁用");
    }

    public static RefreshTokenException backendUnavailable(Throwable cause) {
        RefreshTokenException exception = new RefreshTokenException(
                "AUTH_BACKEND_UNAVAILABLE", 503, "认证服务暂时不可用，请稍后重试");
        exception.initCause(cause);
        return exception;
    }
}
