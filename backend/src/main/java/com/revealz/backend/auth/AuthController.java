package com.revealz.backend.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.web.ApiException;

@RestController
@RequestMapping("/v1/auth")
class AuthController {
    private final AuthService service;
    AuthController(AuthService service) { this.service = service; }
    @PostMapping("/google/start") Map<String, Object> start() { return service.start(); }
    @PostMapping("/google/exchange") Map<String, Object> exchange(@RequestBody Map<String, Object> body) {
        return service.exchange(required(body, "loginAttemptId", 128), required(body, "idToken", 16_384));
    }
    @PostMapping("/refresh") Map<String, Object> refresh(@RequestBody Map<String, Object> body) {
        return service.refresh(required(body, "refreshToken", 512));
    }
    @PostMapping("/logout") Map<String, Object> logout(@RequestBody Map<String, Object> body) {
        service.logout(String.valueOf(body.getOrDefault("refreshToken", "")));
        return Map.of("ok", true);
    }
    private String required(Map<String, Object> body, String key, int maxLength) {
        String value = String.valueOf(body.getOrDefault(key, "")).trim();
        if (value.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, key + "_required");
        if (value.length() > maxLength) throw new ApiException(HttpStatus.BAD_REQUEST, key + "_too_long");
        return value;
    }
}
