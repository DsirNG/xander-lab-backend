package com.xander.lab.config;

import com.xander.lab.common.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Keeps the HTTP response status aligned with Xander Lab's shared Result body.
 */
@RestControllerAdvice
public class ResultStatusResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof Result<?> result) {
            response.setStatusCode(resolveStatus(result.getCode()));
        }
        return body;
    }

    private HttpStatus resolveStatus(int code) {
        HttpStatus direct = HttpStatus.resolve(code);
        if (direct != null) {
            return direct;
        }
        return switch (code) {
            case 1001 -> HttpStatus.UNAUTHORIZED;
            case 1002, 4003 -> HttpStatus.FORBIDDEN;
            case 1003 -> HttpStatus.BAD_REQUEST;
            case 4001 -> HttpStatus.NOT_FOUND;
            case 5000 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
