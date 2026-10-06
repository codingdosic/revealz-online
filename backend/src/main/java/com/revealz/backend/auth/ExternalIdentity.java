package com.revealz.backend.auth;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "external_identities")
class ExternalIdentity {
    @EmbeddedId
    private ExternalIdentityId id;

    @Column(name = "account_key", nullable = false)
    private String accountKey;

    @Column(nullable = false)
    private String email;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExternalIdentity() { }

    ExternalIdentity(String provider, String subject, String accountKey, String email) {
        id = new ExternalIdentityId(provider, subject);
        this.accountKey = accountKey;
        this.email = email;
    }

    @PrePersist void onCreate() { createdAt = Instant.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }

    String accountKey() { return accountKey; }
    void changeEmail(String newEmail) { email = newEmail; }
}
