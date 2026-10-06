package com.revealz.backend.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import com.revealz.backend.meta.MetaService;
import com.revealz.backend.web.ApiException;

class AuthServiceTest {
    private AuthRepository repository;
    private ExternalIdentityRepository identities;
    private RefreshTokenRepository refreshTokens;
    private MetaService meta;
    private JwtEncoder encoder;
    private JwtDecoder google;
    private AuthService service;

    @BeforeEach void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setLoginAttemptLimit(10);
        LoginAttemptStore attempts = new LoginAttemptStore(properties);
        repository = mock(AuthRepository.class);
        identities = mock(ExternalIdentityRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        meta = mock(MetaService.class);
        encoder = mock(JwtEncoder.class);
        google = mock(JwtDecoder.class);
        service = new AuthService(attempts, repository, identities, refreshTokens, meta, encoder, google, properties);
        Jwt issued = Jwt.withTokenValue("access").header("alg", "HS256").subject("account-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        when(encoder.encode(any())).thenReturn(issued);
        when(meta.find(anyString())).thenReturn(Map.of("account", Map.of("accountKey", "account-a")));
    }

    @Test void rejectsNonceMismatch() {
        Map<String, Object> attempt = service.start();
        when(google.decode("id-token")).thenReturn(googleJwt("google-sub", "wrong-nonce"));
        assertThrows(ApiException.class, () -> service.exchange(String.valueOf(attempt.get("loginAttemptId")), "id-token"));
        verifyNoInteractions(repository, identities, refreshTokens);
    }

    @Test void repeatedGoogleSubjectUsesExistingAccountWithoutNewGrant() {
        Map<String, Object> attempt = service.start();
        when(google.decode("id-token")).thenReturn(googleJwt("google-sub", String.valueOf(attempt.get("nonce"))));
        when(identities.findById(any())).thenReturn(java.util.Optional.of(
                new ExternalIdentity("google", "google-sub", "account-a", "old@example.com")));
        service.exchange(String.valueOf(attempt.get("loginAttemptId")), "id-token");
        verify(meta, never()).createGoogleAccount(anyString(), anyString());
        verify(refreshTokens).revokeActive("account-a");
    }

    @Test void consumedRefreshCannotBeUsedAgain() {
        var active = new RefreshToken("hash", "account-a", Instant.now().plusSeconds(300));
        when(refreshTokens.findByTokenHash(anyString())).thenReturn(java.util.Optional.of(active), java.util.Optional.empty());
        service.refresh("refresh-token");
        assertThrows(ApiException.class, () -> service.refresh("refresh-token"));
        verify(refreshTokens, times(2)).findByTokenHash(anyString());
    }

    private Jwt googleJwt(String subject, String nonce) {
        return Jwt.withTokenValue("google").header("alg", "RS256").subject(subject)
                .claim("nonce", nonce).claim("email", "person@example.com").claim("name", "Player")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }
}
