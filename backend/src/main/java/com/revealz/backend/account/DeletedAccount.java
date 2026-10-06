package com.revealz.backend.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "deleted_accounts")
public class DeletedAccount {

    @Id
    @Column(name = "account_key", nullable = false, updatable = false)
    private String accountKey;

    protected DeletedAccount() {
    }

    public DeletedAccount(String accountKey) {
        this.accountKey = accountKey;
    }
}
