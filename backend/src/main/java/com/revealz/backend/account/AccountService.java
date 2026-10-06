package com.revealz.backend.account;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class AccountService {

    private final AccountRepository accountRepository;
    private final DeletedAccountRepository deletedAccountRepository;

    AccountService(
            AccountRepository accountRepository,
            DeletedAccountRepository deletedAccountRepository
    ) {
        this.accountRepository = accountRepository;
        this.deletedAccountRepository = deletedAccountRepository;
    }

    AccountSummaryResponse find(String accountKey) {
        rejectDeletedAccount(accountKey);
        Account account = accountRepository.findById(accountKey)
                .orElseThrow(AccountNotFoundException::new);
        return AccountSummaryResponse.from(account);
    }

    @Transactional
    AccountSummaryResponse changeDisplayName(String accountKey, UpdateAccountRequest request) {
        rejectDeletedAccount(accountKey);
        Account account = accountRepository.findByAccountKey(accountKey)
                .orElseThrow(AccountNotFoundException::new);

        if (account.metaRevision() != request.baseRevision()) {
            throw new AccountRevisionConflictException();
        }

        account.changeDisplayName(request.displayName());
        return AccountSummaryResponse.from(account);
    }

    private void rejectDeletedAccount(String accountKey) {
        if (deletedAccountRepository.existsById(accountKey)) {
            throw new AccountDeletedException();
        }
    }
}
