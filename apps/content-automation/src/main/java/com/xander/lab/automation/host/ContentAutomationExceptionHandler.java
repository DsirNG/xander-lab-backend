package com.xander.lab.automation.host;

import com.xander.lab.common.Result;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Keeps database and Redis failures on the shared Result/HTTP status contract. */
@RestControllerAdvice
public class ContentAutomationExceptionHandler {

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> dataAccessUnavailable(DataAccessException exception) {
        return ResponseEntity.status(503)
                .body(Result.error(503, "内容服务暂时不可用"));
    }
}
