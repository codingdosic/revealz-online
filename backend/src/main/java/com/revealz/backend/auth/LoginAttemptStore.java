package com.revealz.backend.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.revealz.backend.web.ApiException;
import org.springframework.http.HttpStatus;

@Component
class LoginAttemptStore {
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    LoginAttemptStore(AuthProperties properties) { this(properties, Clock.systemUTC()); }
    LoginAttemptStore(AuthProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }

    synchronized Issued issue() {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        while (attempts.size() >= properties.getLoginAttemptLimit())
            attempts.remove(attempts.keySet().iterator().next());
        String id = random(24);
        String nonce = random(32);
        attempts.put(id, new Attempt(nonce, now.plusSeconds(properties.getLoginAttemptSeconds())));
        return new Issued(id, nonce, properties.getLoginAttemptSeconds());
    }

    synchronized String nonce(String id) {
        Attempt attempt = attempts.get(id);
        if (attempt == null || !attempt.expiresAt().isAfter(clock.instant())) {
            attempts.remove(id);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "login_attempt_invalid");
        }
        return attempt.nonce();
    }

    synchronized void consume(String id, String nonce) {
        Attempt attempt = attempts.remove(id);
        if (attempt == null || !attempt.expiresAt().isAfter(clock.instant()) || !attempt.nonce().equals(nonce))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "login_attempt_invalid");
    }

    private String random(int bytes) {
        byte[] value = new byte[bytes]; random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    record Issued(String loginAttemptId, String nonce, long expiresIn) { }
    private record Attempt(String nonce, Instant expiresAt) { }
}
