package com.xander.lab.automation.host;

import com.xander.lab.common.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Keeps database and Redis failures on the shared Result/HTTP status contract. */
@RestControllerAdvice
public class ContentAutomationExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> dataAccessUnavailable(DataAccessException exception) {
        return ResponseEntity.status(503)
                .body(Result.error(503, "内容服务暂时不可用"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> responseStatus(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        String message = exception.getReason() == null ? "请求失败" : exception.getReason();
        return ResponseEntity.status(status).body(Result.error(status, message));
    }
}
