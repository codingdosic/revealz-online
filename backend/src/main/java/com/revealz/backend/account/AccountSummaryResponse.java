package com.revealz.backend.account;

public record AccountSummaryResponse(
        String accountKey,
        String displayName,
        long metaRevision
) {

    static AccountSummaryResponse from(Account account) {
        return new AccountSummaryResponse(
                account.accountKey(),
                account.displayName(),
                account.metaRevision());
    }
}
