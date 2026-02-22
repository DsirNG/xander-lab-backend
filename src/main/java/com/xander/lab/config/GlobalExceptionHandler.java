package com.xander.lab.config;

import com.xander.lab.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
     * 自定义运行时异常
     * 对应业务抛出的 RuntimeException，保留原业务消息
     */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleRuntimeException(RuntimeException ex) {
        log.error("[Server] 运行时异常: {}", ex.getMessage(), ex);
        return Result.error(ex.getMessage() != null ? ex.getMessage() : "服务器内部错误");
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
