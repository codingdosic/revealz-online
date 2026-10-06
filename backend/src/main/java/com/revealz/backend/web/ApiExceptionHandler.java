package com.revealz.backend.web;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = {
        "com.revealz.backend.meta", "com.revealz.backend.mailbox",
        "com.revealz.backend.shop", "com.revealz.backend.patchnote",
        "com.revealz.backend.lobby", "com.revealz.backend.matchlog",
        "com.revealz.backend.ops", "com.revealz.backend.auth",
        "com.revealz.backend.account"
})
class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(exception.body());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> badJson() {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_json"));
    }
}
