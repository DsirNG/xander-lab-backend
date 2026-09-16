package com.xander.lab.config;

import com.xander.lab.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一将异常转换为前端 axios 封装能识别的 { code, message, data } 格式
 *
 * 对应前端 axios 封装中的错误处理逻辑：
 *   - HTTP 4xx/5xx → HTTP_ERROR_MAP 映射
 *   - 业务 code 非 200 → BIZ_ERROR_MAP 映射
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Domain services use ResponseStatusException for explicit 4xx semantics.
     * Preserve the shared Result envelope instead of falling through to the
     * generic RuntimeException handler.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatusException(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String message = ex.getReason() == null ? "请求处理失败" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(Result.error(status, message));
    }

    /**
     * 参数类型转换失败
     * 对应前端 HTTP_ERROR_MAP[400] = '请求参数错误'
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatchException(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        log.warn("[Validation] 参数类型不匹配：{} -> {}", ex.getName(), ex.getValue());
        return Result.badRequest("参数类型错误: " + ex.getName());
    }

    /**
     * 参数校验失败（@Valid 注解触发）
     * 对应前端 HTTP_ERROR_MAP[422] = '请求数据验证失败'
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[Validation] 参数校验失败：{}", message);
        return Result.error(422, message);
    }

    /**
     * 业务逻辑异常（参数非法等）
     * 对应前端 HTTP_ERROR_MAP[400] = '请求参数错误'
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[Business] 业务异常：{}", ex.getMessage());
        return Result.badRequest(ex.getMessage());
    }

    /**
     * 模型网关启用后功能未配置可用模型（见 ModelGateway 契约）
     * 属于业务可修复状态（去管理台配置即可），映射为 400 并向用户展示明确文案。
     */
    @ExceptionHandler(com.xander.lab.core.api.FeatureModelNotConfiguredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleFeatureModelNotConfigured(
            com.xander.lab.core.api.FeatureModelNotConfiguredException ex) {
        log.warn("[ModelGateway] 功能未配置模型：{}", ex.getMessage());
        return Result.badRequest(ex.getMessage());
    }

    /**
     * 文件大小超限
     * 对应前端 HTTP_ERROR_MAP[413]（请求体过大）
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("[Upload] 文件大小超限：{}", ex.getMessage());
        return Result.error(413, "文件大小超过服务器限制");
    }

    /**
     * Redis 不可用或 Redis 命令执行失败时返回 503，明确表示依赖服务故障，
     * 避免前端把它误判成普通服务器 500；完整原因只记录在服务端日志中。
     */
    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
    public ResponseEntity<Result<Void>> handleRedisException(org.springframework.dao.DataAccessException ex) {
        log.error("[Redis] Redis 操作失败：{}", ex.getMostSpecificCause().getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.error(503, "缓存服务暂不可用，请稍后重试"));
    }

    /**
     * Spring 静态资源处理器找不到资源时是正常的 404，不应被通用异常处理器记成 500。
     * 这也会降低互联网扫描请求对错误日志的污染。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("[HTTP] 资源不存在：{} {}", ex.getHttpMethod(), ex.getResourcePath());
        return Result.notFound("资源不存在");
    }

    /**
     * 自定义运行时异常
     * 服务端记录完整堆栈；客户端只返回通用错误，避免泄露供应商异常或内部细节
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException ex) {
        log.error("[Server] 运行时异常: {}", ex.getMessage(), ex);
        return Result.error("服务器内部错误，请稍后重试");
    }

    /**
     * 未捕获的运行时异常
     * 对应前端 HTTP_ERROR_MAP[500] = '服务器内部错误'
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception ex) {
        log.error("[Server] 未处理异常", ex);
        return Result.error("服务器内部错误，请稍后重试");
    }
}
