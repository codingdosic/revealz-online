package com.revealz.backend.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Map<String, Object> body;

    public ApiException(HttpStatus status, String error) {
        this(status, Map.of("error", error));
    }

    public ApiException(HttpStatus status, Map<String, Object> body) {
        super(String.valueOf(body.getOrDefault("error", status.getReasonPhrase())));
        this.status = status;
        this.body = Map.copyOf(body);
    }

    public ApiException(HttpStatus status, String error, String field, Object value) {
        this(status, body(error, field, value));
    }

    public HttpStatus status() { return status; }
    public Map<String, Object> body() { return body; }

    private static Map<String, Object> body(String error, String field, Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", error);
        result.put(field, value);
        return result;
    }
}
