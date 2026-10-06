package com.revealz.backend.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import com.revealz.backend.web.ApiException;

class JwtAndOwnershipTest {
    private final AuthProperties properties = properties();
    private final JwtConfiguration configuration = new JwtConfiguration();
    private final JwtEncoder encoder = configuration.jwtEncoder(configuration.authJwtKey(properties));
    private final JwtDecoder decoder = configuration.jwtDecoder(configuration.authJwtKey(properties), properties);

    @Test void acceptsExpectedIssuerAudienceAndRejectsWrongValuesOrExpiry() {
        assertDoesNotThrow(() -> decoder.decode(token("revealz-backend", List.of("revealz-windows"), Instant.now().plusSeconds(60))));
        assertThrows(JwtException.class, () -> decoder.decode(token("other", List.of("revealz-windows"), Instant.now().plusSeconds(60))));
        assertThrows(JwtException.class, () -> decoder.decode(token("revealz-backend", List.of("other"), Instant.now().plusSeconds(60))));
        assertThrows(JwtException.class, () -> decoder.decode(token("revealz-backend", List.of("revealz-windows"), Instant.now().minusSeconds(120))));
    }

    @Test void accountOwnershipRejectsAnotherSubject() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none").subject("mine").build();
        assertDoesNotThrow(() -> AccountOwnership.require(jwt, "mine"));
        assertThrows(ApiException.class, () -> AccountOwnership.require(jwt, "other"));
    }

    private String token(String issuer, List<String> audience, Instant expiresAt) {
        Instant now = Instant.now();
        Instant issuedAt = expiresAt.isBefore(now) ? expiresAt.minusSeconds(60) : now.minusSeconds(2);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("account").audience(audience)
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    private AuthProperties properties() {
        AuthProperties value = new AuthProperties();
        value.setJwtSecret("01234567890123456789012345678901");
        value.setIssuer("revealz-backend"); value.setAudience("revealz-windows");
        return value;
    }
}
