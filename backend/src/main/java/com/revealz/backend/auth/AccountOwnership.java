package com.revealz.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import com.revealz.backend.web.ApiException;

public final class AccountOwnership {
    private AccountOwnership() { }
    public static String subject(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "authentication_required");
        return jwt.getSubject();
    }
    public static void require(Jwt jwt, String accountKey) {
        if (!subject(jwt).equals(accountKey))
            throw new ApiException(HttpStatus.FORBIDDEN, "account_forbidden");
    }
}
