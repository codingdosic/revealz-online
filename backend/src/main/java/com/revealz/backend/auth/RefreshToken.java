package com.revealz.backend.auth;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_tokens")
class RefreshToken {
    @Id
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "account_key", nullable = false, updatable = false)
    private String accountKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() { }

    RefreshToken(String tokenHash, String accountKey, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.accountKey = accountKey;
        this.expiresAt = expiresAt;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); }
    String accountKey() { return accountKey; }
    Instant expiresAt() { return expiresAt; }
    Instant revokedAt() { return revokedAt; }
    void revoke() { revokedAt = Instant.now(); }
}
