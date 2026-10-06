package com.revealz.backend.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@Table(name = "accounts")
@DynamicUpdate
public class Account {

    @Id
    @Column(name = "account_key", nullable = false, updatable = false)
    private String accountKey;

    @Column(name = "auth_kind", nullable = false)
    private String authKind;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "profile_icon_id", nullable = false)
    private String profileIconId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "client_migrated_at")
    private Instant clientMigratedAt;

    @Column(name = "meta_revision", nullable = false)
    private long metaRevision;

    protected Account() {
    }

    public Account(String accountKey, String authKind, String displayName) {
        this.accountKey = accountKey;
        this.authKind = authKind;
        this.displayName = displayName;
        this.profileIconId = "";
    }

    public String accountKey() {
        return accountKey;
    }

    public String authKind() {
        return authKind;
    }

    public String displayName() {
        return displayName;
    }

    public String profileIconId() {
        return profileIconId;
    }

    public Instant clientMigratedAt() {
        return clientMigratedAt;
    }

    public long metaRevision() {
        return metaRevision;
    }

    public void changeDisplayName(String newDisplayName) {
        displayName = newDisplayName;
        metaRevision++;
    }

    public void updateProfile(String newDisplayName, String newProfileIconId) {
        displayName = newDisplayName;
        profileIconId = newProfileIconId;
        metaRevision++;
    }

    public void updateIdentity(String newAuthKind, String newDisplayName) {
        authKind = newAuthKind;
        displayName = newDisplayName;
    }

    public void bumpRevision() {
        metaRevision++;
    }
}
