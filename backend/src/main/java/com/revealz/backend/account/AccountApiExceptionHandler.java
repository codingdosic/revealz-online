package com.revealz.backend.account;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AccountController.class)
class AccountApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return error(HttpStatus.NOT_FOUND, "account_not_found");
    }

    @ExceptionHandler(AccountDeletedException.class)
    ResponseEntity<Map<String, String>> deleted() {
        return error(HttpStatus.GONE, "account_deleted");
    }

    @ExceptionHandler(AccountRevisionConflictException.class)
    ResponseEntity<Map<String, String>> revisionConflict() {
        return error(HttpStatus.CONFLICT, "revision_conflict");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "bad_request");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> databaseFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal", "message", "account_database_unavailable"));
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String error) {
        return ResponseEntity.status(status).body(Map.of("error", error));
    }
}
