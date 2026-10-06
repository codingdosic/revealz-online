package com.revealz.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.meta.MetaService;
import com.revealz.backend.web.ApiException;

@Service
public class AuthService {
    private static final String PROVIDER = "google";
    private final LoginAttemptStore attempts;
    private final AuthRepository repository;
    private final ExternalIdentityRepository identities;
    private final RefreshTokenRepository refreshTokens;
    private final MetaService meta;
    private final JwtEncoder encoder;
    private final JwtDecoder googleDecoder;
    private final AuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    AuthService(LoginAttemptStore attempts, AuthRepository repository,
            ExternalIdentityRepository identities, RefreshTokenRepository refreshTokens,
            MetaService meta, JwtEncoder encoder,
            @Qualifier("googleJwtDecoder") JwtDecoder googleDecoder, AuthProperties properties) {
        this.attempts = attempts; this.repository = repository;
        this.identities = identities; this.refreshTokens = refreshTokens; this.meta = meta;
        this.encoder = encoder; this.googleDecoder = googleDecoder; this.properties = properties;
    }

    public Map<String, Object> start() {
        LoginAttemptStore.Issued issued = attempts.issue();
        return Map.of("loginAttemptId", issued.loginAttemptId(), "nonce", issued.nonce(),
                "expiresIn", issued.expiresIn());
    }

    @Transactional
    public Map<String, Object> exchange(String attemptId, String idToken) {
        String expectedNonce = attempts.nonce(clean(attemptId));
        Jwt google;
        try { google = googleDecoder.decode(clean(idToken)); }
        catch (RuntimeException exception) { throw new ApiException(HttpStatus.UNAUTHORIZED, "google_token_invalid"); }
        String nonce = clean(google.getClaimAsString("nonce"));
        if (!MessageDigest.isEqual(expectedNonce.getBytes(StandardCharsets.UTF_8), nonce.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "google_nonce_invalid");
        attempts.consume(attemptId, nonce);
        String subject = clean(google.getSubject());
        if (subject.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED, "google_subject_missing");
        repository.lockIdentity(PROVIDER, subject);
        ExternalIdentityId identityId = new ExternalIdentityId(PROVIDER, subject);
        ExternalIdentity identity = identities.findById(identityId).orElse(null);
        String accountKey = identity == null ? null : identity.accountKey();
        String email = clean(google.getClaimAsString("email"));
        if (accountKey == null) {
            accountKey = "google_" + UUID.randomUUID().toString().replace("-", "");
            String name = clean(google.getClaimAsString("name"));
            meta.createGoogleAccount(accountKey, name);
            identities.save(new ExternalIdentity(PROVIDER, subject, accountKey, email));
        } else identity.changeEmail(email);
        refreshTokens.revokeActive(accountKey);
        return issueSession(accountKey, Instant.now().plusSeconds(properties.getRefreshSeconds()));
    }

    @Transactional
    public Map<String, Object> refresh(String rawToken) {
        String oldHash = hash(clean(rawToken));
        RefreshToken token = refreshTokens.findByTokenHash(oldHash).orElse(null);
        Instant now = Instant.now();
        if (token == null || token.revokedAt() != null || !token.expiresAt().isAfter(now))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "refresh_token_invalid");
        token.revoke();
        return issueSession(token.accountKey(), token.expiresAt());
    }

    @Transactional
    public void logout(String rawToken) {
        String value = clean(rawToken);
        if (!value.isEmpty()) refreshTokens.findById(hash(value)).ifPresent(RefreshToken::revoke);
    }

    private Map<String, Object> issueSession(String accountKey, Instant refreshExpiresAt) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.getIssuer()).subject(accountKey)
                .audience(java.util.List.of(properties.getAudience())).issuedAt(now)
                .expiresAt(now.plusSeconds(properties.getAccessSeconds())).build();
        String access = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        String refresh = randomToken();
        refreshTokens.save(new RefreshToken(hash(refresh), accountKey, refreshExpiresAt));
        return Map.of("accessToken", access, "accessExpiresIn", properties.getAccessSeconds(),
                "refreshToken", refresh, "refreshExpiresAt", refreshExpiresAt.toString(),
                "account", meta.find(accountKey));
    }

    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }
}
